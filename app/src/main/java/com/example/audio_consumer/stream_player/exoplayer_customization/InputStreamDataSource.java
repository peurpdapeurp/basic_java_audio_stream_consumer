package com.example.audio_consumer.stream_player.exoplayer_customization;

import android.net.Uri;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.BaseDataSource;
import com.google.android.exoplayer2.upstream.DataSpec;

import java.io.IOException;
import java.io.InputStream;

public class InputStreamDataSource extends BaseDataSource {

    private InputStream is_;
    private Uri uri_;
    private boolean opened_ = false;

    private int readPosition;

    /**
     * Creates base data source.
     *
     * @param inputStream The InputStream object through which data is read. It is assumed
     *                    that the InputStream object has already been initialized and is ready
     *                    to be read from.
     */
    public InputStreamDataSource(InputStream inputStream) {
        super(false);
        is_ = inputStream;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        uri_ = dataSpec.uri;
        transferInitializing(dataSpec);
        readPosition = (int) dataSpec.position;
        if (dataSpec.length != C.LENGTH_UNSET) {
            throw new IOException("The length of the data spec should not be set for an input " +
                                  "stream data source; the total amount of data is unknown at " +
                                  "open time.");
        }
        opened_ = true;
        transferStarted(dataSpec);
        return C.LENGTH_UNSET;
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws IOException {
        if (readLength == 0) {
            return 0;
        }
        int bytesAvailable = is_.available();
        if (bytesAvailable == 0) return 0;
        readLength = Math.min(bytesAvailable, readLength);
        is_.read(buffer, offset, readLength);
        readPosition += readLength;
        bytesTransferred(readLength);
        return readLength;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri_;
    }

    @Override
    public void close() throws IOException {
        if (opened_) {
            opened_ = false;
            transferEnded();
        }
        is_.close();
        uri_ = null;
    }

}
