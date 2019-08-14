package com.example.audio_consumer;

import android.util.Log;

import java.util.ArrayList;

public class StreamFrameBundler {

    private static final String TAG = "StreamFrameBundler";

    private FrameBundler bundler_;
    private byte[][] streamData_;

    StreamFrameBundler(byte[][] streamData) {
        streamData_ = streamData;
        bundler_ = new FrameBundler();
    }

    public byte[][] getBundles() {

        int total_num_bundles = streamData_.length / bundler_.getMaxBundleSize();
        if (streamData_.length % bundler_.getMaxBundleSize() != 0) total_num_bundles++;

        byte[][] bundles = new byte[total_num_bundles][];
        int current_bundle_index = 0;

        Log.d(TAG, "Total number of frames: " + streamData_.length + "\n" +
                        "Max bundle size: " + bundler_.getMaxBundleSize() + "\n" +
                        "Total number of bundles: " + total_num_bundles);

        for (int i = 0; i < streamData_.length-1; i++) {
            bundler_.addIntermediateFrame(streamData_[i]);
            if (bundler_.hasFullBundle()) {
                bundles[current_bundle_index] = bundler_.getCurrentBundle();
                current_bundle_index++;
            }
        }

        bundles[current_bundle_index] =
                bundler_.addFinalFrameAndGetLastBundle(streamData_[streamData_.length-1]);

        return bundles;

    }

    private class FrameBundler {

        private final static String TAG = "FrameBundler";

        // number of frames per audio bundle
        public final static int MAX_BUNDLE_SIZE = 10;

        private ArrayList<byte[]> bundle_;
        private int current_bundle_size_; // number of frames in current bundle

        FrameBundler() {
            bundle_ = new ArrayList<byte[]>();
            current_bundle_size_ = 0;
        }

        public int getMaxBundleSize() {return MAX_BUNDLE_SIZE; }

        public int getCurrentBundleSize() {
            return current_bundle_size_;
        }

        public boolean addIntermediateFrame(byte[] frame) {
            if (current_bundle_size_ == MAX_BUNDLE_SIZE)
                return false;

            bundle_.add(frame);
            current_bundle_size_++;

            return true;
        }

        public byte[] addFinalFrameAndGetLastBundle(byte[] frame) {
            bundle_.add(frame);
            current_bundle_size_++;
            return getCurrentBundle();
        }

        public boolean hasFullBundle() {
            return (current_bundle_size_ == MAX_BUNDLE_SIZE);
        }

        public byte[] getCurrentBundle() {
            int bundleLength = 0;
            for (byte[] frame : bundle_) {
                bundleLength += frame.length;
            }
            Log.d(TAG, "Length of audio bundle: " + bundleLength);
            byte[] byte_array_bundle = new byte[bundleLength];
            int current_index = 0;
            for (byte[] frame : bundle_) {
                System.arraycopy(frame, 0, byte_array_bundle, current_index, frame.length);
                current_index += frame.length;
            }

            bundle_.clear();
            current_bundle_size_ = 0;

            return byte_array_bundle;
        }

    }

}
