package com.example.audio_consumer.stream_fetcher;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.NonNull;

import com.example.audio_consumer.Helpers;

import net.named_data.jndn.ContentType;
import net.named_data.jndn.Data;
import net.named_data.jndn.Interest;
import net.named_data.jndn.Name;
import net.named_data.jndn.encoding.EncodingException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.PriorityQueue;

public class StreamFetcher extends HandlerThread {

    private static final String TAG = "StreamFetcher";

    int printStateCounter_ = 0;

    // Private constants
    private static final int FINAL_BLOCK_ID_UNKNOWN = -1;
    private static final int NO_SEGS_SENT = -1;
    private static final int PROCESSING_INTERVAL_MS = 100;
    private static final int EVENT_DATA_RECEIVE = 0; // for outstanding interest counter
    private static final int EVENT_INTEREST_TIMEOUT = 1; // for outstanding interest counter
    private static final int EVENT_INTEREST_TRANSMIT = 2; // for outstanding interest counter

    // Messages
    public static final int MSG_DATA_RECEIVED = 0;
    private static final int MSG_DO_SOME_WORK = 1;
    public static final int MSG_NETWORK_THREAD_READY = 2;
    public static final int MSG_NETWORK_THREAD_FINISHED = 3;

    private boolean requestStreamFetchStartCalled_ = false;
    private PriorityQueue<Long> retransmissionQueue_;
    private Name streamName_;
    private long streamFinalBlockId_ = FINAL_BLOCK_ID_UNKNOWN;
    private long highestSegSent_ = NO_SEGS_SENT;
    private long msPerSegNum_;
    private long streamFetchStartTime_;
    private HashMap<Long, Long> segSendTimes_;
    private HashMap<Long, Object> rtoTokens_;
    private CwndCalculator cwndCalculator_;
    private RttEstimator rttEstimator_;
    private Handler handler_ = null;
    private Handler networkThreadHandler_;
    boolean closed_ = false;
    private int numOutstandingInterests_ = 0;
    private int numInterestsTransmitted_ = 0;
    private int numInterestTimeouts_ = 0;
    private int numDataReceives_ = 0;

    public static class DataInfo {
        public DataInfo(Data data, long receiveTime) {
            this.data = data;
            this.receiveTime = receiveTime;
        }
        Data data;
        long receiveTime;
    }

    private long getTimeSinceStreamFetchStart() {
        return System.currentTimeMillis() - streamFetchStartTime_;
    }

    private void printState() {
        Log.d(TAG, getTimeSinceStreamFetchStart() + ": " +
                "Current state of StreamFetcher:" + "\n" +
                "streamFinalBlockId_: " + streamFinalBlockId_ + ", " +
                "highestSegSent_: " + highestSegSent_ + ", " +
                "numInterestsTransmitted_: " + numInterestsTransmitted_ + ", " +
                "numInterestTimeouts_: " + numInterestTimeouts_ + ", " +
                "numDataReceives_: " + numDataReceives_ + ", " +
                "numOutstandingInterests_: " + numOutstandingInterests_ + "\n" +
                "retransmissionQueue_: " + retransmissionQueue_ + "\n" +
                "segSendTimes_: " + segSendTimes_);
    }

    public StreamFetcher(Name streamName, long msPerSegNum, Handler networkThreadHandler) {
        super(TAG);
        cwndCalculator_ = new CwndCalculator();
        retransmissionQueue_ = new PriorityQueue<>();
        streamName_ = streamName;
        segSendTimes_ = new HashMap<>();
        rtoTokens_ = new HashMap<>();
        rttEstimator_ = new RttEstimator();
        msPerSegNum_ = msPerSegNum;
        networkThreadHandler_ = networkThreadHandler;
        Log.d(TAG, "Generating segment numbers to fetch at " + msPerSegNum_ + " per segment number.");
    }

    public boolean requestStartStreamFetch() {
        Log.d(TAG, getTimeSinceStreamFetchStart() + ": " +
                "requestStartStreamFetch called");
        if (handler_ == null) return false;
        if (requestStreamFetchStartCalled_) {
            Log.w(TAG, "Already fetching a stream with name : " + streamName_.toUri());
            return false;
        }
        requestStreamFetchStartCalled_ = true;
        networkThreadHandler_
                .obtainMessage(NetworkThread.MSG_STREAM_FETCH_START,
                                new NetworkThread.StreamFetcherState(streamName_, handler_))
                .sendToTarget();
        return true;
    }

