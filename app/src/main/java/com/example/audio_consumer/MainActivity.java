
package com.example.audio_consumer;

import androidx.appcompat.app.AppCompatActivity;

import android.media.MediaPlayer;
import android.os.Bundle;
import android.util.Log;

import java.io.IOException;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        try {
            AACADTSAudioBundleSource source = new AACADTSAudioBundleSource(/*put data source here*/);
            MediaPlayer player = new MediaPlayer();
            player.setDataSource(source);
            player.prepare();
            player.start();
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mediaPlayer) {
                    Log.d(TAG, "Finished playing an AAC ADTS frame.");
                }
            });
        }
        catch (IOException e) { e.printStackTrace(); }
    }
}
