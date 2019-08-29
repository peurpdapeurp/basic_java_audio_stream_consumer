
package com.example.audio_consumer;

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

import com.example.audio_consumer.stream_player.StreamPlayer;
import com.example.audio_consumer.Utils.Helpers;
import com.example.audio_consumer.Utils.Pipe;
import com.example.audio_consumer.stream_consumer.StreamConsumer;

import net.named_data.jndn.Name;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // Messages
    public static final int MSG_STREAM_CONSUMER_INITIALIZED = 0;
    public static final int MSG_STREAM_CONSUMER_FETCH_COMPLETE = 1;
    public static final int MSG_STREAM_CONSUMER_PLAY_COMPLETE = 2;

    Button startFetchingButton_;
    Button generateRandomIdButton_;
    TextView streamFetchStatistics_;
    EditText streamNameInput_;
    EditText streamIdInput_;
    EditText audioBundleSizeInput_;

    StreamConsumer streamConsumer_;
    StreamPlayer streamPlayer_;
    Pipe transferPipe_; // for the transfer of audio data from streamConsumer_ to streamPlayer_
    Handler handler_;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        transferPipe_ = new Pipe();
        streamPlayer_ = new StreamPlayer(this, transferPipe_.getInputStream());

        handler_ = new Handler(getMainLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                switch (msg.what) {
                    case MSG_STREAM_CONSUMER_INITIALIZED: {
                        Name streamName = (Name) msg.obj;
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
                        Name streamName = (Name) msg.obj;
                        Log.d(TAG, System.currentTimeMillis() + ": " +
                                "fetching of stream " + streamName.toString() + " finished");
                        break;
                    }
                    case MSG_STREAM_CONSUMER_PLAY_COMPLETE: {
                        Name streamName = (Name) msg.obj;
                        Log.d(TAG, System.currentTimeMillis() + ": " +
                                "playing of stream " + streamName.toString() + " finished");
                        streamConsumer_ = null;
                        startFetchingButton_.setEnabled(true);
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
        audioBundleSizeInput_ = (EditText) findViewById(R.id.audio_bundle_size_input);

        startFetchingButton_ = (Button) findViewById(R.id.start_fetch_button);
        startFetchingButton_.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                streamConsumer_ = new StreamConsumer(
                        new Name(getString(R.string.network_prefix))
                                .append(streamNameInput_.getText().toString())
                                .append(streamIdInput_.getText().toString())
                                .appendVersion(0),
                        8000,
                        Long.parseLong(audioBundleSizeInput_.getText().toString()),
                        transferPipe_.getOutputStream(),
                        handler_);
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
