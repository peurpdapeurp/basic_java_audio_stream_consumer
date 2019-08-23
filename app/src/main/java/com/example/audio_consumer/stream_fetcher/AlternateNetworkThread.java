package com.example.audio_consumer.stream_fetcher;

import android.util.Log;

import com.example.audio_consumer.Helpers;

import net.named_data.jndn.Data;
import net.named_data.jndn.Face;
import net.named_data.jndn.Interest;
import net.named_data.jndn.Name;
import net.named_data.jndn.OnData;
import net.named_data.jndn.OnTimeout;
import net.named_data.jndn.encoding.EncodingException;
import net.named_data.jndn.security.KeyChain;
import net.named_data.jndn.security.SecurityException;
import net.named_data.jndn.security.identity.IdentityManager;
import net.named_data.jndn.security.identity.MemoryIdentityStorage;
import net.named_data.jndn.security.identity.MemoryPrivateKeyStorage;
import net.named_data.jndn.security.policy.SelfVerifyPolicyManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.LinkedTransferQueue;

public class AlternateNetworkThread implements Runnable {

    private final static String TAG = "NetworkThread";

    private Thread t_;
    private Face face_;
    private KeyChain keyChain_;
    private LinkedTransferQueue<Interest> interestInputQueue_;
    private HashMap<Interest, Long> interestSendTimes_;

    public AlternateNetworkThread(LinkedTransferQueue interestInputQueue) {
        interestInputQueue_ = interestInputQueue;
        interestSendTimes_ = new HashMap<>();
    }

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

    public void run() {

        Log.d(TAG,"Started.");

        try {
            // set up keychain
            keyChain_ = configureKeyChain();

            // set up face
            face_ = new Face();
            try {
                face_.setCommandSigningInfo(keyChain_, keyChain_.getDefaultCertificateName());
            } catch (SecurityException e) {
                e.printStackTrace();
            }

            while (!Thread.interrupted()) {

                if (interestInputQueue_.size() != 0) {
                    Interest interestToSend = interestInputQueue_.poll();
                    if (interestToSend == null) continue;
                    long sendTime = Helpers.currentUnixTimeMilliseconds();
                    Log.d(TAG, "Sending interest with name " + interestToSend.getName().toUri() + " at time " + sendTime);
                    interestSendTimes_.put(interestToSend, sendTime);
                    face_.expressInterest(interestToSend, new OnData() {
                                @Override
                                public void onData(Interest interest, Data data) {
                                    long satisfiedTime = Helpers.currentUnixTimeMilliseconds();
                                    Long sentTime = interestSendTimes_.get(interest);
                                    if (sentTime == null) {
                                        Log.e(TAG, "Unable to get time that " + interest.getName().toUri() + " was sent.");
                                        return;
                                    }
                                    Log.d(TAG, "Interest with name " + interest.getName().toUri() + " satisfied at time " + satisfiedTime + "\n" +
                                            "RTT for interest: " + (satisfiedTime - sentTime));
                                    Log.d(TAG, "Number of outstanding interests (interestSendTimes.size()): " + interestSendTimes_.size());


                                }
                            },
                            new OnTimeout() {
                                @Override
                                public void onTimeout(Interest interest) {
                                    long timeoutTime = Helpers.currentUnixTimeMilliseconds();
                                    Log.d(TAG, "Interest with name " + interest.getName().toUri() + " timed out at time " + timeoutTime);


                                }
                            });

                }

                face_.processEvents();

                try {
                    Thread.sleep(100); // add a small sleep time to save battery
                }
                catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

        } catch (ArrayIndexOutOfBoundsException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (EncodingException e) {
            e.printStackTrace();
        }

        Log.d(TAG,"Stopped.");

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

}