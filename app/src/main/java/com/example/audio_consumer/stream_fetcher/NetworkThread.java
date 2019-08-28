package com.example.audio_consumer.stream_fetcher;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

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
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
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
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

public class NetworkThread extends HandlerThread {

    private final static String TAG = "NetworkThread";

    // Private constants
    private static final int PROCESSING_INTERVAL_MS = 100;

    // Messages
    private static final int MSG_DO_SOME_WORK = 0;
    public static final int MSG_INTEREST_SEND_REQUEST = 1;
    public static final int MSG_STREAM_FETCH_START = 2;
    public static final int MSG_STREAM_FETCH_FINISH = 3;

    private Face face_;
    private KeyChain keyChain_;
    private long startTime_;
    private HashMap<Name, StreamFetcherState> streamFetcherStates_;
    private Handler handler_;

    public static class StreamFetcherState {
        public StreamFetcherState(Name streamName, Handler handler) {
            this.streamName = streamName;
            this.handler = handler;
            recvDatas = new HashSet<>();
            retransmits = new HashSet<>();
        }
        Name streamName;
        Handler handler;
        HashSet<Name> recvDatas;
        HashSet<Name> retransmits;
    }

    private long getTimeSinceNetworkThreadStart() {
        return System.currentTimeMillis() - startTime_;
    }

    private void addStreamFetcherState(StreamFetcherState streamFetcherState) {
        Log.d(TAG, getTimeSinceNetworkThreadStart() + ": " +
                "add stream fetcher state for " + streamFetcherState.streamName.toString());
        streamFetcherStates_.put(streamFetcherState.streamName, streamFetcherState);
        streamFetcherState.handler.obtainMessage(StreamFetcher.MSG_NETWORK_THREAD_READY).sendToTarget();
    }

    private void removeStreamFetcherState(Name streamName) {
        Log.d(TAG, getTimeSinceNetworkThreadStart() + ": " +
                "remove stream fetcher state for " + streamName.toString());
        StreamFetcherState streamFetcherState = streamFetcherStates_.get(streamName);
        streamFetcherStates_.remove(streamName);
        streamFetcherState.handler.obtainMessage(StreamFetcher.MSG_NETWORK_THREAD_FINISHED).sendToTarget();
    }

    public NetworkThread() {
        super("NetworkThread");
        streamFetcherStates_ = new HashMap<>();
    }

    public void close() {
        Log.d(TAG, getTimeSinceNetworkThreadStart() + ": " +
                "close called");
        for (Name streamName : streamFetcherStates_.keySet()) {
            removeStreamFetcherState(streamName);
        }
        handler_.removeCallbacksAndMessages(null);
        handler_.getLooper().quitSafely();
    }

    private void doSomeWork() {
        try {
            face_.processEvents();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (EncodingException e) {
            e.printStackTrace();
        }

        scheduleNextWork(SystemClock.uptimeMillis(), PROCESSING_INTERVAL_MS);
    }

    private void scheduleNextWork(long thisOperationStartTimeMs, long intervalMs) {
        handler_.removeMessages(MSG_DO_SOME_WORK);
        handler_.sendEmptyMessageAtTime(MSG_DO_SOME_WORK, thisOperationStartTimeMs + intervalMs);
    }

    private void sendInterest(Interest interest) {
        Name streamName = interest.getName().getPrefix(-1);
        StreamFetcherState streamFetcherState = streamFetcherStates_.get(streamName);
        if (streamFetcherState == null) {
            Log.w(TAG, getTimeSinceNetworkThreadStart() + ": " +
                    "unable to find stream fetcher state for stream name " + interest.getName().getPrefix(-1));
            return;
        }
        Long segNum = null;
        try {
            segNum = interest.getName().get(-1).toSegment();
        } catch (EncodingException e) {
            e.printStackTrace();
        }
        boolean retransmit = false;
        if (!streamFetcherState.retransmits.contains(interest.getName())) {
            streamFetcherState.retransmits.add(interest.getName());
        }
        else {
            retransmit = true;
        }
        Log.d(TAG, getTimeSinceNetworkThreadStart() + ": " +
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
                    Name streamName = interest.getName().getPrefix(-1);
                    StreamFetcherState streamFetcherState = streamFetcherStates_.get(streamName);

                    if (streamFetcherState == null) {
                        Log.w(TAG, getTimeSinceNetworkThreadStart() + ": " +
                                "unable to find stream fetcher state for stream name " + data.getName().getPrefix(-1));
                        return;
                    }

                    if (!streamFetcherState.recvDatas.contains(data.getName())) {
                        streamFetcherState.recvDatas.add(data.getName());
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

                    Log.d(TAG, getTimeSinceNetworkThreadStart() + ": " +
                            "data received (" +
                            "seg num " + segNum + ", " +
                            "time " + satisfiedTime + ", " +
                            "retx " + streamFetcherState.retransmits.contains(interest.getName()) +
                            ")");

                    streamFetcherState.handler.obtainMessage(StreamFetcher.MSG_DATA_RECEIVED,
                            new StreamFetcher.DataInfo(data, satisfiedTime)).sendToTarget();
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
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
                    case MSG_INTEREST_SEND_REQUEST:
                        Interest interest = (Interest) msg.obj;
                        sendInterest(interest);
                        break;
                    case MSG_STREAM_FETCH_START:
                        StreamFetcherState streamFetcherState = (StreamFetcherState) msg.obj;
                        addStreamFetcherState(streamFetcherState);
                        break;
                    case MSG_STREAM_FETCH_FINISH:
                        Name streamName = (Name) msg.obj;
                        removeStreamFetcherState(streamName);
                        break;
                    default:
                        throw new IllegalStateException();
                }
            }
        };
        startTime_ = System.currentTimeMillis();
        // set up keychain
        keyChain_ = configureKeyChain();
        // set up face
        face_ = new Face();
        try {
            face_.setCommandSigningInfo(keyChain_, keyChain_.getDefaultCertificateName());
        } catch (SecurityException e) {
            e.printStackTrace();
        }
        doSomeWork();
    }

    // taken from https://github.com/named-data-mobile/NFD-android/blob/4a20a88fb288403c6776f81c1d117cfc7fced122/app/src/main/java/net/named_data/nfd/utils/NfdcHelper.java
    private static KeyChain configureKeyChain() {

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

    public Handler getHandler() {
        return handler_;
    }

}
