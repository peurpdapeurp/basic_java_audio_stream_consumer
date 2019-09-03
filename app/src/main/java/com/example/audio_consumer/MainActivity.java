
package com.example.audio_consumer;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.audio_consumer.custom_progress_bar.CustomProgressBar;
import com.example.audio_consumer.stream_player.StreamPlayer;
import com.example.audio_consumer.Utils.Helpers;
import com.example.audio_consumer.stream_player.exoplayer_customization.InputStreamDataSource;
import com.example.audio_consumer.stream_consumer.StreamConsumer;

import net.named_data.jndn.Name;

import java.util.HashMap;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // Messages from Stream Consumers
    public static final int MSG_STREAM_CONSUMER_INITIALIZED = 0;
    public static final int MSG_STREAM_CONSUMER_FETCH_COMPLETE = 1;
    public static final int MSG_STREAM_FETCHER_PRODUCTION_WINDOW_GROW = 2;
    public static final int MSG_STREAM_FETCHER_INTEREST_SKIP = 3;
    public static final int MSG_STREAM_FETCHER_AUDIO_RETRIEVED = 4;
    public static final int MSG_STREAM_FETCHER_NACK_RETRIEVED = 5;
    public static final int MSG_STREAM_FETCHER_FINAL_BLOCK_ID_LEARNED = 6;
    public static final int MSG_STREAM_BUFFER_FRAME_PLAYED = 7;
    public static final int MSG_STREAM_BUFFER_FRAME_SKIP = 8;
    public static final int MSG_STREAM_BUFFER_FINAL_FRAME_NUM_LEARNED = 9;
    public static final int MSG_STREAM_BUFFER_BUFFERING_COMPLETE = 10;

    // Messages from Stream Player
    public static final int MSG_STREAM_PLAYER_PLAY_COMPLETE = 11;

    // Private constants
    private static final int EVENT_INTEREST_SKIP = 0;
    private static final int EVENT_DATA_RETRIEVED = 1;
    private static final int EVENT_NACK_RETRIEVED = 2;
    private static final int EVENT_FRAME_SKIPPED = 3;
    private static final int EVENT_FRAME_PLAYED = 4;

    Button startFetchingButton_;
    Button incrementIdButton_;
    EditText streamNameInput_;
    EditText streamIdInput_;
    EditText framesPerSegmentInput_;
    EditText jitterBufferSizeInput_;
    EditText producerSamplingRateInput_;
    TextView currentStreamNameDisplay_;
    TextView productionProgressBarLabel_;
    CustomProgressBar productionProgressBar_;
    TextView fetchingProgressBarLabel_;
    CustomProgressBar fetchingProgressBar_;
    TextView playingProgressBarLabel_;
    CustomProgressBar playingProgressBar_;

    HashMap<Name, StreamState> streamStates_;
    Name lastStreamName_;
    Handler handler_;
    Context ctx_;

    public static class UiEventInfo {
        public UiEventInfo(Name streamName, long arg1) {
            this.streamName = streamName;
            this.arg1 = arg1;
        }
        private Name streamName;
        private long arg1;
    }

    private class StreamState {
        // Public constants
        private static final int FINAL_BLOCK_ID_UNKNOWN = -1;
        private static final int FINAL_FRAME_NUM_UNKNOWN = -1;
        private static final int NO_SEGMENTS_PRODUCED = -1;

        private StreamState(StreamConsumer streamConsumer, StreamPlayer streamPlayer,
                            long framesPerSegment) {
            this.streamConsumer = streamConsumer;
            this.streamPlayer = streamPlayer;
            this.framesPerSegment = framesPerSegment;
        }

        private StreamConsumer streamConsumer;
        private StreamPlayer streamPlayer;
        private long finalBlockId = FINAL_BLOCK_ID_UNKNOWN;
        private long highestSegProduced = NO_SEGMENTS_PRODUCED;
        private long finalFrameNum = FINAL_FRAME_NUM_UNKNOWN;
        private long framesPerSegment;
        private long segmentsFetched = 0;
        private long interestsSkipped = 0;
        private long nacksFetched = 0;
        private long framesPlayed = 0;
        private long framesSkipped = 0;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ctx_ = this;

        streamStates_ = new HashMap<>();

        handler_ = new Handler(getMainLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {

                UiEventInfo uiEventInfo = (UiEventInfo) msg.obj;
                Name streamName = uiEventInfo.streamName;
                StreamState streamState = streamStates_.get(streamName);

                if (streamState == null) {
                    Log.w(TAG, "streamState was null for msg " + msg.what + " from stream name " +
                            streamName.toString());
                    return;
                }

                switch (msg.what) {
                    case MSG_STREAM_CONSUMER_INITIALIZED: {
                        Log.d(TAG, "fetching of stream " + streamName.toString() + " started");
                        streamState.streamConsumer.getHandler()
                                .obtainMessage(StreamConsumer.MSG_FETCH_START)
                                .sendToTarget();
                        streamState.streamConsumer.getHandler()
                                .obtainMessage(StreamConsumer.MSG_PLAY_START)
                                .sendToTarget();
                        resetProgressBars();
                        productionProgressBar_.setStreamName(streamName);
                        currentStreamNameDisplay_.setText(streamName.toString());
                        startFetchingButton_.setEnabled(false);
                        break;
                    }
                    case MSG_STREAM_CONSUMER_FETCH_COMPLETE: {
                        Log.d(TAG, "fetching of stream " + streamName.toString() + " finished");
                        break;
                    }
                    case MSG_STREAM_PLAYER_PLAY_COMPLETE: {
                        Log.d(TAG, "playing of stream " + streamName.toString() +
                                " finished");
                        streamState.streamConsumer.close();
                        streamState.streamPlayer.close();
                        streamStates_.remove(streamName);
                        startFetchingButton_.setEnabled(true);
                        break;
                    }
                    case MSG_STREAM_FETCHER_PRODUCTION_WINDOW_GROW: {
                        long highestSegProduced = uiEventInfo.arg1;
                        if (!streamName.equals(productionProgressBar_.getStreamName())) {
                            Log.w(TAG, "production window growth for non displayed stream (" +
                                    "current production progress bar stream name " +
                                        productionProgressBar_.getStreamName().toString() + ", " +
                                    "received production growth for stream name " +
                                        streamName.toString() + ", " +
                                    ")");
                            return;
                        }
                        streamState.highestSegProduced = highestSegProduced;
                        updateProductionProgressBar(streamState);
                        updateProductionProgressBarLabel(streamState);
                        Log.d(TAG, "production window grow " + highestSegProduced);
                        break;
                    }
                    case MSG_STREAM_FETCHER_INTEREST_SKIP: {
                        long segNum = uiEventInfo.arg1;
                        streamState.interestsSkipped++;
                        updateFetchingProgressBar(segNum, EVENT_INTEREST_SKIP, streamState);
                        updateFetchingProgressBarLabel(streamState);
                        break;
                    }
                    case MSG_STREAM_FETCHER_AUDIO_RETRIEVED: {
                        long segNum = uiEventInfo.arg1;
                        streamState.segmentsFetched++;
                        updateFetchingProgressBar(segNum, EVENT_DATA_RETRIEVED, streamState);
                        updateFetchingProgressBarLabel(streamState);
                        break;
                    }
                    case MSG_STREAM_FETCHER_NACK_RETRIEVED: {
                        long segNum = uiEventInfo.arg1;
                        streamState.nacksFetched++;
                        updateFetchingProgressBar(segNum, EVENT_NACK_RETRIEVED, streamState);
                        updateFetchingProgressBarLabel(streamState);
                        break;
                    }
                    case MSG_STREAM_FETCHER_FINAL_BLOCK_ID_LEARNED: {
                        streamState.finalBlockId = uiEventInfo.arg1;
                        updateProductionProgressBar(streamState);
                        updateProductionProgressBarLabel(streamState);
                        break;
                    }
                    case MSG_STREAM_BUFFER_FRAME_PLAYED: {
                        long frameNum = uiEventInfo.arg1;
                        streamState.framesPlayed++;
                        updatePlayingProgressBar(frameNum, EVENT_FRAME_PLAYED, streamState);
                        updatePlayingProgressBarLabel(streamState);
                        break;
                    }
                    case MSG_STREAM_BUFFER_FRAME_SKIP: {
                        long frameNum = uiEventInfo.arg1;
                        streamState.framesSkipped++;
                        updatePlayingProgressBar(frameNum, EVENT_FRAME_SKIPPED, streamState);
                        updatePlayingProgressBarLabel(streamState);
                        break;
                    }
                    case MSG_STREAM_BUFFER_BUFFERING_COMPLETE: {
                        Log.d(TAG, "buffering of stream " + streamName.toString() +
                                " finished");
                        break;
                    }
                    case MSG_STREAM_BUFFER_FINAL_FRAME_NUM_LEARNED: {
                        streamState.finalFrameNum = uiEventInfo.arg1;
                        updatePlayingProgressBarLabel(streamState);
                        break;
                    }
                    default: {
                        throw new IllegalStateException("unexpected msg " + msg.what);
                    }
                }
            }
        };

        streamNameInput_ = (EditText) findViewById(R.id.stream_name_input);
        streamIdInput_ = (EditText) findViewById(R.id.stream_id_input);
        framesPerSegmentInput_ = (EditText) findViewById(R.id.frames_per_segment_input);
        jitterBufferSizeInput_ = (EditText) findViewById(R.id.jitter_buffer_size_input);
        producerSamplingRateInput_ = (EditText) findViewById(R.id.producer_sampling_rate_input);

        currentStreamNameDisplay_ = (TextView) findViewById(R.id.current_stream_name_display);

        productionProgressBar_ = (CustomProgressBar) findViewById(R.id.production_progress_bar);
        productionProgressBar_.getThumb().setAlpha(0);
        productionProgressBar_.init();

        productionProgressBarLabel_ = (TextView) findViewById(R.id.production_progress_bar_label);
        initProductionProgressBarLabel();

        fetchingProgressBar_ = (CustomProgressBar) findViewById(R.id.fetching_progress_bar);
        fetchingProgressBar_.getThumb().setAlpha(0);
        fetchingProgressBar_.init();

        fetchingProgressBarLabel_ = (TextView) findViewById(R.id.fetching_progress_bar_label);
        initFetchingProgressBarLabel();

        playingProgressBar_ = (CustomProgressBar) findViewById(R.id.playing_progress_bar);
        playingProgressBar_.getThumb().setAlpha(0);
        playingProgressBar_.init();

        playingProgressBarLabel_ = (TextView) findViewById(R.id.playing_progress_bar_label);
        initPlayingProgressBarLabel();

        startFetchingButton_ = (Button) findViewById(R.id.start_fetch_button);
        startFetchingButton_.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (lastStreamName_ != null) {
                    StreamState lastStreamState = streamStates_.get(lastStreamName_);
                    if (lastStreamState != null) {
                        lastStreamState.streamConsumer.close();
                        lastStreamState.streamPlayer.close();
                        streamStates_.remove(lastStreamName_);
                    }
                }
                InputStreamDataSource transferSource = new InputStreamDataSource();
                Name streamName = new Name(getString(R.string.network_prefix))
                        .append(streamNameInput_.getText().toString())
                        .append(streamIdInput_.getText().toString())
                        .appendVersion(0);
                lastStreamName_ = streamName;
                StreamPlayer streamPlayer = new StreamPlayer(ctx_, transferSource,
                        streamName, handler_);
                long framesPerSegment = Long.parseLong(framesPerSegmentInput_.getText().toString());
                StreamConsumer streamConsumer = new StreamConsumer(
                        streamName,
                        transferSource,
                        handler_,
                        new StreamConsumer.Options(framesPerSegment,
                                Long.parseLong(jitterBufferSizeInput_.getText().toString()),
                                Long.parseLong(producerSamplingRateInput_.getText().toString())));
                streamStates_.put(streamName, new StreamState(streamConsumer, streamPlayer,
                        framesPerSegment));
                streamConsumer.start();
            }
        });

        incrementIdButton_ = (Button) findViewById(R.id.increment_id_button);
        incrementIdButton_.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                streamIdInput_.setText(Long.toString(Long.parseLong(streamIdInput_.getText().toString()) + 1));
            }
        });

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        for (StreamState streamState : streamStates_.values()) {
            streamState.streamPlayer.close();
            streamState.streamConsumer.close();
        }
        streamStates_.clear();

    }

    private void updateProductionProgressBar(StreamState streamState) {
        boolean finalBlockIdKnown = streamState.finalBlockId != StreamState.FINAL_BLOCK_ID_UNKNOWN;
        if (finalBlockIdKnown) {
            productionProgressBar_.setTotalSegments((int) streamState.finalBlockId + 1);
        }
        if (!finalBlockIdKnown &&
                ((float) streamState.highestSegProduced / (float) productionProgressBar_.getTotalSegments()) > 0.90f) {
            productionProgressBar_.setTotalSegments(productionProgressBar_.getTotalSegments() * 2);
        }

        productionProgressBar_.updateSingleSegmentColor((int) streamState.highestSegProduced,
                R.color.green);
    }

    private void initProductionProgressBarLabel() {
        String newProductionProgressBarLabel =
                getString(R.string.production_progress_bar_label) + "\n" + "(" +
                        "anticipated " + "?" + ", " +
                        "total segments " + "?" +
                        ")";
        productionProgressBarLabel_.setText(newProductionProgressBarLabel);
    }

    private void updateProductionProgressBarLabel(StreamState streamState) {
        String label =
                getString(R.string.production_progress_bar_label) + "\n" + "(" +
                        "anticipated " +
                            ((streamState.highestSegProduced == StreamState.NO_SEGMENTS_PRODUCED) ?
                                    "?" : streamState.highestSegProduced + 1) + ", " +
                        "total segments " +
                            (streamState.finalBlockId != StreamState.FINAL_BLOCK_ID_UNKNOWN ?
                                    streamState.finalBlockId + 1: "?") +
                        ")";
        productionProgressBarLabel_.setText(label);
    }

    private void updateFetchingProgressBar(long segNum, int event_code, StreamState streamState) {
        boolean finalBlockIdKnown = streamState.finalBlockId != StreamState.FINAL_BLOCK_ID_UNKNOWN;
        if (finalBlockIdKnown) {
            fetchingProgressBar_.setTotalSegments((int) streamState.finalBlockId + 1);
        }
        if (!finalBlockIdKnown &&
                ((float) streamState.highestSegProduced / (float) fetchingProgressBar_.getTotalSegments()) > 0.90f) {
            fetchingProgressBar_.setTotalSegments(fetchingProgressBar_.getTotalSegments() * 2);
        }

        int segmentColor;
        switch (event_code) {
            case EVENT_INTEREST_SKIP:
                segmentColor = R.color.black;
                break;
            case EVENT_DATA_RETRIEVED:
                segmentColor = R.color.green;
                break;
            case EVENT_NACK_RETRIEVED:
                segmentColor = R.color.red;
                break;
            default:
                Log.w(TAG, "unrecognized event_code " + event_code);
                return;
        }
        fetchingProgressBar_.updateSingleSegmentColor((int) segNum, segmentColor);
    }

    private void initFetchingProgressBarLabel() {
        String label =
                getString(R.string.fetching_progress_bar_label) + "\n" + "(" +
                        "data " + "?" + ", " +
                        "skips " + "?" + ", " +
                        "nacks " + "?" + ", " +
                        "total segments " + "?" +
                        ")";
        ;
        fetchingProgressBarLabel_.setText(label);
    }

    private void updateFetchingProgressBarLabel(StreamState streamState) {
        String newProductionProgressBarLabel =
                getString(R.string.fetching_progress_bar_label) + "\n" + "(" +
                        "data " + (streamState.segmentsFetched + streamState.nacksFetched) + ", " +
                        "skips " + streamState.interestsSkipped + ", " +
                        "nacks " + streamState.nacksFetched + ", " +
                        "total segments " +
                            (streamState.finalBlockId != StreamState.FINAL_BLOCK_ID_UNKNOWN ?
                                    streamState.finalBlockId + 1: "?") +
                        ")";

        fetchingProgressBarLabel_.setText(newProductionProgressBarLabel);
    }

    private void updatePlayingProgressBar(long frameNum, int event_code, StreamState streamState) {
        boolean finalSegNumKnown = streamState.finalBlockId != StreamState.FINAL_BLOCK_ID_UNKNOWN;
        boolean finalFrameNumKnown = streamState.finalFrameNum != StreamState.FINAL_FRAME_NUM_UNKNOWN;
        if (finalSegNumKnown && !finalFrameNumKnown) {
            playingProgressBar_.setTotalSegments((int) (streamState.framesPerSegment * streamState.finalBlockId));
        }
        else if (finalFrameNumKnown) {
            playingProgressBar_.setTotalSegments((int) streamState.finalFrameNum + 1);
        }
        if (!finalSegNumKnown && !finalFrameNumKnown &&
                ((float) streamState.highestSegProduced / (float) playingProgressBar_.getTotalSegments()) > 0.90f) {
            playingProgressBar_.setTotalSegments(playingProgressBar_.getTotalSegments() * 2);
        }

        int segmentColor;
        switch (event_code) {
            case EVENT_FRAME_SKIPPED:
                segmentColor = R.color.black;
                break;
            case EVENT_FRAME_PLAYED:
                segmentColor = R.color.green;
                break;
            default:
                Log.w(TAG, "unrecognized event_code " + event_code);
                return;
        }
        playingProgressBar_.updateSingleSegmentColor((int) frameNum, segmentColor);
    }

    private void initPlayingProgressBarLabel() {
        String label =
                getString(R.string.fetching_progress_bar_label) + "\n" + "(" +
                        "plays " + "?" + ", " +
                        "skips " + "?" + ", " +
                        "total frames " + "?" +
                        ")";
        ;
        playingProgressBarLabel_.setText(label);
    }

    private void updatePlayingProgressBarLabel(StreamState streamState) {
        String label =
                getString(R.string.playing_progress_bar_label) + "\n" + "(" +
                        "plays " + streamState.framesPlayed + ", " +
                        "skips " + streamState.framesSkipped + ", " +
                        "total frames " +
                        (streamState.finalFrameNum != StreamState.FINAL_FRAME_NUM_UNKNOWN ?
                                streamState.finalFrameNum + 1 : "?") +
                        ")";
        playingProgressBarLabel_.setText(label);
    }

    private void resetProgressBars() {
        productionProgressBar_.reset();
        initProductionProgressBarLabel();
        fetchingProgressBar_.reset();
        initFetchingProgressBarLabel();
        playingProgressBar_.reset();
        initPlayingProgressBarLabel();
    }
}
