package com.example.audio_consumer;

import android.media.AudioFormat;
import android.media.MediaFormat;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class AudioProcessingHelpers {

    private static final String TAG = "AudioProcessingHelpers";

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

    /**
     * The code profile, Sample rate, channel Count is used to
     * produce the AAC Codec SpecificData.
     * Android 4.4.2/frameworks/av/media/libstagefright/avc_utils.cpp refer
     * to the portion of the code written.
     *
     * MPEG-4 Audio refer : http://wiki.multimedia.cx/index.php?title=MPEG-4_Audio#Audio_Specific_Config
     *
     * @param audioProfile is MPEG-4 Audio Object Types
     * @param sampleRate
     * @param channelConfig
     * @return MediaFormat
     */
    public static MediaFormat makeAACCodecSpecificData(int audioProfile, int sampleRate, int channelConfig) {
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, "audio/mp4a-latm");
        format.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, channelConfig);

        int samplingFreq[] = {
                96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
                16000, 12000, 11025, 8000
        };

        // Search the Sampling Frequencies
        int sampleIndex = -1;
        for (int i = 0; i < samplingFreq.length; ++i) {
            if (samplingFreq[i] == sampleRate) {
                Log.d("TAG", "kSamplingFreq " + samplingFreq[i] + " i : " + i);
                sampleIndex = i;
            }
        }

        if (sampleIndex == -1) {
            return null;
        }

        ByteBuffer csd = ByteBuffer.allocate(2);
        csd.put((byte) ((audioProfile << 3) | (sampleIndex >> 1)));

        csd.position(1);
        csd.put((byte) ((byte) ((sampleIndex << 7) & 0x80) | (channelConfig << 3)));
        csd.flip();
        format.setByteBuffer("csd-0", csd); // add csd-0

        Log.d(TAG, "Value of csd-0 array generated by makeAACCodecSpecificData: " + Helpers.bytesToHex(csd.array()));

        return format;
    }

    public static void writeTestAudioToFile(String filePath) {
        try {
            FileOutputStream os = new FileOutputStream(new File(filePath));
            for (int i = 0; i < TestFrames.MUSIC_ADTS_FRAME_BUFFERS.length; i++) {
                os.write(TestFrames.MUSIC_ADTS_FRAME_BUFFERS[i]);
            }
            os.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
