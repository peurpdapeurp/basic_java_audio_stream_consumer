package com.example.audio_consumer.stream_fetcher;

import android.util.Log;

import com.example.audio_consumer.Constants;
import com.example.audio_consumer.Helpers;

import net.named_data.jndn.ContentType;
import net.named_data.jndn.Data;
import net.named_data.jndn.Interest;
import net.named_data.jndn.Name;
import net.named_data.jndn.encoding.EncodingException;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.PriorityBlockingQueue;

public class AlternateStreamFetcher {

    private static final String TAG = "StreamFetcher";

    private final int FINAL_BLOCK_ID_UNKNOWN = -1,
                        NO_SEGS_SENT = -1;

    boolean currentlyFetchingStream_ = false;
    Name currentStreamName_;
    PriorityBlockingQueue<Long> retransmissionQueue_;
    LinkedTransferQueue<DataAndTimestamp> pendingDataQueue_;
    LinkedTransferQueue<Interest> interestOutputQueue_;
    RttEstimator rttEstimator_;
    CwndCalculator cwndCalculator_;
    long currentStreamFinalBlockId_ = FINAL_BLOCK_ID_UNKNOWN,
            highestSegSent_ = NO_SEGS_SENT,
            msPerSegNum_,
            streamFetchStartTime_;
    int numOutstandingInterests_ = 0;
    ConcurrentHashMap<Long, Long> segSendTimes_;
    ConcurrentHashMap<Long, Timer> rtoTimers_;
    ProcessLoop processLoop_;

    public class DataAndTimestamp {
        public DataAndTimestamp(Data data, long receiveTime) {
            data_ = data;
            receiveTime_ = receiveTime;
        }
        Data data_;
        long receiveTime_;
    }

    /**
     * @param producerSamplingRate Audio sampling rate of producer (samples per second).
     * @param framesPerSegment ADTS frames per segment.
     */
    public AlternateStreamFetcher(LinkedTransferQueue interestOutputQueue, LinkedTransferQueue<DataAndTimestamp> pendingDataQueue,
                                  Name streamName, long producerSamplingRate, long framesPerSegment) {
        retransmissionQueue_ = new PriorityBlockingQueue<>();
        interestOutputQueue_ = interestOutputQueue;
        rttEstimator_ = new RttEstimator();
        cwndCalculator_ = new CwndCalculator();
        rtoTimers_ = new ConcurrentHashMap<>();
        segSendTimes_ = new ConcurrentHashMap<>();
        pendingDataQueue_ = pendingDataQueue;
        processLoop_ = new ProcessLoop();
        currentStreamName_ = streamName;
        Log.d(TAG, "Calculating msPerSegNum as : " + "\n" +
                "(" + framesPerSegment + " * " + Constants.SAMPLES_PER_ADTS_FRAME +
                " * " + Constants.MILLISECONDS_PER_SECOND + ")" + " / " + producerSamplingRate + ")" );
        msPerSegNum_ = (framesPerSegment * Constants.SAMPLES_PER_ADTS_FRAME *
                Constants.MILLISECONDS_PER_SECOND) / producerSamplingRate;
        Log.d(TAG, "Generating segment numbers to fetch at " + msPerSegNum_ + " per segment number.");
    }

    public void close() {
        retransmissionQueue_.clear();
        segSendTimes_.clear();
        numOutstandingInterests_ = 0;
        highestSegSent_ = 0;
        for (Timer t : rtoTimers_.values()) {
            t.cancel();
            t.purge();
        }
        rtoTimers_.clear();
        currentStreamName_ = null;
        currentlyFetchingStream_ = false;
        currentStreamFinalBlockId_ = FINAL_BLOCK_ID_UNKNOWN;
        processLoop_.stop();
        Log.d(TAG, "Close called.");
    }

