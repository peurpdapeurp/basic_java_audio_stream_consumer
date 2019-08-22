
package com.example.audio_consumer;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.util.Log;

import com.example.audio_consumer.stream_fetcher.NetworkThread;
import com.example.audio_consumer.stream_fetcher.StreamFetchManager;
import com.example.audio_consumer.stream_fetcher.StreamPlayer;

import net.named_data.jndn.Interest;
import net.named_data.jndn.Name;

import java.util.ArrayList;
import java.util.concurrent.LinkedTransferQueue;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    StreamPlayer streamPlayer_;
    StreamFetchManager streamFetchManager_;
    NetworkThread networkThread_;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        LinkedTransferQueue<Interest> interestTransferQueue = new LinkedTransferQueue<>();

        streamFetchManager_ = new StreamFetchManager(interestTransferQueue);
        streamPlayer_ = new StreamPlayer(this);
        ArrayList<NetworkThread.Observer> networkThreadObservers = new ArrayList<NetworkThread.Observer>();
        networkThreadObservers.add(streamFetchManager_);
        networkThreadObservers.add(streamPlayer_);
        networkThread_ = new NetworkThread(networkThreadObservers, interestTransferQueue);

        networkThread_.start();
        streamPlayer_.start();

        boolean ret = streamFetchManager_.startFetchingStream(new Name("/dummy/name"), 8000, 10);

        Log.d(TAG, ret ? "Successfully began fetching stream." : "Failed to start fetching stream.");

    }

}
