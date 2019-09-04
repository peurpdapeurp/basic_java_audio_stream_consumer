
package com.example.audio_consumer;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.audio_consumer.custom_progress_bar.CustomProgressBar;
import com.example.audio_consumer.stream_player.StreamPlayer;
import com.example.audio_consumer.stream_player.exoplayer_customization.InputStreamDataSource;
import com.example.audio_consumer.stream_consumer.StreamConsumer;

import net.named_data.jndn.Name;

import java.util.HashMap;

import static com.example.audio_consumer.Utils.Helpers.getNumFrames;

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
    private static final int EVENT_PRODUCTION_WINDOW_GROW = 0;
    private static final int EVENT_AUDIO_RETRIEVED = 1;
    private static final int EVENT_FRAME_SKIPPED = 2;
    private static final int EVENT_FRAME_PLAYED = 3;
    private static final int EVENT_FINAL_FRAME_NUM_LEARNED = 4;
    private static final int EVENT_FINAL_BLOCK_ID_LEARNED = 5;

    ImageButton fetchButton_;
    boolean currentlyFetching_ = false;
    Button incrementIdButton_;
    EditText streamNameInput_;
    EditText streamIdInput_;
    EditText framesPerSegmentInput_;
    EditText jitterBufferSizeInput_;
    EditText producerSamplingRateInput_;
    TextView currentStreamNameDisplay_;
    TextView streamStatistics_;
    CustomProgressBar progressBar_;
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
        private static final int FRAMES_PER_SEGMENT_UNKNOWN = -1;

        private StreamState(StreamConsumer streamConsumer, StreamPlayer streamPlayer,
                            long framesPerSegment) {
            this.streamConsumer = streamConsumer;
            this.streamPlayer = streamPlayer;
            this.framesPerSegment = framesPerSegment;
        }

        private StreamConsumer streamConsumer;
        private StreamPlayer streamPlayer;
        private long finalBlockId = FINAL_BLOCK_ID_UNKNOWN;
        private long highestSegAnticipated = NO_SEGMENTS_PRODUCED;
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
                        resetProgressBar();
                        progressBar_.setStreamName(streamName);
                        currentStreamNameDisplay_.setText(streamName.toString());
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
                        fetchButton_.setImageDrawable(getDrawable(R.drawable.play_image));
                        currentlyFetching_ = false;
                        break;
                    }
                    case MSG_STREAM_FETCHER_PRODUCTION_WINDOW_GROW: {
                        long highestSegProduced = uiEventInfo.arg1;
                        if (!streamName.equals(progressBar_.getStreamName())) {
                            Log.w(TAG, "production window growth for non displayed stream (" +
                                    "current production progress bar stream name " +
                                        progressBar_.getStreamName().toString() + ", " +
                                    "received production growth for stream name " +
                                        streamName.toString() + ", " +
                                    ")");
                            return;
                        }
                        streamState.highestSegAnticipated = highestSegProduced;
                        updateProgressBar(EVENT_PRODUCTION_WINDOW_GROW, 0, streamState);
                        updateStreamStatisticsDisplay(streamState);
                        break;
                    }
                    case MSG_STREAM_FETCHER_INTEREST_SKIP: {
                        long segNum = uiEventInfo.arg1;
                        streamState.interestsSkipped++;
                        updateStreamStatisticsDisplay(streamState);
                        break;
                    }
                    case MSG_STREAM_FETCHER_AUDIO_RETRIEVED: {
                        long segNum = uiEventInfo.arg1;
                        streamState.segmentsFetched++;
                        updateProgressBar(EVENT_AUDIO_RETRIEVED, segNum, streamState);
                        updateStreamStatisticsDisplay(streamState);
                        break;
                    }
                    case MSG_STREAM_FETCHER_NACK_RETRIEVED: {
                        long segNum = uiEventInfo.arg1;
                        streamState.nacksFetched++;
                        updateStreamStatisticsDisplay(streamState);
                        break;
                    }
                    case MSG_STREAM_FETCHER_FINAL_BLOCK_ID_LEARNED: {
                        streamState.finalBlockId = uiEventInfo.arg1;
                        updateProgressBar(EVENT_FINAL_BLOCK_ID_LEARNED, 0, streamState);
                        updateStreamStatisticsDisplay(streamState);
                        break;
                    }
                    case MSG_STREAM_BUFFER_FRAME_PLAYED: {
                        long frameNum = uiEventInfo.arg1;
                        streamState.framesPlayed++;
                        updateProgressBar(EVENT_FRAME_PLAYED, frameNum, streamState);
                        updateStreamStatisticsDisplay(streamState);
                        break;
                    }
                    case MSG_STREAM_BUFFER_FRAME_SKIP: {
                        long frameNum = uiEventInfo.arg1;
                        streamState.framesSkipped++;
                        updateProgressBar(EVENT_FRAME_SKIPPED, frameNum, streamState);
                        updateStreamStatisticsDisplay(streamState);
                        break;
                    }
                    case MSG_STREAM_BUFFER_BUFFERING_COMPLETE: {
                        Log.d(TAG, "buffering of stream " + streamName.toString() +
                                " finished");
                        break;
                    }
                    case MSG_STREAM_BUFFER_FINAL_FRAME_NUM_LEARNED: {
                        streamState.finalFrameNum = uiEventInfo.arg1;
                        updateProgressBar(EVENT_FINAL_FRAME_NUM_LEARNED, 0, streamState);
                        updateStreamStatisticsDisplay(streamState);
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

        progressBar_ = (CustomProgressBar) findViewById(R.id.progress_bar);
        progressBar_.getThumb().setAlpha(0);
        progressBar_.init();

        streamStatistics_ = (TextView) findViewById(R.id.stream_statistics);
        initStreamStatisticsDisplay();

        fetchButton_ = (ImageButton) findViewById(R.id.start_fetch_button);
        fetchButton_.setOnClickListener(new View.OnClickListener() {
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
                if (currentlyFetching_) {
                    StreamState lastStreamState = streamStates_.get(lastStreamName_);
                    if (lastStreamState != null) {
                        lastStreamState.streamConsumer.close();
                        lastStreamState.streamPlayer.close();
                        streamStates_.remove(lastStreamName_);
                    }
                    currentlyFetching_ = false;
                    fetchButton_.setImageDrawable(getDrawable(R.drawable.play_image));
                    return;
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
                currentlyFetching_ = true;
                fetchButton_.setImageDrawable(getDrawable(R.drawable.stop_image));
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

    private void updateProgressBar(int event_code, long arg1, StreamState streamState) {
        boolean finalBlockIdKnown = streamState.finalBlockId != StreamState.FINAL_BLOCK_ID_UNKNOWN;
        boolean finalFrameNumKnown = streamState.finalFrameNum != StreamState.FINAL_FRAME_NUM_UNKNOWN;

        // rescaling logic
        if (!finalBlockIdKnown && finalFrameNumKnown) {
            if (progressBar_.getTotalSegments() != streamState.finalFrameNum + 1) {
                progressBar_.setTotalSegments((int) (streamState.finalFrameNum + 1));
            }
        }
        else if (finalBlockIdKnown) {
            long numFrames = getNumFrames(streamState.finalBlockId, streamState.framesPerSegment);
            if (progressBar_.getTotalSegments() != numFrames) {
                progressBar_.setTotalSegments((int) numFrames);
            }
        }
        else if ((float) streamState.highestSegAnticipated / (float) progressBar_.getTotalSegments() > 0.90f) {
            progressBar_.setTotalSegments(progressBar_.getTotalSegments() * 2);
        }

        // single progress bar segment update logic
        switch (event_code) {
            case EVENT_PRODUCTION_WINDOW_GROW: {
                long segNum = streamState.highestSegAnticipated;
                for (int i = 0; i < streamState.framesPerSegment; i++) {
                    long frameNum = segNum * streamState.framesPerSegment + i;
                    progressBar_.updateSingleSegmentColor((int) frameNum, R.color.red);
                }
                break;
            }
            case EVENT_AUDIO_RETRIEVED: {
                long segNum = arg1;
                for (int i = 0; i < streamState.framesPerSegment; i++) {
                    long frameNum = segNum * streamState.framesPerSegment + i;
                    progressBar_.updateSingleSegmentColor((int) frameNum, R.color.yellow);
                }
                break;
            }
            case EVENT_FRAME_SKIPPED: {
                long frameNum = arg1;
                progressBar_.updateSingleSegmentColor((int) frameNum, R.color.black);
                break;
            }
            case EVENT_FRAME_PLAYED: {
                long frameNum = arg1;
                progressBar_.updateSingleSegmentColor((int) frameNum, R.color.green);
                break;
            }
            case EVENT_FINAL_FRAME_NUM_LEARNED:
                break;
            case EVENT_FINAL_BLOCK_ID_LEARNED:
                break;
            default:
                throw new IllegalStateException("updateProgressBar unexpected event_code " + event_code);
        }
    }



    private void initStreamStatisticsDisplay() {
        StreamState tempStreamState = new StreamState(null, null, StreamState.FRAMES_PER_SEGMENT_UNKNOWN);
        updateStreamStatisticsDisplay(tempStreamState);
    }

    private void updateStreamStatisticsDisplay(StreamState streamState) {
        String label =
                        "Final block id: " +
                                ((streamState.finalBlockId == StreamState.FINAL_BLOCK_ID_UNKNOWN) ?
                                "?" : streamState.finalBlockId) + "\n" +
                        "Highest segment anticipated: " +
                                ((streamState.highestSegAnticipated == StreamState.NO_SEGMENTS_PRODUCED) ?
                                        "no segments produced" : streamState.highestSegAnticipated) + "\n" +
                        "Final frame number: " +
                                ((streamState.finalFrameNum == StreamState.FINAL_FRAME_NUM_UNKNOWN) ?
                                        "?" : streamState.finalFrameNum) + "\n" +
                        "Frames per segment: " +
                                ((streamState.framesPerSegment == StreamState.FRAMES_PER_SEGMENT_UNKNOWN) ?
                                        "?" : streamState.framesPerSegment) + "\n" +
                        "Segments fetched: " + streamState.segmentsFetched + "\n" +
                        "Interests skipped: " + streamState.interestsSkipped + "\n" +
                        "Nacks fetched: " + streamState.nacksFetched + "\n" +
                        "Frames played: " + streamState.framesPlayed + "\n" +
                        "Frames skipped: " + streamState.framesSkipped;
        streamStatistics_.setText(label);
    }

    private void resetProgressBar() {
        progressBar_.reset();
        initStreamStatisticsDisplay();
    }
}