    private void startStreamFetch() {
        Log.d(TAG, getTimeSinceStreamFetchStart() + ": " +
                "startStreamFetch called");
        streamFetchStartTime_ = System.currentTimeMillis();
        doSomeWork(); // start the doSomeWork processing cycle
    }

    public void requestClose() {
        Log.d(TAG, getTimeSinceStreamFetchStart() + ": " +
                "requestClose called");
        networkThreadHandler_
                .obtainMessage(NetworkThread.MSG_STREAM_FETCH_FINISH, streamName_)
                .sendToTarget();
    }

    private void close() {
        Log.d(TAG, getTimeSinceStreamFetchStart() + ": " +
                "close called");
        handler_.removeCallbacksAndMessages(null);
        handler_.getLooper().quitSafely();
        closed_ = true;
    }

    public Handler getHandler() {
        return handler_;
    }

    private void doSomeWork() {

        if (closed_) return;

        while (retransmissionQueue_.size() != 0 && withinCwnd()) {
            Long segNum = retransmissionQueue_.poll();
            if (segNum == null) continue;
            transmitInterest(segNum, true);
        }

        if (retransmissionQueue_.size() == 0 && numOutstandingInterests_ == 0 &&
                streamFinalBlockId_ != FINAL_BLOCK_ID_UNKNOWN) {
            requestClose();
            return;
        }

        if (streamFinalBlockId_ == FINAL_BLOCK_ID_UNKNOWN ||
                highestSegSent_ < streamFinalBlockId_) {
            while (nextSegShouldBeSent() && withinCwnd()) {
                highestSegSent_++;
                transmitInterest(highestSegSent_, false);
            }
        }

        if (printStateCounter_ < 10) printStateCounter_++; else { printState(); printStateCounter_ = 0; }

        scheduleNextWork(SystemClock.uptimeMillis(), PROCESSING_INTERVAL_MS);
    }

    private void scheduleNextWork(long thisOperationStartTimeMs, long intervalMs) {
        handler_.removeMessages(MSG_DO_SOME_WORK);
        handler_.sendEmptyMessageAtTime(MSG_DO_SOME_WORK, thisOperationStartTimeMs + intervalMs);
    }

    private boolean nextSegShouldBeSent() {
        long timeSinceFetchStart = getTimeSinceStreamFetchStart();
        boolean nextSegShouldBeSent = false;
        if (timeSinceFetchStart / msPerSegNum_ > highestSegSent_) {
            nextSegShouldBeSent = true;
        }
        return nextSegShouldBeSent;
    }

