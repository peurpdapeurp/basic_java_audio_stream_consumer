
package com.example.audio_consumer;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.example.audio_consumer.stream_fetcher.NetworkThread;
import com.example.audio_consumer.stream_fetcher.StreamFetchManager;
import com.example.audio_consumer.stream_fetcher.StreamPlayer;

import net.named_data.jndn.Data;
import net.named_data.jndn.Interest;
import net.named_data.jndn.Name;

import java.util.ArrayList;
import java.util.concurrent.LinkedTransferQueue;

public class MainActivity extends AppCompatActivity implements NetworkThread.Observer {

    private static final String TAG = "MainActivity";

    Button startFetchingButton_;
    Button stopFetchingButton_;
    TextView uiLog_;
    EditText streamNameInput_;
    EditText streamIdInput_;

    StreamPlayer streamPlayer_;
    StreamFetchManager streamFetchManager_;
    NetworkThread networkThread_;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        uiLog_ = (TextView) findViewById(R.id.ui_log);
        streamNameInput_ = (EditText) findViewById(R.id.stream_name_input);
        streamIdInput_ = (EditText) findViewById(R.id.stream_id_input);

        LinkedTransferQueue<Interest> interestTransferQueue = new LinkedTransferQueue<>();

        streamFetchManager_ = new StreamFetchManager(interestTransferQueue);
        streamPlayer_ = new StreamPlayer(this);
        ArrayList<NetworkThread.Observer> networkThreadObservers = new ArrayList<NetworkThread.Observer>();
        networkThreadObservers.add(streamFetchManager_);
        networkThreadObservers.add(streamPlayer_);
        networkThreadObservers.add(this);
        networkThread_ = new NetworkThread(networkThreadObservers, interestTransferQueue);

        networkThread_.start();
        streamPlayer_.start();

        startFetchingButton_ = (Button) findViewById(R.id.start_fetch_button);
        startFetchingButton_.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Name streamName = new Name(getString(R.string.network_prefix))
                        .append(streamNameInput_.getText().toString())
                        .append(streamIdInput_.getText().toString())
                        .appendVersion(0);
                boolean ret = streamFetchManager_.startFetchingStream(
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
                streamFetchManager_.stop();
            }
        });

    }

    @Override
    public void onAudioPacketReceived(Data audioPacket, long sentTime, long satisfiedTime) {
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
