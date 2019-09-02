
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

import com.example.audio_consumer.custom_progress_bar.CustomSeekBar;
import com.example.audio_consumer.custom_progress_bar.ProgressItem;
import com.example.audio_consumer.stream_player.StreamPlayer;
import com.example.audio_consumer.Utils.Helpers;
import com.example.audio_consumer.stream_player.exoplayer_customization.InputStreamDataSource;
import com.example.audio_consumer.stream_consumer.StreamConsumer;

import net.named_data.jndn.Name;

import java.util.ArrayList;
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
    public static final int MSG_STREAM_BUFFER_PLAY_COMPLETE = 9;

    // Messages from Stream Player
    public static final int MSG_STREAM_PLAYER_PLAY_COMPLETE = 10;

    Button startFetchingButton_;
    Button generateRandomIdButton_;
    TextView streamFetchStatistics_;
    EditText streamNameInput_;
    EditText streamIdInput_;
    EditText framesPerSegmentInput_;
    EditText jitterBufferSizeInput_;
    EditText producerSamplingRateInput_;

    TextView productionProgressBarLabel_;
    CustomSeekBar productionProgressBar_;
    CustomSeekBar fetchingProgressBar_;
    CustomSeekBar playingProgressBar_;

    HashMap<Name, StreamState> streamStates_;
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
        private static final int NO_SEGMENTS_PRODUCED = -1;

        private StreamState(StreamConsumer streamConsumer, StreamPlayer streamPlayer) {
            this.streamConsumer = streamConsumer;
            this.streamPlayer = streamPlayer;
        }

        private StreamConsumer streamConsumer;
        private StreamPlayer streamPlayer;
        private long finalBlockId = FINAL_BLOCK_ID_UNKNOWN;
        private long highestSegProduced = NO_SEGMENTS_PRODUCED;
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

                switch (msg.what) {
                    case MSG_STREAM_CONSUMER_INITIALIZED: {
                        Log.d(TAG, System.currentTimeMillis() + ": " +
                                "fetching of stream " + streamName.toString() + " started");
                        streamState.streamConsumer.getHandler()
                                .obtainMessage(StreamConsumer.MSG_FETCH_START)
                                .sendToTarget();
                        streamState.streamConsumer.getHandler()
                                .obtainMessage(StreamConsumer.MSG_PLAY_START)
                                .sendToTarget();
                        productionProgressBar_.setStreamName(streamName);
                        break;
                    }
                    case MSG_STREAM_CONSUMER_FETCH_COMPLETE: {
                        Log.d(TAG, System.currentTimeMillis() + ": " +
                                "fetching of stream " + streamName.toString() + " finished");
                        break;
                    }
                    case MSG_STREAM_BUFFER_PLAY_COMPLETE: {
                        Log.d(TAG, System.currentTimeMillis() + ": " +
                                "buffering of stream " + streamName.toString() + " finished");
                        break;
                    }
                    case MSG_STREAM_PLAYER_PLAY_COMPLETE: {
                        Log.d(TAG, System.currentTimeMillis() + ": " +
                                "playing of stream " + streamName.toString() +
                                " finished");
                        streamState.streamConsumer.close();
                        streamState.streamPlayer.close();
                        streamStates_.remove(streamName);
                        break;
                    }
                    case MSG_STREAM_FETCHER_PRODUCTION_WINDOW_GROW: {
                        long highestSegProduced = uiEventInfo.arg1;
                        if (!streamName.equals(productionProgressBar_.getStreamName())) {
                            Log.w(TAG, System.currentTimeMillis() + ": " +
                                    "production window growth for non displayed stream (" +
                                    "current production progress bar stream name " +
                                        productionProgressBar_.getStreamName().toString() + ", " +
                                    "received production growth for stream name " +
                                        streamName.toString() + ", " +
                                    ")");
                            return;
                        }
                        streamState.highestSegProduced = highestSegProduced;
                        updateProductionProgressBar(streamState.highestSegProduced,
                                streamState.finalBlockId);
                        break;
                    }
                    case MSG_STREAM_FETCHER_INTEREST_SKIP: {
                        long segNum = uiEventInfo.arg1;
                        break;
                    }
                    case MSG_STREAM_FETCHER_AUDIO_RETRIEVED: {
                        long segNum = uiEventInfo.arg1;
                        break;
                    }
                    case MSG_STREAM_FETCHER_NACK_RETRIEVED: {
                        long segNum = uiEventInfo.arg1;
                        break;
                    }
                    case MSG_STREAM_FETCHER_FINAL_BLOCK_ID_LEARNED: {
                        streamState.finalBlockId = uiEventInfo.arg1;
                        updateProductionProgressBar(streamState.highestSegProduced,
                                streamState.finalBlockId);
                    }
                    case MSG_STREAM_BUFFER_FRAME_PLAYED: {
                        long highestFrameNumPlayed = uiEventInfo.arg1;
                        break;
                    }
                    case MSG_STREAM_BUFFER_FRAME_SKIP: {
                        long highestFrameNumPlayed = uiEventInfo.arg1;
                        break;
                    }
                    default: {
                        throw new IllegalStateException();
                    }
                }
            }
        };

        streamFetchStatistics_ = (TextView) findViewById(R.id.stream_fetch_statistics);
        streamNameInput_ = (EditText) findViewById(R.id.stream_name_input);
        streamIdInput_ = (EditText) findViewById(R.id.stream_id_input);
        framesPerSegmentInput_ = (EditText) findViewById(R.id.frames_per_segment_input);
        jitterBufferSizeInput_ = (EditText) findViewById(R.id.jitter_buffer_size_input);
        producerSamplingRateInput_ = (EditText) findViewById(R.id.producer_sampling_rate_input);

        productionProgressBar_ = (CustomSeekBar) findViewById(R.id.production_progress_bar);
        productionProgressBar_.getThumb().setAlpha(0);
        productionProgressBarLabel_ = (TextView) findViewById(R.id.production_progress_bar_label);
        updateProductionProgressBar(StreamState.NO_SEGMENTS_PRODUCED, StreamState.FINAL_BLOCK_ID_UNKNOWN);

        fetchingProgressBar_ = (CustomSeekBar) findViewById(R.id.fetching_progress_bar);
        fetchingProgressBar_.getThumb().setAlpha(0);
        updateFetchingProgressBar();

        playingProgressBar_ = (CustomSeekBar) findViewById(R.id.playing_progress_bar);
        playingProgressBar_.getThumb().setAlpha(0);
        updatePlayingProgressBar();

        startFetchingButton_ = (Button) findViewById(R.id.start_fetch_button);
        startFetchingButton_.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                InputStreamDataSource transferSource = new InputStreamDataSource();
                Name streamName = new Name(getString(R.string.network_prefix))
                        .append(streamNameInput_.getText().toString())
                        .append(streamIdInput_.getText().toString())
                        .appendVersion(0);
                StreamPlayer streamPlayer = new StreamPlayer(ctx_, transferSource,
                        streamName, handler_);
                StreamConsumer streamConsumer = new StreamConsumer(
                        streamName,
                        transferSource,
                        handler_,
                        new StreamConsumer.Options(Long.parseLong(framesPerSegmentInput_.getText().toString()),
                                Long.parseLong(jitterBufferSizeInput_.getText().toString()),
                                Long.parseLong(producerSamplingRateInput_.getText().toString())));
                streamStates_.put(streamName, new StreamState(streamConsumer, streamPlayer));
                streamConsumer.start();
            }
        });

        generateRandomIdButton_ = (Button) findViewById(R.id.generate_random_id_button);
        generateRandomIdButton_.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                streamIdInput_.setText(Long.toString(Helpers.getRandomLongBetweenRange(0, 10000)));
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

    private void updateProductionProgressBar(long highestSegNum, long finalBlockId) {
        boolean finalBlockIdKnown = finalBlockId != StreamState.FINAL_BLOCK_ID_UNKNOWN;
        if (finalBlockIdKnown) {
            productionProgressBar_.setTotalSegments(finalBlockId+1);
        }
        if (finalBlockId == StreamState.FINAL_BLOCK_ID_UNKNOWN &&
                ((float) highestSegNum / (float) productionProgressBar_.getTotalSegments()) > 0.90f) {
            productionProgressBar_.setTotalSegments(productionProgressBar_.getTotalSegments() * 2);
        }

        float redPercentage = ((float) (highestSegNum+1) / (float) productionProgressBar_.getTotalSegments()) * 100f;
        float greyPercentage = 100f - redPercentage;

//        Log.d(TAG, System.currentTimeMillis() + ": " +
//                "production progress bar updated \n" +
//                "final block id " +
//                        (finalBlockId != StreamState.FINAL_BLOCK_ID_UNKNOWN
//                                ? finalBlockId : "unknown") + ", " +
//                "highest seg num " + highestSegNum + ", " +
//                "total segments " + productionProgressBar_.getTotalSegments() + "\n" +
//                "red percentage " + greenPercentage + ", " +
//                "grey percentage " + greyPercentage);

        ArrayList<ProgressItem> progressItemList = new ArrayList<>();
        ProgressItem progressItem;

        if (finalBlockIdKnown && highestSegNum == finalBlockId) {
            // green span
            progressItem = new ProgressItem();
            progressItem.progressItemPercentage = 100f;
            Log.i("Mainactivity", progressItem.progressItemPercentage + "");
            progressItem.color = R.color.green;
            progressItemList.add(progressItem);
        }
        else {
            // red span
            progressItem = new ProgressItem();
            progressItem.progressItemPercentage = redPercentage;
            Log.i("Mainactivity", progressItem.progressItemPercentage + "");
            progressItem.color = R.color.red;
            progressItemList.add(progressItem);
            // grey span
            progressItem = new ProgressItem();
            progressItem.progressItemPercentage = greyPercentage;
            progressItem.color = R.color.grey;
            progressItemList.add(progressItem);
        }

        productionProgressBar_.initData(progressItemList);
        productionProgressBar_.invalidate();
        String newProductionProgressBarLabel =
                getString(R.string.production_progress_bar_label) + " " +
                "(segment " +
                        ((highestSegNum == StreamState.NO_SEGMENTS_PRODUCED) ? "?" : highestSegNum) +
                        "/" +
                        (finalBlockIdKnown ? finalBlockId : "?") + ")";
        productionProgressBarLabel_.setText(newProductionProgressBarLabel);
    }

    private void updateFetchingProgressBar() {

        ArrayList<ProgressItem> progressItemList = new ArrayList<>();
        ProgressItem progressItem;
        // grey span
        progressItem = new ProgressItem();
        progressItem.progressItemPercentage = 100f;
        progressItem.color = R.color.grey;
        progressItemList.add(progressItem);

        fetchingProgressBar_.initData(progressItemList);
        fetchingProgressBar_.invalidate();

    }

    private void updatePlayingProgressBar() {

        ArrayList<ProgressItem> progressItemList = new ArrayList<>();
        ProgressItem progressItem;
        // grey span
        progressItem = new ProgressItem();
        progressItem.progressItemPercentage = 100f;
        progressItem.color = R.color.grey;
        progressItemList.add(progressItem);

        playingProgressBar_.initData(progressItemList);
        playingProgressBar_.invalidate();

    }
}
