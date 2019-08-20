package com.example.audio_consumer;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class StreamPlayerTester {

    private final static String TAG = "StreamPlayerTester";

    Context ctx_;

    private InputStream is_;
    private OutputStream os_;

    private ParcelFileDescriptor[] parcelFileDescriptors_;
    private ParcelFileDescriptor parcelRead_;
    private ParcelFileDescriptor parcelWrite_;

    AudioWritingThread awt_;

    public StreamPlayerTester(Context ctx, byte[][] frames) {

        ctx_ = ctx;

        try {
            parcelFileDescriptors_ = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            e.printStackTrace();
        }

        parcelRead_ = new ParcelFileDescriptor(parcelFileDescriptors_[0]);
        parcelWrite_ = new ParcelFileDescriptor(parcelFileDescriptors_[1]);

        is_ = new ParcelFileDescriptor.AutoCloseInputStream(parcelRead_);
        os_ = new ParcelFileDescriptor.AutoCloseOutputStream(parcelWrite_);

        awt_ = new AudioWritingThread(os_, frames);

    }

    public void startPlaying() {

        Log.d(TAG,"StreamPlayerTester started.");

        ExoPlayer player = ExoPlayerFactory.newSimpleInstance(ctx_, new DefaultTrackSelector(),
                                                              new DefaultLoadControl.Builder()
                                                              .setBackBuffer(600, false)
                                                              .setBufferDurationsMs(600,
                                                                                    600,
                                                                              600,
                                                                    600)
                                                              .setTargetBufferBytes(600)
                                                              .createDefaultLoadControl());

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
                Log.d(TAG, "(Playing from input stream) Exoplayer state changed to: " + playbackStateString);
            }
        });

        player.prepare(audioSource);

        player.setPlayWhenReady(true);

        awt_.start();

        Log.d(TAG,"StreamPlayerTester stopped.");

    }

    private class AudioWritingThread implements Runnable {

        private final static String TAG = "AudioWritingThread";

        private Thread t_;
        private OutputStream os_;
        private byte[][] frames_;

        AudioWritingThread(OutputStream os, byte[][] frames) {
            os_ = os;
            frames_ = frames;
        }

        public void start() {
            if (t_ == null) {
                t_ = new Thread(this);
                t_.start();
            }
        }

        public void stop() {
            if (t_ != null) {
                t_.interrupt();
                try {
                    t_.join();
                } catch (InterruptedException e) {}
                t_ = null;
            }
        }

        public void run() {

            Log.d(TAG,"AudioWritingThread started.");

            try {
                 // for (int j = 0; j < 1000; j++) {
                    for (int i = 0; i < frames_.length; i++) {
                        os_.write(frames_[i]);
                    }
                // }
            } catch (IOException e) {
                e.printStackTrace();
            }

            Log.d(TAG,"AudioWritingThread stopped.");

        }

    }

}
