package com.example.audio_consumer.Utils;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.example.audio_consumer.stream_player.exoplayer_customization.InputStreamDataSource;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

public class AudioProcessingHelpers {

    private static final String TAG = "AudioProcessingHelpers";

    public static final int AAC_ADTS_SAMPLES_PER_FRAME = 1024;

    public static boolean checkStreamPlayerAdtsParser() {
//        byte[] adtsFramesTestBuffer = AudioProcessingHelpers.getByteArraysAsSingleArray(TestFrames.MUSIC_ADTS_FRAME_BUFFERS);
//        ArrayList<byte[]> adtsFramesArrayList = StreamPlayerBuffer.AdtsFrameParser.parseAdtsFrames(adtsFramesTestBuffer);
//        if (adtsFramesArrayList.size() != TestFrames.MUSIC_ADTS_FRAME_BUFFERS.length) {
//            Log.e(TAG, "MISMATCH OF NUMBER OF ADTS PARSED FRAMES AND NUMBER OF ORIGINAL FRAMES: " + "\n" +
//                    "NUMBER OF PARSED FRAMES: " + adtsFramesArrayList.size() + "\n" +
//                    "NUMBER OF ORIGINAL FRAMES: " + TestFrames.MUSIC_ADTS_FRAME_BUFFERS.length);
//            return false;
//        }
//        for (int i = 0; i < adtsFramesArrayList.size(); i++) {
//            if (!Arrays.equals(adtsFramesArrayList.get(i), TestFrames.MUSIC_ADTS_FRAME_BUFFERS[i])) {
//                Log.e(TAG, "MISMATCH OF ADTS PARSED FRAMES AND ORIGINAL FRAMES: " + "\n" +
//                        "ADTS PARSED FRAME : " + Helpers.bytesToHex(adtsFramesArrayList.get(i)) + "\n" +
//                        "ORIGINAL FRAME: " + TestFrames.MUSIC_ADTS_FRAME_BUFFERS[i]);
//                return false;
//            }
//        }
        return true;
    }

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

    public static byte[] getByteArraysAsSingleArray(byte[][] arrays) {
        int singleAudioFileArrayLength = 0;
        for (int i = 0; i < arrays.length; i++) {
            singleAudioFileArrayLength += arrays[i].length;
        }
        byte[] singleAudioFileArray = new byte[singleAudioFileArrayLength];
        int currentOffset = 0;
        for (int i = 0; i < arrays.length; i++) {
            System.arraycopy(arrays[i], 0, singleAudioFileArray, currentOffset,
                    arrays[i].length);
            currentOffset += arrays[i].length;
        }
        return singleAudioFileArray;
    }

    public static byte[] getFileAsByteArray(String filePath) {
        RandomAccessFile f = null;
        byte[] musicSampleArray = null;
        try {
            f = new RandomAccessFile(filePath, "r");
            musicSampleArray = new byte[(int) f.length()];
            f.readFully(musicSampleArray);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return musicSampleArray;
    }

    public static void playAudioFileFromCacheWithExoPlayer(Context ctx, String filePath) {

        ExoPlayer player = ExoPlayerFactory.newSimpleInstance(ctx);

        DataSource.Factory dataSourceFactory = new DefaultDataSourceFactory(ctx,
                Util.getUserAgent(ctx, "test"));
        MediaSource audioSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(Uri.fromFile(new File(filePath)));

        player.addListener(new Player.EventListener() {
            @Override
            public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                String playbackStateString = "";
                switch (playbackState) {
                    case Player.STATE_IDLE:
                        playbackStateString = "STATE_IDLE";
                        break;
                    case Player.STATE_BUFFERING:
                        playbackStateString = "STATE_BUFFERING";
                        break;
                    case Player.STATE_READY:
                        playbackStateString = "STATE_READY";
                        break;
                    case Player.STATE_ENDED:
                        playbackStateString = "STATE_ENDED";
                        break;
                    default:
                        playbackStateString = "UNEXPECTED STATE CODE (" + playbackState + ")";
                }
                Log.d(TAG, "(Playing from cache) Exoplayer state changed to: " + playbackStateString);
            }
        });

        player.prepare(audioSource);

        player.setPlayWhenReady(true);

    }

    public static void playAudioFileFromInputStreamWithExoPlayer(Context ctx, byte[][] frames) {

        InputStream is_;
        OutputStream os_;

        ParcelFileDescriptor[] parcelFileDescriptors_;
        ParcelFileDescriptor parcelRead_;
        ParcelFileDescriptor parcelWrite_;

        try {
            parcelFileDescriptors_ = ParcelFileDescriptor.createPipe();
            parcelRead_ = new ParcelFileDescriptor(parcelFileDescriptors_[0]);
            parcelWrite_ = new ParcelFileDescriptor(parcelFileDescriptors_[1]);

            is_ = new ParcelFileDescriptor.AutoCloseInputStream(parcelRead_);
            os_ = new ParcelFileDescriptor.AutoCloseOutputStream(parcelWrite_);

            for (int j = 0; j < 1000; j++) {
                for (int i = 0; i < frames.length; i++) {
                    os_.write(frames[i]);
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        ExoPlayer player = ExoPlayerFactory.newSimpleInstance(ctx);

        // This is the MediaSource representing the media to be played.
        MediaSource audioSource = new ProgressiveMediaSource.Factory(
                () -> {
                    InputStreamDataSource dataSource =
                            new InputStreamDataSource(is_);
                    return dataSource;
                })
                .createMediaSource(Uri.parse("fake_uri"));

        player.addListener(new Player.EventListener() {
            @Override
            public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                String playbackStateString = "";
                switch (playbackState) {
                    case Player.STATE_IDLE:
                        playbackStateString = "STATE_IDLE";
                        break;
                    case Player.STATE_BUFFERING:
                        playbackStateString = "STATE_BUFFERING";
                        break;
                    case Player.STATE_READY:
                        playbackStateString = "STATE_READY";
                        break;
                    case Player.STATE_ENDED:
                        playbackStateString = "STATE_ENDED";
                        break;
                    default:
                        playbackStateString = "UNEXPECTED STATE CODE (" + playbackState + ")";
                }
                Log.d(TAG, "(Playing from input stream) Exoplayer state changed to: " + playbackStateString);
            }
        });

        player.prepare(audioSource);

        player.setPlayWhenReady(true);
    }

}
