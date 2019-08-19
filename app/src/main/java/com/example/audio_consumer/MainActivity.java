
package com.example.audio_consumer;

import androidx.appcompat.app.AppCompatActivity;

import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    InputStream is_;
    OutputStream os_;

    ParcelFileDescriptor[] parcelFileDescriptors_;
    ParcelFileDescriptor parcelRead_;
    ParcelFileDescriptor parcelWrite_;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        String filePath = getExternalCacheDir().getAbsolutePath() + "/" + "test.aac";

        AudioProcessingHelpers.writeTestAudioToFile(filePath);

        try {
            parcelFileDescriptors_ = ParcelFileDescriptor.createPipe();
            parcelRead_ = new ParcelFileDescriptor(parcelFileDescriptors_[0]);
            parcelWrite_ = new ParcelFileDescriptor(parcelFileDescriptors_[1]);

            is_ = new ParcelFileDescriptor.AutoCloseInputStream(parcelRead_);
            os_ = new ParcelFileDescriptor.AutoCloseOutputStream(parcelWrite_);

            os_.write(TestFrames.MUSIC_ADTS_FRAME_BUFFERS[0]);
            os_.write(TestFrames.MUSIC_ADTS_FRAME_BUFFERS[1]);
            os_.write(TestFrames.MUSIC_ADTS_FRAME_BUFFERS[2]);
            os_.write(TestFrames.MUSIC_ADTS_FRAME_BUFFERS[3]);

        } catch (IOException e) {
            e.printStackTrace();
        }

        ExoPlayer player = ExoPlayerFactory.newSimpleInstance(this);

        // This is the MediaSource representing the media to be played.
        MediaSource audioSource = new ProgressiveMediaSource.Factory(
                () -> {
                    InputStreamDataSource dataSource =
                            new InputStreamDataSource(is_);
                    return dataSource;
                })
                .createMediaSource(Uri.parse("fake_uri"));

        player.addListener(new Player.EventListener() {
            @Override
            public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                String playbackStateString = "";
                switch (playbackState) {
                    case Player.STATE_IDLE:
                        playbackStateString = "STATE_IDLE";
                        break;
                    case Player.STATE_BUFFERING:
                        playbackStateString = "STATE_BUFFERING";
                        break;
                    case Player.STATE_READY:
                        playbackStateString = "STATE_READY";
                        break;
                    case Player.STATE_ENDED:
                        playbackStateString = "STATE_ENDED";
                        break;
                    default:
                        playbackStateString = "UNEXPECTED STATE CODE (" + playbackState + ")";
                }
                Log.d(TAG, "Exoplayer state changed to: " + playbackStateString);
            }
        });

        player.prepare(audioSource);

        player.setPlayWhenReady(true);
    }

}
