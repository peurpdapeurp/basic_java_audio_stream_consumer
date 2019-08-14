package com.example.audio_consumer;

import android.media.MediaPlayer;
import android.util.Log;

import java.util.concurrent.LinkedTransferQueue;

public class StreamPlayer implements Runnable {

    private static final String TAG = "StreamPlayer";

    private LinkedTransferQueue<byte[]> input_queue;
    private MediaPlayer player_;
    boolean currentlyPlaying_ = false;

    private Thread t_;

    public StreamPlayer() {
        input_queue = new LinkedTransferQueue<byte[]>();
        player_ = new MediaPlayer();
    }

    public void start() {
        if (t_ == null) {
            t_ = new Thread(this);
            t_.start();
        }
    }

    public void stop() {
        player_.release();
        player_ = null;
        if (t_ != null) {
            t_.interrupt();
            try {
                t_.join();
            } catch (InterruptedException e) {}
            t_ = null;
        }
    }

    public void giveAudioBundle(byte[] bundle) {
        input_queue.add(bundle);
    }

    @Override
    public void run() {

        byte[] currentBundle;

        while (!Thread.interrupted()) {

            if (input_queue.size() != 0) {
                currentBundle = input_queue.poll();
            }
            else { continue; }

            AACADTSAudioBundleSource source = new AACADTSAudioBundleSource(currentBundle);
            player_.setDataSource(source);
            try {
                player_.prepare();
            } catch (Exception e) { e.printStackTrace(); continue; }
            player_.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mediaPlayer) {
                    Log.d(TAG, "Finished playing an audio bundle.");
                    player_.reset();
                    currentlyPlaying_ = false;
                }
            });
            player_.start();
            currentlyPlaying_ = true;
            while (currentlyPlaying_) { }
            Log.d(TAG, "Got to end of while loop iteration.");
        }

    }

}
