package com.example.audio_consumer.stream_consumer;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.audio_consumer.Constants;
import com.example.audio_consumer.MainActivity;
import com.example.audio_consumer.Utils.Helpers;
import com.example.audio_consumer.stream_consumer.jndn_utils.RttEstimator;

import net.named_data.jndn.ContentType;
import net.named_data.jndn.Data;
import net.named_data.jndn.Face;
import net.named_data.jndn.Interest;
import net.named_data.jndn.Name;
import net.named_data.jndn.OnData;
import net.named_data.jndn.encoding.EncodingException;
import net.named_data.jndn.security.KeyChain;
import net.named_data.jndn.security.SecurityException;
import net.named_data.jndn.security.identity.IdentityManager;
import net.named_data.jndn.security.identity.MemoryIdentityStorage;
import net.named_data.jndn.security.identity.MemoryPrivateKeyStorage;
import net.named_data.jndn.security.policy.SelfVerifyPolicyManager;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.PriorityQueue;

public class StreamConsumer extends HandlerThread {

    private static final String TAG = "StreamConsumer";

    // Private constants
    private static final int PROCESSING_INTERVAL_MS = 50;

    // Messages
    private static final int MSG_DO_SOME_WORK = 0;
    public static final int MSG_FETCH_START = 1;

    private Network network_;
    private StreamFetcher streamFetcher_;
    private StreamPlayerBuffer streamPlayerBuffer_;
    private boolean streamFetchStartCalled_ = false;
    private Handler uiHandler_;
    private Name streamName_;
    private long producerSamplingRate_;
    private long framesPerSegment_;
    private OutputStream os_;
    private Handler handler_;
    private long streamFetchStartTime_;

    private long getTimeSinceStreamFetchStart() {
        return System.currentTimeMillis() - streamFetchStartTime_;
    }

    public StreamConsumer(Name streamName, long producerSamplingRate, long framesPerSegment,
                          OutputStream os,
                          Handler uiHandler) {
        super("StreamConsumer");
        streamName_ = streamName;
        producerSamplingRate_ = producerSamplingRate;
        framesPerSegment_ = framesPerSegment;
        os_ = os;
        uiHandler_ = uiHandler;
    }

    public void close() {
        Log.d(TAG, getTimeSinceStreamFetchStart() + ": " +
            "close called");
        streamFetcher_.close();
        network_.close();
        streamPlayerBuffer_.close();
        handler_.removeCallbacksAndMessages(null);
        handler_.getLooper().quitSafely();
        uiHandler_
                .obtainMessage(MainActivity.MSG_STREAM_CONSUMER_FINISHED, streamName_)
                .sendToTarget();
    }

    private void doSomeWork() {
        network_.doSomeWork();
        streamFetcher_.doSomeWork();
        streamPlayerBuffer_.doSomeWork();
        scheduleNextWork(SystemClock.uptimeMillis(), PROCESSING_INTERVAL_MS);
    }

    private void scheduleNextWork(long thisOperationStartTimeMs, long intervalMs) {
        handler_.removeMessages(MSG_DO_SOME_WORK);
        handler_.sendEmptyMessageAtTime(MSG_DO_SOME_WORK, thisOperationStartTimeMs + intervalMs);
    }

    @SuppressLint("HandlerLeak")
    @Override
    protected void onLooperPrepared() {
        super.onLooperPrepared();
        handler_ = new Handler() {
            @Override
            public void handleMessage(@NonNull Message msg) {
                super.handleMessage(msg);
                switch (msg.what) {
                    case MSG_DO_SOME_WORK:
                        doSomeWork();
                        break;
                    case MSG_FETCH_START:
                        streamFetchStart();
                        break;
                }
            }
        };
        network_ = new Network();
        streamFetcher_ = new StreamFetcher(streamName_, producerSamplingRate_, framesPerSegment_);
        streamPlayerBuffer_ = new StreamPlayerBuffer(this, os_);
        uiHandler_
                .obtainMessage(MainActivity.MSG_STREAM_CONSUMER_INITIALIZED, streamName_)
                .sendToTarget();
    }

