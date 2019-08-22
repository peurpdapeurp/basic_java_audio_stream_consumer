package com.example.audio_consumer.stream_fetcher;

import android.util.Log;

import com.example.audio_consumer.Constants;
import com.example.audio_consumer.Helpers;

import net.named_data.jndn.ContentType;
import net.named_data.jndn.Data;
import net.named_data.jndn.Interest;
import net.named_data.jndn.Name;
import net.named_data.jndn.encoding.EncodingException;

import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.LinkedTransferQueue;

public class StreamFetchManager implements NetworkThread.Observer {

    private static final String TAG = "StreamFetchManager";

    private final int FINAL_BLOCK_ID_UNKNOWN = -1;

    boolean currentlyFetchingStream_ = false;
    Name currentStreamName_;
    PriorityQueue<Long> requestQueue_;
    SegNumGenerator segNumGenerator_;
    RequestSender requestSender_;
    LinkedTransferQueue<Interest> interestOutputQueue_;
    RttEstimator rttEstimator_;
    HashMap<Long, Timer> interestTimers_;
    long currentStreamFinalBlockId_ = FINAL_BLOCK_ID_UNKNOWN;

    public StreamFetchManager(LinkedTransferQueue interestOutputQueue) {
        requestQueue_ = new PriorityQueue<>();
        segNumGenerator_ = new SegNumGenerator();
        requestSender_ = new RequestSender();
        interestOutputQueue_ = interestOutputQueue;
        rttEstimator_ = new RttEstimator();
        interestTimers_ = new HashMap<>();
    }

    public void stop() {
        segNumGenerator_.stop();
        requestSender_.stop();
        requestQueue_.clear();
        currentStreamName_ = null;
        currentlyFetchingStream_ = false;
        currentStreamFinalBlockId_ = FINAL_BLOCK_ID_UNKNOWN;
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
        requestSender_.start();
        currentlyFetchingStream_ = true;
        return true;
    }

    @Override
    public void onAudioPacketReceived(Data audioPacket, long sentTime, long satisfiedTime,
                                      int outstandingInterests) {
        Log.d(TAG, "Got audio packet with name: " + audioPacket.getName());
        rttEstimator_.addMeasurement(satisfiedTime-sentTime, outstandingInterests);
        long segmentNumber;
        try {
            segmentNumber = audioPacket.getName().get(-1).toSegment();
        } catch (EncodingException e) {
            e.printStackTrace();
            return;
        }
        Log.d(TAG, "Got packet with segment number " + segmentNumber);
        Timer rtoTimer = interestTimers_.get(segmentNumber);
        rtoTimer.cancel();
        rtoTimer.purge();

        if (audioPacket.getMetaInfo().getType() == ContentType.NACK) {
            long finalBlockId = Helpers.bytesToLong(audioPacket.getContent().getImmutableArray());
            Log.d(TAG, "Final block id found in application nack: " + finalBlockId);
            currentStreamFinalBlockId_ = finalBlockId;
        }
        else {
            Name.Component finalBlockIdComponent = audioPacket.getMetaInfo().getFinalBlockId();
            if (finalBlockIdComponent != null) {
                try {
                    long finalBlockId = finalBlockIdComponent.toSegment();
                    Log.d(TAG, "Got a packet with final block id of " + finalBlockId);
                    currentStreamFinalBlockId_ = finalBlockId;
                } catch (EncodingException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void onInterestTimeout(Interest interest, long timeoutTime) {

    }

    private class CwndCalculator {

        private static final String TAG = "CwndCalculator";

        private static final long MAX_CWND = 50; // max # of outstanding interests

        long currentCwnd_;

        public CwndCalculator() {
            currentCwnd_ = MAX_CWND;
        }

        public long getCurrentCwnd() {
            return currentCwnd_;
        }

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

    private class RequestSender implements Runnable {

        private static final String TAG = "RequestSender";

        private Thread t_;

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

        @Override
        public void run() {

            Log.d(TAG,"Started.");

            while (!Thread.interrupted()) {
                if (requestQueue_.size() != 0) {
                    Long currentSegNum = requestQueue_.poll();
                    if (currentSegNum == null) continue;
                    Log.d(TAG, "Detected segment number " + currentSegNum + " in request queue.");
                    Interest interestToSend = new Interest(currentStreamName_);
                    interestToSend.getName().appendSegment(currentSegNum);
                    long rto = (long) rttEstimator_.getEstimatedRto();
                    Log.d(TAG, "Sending interest with rto: " + rto);
                    interestToSend.setInterestLifetimeMilliseconds(rto);
                    interestToSend.setCanBePrefix(false);
                    interestToSend.setMustBeFresh(false);
                    Timer rtoTimer = new Timer();
                    rtoTimer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            Log.d(TAG, "Rto timer for segment number " + currentSegNum + " timed out.");
                            requestQueue_.add(currentSegNum);
                        }
                    }, rto);
                    interestTimers_.put(currentSegNum, rtoTimer);
                    interestOutputQueue_.add(interestToSend);
                }
            }

            Log.d(TAG,"Stopped.");

        }
    }

}
