
package com.example.audio_consumer;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
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

public class MainActivity extends AppCompatActivity implements NetworkThread.Observer {

    private static final String TAG = "MainActivity";

    Button startFetchingButton_;
    Button stopFetchingButton_;
    Button clearLogButton_;
    TextView uiLog_;
    EditText streamNameInput_;
    EditText streamIdInput_;

    StreamFetcher currentStreamFetcher_;
    StreamPlayer streamPlayer_;
    NetworkThread networkThread_;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        uiLog_ = (TextView) findViewById(R.id.ui_log);
        streamNameInput_ = (EditText) findViewById(R.id.stream_name_input);
        streamIdInput_ = (EditText) findViewById(R.id.stream_id_input);

        LinkedTransferQueue<Interest> interestTransferQueue = new LinkedTransferQueue<>();

        streamPlayer_ = new StreamPlayer(this);
        networkThread_ = new NetworkThread(interestTransferQueue);
        networkThread_.addObserver(streamPlayer_);
        networkThread_.addObserver(this);

        networkThread_.start();
        streamPlayer_.start();

        startFetchingButton_ = (Button) findViewById(R.id.start_fetch_button);
        startFetchingButton_.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                currentStreamFetcher_ = new StreamFetcher(interestTransferQueue);
                networkThread_.addObserver(currentStreamFetcher_);
                Name streamName = new Name(getString(R.string.network_prefix))
                        .append(streamNameInput_.getText().toString())
                        .append(streamIdInput_.getText().toString())
                        .appendVersion(0);
                boolean ret = currentStreamFetcher_.startFetchingStream(
                        streamName, 8000, 10);
                Log.d(TAG, (ret ? "Successfully began fetching stream" : "Failed to start fetching stream") +
                            " with name: " + streamName.toUri());
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        uiLog_.append("---" + "Time: " + System.currentTimeMillis() + "---" + "\n" +
                                (ret ? "Successfully began fetching stream" : "Failed to start fetching stream") +
                                " with name: " + streamName.toUri() + "\n" +
                                "\n");
                    }
                });
            }
        });

        stopFetchingButton_ = (Button) findViewById(R.id.stop_fetch_button);
        stopFetchingButton_.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                currentStreamFetcher_.close();
                networkThread_.removeObserver(currentStreamFetcher_);
                currentStreamFetcher_ = null;
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
    public void onAudioPacketReceived(Data audioPacket, long sentTime, long satisfiedTime,
                                      int outstandingInterests) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                uiLog_.append("---" + "Time: " + System.currentTimeMillis() + "---" + "\n" +
                        "Retrieved audio data packet." + "\n" +
                        "Name: " + audioPacket.getName() + "\n" +
                        "Interest send time: " + sentTime + "\n" +
                        "Data received time: " + satisfiedTime + "\n" +
                        "Rtt: " + (satisfiedTime - sentTime) + "\n" +
                        "\n");
            }
        });
    }

    @Override
    public void onInterestTimeout(Interest interest, long timeoutTime) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                uiLog_.append("---" + "Time: " + System.currentTimeMillis() + "---" + "\n" +
                        "Interest timed out." + "\n" +
                        "Name: " + interest.getName() + "\n" +
                        "Timeout time: " + timeoutTime + "\n" +
                        "\n");
            }
        });
    }


}