    private void streamFetchStart() {
        if (streamFetchStartCalled_) return;
        streamFetchStartTime_ = System.currentTimeMillis();
        Log.d(TAG, streamFetchStartTime_ + ": " +
                "stream fetch started");
        streamFetchStartCalled_ = true;
        doSomeWork();
    }

    public Handler getHandler() {
        return handler_;
    }

    private class Network {

        private final static String TAG = "StreamConsumer_Network";

        private Face face_;
        private KeyChain keyChain_;
        private HashSet<Name> recvDatas;
        private HashSet<Name> retransmits;
        private boolean closed_ = false;

        public Network() {
            // set up keychain
            keyChain_ = configureKeyChain();
            // set up face
            face_ = new Face();
            try {
                face_.setCommandSigningInfo(keyChain_, keyChain_.getDefaultCertificateName());
            } catch (SecurityException e) {
                e.printStackTrace();
            }
            recvDatas = new HashSet<>();
            retransmits = new HashSet<>();
        }

        public void close() {
            closed_ = true;
        }

        public void doSomeWork() {
            if (closed_) return;
            try {
                face_.processEvents();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (EncodingException e) {
                e.printStackTrace();
            }
        }

        public void sendInterest(Interest interest) {
            Long segNum = null;
            try {
                segNum = interest.getName().get(-1).toSegment();
            } catch (EncodingException e) {
                e.printStackTrace();
            }
            boolean retransmit = false;
            if (!retransmits.contains(interest.getName())) {
                retransmits.add(interest.getName());
            }
            else {
                retransmit = true;
            }
            Log.d(TAG, getTimeSinceStreamFetchStart() + ": " +
                    "send interest (" +
                    "retx " + retransmit + ", " +
                    "seg num " + segNum + ", " +
                    "name " + interest.getName().toString() +
                    ")");
            try {
                face_.expressInterest(interest, new OnData() {
                    @Override
                    public void onData(Interest interest, Data data) {
                        long satisfiedTime = System.currentTimeMillis();

                        if (!recvDatas.contains(data.getName())) {
                            recvDatas.add(data.getName());
                        }
                        else {
                            return;
                        }

                        Long segNum = null;
                        try {
                            segNum = data.getName().get(-1).toSegment();
                        } catch (EncodingException e) {
                            e.printStackTrace();
                        }

                        Log.d(TAG, getTimeSinceStreamFetchStart() + ": " +
                                "data received (" +
                                "seg num " + segNum + ", " +
                                "time " + satisfiedTime + ", " +
                                "retx " + retransmits.contains(interest.getName()) +
                                ")");

                        streamFetcher_.processData(data, satisfiedTime);
                    }
                });
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // taken from https://github.com/named-data-mobile/NFD-android/blob/4a20a88fb288403c6776f81c1d117cfc7fced122/app/src/main/java/net/named_data/nfd/utils/NfdcHelper.java
        private KeyChain configureKeyChain() {

            final MemoryIdentityStorage identityStorage = new MemoryIdentityStorage();
            final MemoryPrivateKeyStorage privateKeyStorage = new MemoryPrivateKeyStorage();
            final KeyChain keyChain = new KeyChain(new IdentityManager(identityStorage, privateKeyStorage),
                    new SelfVerifyPolicyManager(identityStorage));

            Name name = new Name("/tmp-identity");

            try {
                // create keys, certs if necessary
                if (!identityStorage.doesIdentityExist(name)) {
                    keyChain.createIdentityAndCertificate(name);

                    // set default identity
                    keyChain.getIdentityManager().setDefaultIdentity(name);
                }
            }
            catch (SecurityException e){
                e.printStackTrace();
            }

            return keyChain;
        }
    }

    private class StreamFetcher {

        private static final String TAG = "StreamConsumer_Fetcher";

        // Private constants
        private static final int FINAL_BLOCK_ID_UNKNOWN = -1;
        private static final int NO_SEGS_SENT = -1;
        private static final int EVENT_DATA_RECEIVE = 0; // for outstanding interest counter
        private static final int EVENT_INTEREST_TIMEOUT = 1; // for outstanding interest counter
        private static final int EVENT_INTEREST_TRANSMIT = 2; // for outstanding interest counter

        private PriorityQueue<Long> retransmissionQueue_;
        private Name streamName_;
        private long streamFinalBlockId_ = FINAL_BLOCK_ID_UNKNOWN;
        private long highestSegSent_ = NO_SEGS_SENT;
        private long producerSamplingRate_;
        private long framesPerSegment_;
        private long msPerSegNum_;
        private HashMap<Long, Long> segSendTimes_;
        private HashMap<Long, Long> rtoTimes_;
        private CwndCalculator cwndCalculator_;
        private RttEstimator rttEstimator_;
        private int numOutstandingInterests_ = 0;
        private int numInterestsTransmitted_ = 0;
        private int numInterestTimeouts_ = 0;
        private int numDataReceives_ = 0;
        private boolean closed_ = false;

        private void printState() {
            Log.d(TAG, getTimeSinceStreamFetchStart() + ": " +
                    "State of StreamFetcher:" + "\n" +
                    "streamFinalBlockId_: " + streamFinalBlockId_ + ", " +
                    "highestSegSent_: " + highestSegSent_ + ", " +
                    "numInterestsTransmitted_: " + numInterestsTransmitted_ + ", " +
                    "numInterestTimeouts_: " + numInterestTimeouts_ + ", " +
                    "numDataReceives_: " + numDataReceives_ + ", " +
                    "numOutstandingInterests_: " + numOutstandingInterests_ + "\n" +
                    "retransmissionQueue_: " + retransmissionQueue_ + "\n" +
                    "segSendTimes_: " + segSendTimes_);
        }

        /**
         * @param producerSamplingRate Audio sampling rate of producer (samples per second).
         * @param framesPerSegment ADTS frames per segment.
         */
        private long calculateMsPerSeg(long producerSamplingRate, long framesPerSegment) {
            return (framesPerSegment * Constants.SAMPLES_PER_ADTS_FRAME *
                    Constants.MILLISECONDS_PER_SECOND) / producerSamplingRate;
        }

        public StreamFetcher(Name streamName, long producerSamplingRate, long framesPerSegment) {
            cwndCalculator_ = new CwndCalculator();
            retransmissionQueue_ = new PriorityQueue<>();
            streamName_ = streamName;
            segSendTimes_ = new HashMap<>();
            rtoTimes_ = new HashMap<>();
            rttEstimator_ = new RttEstimator();
            producerSamplingRate_ = producerSamplingRate;
            framesPerSegment_ = framesPerSegment;
            msPerSegNum_ = calculateMsPerSeg(producerSamplingRate_, framesPerSegment_);
            Log.d(TAG, "Generating segment numbers to fetch at " + msPerSegNum_ + " per segment number.");
        }

        public void close() {
            Log.d(TAG, getTimeSinceStreamFetchStart() + ": " +
                    "close called");
            printState();
            closed_ = true;
        }

        public long getFramesPerSegment() {
            return framesPerSegment_;
        }

        private void doSomeWork() {

            if (closed_) return;

            for (long segNum : rtoTimes_.keySet()) {
                long rtoTime = rtoTimes_.get(segNum);
                if (System.currentTimeMillis() >= rtoTime) {
                    Log.d(TAG, getTimeSinceStreamFetchStart() + ": " + "rto timeout (seg num " + segNum + ")");
                    modifyNumOutstandingInterests(-1, EVENT_INTEREST_TIMEOUT);
                    retransmissionQueue_.add(segNum);
                }
            }

            while (retransmissionQueue_.size() != 0 && withinCwnd()) {
                Long segNum = retransmissionQueue_.poll();
                if (segNum == null) continue;
                transmitInterest(segNum, true);
            }

            if (retransmissionQueue_.size() == 0 && numOutstandingInterests_ == 0 &&
                    streamFinalBlockId_ != FINAL_BLOCK_ID_UNKNOWN) {
                close();
                return;
            }

            if (streamFinalBlockId_ == FINAL_BLOCK_ID_UNKNOWN ||
                    highestSegSent_ < streamFinalBlockId_) {
                while (nextSegShouldBeSent() && withinCwnd()) {
                    highestSegSent_++;
                    transmitInterest(highestSegSent_, false);
                }
            }
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

            rtoTimes_.put(segNum, System.currentTimeMillis() + rto);

            if (isRetransmission) {
                segSendTimes_.remove(segNum);
            } else {
                segSendTimes_.put(segNum, System.currentTimeMillis());
            }
            network_.sendInterest(interestToSend);
            modifyNumOutstandingInterests(1, EVENT_INTEREST_TRANSMIT);
            Log.d(TAG, getTimeSinceStreamFetchStart() + ": " +
                    "interest transmitted (" +
                    "seg num " + segNum + ", " +
                    "rto " + rto + ", " +
                    "retx: " + isRetransmission + ", " +
                    "current num outstanding " + numOutstandingInterests_ +
                    ")");
        }

        private void processData(Data audioPacket, long receiveTime) {
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
                streamPlayerBuffer_.processAdtsFrames(audioPacket.getContent().getImmutableArray(), segNum);
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

            rtoTimes_.remove(segNum);
            retransmissionQueue_.remove(segNum);
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

            if (numOutstandingInterests_ < 0) {
                Log.e(TAG, "invalid outstanding interest count: " + numOutstandingInterests_);
                close();
            }
        }
    }

    public class StreamPlayerBuffer {

        private final static String TAG = "StreamConsumer_PlayerBuffer";

        // Private constants
        private static final int FINAL_FRAME_NUM_UNKNOWN = -1;

        private OutputStream os_;
        private long jitterBufferDelay_;
        private boolean closed_ = false;
        private StreamConsumer streamConsumer_;
        private long finalFrameNum_ = FINAL_FRAME_NUM_UNKNOWN;

        public StreamPlayerBuffer(StreamConsumer streamConsumer, OutputStream os) {
            streamConsumer_ = streamConsumer;
            os_ = os;
            jitterBufferDelay_ = 0;
        }

        public void close() {
            closed_ = true;
        }

        public void doSomeWork() {
            if (closed_) return;
        }

        public void processAdtsFrames(byte[] frames, long segNum) {
            Log.d(TAG, getTimeSinceStreamFetchStart() + " : " +
                    "Processing adts frames (" +
                    "length " + frames.length + ", " +
                    "seg num " + segNum +
                    ")");
            ArrayList<byte[]> parsedFrames = parseAdtsFrames(frames);
            int parsedFramesLength = parsedFrames.size();
            for (int i = 0; i < parsedFramesLength; i++) {
                byte[] frame = parsedFrames.get(i);
                long frameNum = (segNum * streamFetcher_.getFramesPerSegment()) + i;
                Log.d(TAG, getTimeSinceStreamFetchStart() + ": " +
                        "got frame " + frameNum);
            }
            // to detect end of stream, assume that every batch of frames besides the batch of
            // frames associated with the final segment of a stream will have exactly framesPerSegment_
            // frames in it
            if (parsedFrames.size() < framesPerSegment_) {
                finalFrameNum_ = (segNum * framesPerSegment_) + parsedFrames.size() - 1;
                Log.d(TAG, getTimeSinceStreamFetchStart() + ": " +
                        "detected end of stream (" +
                        "final seg num " + segNum + ", " +
                        "final frame num " + finalFrameNum_ +
                        ")");
            }
        }

        public ArrayList<byte[]> parseAdtsFrames(byte[] frames) {
            ArrayList<byte[]> parsedFrames = new ArrayList<>();
            for (int i = 0; i < frames.length;) {
                int frameLength = (frames[i+3]&0x03) << 11 |
                        (frames[i+4]&0xFF) << 3 |
                        (frames[i+5]&0xFF) >> 5 ;
                byte[] frame = Arrays.copyOfRange(frames, i, i + frameLength);
                parsedFrames.add(frame);
                i+= frameLength;
            }
            return parsedFrames;
        }

    }
}
