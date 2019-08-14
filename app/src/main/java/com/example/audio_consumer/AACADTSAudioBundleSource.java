package com.example.audio_consumer;

import android.media.MediaDataSource;

import java.io.IOException;

public class AACADTSAudioBundleSource extends MediaDataSource {

    byte[] buffer_;

    public AACADTSAudioBundleSource(byte[] audioBundle) {
        super();
        buffer_ = audioBundle;
    }

    @Override
    public void close() throws IOException { }

    @Override
    public int readAt(long source_offset, byte[] dest_buf, int dest_offset, int length) throws IOException {
        try {
            int read_length = 0;
            if (source_offset > buffer_.length - 1) {
                return -1; // end of stream reached
            }
            if (source_offset + length > buffer_.length) {
                read_length = buffer_.length - (int) source_offset;
            }
            System.arraycopy(buffer_, (int) source_offset, dest_buf, dest_offset, read_length);
            return read_length;
        }
        catch (ArrayIndexOutOfBoundsException e) {
            throw new IOException(e.getMessage());
        }
    }

    @Override
    public long getSize() throws IOException {
        return buffer_.length;
    }
}
