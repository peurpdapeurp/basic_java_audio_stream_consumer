
package com.example.audio_consumer;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.example.audio_consumer.Helpers.Helpers;
import com.example.audio_consumer.stream_fetcher.NetworkThread;
import com.example.audio_consumer.stream_fetcher.StreamFetcher;
import com.example.audio_consumer.stream_fetcher.StreamPlayer;

import net.named_data.jndn.Name;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    Handler handler_;

    // Messages
    public static final int MSG_STREAM_FETCHER_INITIALIZED = 0;
    public static final int MSG_STREAM_FETCHER_FINISHED = 1;

    Button startFetchingButton_;
    Button generateRandomIdButton_;
    TextView streamFetchStatistics_;
    EditText streamNameInput_;
    EditText streamIdInput_;
    EditText audioBundleSizeInput_;

    StreamFetcher currentStreamFetcher_;
    NetworkThread networkThread_;
    StreamPlayer streamPlayer_;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        handler_ = new Handler(getMainLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                switch (msg.what) {
                    case MSG_STREAM_FETCHER_INITIALIZED:
                        Log.d(TAG, "Handler for stream fetcher for stream " +
                                currentStreamFetcher_.getStreamName().toString() +
                                " successfully initialized.");

                        boolean ret = currentStreamFetcher_.requestStartStreamFetch();
                        Log.d(TAG, (ret ? "Successfully requested stream fetch start" : "Failed to request stream fetch start") +
                                " for name: " + currentStreamFetcher_.getStreamName().toString());

                        startFetchingButton_.setEnabled(false);
                        if (!ret) currentStreamFetcher_.requestClose();
                        break;
                    case MSG_STREAM_FETCHER_FINISHED:
                        Name streamName = (Name) msg.obj;
                        Log.d(TAG, System.currentTimeMillis() + ": " +
                                "fetching of stream " + streamName.toString() + " finished");
                        currentStreamFetcher_ = null;
                        startFetchingButton_.setEnabled(true);
                        break;
                    default:
                        throw new IllegalStateException();
                }
            }
        };

        streamFetchStatistics_ = (TextView) findViewById(R.id.stream_fetch_statistics);
        streamNameInput_ = (EditText) findViewById(R.id.stream_name_input);
        streamIdInput_ = (EditText) findViewById(R.id.stream_id_input);
        audioBundleSizeInput_ = (EditText) findViewById(R.id.audio_bundle_size_input);

        streamPlayer_ = new StreamPlayer(this);
        streamPlayer_.start();
        while (streamPlayer_.getHandler() == null) {} // block until stream player's handler is initialized

        networkThread_ = new NetworkThread();
        networkThread_.start();
        while (networkThread_.getHandler() == null) {} // block until network thread's handler is initialized

        startFetchingButton_ = (Button) findViewById(R.id.start_fetch_button);
        startFetchingButton_.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                currentStreamFetcher_ = new StreamFetcher(
                        new Name(getString(R.string.network_prefix))
                                .append(streamNameInput_.getText().toString())
                                .append(streamIdInput_.getText().toString())
                                .appendVersion(0),
                        Helpers.calculateMsPerSeg(8000,
                                Long.parseLong(audioBundleSizeInput_.getText().toString())),
                        networkThread_.getHandler(),
                        handler_);
                currentStreamFetcher_.start();
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

        streamPlayer_.stop();
        if (currentStreamFetcher_ != null) {
            currentStreamFetcher_.requestClose();
        }
        networkThread_.close();
    }
}
