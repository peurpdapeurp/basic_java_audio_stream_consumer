package com.example.audio_consumer.stream_consumer;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.audio_consumer.Constants;
import com.example.audio_consumer.MainActivity;
import com.example.audio_consumer.Utils.Helpers;
import com.example.audio_consumer.stream_consumer.jndn_utils.RttEstimator;
import com.example.audio_consumer.stream_player.exoplayer_customization.InputStreamDataSource;

import net.named_data.jndn.ContentType;
import net.named_data.jndn.Data;
import net.named_data.jndn.Face;
import net.named_data.jndn.Interest;
import net.named_data.jndn.Name;
import net.named_data.jndn.encoding.EncodingException;
import net.named_data.jndn.security.KeyChain;
import net.named_data.jndn.security.SecurityException;
import net.named_data.jndn.security.identity.IdentityManager;
import net.named_data.jndn.security.identity.MemoryIdentityStorage;
import net.named_data.jndn.security.identity.MemoryPrivateKeyStorage;
import net.named_data.jndn.security.policy.SelfVerifyPolicyManager;

import java.io.IOException;
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
    public static final int MSG_PLAY_START = 2;

    // Events for stream statistics and ui notifications
    private static final int EVENT_INITIALIZED = 0;
    private static final int EVENT_STREAM_PLAY_COMPLETE = 1;
    private static final int EVENT_STREAM_FETCHER_AUDIO_PACKET_RECEIVE = 2;
    private static final int EVENT_STREAM_FETCHER_INTEREST_TIMEOUT = 3;
    private static final int EVENT_STREAM_FETCHER_INTEREST_TRANSMIT = 4;
    private static final int EVENT_STREAM_FETCHER_PREMATURE_RTO = 5;
    private static final int EVENT_STREAM_FETCHER_INTEREST_SKIP = 6;
    private static final int EVENT_STREAM_FETCHER_NACK_RECEIVE = 7;
    private static final int EVENT_STREAM_FETCHER_PRODUCTION_WINDOW_GROW = 8;
    private static final int EVENT_STREAM_FETCHER_FETCH_COMPLETE = 9;
    private static final int EVENT_STREAM_FETCHER_FINAL_BLOCK_ID_LEARNED = 10;
    private static final int EVENT_STREAM_PLAYER_FRAME_PLAYED = 11;
    private static final int EVENT_STREAM_PLAYER_FRAME_SKIP = 12;

    private Network network_;
    private StreamFetcher streamFetcher_;
    private StreamPlayerBuffer streamPlayerBuffer_;
    private boolean streamFetchStartCalled_ = false;
    private boolean streamPlayStartCalled_ = false;
    private Handler uiHandler_;
    private Name streamName_;
    private InputStreamDataSource outSource_;
    private Handler handler_;
    private boolean streamConsumerClosed_ = false;
    private Options options_;

    public static class Options {
        public Options(long framesPerSegment, long jitterBufferSize, long producerSamplingRate) {
            this.framesPerSegment = framesPerSegment;
            this.jitterBufferSize = jitterBufferSize;
            this.producerSamplingRate = producerSamplingRate;
        }
        long framesPerSegment; // # of frames per segment
        long jitterBufferSize; // # of initial frames in StreamPlayerBuffer's jitter buffer before playback begins
        long producerSamplingRate; // samples/sec from producer
    }

    private long getLogTime() {
        return System.currentTimeMillis();
    }

    public StreamConsumer(Name streamName, InputStreamDataSource outSource, Handler uiHandler,
                          Options options) {
        super("StreamConsumer");
        streamName_ = streamName;
        options_ = options;
        outSource_ = outSource;
        uiHandler_ = uiHandler;
        Log.d(TAG, getLogTime() + "; " +
                "Initialized (" +
                "framesPerSegment " + options_.framesPerSegment + ", " +
                "jitterBufferSize " + options_.jitterBufferSize + ", " +
                "producerSamplingRate " + options_.producerSamplingRate +
                ")");
    }

    public void close() {
        Log.d(TAG, getLogTime() + ": " +
            "close called");
        streamFetcher_.close();
        network_.close();
        streamPlayerBuffer_.close();
        handler_.removeCallbacksAndMessages(null);
        handler_.getLooper().quitSafely();
        notifyUiEvent(EVENT_STREAM_PLAY_COMPLETE, 0);
        streamConsumerClosed_ = true;
    }

    private void doSomeWork() {
        network_.doSomeWork();
        streamFetcher_.doSomeWork();
        streamPlayerBuffer_.doSomeWork();
        if (!streamConsumerClosed_) {
            scheduleNextWork(SystemClock.uptimeMillis());
        }
    }

    private void scheduleNextWork(long thisOperationStartTimeMs) {
        handler_.removeMessages(MSG_DO_SOME_WORK);
        handler_.sendEmptyMessageAtTime(MSG_DO_SOME_WORK, thisOperationStartTimeMs + PROCESSING_INTERVAL_MS);
    }

    private void streamFetchStart() {
        if (streamFetchStartCalled_) return;
        streamFetcher_.setStreamFetchStartTime(System.currentTimeMillis());
        Log.d(TAG, streamFetcher_.getStreamFetchStartTime() + ": " +
                "stream fetch started");
        streamFetchStartCalled_ = true;
        doSomeWork();
    }

    private void streamPlayStart() {
        if (streamPlayStartCalled_) return;
        streamPlayerBuffer_.setStreamPlayStartTime(System.currentTimeMillis());
        Log.d(TAG, streamPlayerBuffer_.getStreamPlayStartTime() + ": " +
                "stream play started");
        streamPlayStartCalled_ = true;
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
                    case MSG_PLAY_START:
                        streamPlayStart();
                        break;
                }
            }
        };
        network_ = new Network();
        streamFetcher_ = new StreamFetcher(Looper.getMainLooper());
        streamPlayerBuffer_ = new StreamPlayerBuffer(this);
        notifyUiEvent(EVENT_INITIALIZED, 0);
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

        private Network() {
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

        private void close() {
            if (closed_) return;
            closed_ = true;
        }

        private void doSomeWork() {
            if (closed_) return;
            try {
                face_.processEvents();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (EncodingException e) {
                e.printStackTrace();
            }
        }

        private void sendInterest(Interest interest) {
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
            Log.d(TAG, getLogTime() + ": " +
                    "send interest (" +
                    "retx " + retransmit + ", " +
                    "seg num " + segNum + ", " +
                    "name " + interest.getName().toString() +
                    ")");
            try {
                face_.expressInterest(interest, (Interest callbackInterest, Data callbackData) -> {
                        long satisfiedTime = System.currentTimeMillis();

                        if (!recvDatas.contains(callbackData.getName())) {
                            recvDatas.add(callbackData.getName());
                        }
                        else {
                            return;
                        }

                        Long callbackSegNum = null;
                        try {
                            callbackSegNum = callbackData.getName().get(-1).toSegment();
                        } catch (EncodingException e) {
                            e.printStackTrace();
                        }

                        Log.d(TAG, getLogTime() + ": " +
                                "data received (" +
                                "seg num " + callbackSegNum + ", " +
                                "time " + satisfiedTime + ", " +
                                "retx " + retransmits.contains(interest.getName()) +
                                ")");

                        streamFetcher_.processData(callbackData, satisfiedTime);
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
        private static final int DEFAULT_INTEREST_LIFETIME_MS = 4000;
        private static final int N_EXPECTED_SAMPLES = 1;

        private PriorityQueue<Long> retransmissionQueue_;
        private long streamFinalBlockId_ = FINAL_BLOCK_ID_UNKNOWN;
        private long highestSegSent_ = NO_SEGS_SENT;
        private long msPerSegNum_;
        private HashMap<Long, Long> segSendTimes_;
        private HashMap<Long, Object> rtoTokens_;
        private CwndCalculator cwndCalculator_;
        private RttEstimator rttEstimator_;
        private int numInterestsTransmitted_ = 0;
        private int numInterestTimeouts_ = 0;
        private int numDataReceives_ = 0;
        private int numPrematureRtos_ = 0;
        private int numInterestSkips_ = 0;
        private int numNacks_ = 0;
        private boolean closed_ = false;
        private long streamFetchStartTime_;
        private Handler streamConsumerHandler_;

        private void printState() {
            Log.d(TAG, getLogTime() + ": " +
                    "State of StreamFetcher:" + "\n" +
                    "streamFetchStartTime_ " + streamFetchStartTime_ + ", " +
                    "streamFinalBlockId_ " +
                        ((streamFinalBlockId_ == FINAL_BLOCK_ID_UNKNOWN) ? "unknown" : streamFinalBlockId_) +
                        ", " +
                    "highestSegSent_ " +
                        ((highestSegSent_ == NO_SEGS_SENT) ? "none " : highestSegSent_) +
                        "\n" +
                    "numInterestsTransmitted_ " + numInterestsTransmitted_ + ", " +
                    "numInterestTimeouts_ " + numInterestTimeouts_ + ", " +
                    "numDataReceives_ " + numDataReceives_ + "\n" +
                    "numInterestSkips_ " + numInterestSkips_ + ", " +
                    "numPrematureRtos_ " + numPrematureRtos_ + ", " +
                    "numNacks_ " + numNacks_ + "\n" +
                    "retransmissionQueue_ " + retransmissionQueue_ + "\n" +
                    "segSendTimes_ " + segSendTimes_);
        }

        private long getTimeSinceStreamFetchStart() {
            return System.currentTimeMillis() - streamFetchStartTime_;
        }

        private StreamFetcher(Looper streamConsumerLooper) {
            cwndCalculator_ = new CwndCalculator();
            retransmissionQueue_ = new PriorityQueue<>();
            segSendTimes_ = new HashMap<>();
            rtoTokens_ = new HashMap<>();
            long streamPlayerBufferJitterDelay = options_.jitterBufferSize * calculateMsPerFrame(options_.producerSamplingRate);
            rttEstimator_ = new RttEstimator(new RttEstimator.Options(streamPlayerBufferJitterDelay, streamPlayerBufferJitterDelay));
            msPerSegNum_ = calculateMsPerSeg(options_.producerSamplingRate, options_.framesPerSegment);
            streamConsumerHandler_ = new Handler(streamConsumerLooper);
            Log.d(TAG, getLogTime() + "; " +
                    "Initialized (" +
                    "maxRto / initialRto " + streamPlayerBufferJitterDelay + ", " +
                    "ms per seg num " + msPerSegNum_ +
                    ")");
        }

        private void setStreamFetchStartTime(long streamFetchStartTime) {
            streamFetchStartTime_ = streamFetchStartTime;
        }

        private long getStreamFetchStartTime() {
            return streamFetchStartTime_;
        }

        private void close() {
            if (closed_) return;
            Log.d(TAG, getLogTime() + ": " +
                    "close called");
            printState();
            notifyUiEvent(EVENT_STREAM_FETCHER_FETCH_COMPLETE, 0);
            closed_ = true;
        }

        private void doSomeWork() {

            if (closed_) return;

            while (retransmissionQueue_.size() != 0 && withinCwnd()) {
                if (closed_) return;
                Long segNum = retransmissionQueue_.poll();
                if (segNum == null) continue;
                transmitInterest(segNum, true);
            }

            if (closed_) return;

            if (streamFinalBlockId_ == FINAL_BLOCK_ID_UNKNOWN ||
                    highestSegSent_ < streamFinalBlockId_) {
                while (nextSegShouldBeSent() && withinCwnd()) {
                    if (closed_) return;
                    highestSegSent_++;
                    notifyUiEvent(EVENT_STREAM_FETCHER_PRODUCTION_WINDOW_GROW, highestSegSent_);
                    transmitInterest(highestSegSent_, false);
                }
            }

            if (retransmissionQueue_.size() == 0 && rtoTokens_.size() == 0 &&
                    streamFinalBlockId_ != FINAL_BLOCK_ID_UNKNOWN) {
                close();
                return;
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
            // if playback deadline for first frame of segment is known, set interest lifetime to expire at playback deadline
            long segFirstFrameNum = options_.framesPerSegment * segNum;
            long playbackDeadline = streamPlayerBuffer_.getPlaybackDeadline(segFirstFrameNum);
            long transmitTime = System.currentTimeMillis();
            if (playbackDeadline != StreamPlayerBuffer.PLAYBACK_DEADLINE_UNKNOWN && transmitTime + rto > playbackDeadline) {
                Log.d(TAG, getLogTime() + ": " +
                        "interest skipped (" +
                        "seg num " + segNum + ", " +
                        "first frame num " + segFirstFrameNum + ", " +
                        "rto " + rto + ", " +
                        "transmit time " + transmitTime + ", " +
                        "playback deadline " + playbackDeadline + ", " +
                        "retx: " + isRetransmission +
                        ")");
                recordPacketEvent(segNum, EVENT_STREAM_FETCHER_INTEREST_SKIP);
                notifyUiEvent(EVENT_STREAM_FETCHER_INTEREST_SKIP, segNum);
                return;
            }

            long lifetime = (playbackDeadline == StreamPlayerBuffer.PLAYBACK_DEADLINE_UNKNOWN)
                            ? DEFAULT_INTEREST_LIFETIME_MS : playbackDeadline - transmitTime;

            interestToSend.setInterestLifetimeMilliseconds(lifetime);
            interestToSend.setCanBePrefix(false);
            interestToSend.setMustBeFresh(false);

            Object rtoToken = new Object();
            streamConsumerHandler_.postAtTime(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, getTimeSinceStreamFetchStart() + ": " + "rto timeout (seg num " + segNum + ")");
                    retransmissionQueue_.add(segNum);
                    rtoTokens_.remove(segNum);
                    recordPacketEvent(segNum, EVENT_STREAM_FETCHER_INTEREST_TIMEOUT);
                }
            }, rtoToken, SystemClock.uptimeMillis() + rto);
            rtoTokens_.put(segNum, rtoToken);

            if (isRetransmission) {
                segSendTimes_.remove(segNum);
            } else {
                segSendTimes_.put(segNum, System.currentTimeMillis());
            }
            network_.sendInterest(interestToSend);
            recordPacketEvent(segNum, EVENT_STREAM_FETCHER_INTEREST_TRANSMIT);
            Log.d(TAG, getLogTime() + ": " +
                    "interest transmitted (" +
                    "seg num " + segNum + ", " +
                    "first frame num " + segFirstFrameNum + ", " +
                    "rto " + rto + ", " +
                    "lifetime " + lifetime + ", " +
                    "retx: " + isRetransmission + ", " +
                    "numPrematureRtos_ " + numPrematureRtos_ +
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

            boolean detectedPrematureRto = retransmissionQueue_.contains(segNum);
            if (detectedPrematureRto) {
                recordPacketEvent(segNum, EVENT_STREAM_FETCHER_PREMATURE_RTO);
            }

            if (segSendTimes_.containsKey(segNum)) {
                long rtt = receiveTime - segSendTimes_.get(segNum);
                Log.d(TAG, getLogTime() + ": " +
                        "rtt estimator add measure (rtt " + rtt + ", " +
                        "num outstanding interests " + N_EXPECTED_SAMPLES +
                        ")");
                rttEstimator_.addMeasurement(rtt, N_EXPECTED_SAMPLES);
                Log.d(TAG, getLogTime() + " : " + "rto after last measure add: " +
                        rttEstimator_.getEstimatedRto());
                segSendTimes_.remove(segNum);
            }

            long finalBlockId = FINAL_BLOCK_ID_UNKNOWN;
            boolean audioPacketWasAppNack = audioPacket.getMetaInfo().getType() == ContentType.NACK;
            if (audioPacketWasAppNack) {
                finalBlockId = Helpers.bytesToLong(audioPacket.getContent().getImmutableArray());
                streamFinalBlockId_ = finalBlockId;
                streamPlayerBuffer_.receiveFinalSegNum(finalBlockId);
            }
            else {
                streamPlayerBuffer_.processAdtsFrames(audioPacket.getContent().getImmutableArray(), segNum);
                Name.Component finalBlockIdComponent = audioPacket.getMetaInfo().getFinalBlockId();
                if (finalBlockIdComponent != null) {
                    try {
                        finalBlockId = finalBlockIdComponent.toSegment();
                        streamFinalBlockId_ = finalBlockId;
                    }
                    catch (EncodingException ignored) { }
                }
            }
            if (streamFinalBlockId_ != FINAL_BLOCK_ID_UNKNOWN) {
                notifyUiEvent(EVENT_STREAM_FETCHER_FINAL_BLOCK_ID_LEARNED, streamFinalBlockId_);
            }

            Log.d(TAG, getLogTime() + ": " +
                    "receive data (" +
                    "name " + audioPacket.getName().toString() + ", " +
                    "seg num " + segNum + ", " +
                    "app nack " + audioPacketWasAppNack + ", " +
                    "premature rto " + detectedPrematureRto +
                    ((finalBlockId == FINAL_BLOCK_ID_UNKNOWN) ? "" : ", final block id " + finalBlockId) +
                    ")");

            if (audioPacketWasAppNack) {
                recordPacketEvent(segNum, EVENT_STREAM_FETCHER_NACK_RECEIVE);
                notifyUiEvent(EVENT_STREAM_FETCHER_NACK_RECEIVE, segNum);
            }
            else {
                recordPacketEvent(segNum, EVENT_STREAM_FETCHER_AUDIO_PACKET_RECEIVE);
                notifyUiEvent(EVENT_STREAM_FETCHER_AUDIO_PACKET_RECEIVE, segNum);
            }
            retransmissionQueue_.remove(segNum);
            streamConsumerHandler_.removeCallbacksAndMessages(rtoTokens_.get(segNum));
            rtoTokens_.remove(segNum);

        }

        private class CwndCalculator {

            private static final long MAX_CWND = 50; // max # of outstanding interests

            long currentCwnd_;

            private CwndCalculator() {
                currentCwnd_ = MAX_CWND;
            }

            private long getCurrentCwnd() {
                return currentCwnd_;
            }

        }

        private boolean withinCwnd() {
            return rtoTokens_.size() < cwndCalculator_.getCurrentCwnd();
        }

        private void recordPacketEvent(long segNum, int event_code) {
            String eventString = "";
            switch (event_code) {
                case EVENT_STREAM_FETCHER_AUDIO_PACKET_RECEIVE:
                    numDataReceives_++;
                    eventString = "data_receive";
                    break;
                case EVENT_STREAM_FETCHER_INTEREST_TIMEOUT:
                    numInterestTimeouts_++;
                    eventString = "interest_timeout";
                    break;
                case EVENT_STREAM_FETCHER_INTEREST_TRANSMIT:
                    numInterestsTransmitted_++;
                    eventString = "interest_transmit";
                    break;
                case EVENT_STREAM_FETCHER_PREMATURE_RTO:
                    numPrematureRtos_++;
                    eventString = "premature_rto";
                    break;
                case EVENT_STREAM_FETCHER_INTEREST_SKIP:
                    numInterestSkips_++;
                    eventString = "interest_skip";
                    break;
                case EVENT_STREAM_FETCHER_NACK_RECEIVE:
                    numNacks_++;
                    eventString = "nack_receive";
                    break;
            }

            Log.d(TAG, getLogTime() + ": " +
                    "recorded packet event (" +
                    "seg num " + segNum + ", " +
                    "event " + eventString + ", " +
                    "num outstanding interests " + rtoTokens_.size() +
                    ")");
        }
    }

    public class StreamPlayerBuffer {

        private final static String TAG = "StreamConsumer_PlayerBuffer";

        // Private constants
        private static final int FINAL_FRAME_NUM_UNKNOWN = -1;
        private static final int STREAM_PLAY_START_TIME_UNKNOWN = -1;
        private static final int FINAL_SEG_NUM_UNKNOWN = -1;
        private static final int PLAYBACK_DEADLINE_UNKNOWN = -1;
        private static final int FINAL_FRAME_NUM_DEADLINE_UNKNOWN = -1;

        private class Frame implements Comparable<Frame> {
            long frameNum;
            byte[] data;

            private Frame(long frameNum, byte[] data) {
                this.frameNum = frameNum;
                this.data = data;
            }

            @Override
            public int compareTo(Frame frame) {
                return Long.compare(frameNum, frame.frameNum);
            }

            @Override
            public String toString() {
                return Long.toString(frameNum);
            }
        }

        private PriorityQueue<Frame> jitterBuffer_;
        private long jitterBufferDelay_;
        private boolean closed_ = false;
        private StreamConsumer streamConsumer_;
        private long highestFrameNumPlayed_ = 0;
        private long highestFrameNumPlayedDeadline_;
        private long finalSegNum_ = FINAL_SEG_NUM_UNKNOWN;
        private long finalFrameNum_ = FINAL_FRAME_NUM_UNKNOWN;
        private long finalFrameNumDeadline_ = FINAL_FRAME_NUM_DEADLINE_UNKNOWN;
        private long streamPlayStartTime_ = STREAM_PLAY_START_TIME_UNKNOWN;
        private long msPerFrame_;
        private boolean firstRealDoSomeWork_ = true;
        private long framesSkipped_ = 0;
        private long framesPlayed_ = 0;

        private void printState() {
            Log.d(TAG, getLogTime() + ": " +
                    "State of StreamPlayerBuffer:" + "\n" +
                    "streamPlayStartTime_ " + streamPlayStartTime_ + ", " +
                    "framesPlayed_ " + framesPlayed_ + ", " +
                    "framesSkipped_ " + framesSkipped_ + "\n" +
                    "finalSegNum_ " +
                        ((finalSegNum_ == FINAL_SEG_NUM_UNKNOWN) ?
                        "unknown" : finalSegNum_) + ", " +
                    "finalFrameNum_ " +
                        ((finalFrameNum_ == FINAL_FRAME_NUM_UNKNOWN) ?
                        "unknown" : finalFrameNum_) + ", " +
                    "finalFrameNumDeadline_ " +
                        ((finalFrameNum_ == FINAL_FRAME_NUM_DEADLINE_UNKNOWN) ?
                        "unknown" : finalFrameNumDeadline_) + "\n" +
                    "jitterBuffer_ " + jitterBuffer_);
        }

        private StreamPlayerBuffer(StreamConsumer streamConsumer) {
            jitterBuffer_ = new PriorityQueue<>();
            streamConsumer_ = streamConsumer;
            jitterBufferDelay_ = options_.jitterBufferSize * calculateMsPerFrame(options_.producerSamplingRate);
            msPerFrame_ = calculateMsPerFrame(options_.producerSamplingRate);
            Log.d(TAG, getLogTime() + "; " +
                    "Initialized (" +
                    "jitterBufferDelay_ " + jitterBufferDelay_ + ", " +
                    "ms per frame " + msPerFrame_ +
                    ")");
        }

        private void setStreamPlayStartTime(long streamPlayStartTime) {
            streamPlayStartTime_ = streamPlayStartTime;
        }

        private long getStreamPlayStartTime() {
            return streamPlayStartTime_;
        }

        private void close() {
            if (closed_) return;
            closed_ = true;
        }

        private void doSomeWork() {
            if (streamPlayStartTime_ == STREAM_PLAY_START_TIME_UNKNOWN) return;

            if (firstRealDoSomeWork_) {
                highestFrameNumPlayedDeadline_ = streamPlayStartTime_ + jitterBufferDelay_;
                firstRealDoSomeWork_ = false;
            }

            if (closed_) return;

            long currentTime = System.currentTimeMillis();
            if (currentTime > highestFrameNumPlayedDeadline_) {
                Log.d(TAG, getLogTime() + ": " +
                        "reached playback deadline for frame " + highestFrameNumPlayed_ + " (" +
                        "playback deadline " + highestFrameNumPlayedDeadline_ +
                        ")"
                );

                Frame nextFrame = jitterBuffer_.peek();
                boolean gotExpectedFrame = nextFrame != null && nextFrame.frameNum == highestFrameNumPlayed_;
                Log.d(TAG, getLogTime() + ": " +
                        "next frame was " + ((gotExpectedFrame) ? "" : "not ") + "expected frame (" +
                        "expected frame num " + highestFrameNumPlayed_ + ", " +
                        "frame num " + ((nextFrame != null) ? nextFrame.frameNum : "unknown (null frame)") +
                        ")");
                if (gotExpectedFrame) {
                    framesPlayed_++;
                    outSource_.write(nextFrame.data,
                        (finalFrameNumDeadline_ != FINAL_FRAME_NUM_DEADLINE_UNKNOWN &&
                            currentTime > finalFrameNumDeadline_));
                    jitterBuffer_.poll();
                    notifyUiEvent(EVENT_STREAM_PLAYER_FRAME_PLAYED, highestFrameNumPlayed_);
                }
                else {
                    if (nextFrame == null || nextFrame.frameNum > highestFrameNumPlayed_) {
                        framesSkipped_++;
                        outSource_.write(getSilentFrame(),
                            (finalFrameNumDeadline_ != FINAL_FRAME_NUM_DEADLINE_UNKNOWN &&
                                currentTime > finalFrameNumDeadline_));
                        notifyUiEvent(EVENT_STREAM_PLAYER_FRAME_SKIP, highestFrameNumPlayed_);
                    }
                }

                highestFrameNumPlayed_++;
                highestFrameNumPlayedDeadline_ += msPerFrame_;
            }

            if (finalFrameNumDeadline_ == FINAL_FRAME_NUM_DEADLINE_UNKNOWN) { return; }

            if (currentTime > finalFrameNumDeadline_) {
                Log.d(TAG, getLogTime() + ": " +
                        "finished playing all frames (" +
                        "final frame num " + finalFrameNum_  + ", " +
                        "playback deadline " + finalFrameNumDeadline_ +
                        ")");
                printState();
                streamConsumer_.close(); // close the entire stream consumer, now that playback is done
            }
        }

        private void processAdtsFrames(byte[] frames, long segNum) {
            Log.d(TAG, getLogTime() + " : " +
                    "Processing adts frames (" +
                    "length " + frames.length + ", " +
                    "seg num " + segNum +
                    ")");
            ArrayList<byte[]> parsedFrames = parseAdtsFrames(frames);
            int parsedFramesLength = parsedFrames.size();
            for (int i = 0; i < parsedFramesLength; i++) {
                byte[] frameData = parsedFrames.get(i);
                long frameNum = (segNum * options_.framesPerSegment) + i;
                Log.d(TAG, getLogTime() + ": " +
                        "got frame " + frameNum);
                jitterBuffer_.add(new Frame(frameNum, frameData));
            }
            // to detect end of stream, assume that every batch of frames besides the batch of
            // frames associated with the final segment of a stream will have exactly framesPerSegment_
            // frames in it
            if (parsedFrames.size() < options_.framesPerSegment) {
                finalFrameNum_ = (segNum * options_.framesPerSegment) + parsedFrames.size() - 1;
                Log.d(TAG, getLogTime() + ": " +
                        "detected end of stream (" +
                        "final seg num " + segNum + ", " +
                        "final frame num " + finalFrameNum_ +
                        ")");
                finalFrameNumDeadline_ = getPlaybackDeadline(finalFrameNum_);
            }
        }

        private void receiveFinalSegNum(long finalSegNum) {
            if (finalFrameNum_ != FINAL_FRAME_NUM_UNKNOWN) return;
            if (finalSegNum_ != FINAL_SEG_NUM_UNKNOWN) return;

            finalSegNum_ = finalSegNum;
            // calculate the finalFrameNumDeadline_ based on the assumption that the last segment had framesPerSegment_ frames in it
            finalFrameNumDeadline_ = getPlaybackDeadline((finalSegNum * options_.framesPerSegment) + options_.framesPerSegment);
        }

        private ArrayList<byte[]> parseAdtsFrames(byte[] frames) {
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

        private long getPlaybackDeadline(long frameNum) {
            if (streamPlayStartTime_ == STREAM_PLAY_START_TIME_UNKNOWN) {
                return PLAYBACK_DEADLINE_UNKNOWN;
            }
            long framePlayTimeOffset = (Constants.SAMPLES_PER_ADTS_FRAME * Constants.MILLISECONDS_PER_SECOND * frameNum) / options_.producerSamplingRate;
            long deadline = streamPlayStartTime_ + jitterBufferDelay_ + framePlayTimeOffset;
            Log.d(TAG, getLogTime() + ": " +
                    "calculated deadline (" +
                    "frame num " + frameNum + ", " +
                    "framePlayTimeOffset " + framePlayTimeOffset + ", " +
                    "jitterBufferDelay " + jitterBufferDelay_ + ", " +
                    "deadline " + deadline +
                    ")");
            return deadline;
        }

        private byte[] getSilentFrame() {
            return new byte[] {
                    (byte)0xff, (byte)0xf1, (byte)0x6c, (byte)0x40, (byte)0x0b, (byte)0x7f, (byte)0xfc, // 7 byte header
                    (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, // empty payload, silence
                    (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                    (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                    (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                    (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                    (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                    (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                    (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                    (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                    (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                    (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                    (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            };
        }

    }

    /**
     * @param producerSamplingRate Audio sampling rate of producer (samples per second).
     */
    private long calculateMsPerFrame(long producerSamplingRate) {
        return (Constants.SAMPLES_PER_ADTS_FRAME *
                Constants.MILLISECONDS_PER_SECOND) / producerSamplingRate;
    }

    /**
     * @param producerSamplingRate Audio sampling rate of producer (samples per second).
     * @param framesPerSegment ADTS frames per segment.
     */
    private long calculateMsPerSeg(long producerSamplingRate, long framesPerSegment) {
        return (framesPerSegment * Constants.SAMPLES_PER_ADTS_FRAME *
                Constants.MILLISECONDS_PER_SECOND) / producerSamplingRate;
    }

    private void notifyUiEvent(int event_code, long arg1) {
        int what;
        switch (event_code) {
            case EVENT_INITIALIZED:
                what = MainActivity.MSG_STREAM_CONSUMER_INITIALIZED;
                break;
            case EVENT_STREAM_PLAY_COMPLETE:
                what = MainActivity.MSG_STREAM_BUFFER_PLAY_COMPLETE;
                break;
            case EVENT_STREAM_FETCHER_AUDIO_PACKET_RECEIVE:
                what = MainActivity.MSG_STREAM_FETCHER_AUDIO_RETRIEVED;
                break;
            case EVENT_STREAM_FETCHER_NACK_RECEIVE:
                what = MainActivity.MSG_STREAM_FETCHER_NACK_RETRIEVED;
                break;
            case EVENT_STREAM_FETCHER_INTEREST_SKIP:
                what = MainActivity.MSG_STREAM_FETCHER_INTEREST_SKIP;
                break;
            case EVENT_STREAM_FETCHER_PRODUCTION_WINDOW_GROW:
                what = MainActivity.MSG_STREAM_FETCHER_PRODUCTION_WINDOW_GROW;
                break;
            case EVENT_STREAM_FETCHER_FETCH_COMPLETE:
                what = MainActivity.MSG_STREAM_CONSUMER_FETCH_COMPLETE;
                break;
            case EVENT_STREAM_FETCHER_FINAL_BLOCK_ID_LEARNED:
                what = MainActivity.MSG_STREAM_FETCHER_FINAL_BLOCK_ID_LEARNED;
                break;
            case EVENT_STREAM_PLAYER_FRAME_PLAYED:
                what = MainActivity.MSG_STREAM_BUFFER_FRAME_PLAYED;
                break;
            case EVENT_STREAM_PLAYER_FRAME_SKIP:
                what = MainActivity.MSG_STREAM_BUFFER_FRAME_SKIP;
                break;
            default:
                throw new IllegalStateException("unrecognized event_code: " + event_code);
        }
        uiHandler_
                .obtainMessage(what, new MainActivity.UiEventInfo(streamName_, arg1))
                .sendToTarget();
    }
}
