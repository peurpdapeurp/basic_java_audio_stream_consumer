package com.example.audio_consumer.stream_fetcher;

import android.util.Log;

import com.example.audio_consumer.Constants;

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

    boolean currentlyFetchingStream_ = false;
    Name currentStreamName_;
    PriorityQueue<Long> requestQueue_;
    SegNumGenerator segNumGenerator_;
    RequestSender requestSender_;
    LinkedTransferQueue<Interest> interestOutputQueue_;
    RttEstimator rttEstimator_;
    HashMap<Long, Timer> interestTimers_;

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
        rttEstimator_.clear();
        currentlyFetchingStream_ = false;
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
    public void onAudioPacketReceived(Data audioPacket, long sentTime, long satisfiedTime) {
        rttEstimator_.addNewRtt(satisfiedTime - sentTime);
        long segmentNumber;
        try {
            segmentNumber = audioPacket.getName().get(-1).toSequenceNumber();
        } catch (EncodingException e) {
            e.printStackTrace();
            return;
        }
        Log.d(TAG, "Got packet with segment number " + segmentNumber);
        Timer rtoTimer = interestTimers_.get(segmentNumber);
        rtoTimer.cancel();
        rtoTimer.purge();
    }

    @Override
    public void onInterestTimeout(Interest interest, long timeoutTime) {

    }

    private class RttEstimator {

        private static final String TAG = "RttEstimator";

        // see https://tools.ietf.org/html/rfc793, "An Example Retransmission Timeout Procedure"
        public static final long INITIAL_RTT_ESTIMATE = 2000;
        public static final double ALPHA = .8;
        public static final long UBOUND = 60000; // max rto in milliseconds
        public static final long LBOUND = 500; // min rto in milliseconds
        public static final double BETA = 1.3;

        double currentRttEstimate_; // current RTT estimate in milliseconds

        public RttEstimator() {
            currentRttEstimate_ = INITIAL_RTT_ESTIMATE;
        }

        // see https://tools.ietf.org/html/rfc793, "An Example Retransmission Timeout Procedure"
        public void addNewRtt(long newRtt) {
            Log.d(TAG, "Calculating new rtt estimate as : " + "(" + ALPHA + " * " + currentRttEstimate_ + ")" + " + " + "(" + (1-ALPHA) + " * " + newRtt + ")");
            currentRttEstimate_ = (ALPHA * currentRttEstimate_) + ((1 - ALPHA) * newRtt);
            Log.d(TAG, "Calculated new rtt estimate: " + currentRttEstimate_);
        }

        // see https://tools.ietf.org/html/rfc793, "An Example Retransmission Timeout Procedure"
        public long getRto() {
            Log.d(TAG, "Calculating rto as: " + "Math.min(" + UBOUND + ", " + "Math.max(" + LBOUND + ", " + "(" + BETA + " * " + currentRttEstimate_ + "))");
            return Math.min(UBOUND,Math.max(LBOUND, (long) (BETA * currentRttEstimate_)));
        }

        public void clear() {
            currentRttEstimate_ = INITIAL_RTT_ESTIMATE;
        }

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
                    long rto = rttEstimator_.getRto();
                    Log.d(TAG, "Sending interest with rto: " + rto);
                    interestToSend.setInterestLifetimeMilliseconds(rto);
                    Timer rtoTimer = new Timer();
                    rtoTimer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            Log.d(TAG, "Rto timer for segment number " + currentSegNum + " timed out.");
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
