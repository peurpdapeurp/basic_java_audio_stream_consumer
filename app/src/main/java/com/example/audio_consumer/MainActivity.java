
package com.example.audio_consumer;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    StreamPlayer streamPlayer_;
    int streamRepeats_ = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        String filePath = getExternalCacheDir().getAbsolutePath() + "/" + "test.aac";

        AudioProcessingHelpers.writeTestAudioToFile(filePath);

        streamPlayer_ = new StreamPlayer(this);
        streamPlayer_.start();
        for (int i = 0; i < TestFrames.MUSIC_ADTS_FRAME_BUFFERS.length; i++) {
            streamPlayer_.writeADTSFrames(TestFrames.MUSIC_ADTS_FRAME_BUFFERS[i]);
        }

    }

}
