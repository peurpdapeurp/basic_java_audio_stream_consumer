package com.example.audio_consumer;

import android.media.AudioFormat;
import android.media.MediaFormat;

public class AudioProcessingHelpers {

    public static final int AAC_ADTS_SAMPLES_PER_FRAME = 1024;

    public static long getPresentationTime(long frameOffset, long samplingRate, long samplesPerFrame) {
        return ((samplesPerFrame * frameOffset) / samplingRate) * 1000000;
    }

    public static String getPCMEncodingString(MediaFormat outputFormat) {
        String pcmEncodingString = "";
        int pcmEncodingKey = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
        switch (pcmEncodingKey) {
            case AudioFormat.ENCODING_PCM_8BIT:
                pcmEncodingString = "8 bit PCM encoding";
                break;
            case AudioFormat.ENCODING_PCM_16BIT:
                pcmEncodingString = "16 bit PCM encoding";
                break;
            case AudioFormat.ENCODING_PCM_FLOAT:
                pcmEncodingString = "float PCM encoding";
                break;
            default:
                pcmEncodingString = "Unknown PCM encoding code: " + pcmEncodingKey;
        }
        return pcmEncodingString;
    }

}
