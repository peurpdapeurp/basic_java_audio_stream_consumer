package com.example.audio_consumer.stream_fetcher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.audio_consumer.stream_fetcher.exoplayer_customization.AdtsExtractorFactory;
import com.example.audio_consumer.stream_fetcher.exoplayer_customization.InputStreamDataSource;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;

import net.named_data.jndn.Data;
import net.named_data.jndn.Interest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.LinkedTransferQueue;

public class StreamPlayer {

    private final static String TAG = "StreamPlayer";

    // Private Constants
    public static final int AWT_PROCESSING_INTERVAL_MS = 100;

    // Messages
    private static final int AWT_MSG_DO_SOME_WORK = 0;
    public static final int AWT_MSG_ADTS_FRAMES_RECEIVED = 1;

    private Context ctx_;
    private InternalPipe iPipe_;
    private AudioWritingThread awt_;

    public StreamPlayer(Context ctx) {
        ctx_ = ctx;
        iPipe_ = new InternalPipe();
        awt_ = new AudioWritingThread(iPipe_.getOutputStream());
    }

    public void stop() {
        awt_.close();
    }

    public void start() {

        Log.d(TAG,"StreamPlayer started.");

        ExoPlayer player = ExoPlayerFactory.newSimpleInstance(ctx_, new DefaultTrackSelector(),
                                                              new DefaultLoadControl.Builder()
                                                              .setBackBuffer(600, false)
                                                              .setBufferDurationsMs(600, 600, 600, 600)
                                                              .setTargetBufferBytes(600)
                                                              .createDefaultLoadControl());
        // This is the MediaSource representing the media to be played.
        MediaSource audioSource = new ProgressiveMediaSource.Factory(
                () -> {
                    InputStreamDataSource dataSource =
                            new InputStreamDataSource(iPipe_.getInputStream());
                    return dataSource;
                },
                new AdtsExtractorFactory())
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
                Log.d(TAG, System.currentTimeMillis() + ": " + "Exoplayer state changed to: " + playbackStateString);
            }
        });

        player.prepare(audioSource);

        player.setPlayWhenReady(true);

        awt_.start();

        Log.d(TAG,"StreamPlayer stopped.");

    }

    private class InternalPipe {

        private InputStream is_;
        private OutputStream os_;
        private ParcelFileDescriptor[] parcelFileDescriptors_;
        private ParcelFileDescriptor parcelRead_;
        private ParcelFileDescriptor parcelWrite_;

        public InternalPipe() {
            try {
                parcelFileDescriptors_ = ParcelFileDescriptor.createPipe();
            } catch (IOException e) {
                e.printStackTrace();
            }

            parcelRead_ = new ParcelFileDescriptor(parcelFileDescriptors_[0]);
            parcelWrite_ = new ParcelFileDescriptor(parcelFileDescriptors_[1]);

            is_ = new ParcelFileDescriptor.AutoCloseInputStream(parcelRead_);
            os_ = new ParcelFileDescriptor.AutoCloseOutputStream(parcelWrite_);
        }

        public InputStream getInputStream() {
            return is_;
        }

        public OutputStream getOutputStream() {
            return os_;
        }

    }

    private class AudioWritingThread extends HandlerThread {

        private final static String TAG = "StreamPlayerAwt";

        private OutputStream os_;
        private LinkedTransferQueue<byte[]> inputQueue_;
        private Handler handler_;
        private boolean closed_ = false;
        private long startTime_;

        private long getTimeSinceStart() {
            return System.currentTimeMillis() - startTime_;
        }

        AudioWritingThread(OutputStream os) {
            super("StreamPlayerAwt");
            os_ = os;
            inputQueue_ = new LinkedTransferQueue();
        }

        public void close() {
            Log.d(TAG, getTimeSinceStart() + ": " + "close called");
            handler_.removeCallbacksAndMessages(null);
            handler_.getLooper().quitSafely();
            closed_ = true;
        }

        private void doSomeWork() {

            if (closed_) return;

            try {
                if (inputQueue_.size() != 0) {
                    byte[] currentFrames = inputQueue_.poll();
                    if (currentFrames == null) return;
                    os_.write(currentFrames);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            scheduleNextWork(SystemClock.uptimeMillis(), AWT_PROCESSING_INTERVAL_MS);
        }

        private void processAdtsFrames(byte[] adtsFrames) {
            inputQueue_.add(adtsFrames);
        }

        private void scheduleNextWork(long thisOperationStartTimeMs, long intervalMs) {
            handler_.removeMessages(AWT_MSG_DO_SOME_WORK);
            handler_.sendEmptyMessageAtTime(AWT_MSG_DO_SOME_WORK, thisOperationStartTimeMs + intervalMs);
        }

        @SuppressLint("HandlerLeak")
        @Override
        protected void onLooperPrepared() {
            super.onLooperPrepared();

            startTime_ = System.currentTimeMillis();

            handler_ = new Handler() {
                @Override
                public void handleMessage(@NonNull Message msg) {
                    switch (msg.what) {
                        case AWT_MSG_DO_SOME_WORK:
                            doSomeWork();
                            break;
                        case AWT_MSG_ADTS_FRAMES_RECEIVED:
                            Log.d(TAG, getTimeSinceStart() + ": " + "adts frames received");
                            processAdtsFrames((byte[]) msg.obj);
                            break;
                        default:
                            throw new IllegalStateException();
                    }
                }
            };

            doSomeWork();
        }

        public Handler getHandler() {
            return handler_;
        }
    }

    public Handler getHandler() {
        return awt_.getHandler();
    }

}
