package com.example.audio_consumer;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class MediaExtractorMetaInfoCheck {

    private static final String TAG = "MediaExtractorMetaInfoCheck";

    public static boolean checkAllFramesHaveSameMetaData(byte[][] frames) {

        int lastSampleRate = 0;
        int lastNumChannels = 0;
        byte[] lastCSD0 = null;

        try {
            for (int i = 0; i < frames.length; i++) {
                AACADTSFrameSource frameSource = new AACADTSFrameSource(frames[i]);
                MediaExtractor extractor = new MediaExtractor();
                extractor.setDataSource(frameSource);
                int numTracks = extractor.getTrackCount();
                for (int j = 0; j < numTracks; j++) {
                    MediaFormat format = extractor.getTrackFormat(j);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    Log.d(TAG, "Mime for track " + j + " of frame " + i + ": " + mime);
                    if (mime.startsWith("audio/")) {
                        extractor.selectTrack(j);

                        ByteBuffer csd = format.getByteBuffer("csd-0");
                        int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        int numChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

                        Log.d(TAG, "MediaExtractor found audio track in data source." + "\n" +
                                "KEY_MIME: " + mime + "\n" +
                                "csd-0 contents: " + Helpers.bytesToHex(csd.array()) + "\n" +
                                "KEY_SAMPLE_RATE: " + sampleRate + "\n" +
                                "KEY_CHANNEL_COUNT: " + numChannels
                        );

                        if (i != 0 &&
                                (lastSampleRate != sampleRate || lastNumChannels != numChannels || !Arrays.equals(lastCSD0, csd.array()))) {
                            Log.d(TAG, "FOUND A MISMATCH BETWEEN DATA OF FRAME " + i + " AND FRAME " + (i - 1) + ":" + "\n" +
                                    "Frame " + i + " info: sample rate " + sampleRate + ", num channels " + numChannels +
                                    ", csd-0 " + Helpers.bytesToHex(csd.array()) + "\n" +
                                    "Frame " + (i - 1) + " info: sample rate " + lastSampleRate + ", num channels " + lastNumChannels +
                                    ", csd-0 " + Helpers.bytesToHex(lastCSD0));
                            return false;
                        }

                        lastSampleRate = sampleRate;
                        lastNumChannels = numChannels;
                        lastCSD0 = Arrays.copyOf(csd.array(), csd.array().length);

                        break;
                    }
                }
                extractor.release();
                extractor = null;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return true;
    }

}
