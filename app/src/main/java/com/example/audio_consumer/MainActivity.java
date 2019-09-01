
package com.example.audio_consumer;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.audio_consumer.stream_player.StreamPlayer;
import com.example.audio_consumer.Utils.Helpers;
import com.example.audio_consumer.stream_player.exoplayer_customization.InputStreamDataSource;
import com.example.audio_consumer.stream_consumer.StreamConsumer;

import net.named_data.jndn.Name;

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
    ProgressBar productionProgressBar_;
    ProgressBar fetchingProgressBar_;

    StreamConsumer streamConsumer_;
    StreamPlayer streamPlayer_;
    Handler handler_;
    Context ctx_;

    public static class UiEventInfo {
        public UiEventInfo(Name streamName, long arg1) {
            this.streamName = streamName;
            this.arg1 = arg1;
        }
        public Name streamName;
        public long arg1;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ctx_ = this;

        handler_ = new Handler(getMainLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {

                UiEventInfo uiEventInfo = (UiEventInfo) msg.obj;
                Name streamName = uiEventInfo.streamName;

                switch (msg.what) {
                    case MSG_STREAM_CONSUMER_INITIALIZED: {
                        Log.d(TAG, System.currentTimeMillis() + ": " +
                                "fetching of stream " + streamName.toString() + " started");
                        streamConsumer_.getHandler()
                                .obtainMessage(StreamConsumer.MSG_FETCH_START)
                                .sendToTarget();
                        streamConsumer_.getHandler()
                                .obtainMessage(StreamConsumer.MSG_PLAY_START)
                                .sendToTarget();
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
                        streamConsumer_ = null;
                        break;
                    }
                    case MSG_STREAM_PLAYER_PLAY_COMPLETE: {
                        Log.d(TAG, System.currentTimeMillis() + ": " +
                                "playing of stream " + streamPlayer_.getStreamName().toString() +
                                " finished");
                        streamPlayer_.close();
                        streamPlayer_ = null;
                        break;
                    }
                    case MSG_STREAM_FETCHER_PRODUCTION_WINDOW_GROW: {
                        long highestSegNum = uiEventInfo.arg1;
                        Log.d(TAG, System.currentTimeMillis() + ": " +
                                "stream fetcher production window grow (" +
                                "highestSegNum " + highestSegNum +
                                ")");
                        break;
                    }
                    case MSG_STREAM_FETCHER_INTEREST_SKIP: {
                        long segNum = uiEventInfo.arg1;
                        Log.d(TAG, System.currentTimeMillis() + ": " +
                                "stream fetcher interest skip (" +
                                "seg num " + segNum +
                                ")");
                        break;
                    }
                    case MSG_STREAM_FETCHER_AUDIO_RETRIEVED: {
                        long segNum = uiEventInfo.arg1;
                        Log.d(TAG, System.currentTimeMillis() + ": " +
                                "stream fetcher audio packet retrieved (" +
                                "seg num " + segNum +
                                ")");
                        break;
                    }
                    case MSG_STREAM_FETCHER_NACK_RETRIEVED: {
                        long segNum = uiEventInfo.arg1;
                        Log.d(TAG, System.currentTimeMillis() + ": " +
                                "stream fetcher nack retrieved (" +
                                "seg num " + segNum +
                                ")");
                        break;
                    }
                    case MSG_STREAM_FETCHER_FINAL_BLOCK_ID_LEARNED: {

                    }
                    case MSG_STREAM_BUFFER_FRAME_PLAYED: {
                        long highestFrameNumPlayed = uiEventInfo.arg1;
                        Log.d(TAG, System.currentTimeMillis() + ": " +
                                "stream player frame played (" +
                                "highestFrameNumPlayed " + highestFrameNumPlayed +
                                ")");
                        break;
                    }
                    case MSG_STREAM_BUFFER_FRAME_SKIP: {
                        long highestFrameNumPlayed = uiEventInfo.arg1;
                        Log.d(TAG, System.currentTimeMillis() + ": " +
                                "stream player frame skipped (" +
                                "highestFrameNumPlayed " + highestFrameNumPlayed +
                                ")");
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
        productionProgressBar_ = (ProgressBar) findViewById(R.id.production_progress_bar);
        fetchingProgressBar_ = (ProgressBar) findViewById(R.id.fetching_progress_bar);

        startFetchingButton_ = (Button) findViewById(R.id.start_fetch_button);
        startFetchingButton_.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                InputStreamDataSource transferSource = new InputStreamDataSource();
                Name streamName = new Name(getString(R.string.network_prefix))
                        .append(streamNameInput_.getText().toString())
                        .append(streamIdInput_.getText().toString())
                        .appendVersion(0);
                streamPlayer_ = new StreamPlayer(ctx_, transferSource,
                        streamName, handler_);
                streamConsumer_ = new StreamConsumer(
                        streamName,
                        transferSource,
                        handler_,
                        new StreamConsumer.Options(Long.parseLong(framesPerSegmentInput_.getText().toString()),
                                Long.parseLong(jitterBufferSizeInput_.getText().toString()),
                                Long.parseLong(producerSamplingRateInput_.getText().toString())));
                streamConsumer_.start();
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

        if (streamConsumer_ != null)
            streamConsumer_.close();
    }
}
