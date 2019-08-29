package com.example.audio_consumer.Utils;

import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class Pipe {

    private InputStream is_;
    private OutputStream os_;
    private ParcelFileDescriptor[] parcelFileDescriptors_;
    private ParcelFileDescriptor parcelRead_;
    private ParcelFileDescriptor parcelWrite_;

    public Pipe() {
        try {
            parcelFileDescriptors_ = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            e.printStackTrace();
        }

        parcelRead_ = new ParcelFileDescriptor(parcelFileDescriptors_[0]);
        parcelWrite_ = new ParcelFileDescriptor(parcelFileDescriptors_[1]);

        is_ = new ParcelFileDescriptor.AutoCloseInputStream(parcelRead_);
        os_ = new ParcelFileDescriptor.AutoCloseOutputStream(parcelWrite_);
    }

    public InputStream getInputStream() {
        return is_;
    }

    public OutputStream getOutputStream() {
        return os_;
    }

}
