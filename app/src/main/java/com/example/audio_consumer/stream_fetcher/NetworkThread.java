package com.example.audio_consumer.stream_fetcher;

import android.util.Log;

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
import java.util.ArrayList;
import java.util.concurrent.LinkedTransferQueue;

public class NetworkThread implements Runnable {

    private final static String TAG = "NetworkThread";

    private Thread t_;
    private Face face_;
    private KeyChain keyChain_;
    private ArrayList<Observer> observers_;
    private LinkedTransferQueue<Interest> inputQueue_;

    public interface Observer {
        void onAudioPacketReceived(Data audioPacket);
    }

    public NetworkThread(ArrayList<Observer> observers) {
        observers_ = observers;
        inputQueue_ = new LinkedTransferQueue<>();
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

                if (inputQueue_.size() != 0) {
                    face_.expressInterest(inputQueue_.poll(), new OnData() {
                        @Override
                        public void onData(Interest interest, Data data) {
                            for (int i = 0; i < observers_.size(); i++) {
                                observers_.get(i).onAudioPacketReceived(data);
                            }
                        }
                    });
                }

                face_.processEvents();
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