    private void transmitInterest(final long segNum, boolean isRetransmission) {
        Interest interestToSend = new Interest(streamName_);
        interestToSend.getName().appendSegment(segNum);
        long rto = (long) rttEstimator_.getEstimatedRto();
        interestToSend.setInterestLifetimeMilliseconds(rto);
        interestToSend.setCanBePrefix(false);
        interestToSend.setMustBeFresh(false);
        Object rtoToken = new Object();
        handler_.postAtTime(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, getTimeSinceStreamFetchStart() + ": " + "rto timeout (seg num " + segNum + ")");
                modifyNumOutstandingInterests(-1, EVENT_INTEREST_TIMEOUT);
                retransmissionQueue_.add(segNum);
            }
        }, rtoToken, SystemClock.uptimeMillis() + rto);
        rtoTokens_.put(segNum, rtoToken);
        if (isRetransmission) {
            segSendTimes_.remove(segNum);
        } else {
            segSendTimes_.put(segNum, System.currentTimeMillis());
        }
        networkThreadHandler_.obtainMessage(NetworkThread.MSG_INTEREST_SEND_REQUEST, interestToSend).sendToTarget();
        modifyNumOutstandingInterests(1, EVENT_INTEREST_TRANSMIT);
        Log.d(TAG, getTimeSinceStreamFetchStart() + ": " +
                "interest transmitted (" +
                "seg num " + segNum + ", " +
                "rto " + rto + ", " +
                "retx: " + isRetransmission + ", " +
                "current num outstanding " + numOutstandingInterests_ +
                ")");
    }

    private void processData(DataInfo dataInfo) {
        Data audioPacket = dataInfo.data;
        long receiveTime = dataInfo.receiveTime;
        long segNum;
        try {
            segNum = audioPacket.getName().get(-1).toSegment();
        } catch (EncodingException e) {
            e.printStackTrace();
            return;
        }

        if (segSendTimes_.containsKey(segNum)) {
            long rtt = receiveTime - segSendTimes_.get(segNum);
            Log.d(TAG, getTimeSinceStreamFetchStart() + ": " +
                    "rtt estimator add measure (rtt " + rtt + ", " +
                    "num outstanding interests " + numOutstandingInterests_ +
                    ")");
            if (numOutstandingInterests_ <= 0) {
                throw new IllegalStateException(getTimeSinceStreamFetchStart() + ": " +
                        "detected bad measurements being fed to rtt estimator (" +
                        "numOutstandingInterests_ " + numOutstandingInterests_ + ", " +
                        "numDataReceives_ " + numDataReceives_ + ", " +
                        "numInterestTimeouts_ " + numInterestTimeouts_ + ", " +
                        "numInterestsTransmitted_ " + numInterestsTransmitted_ +
                        ")");
            }
            rttEstimator_.addMeasurement(rtt, numOutstandingInterests_);
            Log.d(TAG, getTimeSinceStreamFetchStart() + " : " + "rto after last measure add: " +
                    rttEstimator_.getEstimatedRto());
            segSendTimes_.remove(segNum);
        }

        long finalBlockId = FINAL_BLOCK_ID_UNKNOWN;
        boolean audioPacketWasAppNack = audioPacket.getMetaInfo().getType() == ContentType.NACK;
        if (audioPacketWasAppNack) {
            finalBlockId = Helpers.bytesToLong(audioPacket.getContent().getImmutableArray());
            streamFinalBlockId_ = finalBlockId;
        }
        else {
            Name.Component finalBlockIdComponent = audioPacket.getMetaInfo().getFinalBlockId();
            if (finalBlockIdComponent != null) {
                try {
                    finalBlockId = finalBlockIdComponent.toSegment();
                    streamFinalBlockId_ = finalBlockId;
                }
                catch (EncodingException e) { }
            }
        }
        Log.d(TAG, getTimeSinceStreamFetchStart() + ": " +
                "receive data (" +
                "name " + audioPacket.getName().toString() + ", " +
                "seg num " + segNum + ", " +
                "app nack " + audioPacketWasAppNack + ", " +
                "premature rto " + retransmissionQueue_.contains(segNum) +
                ((finalBlockId == FINAL_BLOCK_ID_UNKNOWN) ? "" : ", final block id " + finalBlockId)
                + ")");

        if (retransmissionQueue_.contains(segNum)) {
            modifyNumOutstandingInterests(0, EVENT_DATA_RECEIVE);
        }
        else {
            modifyNumOutstandingInterests(-1, EVENT_DATA_RECEIVE);
        }

        retransmissionQueue_.remove(segNum);
        handler_.removeCallbacksAndMessages(rtoTokens_.get(segNum));
    }

    @SuppressLint("HandlerLeak")
    @Override
    protected void onLooperPrepared() {
        handler_ = new Handler() {
            @Override
            public void handleMessage(@NonNull Message msg) {
                switch (msg.what) {
                    case MSG_DO_SOME_WORK:
                        doSomeWork();
                        break;
                    case MSG_DATA_RECEIVED:
                        processData((DataInfo) msg.obj);
                        break;
                    case MSG_NETWORK_THREAD_READY:
                        startStreamFetch();
                        break;
                    case MSG_NETWORK_THREAD_FINISHED:
                        close();
                        break;
                    default:
                        throw new IllegalStateException();
                }
            }
        };
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

    private void modifyNumOutstandingInterests(int modifier, int event_code) {
        numOutstandingInterests_ += modifier;
        String eventString = "";
        switch (event_code) {
            case EVENT_DATA_RECEIVE:
                numDataReceives_++;
                eventString = "data_receive";
                break;
            case EVENT_INTEREST_TIMEOUT:
                numInterestTimeouts_++;
                eventString = "interest_timeout";
                break;
            case EVENT_INTEREST_TRANSMIT:
                numInterestsTransmitted_++;
                eventString = "interest_transmit";
                break;
        }

        Log.d(TAG, getTimeSinceStreamFetchStart() + ": " +
                "num outstanding interests changed (" +
                "event " + eventString + ", " +
                "new value " + numOutstandingInterests_ +
                ")");
    }
}
