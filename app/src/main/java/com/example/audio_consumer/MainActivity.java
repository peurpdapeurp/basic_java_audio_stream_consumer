
package com.example.audio_consumer;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        String filePath = getExternalCacheDir().getAbsolutePath() + "/" + "test.aac";

        AudioProcessingHelpers.writeTestAudioToFile(filePath);

        AudioProcessingHelpers.playAudioFileFromCacheWithExoPlayer(this, filePath);

        AudioProcessingHelpers.playAudioFileFromInputStreamWithExoPlayer(this, TestFrames.MUSIC_ADTS_FRAME_BUFFERS);

    }

}
