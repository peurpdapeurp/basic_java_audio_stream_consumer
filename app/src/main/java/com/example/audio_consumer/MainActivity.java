
package com.example.audio_consumer;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.example.audio_consumer.stream_fetcher.NetworkThread;
import com.example.audio_consumer.stream_fetcher.StreamFetcher;
import com.example.audio_consumer.stream_fetcher.StreamPlayer;

import net.named_data.jndn.Data;
import net.named_data.jndn.Interest;
import net.named_data.jndn.Name;

import java.util.concurrent.LinkedTransferQueue;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    Button startFetchingButton_;
    Button stopFetchingButton_;
    Button clearLogButton_;
    TextView uiLog_;
    EditText streamNameInput_;
    EditText streamIdInput_;

    StreamFetcher currentStreamFetcher_;
    Name currentStreamName_;
    NetworkThread networkThread_;
    StreamPlayer streamPlayer_;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        uiLog_ = (TextView) findViewById(R.id.ui_log);
        streamNameInput_ = (EditText) findViewById(R.id.stream_name_input);
        streamIdInput_ = (EditText) findViewById(R.id.stream_id_input);

        streamPlayer_ = new StreamPlayer(this);
        streamPlayer_.start();
        while (streamPlayer_.getHandler() == null) {} // block until stream player's handler is initialized

        networkThread_ = new NetworkThread(streamPlayer_.getHandler());
        networkThread_.start();
        while (networkThread_.getHandler() == null) {} // block until network thread's handler is initialized

        startFetchingButton_ = (Button) findViewById(R.id.start_fetch_button);
        startFetchingButton_.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                currentStreamName_ = new Name(getString(R.string.network_prefix))
                        .append(streamNameInput_.getText().toString())
                        .append(streamIdInput_.getText().toString())
                        .appendVersion(0);
                currentStreamFetcher_ = new StreamFetcher(currentStreamName_,
                        Helpers.calculateMsPerSeg(8000, 10),
                        networkThread_.getHandler());
                currentStreamFetcher_.start();
                while (currentStreamFetcher_.getHandler() == null) {} // block until stream fetcher's handler is initialized

                currentStreamName_ = new Name(getString(R.string.network_prefix))
                        .append(streamNameInput_.getText().toString())
                        .append(streamIdInput_.getText().toString())
                        .appendVersion(0);
                networkThread_.addStreamFetcherHandler(currentStreamName_, currentStreamFetcher_.getHandler());
                boolean ret = currentStreamFetcher_.startFetchingStream();
                Log.d(TAG, (ret ? "Successfully began fetching stream" : "Failed to start fetching stream") +
                        " with name: " + currentStreamName_.toUri());
            }
        });

        stopFetchingButton_ = (Button) findViewById(R.id.stop_fetch_button);
        stopFetchingButton_.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                currentStreamFetcher_.close();
                networkThread_.removeStreamFetcherHandler(currentStreamName_);
                currentStreamFetcher_ = null;
                currentStreamName_ = null;
            }
        });

        clearLogButton_ = (Button) findViewById(R.id.clear_log_button);
        clearLogButton_.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                uiLog_.setText("");
            }
        });

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        networkThread_.close();
        streamPlayer_.stop();
        if (currentStreamFetcher_ != null) {
            currentStreamFetcher_.close();
        }
    }
}