    public boolean startFetchingStream() {
        if (currentlyFetchingStream_) {
            Log.w(TAG, "Already fetching a stream with name : " + currentStreamName_.toUri());
            return false;
        }
        currentlyFetchingStream_ = true;
        streamFetchStartTime_ = Helpers.currentUnixTimeMilliseconds();
        processLoop_.start();
        return true;
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

    private boolean withinCwnd() {
        return numOutstandingInterests_ < cwndCalculator_.getCurrentCwnd();
    }

    private void transmitInterest(Long segNum, boolean isRetransmission) {
        Log.d(TAG, "Sending " + (isRetransmission ? "retransmitted " : "") + "interest with seg num " + segNum);
        Interest interestToSend = new Interest(currentStreamName_);
        interestToSend.getName().appendSegment(segNum);
        long rto = (long) rttEstimator_.getEstimatedRto();
        Log.d(TAG, "Sending interest with rto: " + rto);
        interestToSend.setInterestLifetimeMilliseconds(rto);
        interestToSend.setCanBePrefix(false);
        interestToSend.setMustBeFresh(false);
        Timer rtoTimer = new Timer();
        rtoTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                Log.d(TAG, "Rto timer for segment number " + segNum + " timed out.");
                retransmissionQueue_.add(segNum);
            }
        }, rto);
        rtoTimers_.put(segNum, rtoTimer);
        interestOutputQueue_.add(interestToSend);
        if (isRetransmission) {
            segSendTimes_.remove(segNum);
        } else {
            segSendTimes_.put(segNum, Helpers.currentUnixTimeMilliseconds());
        }
        numOutstandingInterests_++;
        Log.d(TAG, "Current number of outstanding interests: " + numOutstandingInterests_);
    }

    private boolean nextSegShouldBeSent() {
        long timeSinceFetchStart = Helpers.currentUnixTimeMilliseconds() - streamFetchStartTime_;
        Log.d(TAG, "Time since stream fetching started: " + timeSinceFetchStart);
        Log.d(TAG, "Highest segment number that should have been sent by now: " + timeSinceFetchStart / msPerSegNum_);
        Log.d(TAG, "Highest segment number sent so far: " + highestSegSent_);
        boolean nextSegShouldBeSent = false;
        if (timeSinceFetchStart / msPerSegNum_ > highestSegSent_) {
            nextSegShouldBeSent = true;
        }
        Log.d(TAG, "Detected that next seg should " + (nextSegShouldBeSent ? "" : "not ") + "be sent.");
        return nextSegShouldBeSent;
    }

    private void processData() {
        while (pendingDataQueue_.size() != 0) {
            DataAndTimestamp audioPacketAndReceiveTime = pendingDataQueue_.poll();
            if (audioPacketAndReceiveTime == null) continue;
            Data audioPacket = audioPacketAndReceiveTime.data_;
            long receiveTime = audioPacketAndReceiveTime.receiveTime_;
            Log.d(TAG, "Got audio packet with name: " + audioPacket.getName());
            long segNum;
            try {
                segNum = audioPacket.getName().get(-1).toSegment();
            } catch (EncodingException e) {
                e.printStackTrace();
                return;
            }
            Log.d(TAG, "Got packet with segment number " + segNum);

            if (!segSendTimes_.contains(segNum)) {
                Log.d(TAG, "Segment number " + segNum + " was retransmitted, not using it for RTT estimation.");
            } else {
                long rtt = segSendTimes_.get(segNum) - receiveTime;
                        Log.d(TAG, "Segment number " + segNum + " was not retransmitted." + " \n" +
                        "Adding measurement to rtt estimator: " + "\n" +
                        "RTT: " + rtt + "\n" +
                        "Number of outstanding interests: " + numOutstandingInterests_);
                rttEstimator_.addMeasurement(rtt, numOutstandingInterests_);
                segSendTimes_.remove(segNum);
            }

            Timer rtoTimer = rtoTimers_.get(segNum);
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
                    }
                    catch (EncodingException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private class ProcessLoop implements Runnable {

        private static final String TAG = "StreamFetcherProcessLoop";

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

            try {
                while (!Thread.interrupted()) {
                    while (retransmissionQueue_.size() != 0 && withinCwnd()) {
                        Long segNum = retransmissionQueue_.poll();
                        if (segNum == null) continue;
                        transmitInterest(segNum, true);
                    }
                    while (nextSegShouldBeSent() && withinCwnd()) {
                        highestSegSent_++;
                        transmitInterest(highestSegSent_, false);
                    }
                    processData();
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            Log.d(TAG,"Stopped.");

        }
    }

}
