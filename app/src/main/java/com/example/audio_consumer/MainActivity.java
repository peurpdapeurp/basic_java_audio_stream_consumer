
package com.example.audio_consumer;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    StreamFrameBundler streamFrameBundler_;
    StreamPlayer player_;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        streamFrameBundler_ = new StreamFrameBundler(TestFrames.AAC);
        byte[][] bundles = streamFrameBundler_.getBundles();

        player_ = new StreamPlayer();
        player_.start();
        for (int i = 0; i < bundles.length; i++) {
            player_.giveAudioBundle(bundles[i]);
        }



    }

}
