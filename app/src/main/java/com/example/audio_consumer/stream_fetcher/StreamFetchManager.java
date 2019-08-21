package com.example.audio_consumer.stream_fetcher;

import android.util.Log;

import com.example.audio_consumer.Constants;

import net.named_data.jndn.Data;
import net.named_data.jndn.Name;

import java.util.PriorityQueue;

public class StreamFetchManager implements NetworkThread.Observer {

    private static final String TAG = "StreamFetchManager";

    boolean currentlyFetchingStream_ = false;
    Name currentStreamName_;
    PriorityQueue<Long> requestQueue_;
    SegNumGenerator segNumGenerator_;

    public StreamFetchManager() {
        requestQueue_ = new PriorityQueue<>();
        segNumGenerator_ = new SegNumGenerator();
    }

    /**
     * @param producerSamplingRate Audio sampling rate of producer (samples per second).
     * @param framesPerSegment ADTS frames per segment.
     */
    public boolean startFetchingStream(Name streamName, long producerSamplingRate, long framesPerSegment) {
        if (currentlyFetchingStream_) {
            Log.w(TAG, "Already fetching a stream with name : " + currentStreamName_.toUri());
            return false;
        }
        requestQueue_.clear();
        currentStreamName_ = streamName;
        Log.d(TAG, "Calculating msPerSegNum as : " + "\n" +
                        "(" + framesPerSegment + " * " + Constants.SAMPLES_PER_ADTS_FRAME +
                " * " + Constants.MILLISECONDS_PER_SECOND + ")" + " / " + producerSamplingRate + ")" );
        long msPerSegNum = (framesPerSegment * Constants.SAMPLES_PER_ADTS_FRAME *
                            Constants.MILLISECONDS_PER_SECOND) / producerSamplingRate;
        Log.d(TAG, "Generating segment numbers to fetch at " + msPerSegNum + " per segment number.");
        segNumGenerator_.start(0, msPerSegNum);
        currentlyFetchingStream_ = true;
        return true;
    }

    @Override
    public void onAudioPacketReceived(Data audioPacket) {

    }

    private class RttEstimator {

    }

    private class CwndCalculator {

    }

    private class RtoCalculator {

    }

    private class SegNumGenerator implements Runnable {

        private static final String TAG = "SegNumGenerator";

        private Thread t_;
        private long initialSegNum_;
        private long msPerSegNum_; // how many milliseconds to wait between each segment number generation

        public void start(long initialSegNum, long msPerSegNum) {
            initialSegNum_ = initialSegNum;
            msPerSegNum_ = msPerSegNum;
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

        @Override
        public void run() {
            Log.d(TAG,"Started.");

            long currentSegNum = initialSegNum_;
            try {
                while (!Thread.interrupted()) {
                    Log.d(TAG, "Adding segment number " + currentSegNum + " to request queue.");
                    requestQueue_.add(currentSegNum);
                    currentSegNum++;
                    Thread.sleep(msPerSegNum_);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            Log.d(TAG,"Stopped.");
        }
    }

}
