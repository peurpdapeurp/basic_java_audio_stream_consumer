package com.example.audio_consumer.custom_progress_bar;

import java.util.ArrayList;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.SeekBar;

import androidx.appcompat.widget.AppCompatSeekBar;

import net.named_data.jndn.Name;

public class CustomSeekBar extends AppCompatSeekBar {

    private static final String TAG = "CustomSeekBar";

    Name currentStreamName_;
    private long totalSegments_ = 10;
    private ArrayList<ProgressItem> progressItemsList_;

    public void setStreamName(Name streamName) {
        currentStreamName_ = streamName;
    }

    public Name getStreamName() {
        return currentStreamName_;
    }

    public void setTotalSegments(long totalSegments) {
        Log.d(TAG, System.currentTimeMillis() + ": " +
                "total segments changed (" +
                "stream name " + currentStreamName_.toString() + ", " +
                "total segments " + totalSegments +
                ")");
        totalSegments_ = totalSegments;
    }

    public long getTotalSegments() {
        return totalSegments_;
    }

    public CustomSeekBar(Context context) {
        super(context);
    }

    public CustomSeekBar(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CustomSeekBar(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void initData(ArrayList<ProgressItem> progressItemsList) {
        this.progressItemsList_ = progressItemsList;
    }

    @Override
    protected synchronized void onMeasure(int widthMeasureSpec,
                                          int heightMeasureSpec) {
        // TODO Auto-generated method stub
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    protected void onDraw(Canvas canvas) {
        if (progressItemsList_.size() > 0) {
            int progressBarWidth = getWidth();
            int progressBarHeight = getHeight();
            int thumboffset = getThumbOffset();
            int lastProgressX = 0;
            int progressItemWidth, progressItemRight;
            for (int i = 0; i < progressItemsList_.size(); i++) {
                ProgressItem progressItem = progressItemsList_.get(i);
                Paint progressPaint = new Paint();
                progressPaint.setColor(getResources().getColor(
                        progressItem.color));

                progressItemWidth = (int) (progressItem.progressItemPercentage
                        * progressBarWidth / 100);

                progressItemRight = lastProgressX + progressItemWidth;

                // for last item give right to progress item to the width
                if (i == progressItemsList_.size() - 1
                        && progressItemRight != progressBarWidth) {
                    progressItemRight = progressBarWidth;
                }
                Rect progressRect = new Rect();
                progressRect.set(lastProgressX, thumboffset / 2,
                        progressItemRight, progressBarHeight - thumboffset / 2);
                canvas.drawRect(progressRect, progressPaint);
                lastProgressX = progressItemRight;
            }
            super.onDraw(canvas);
        }

    }

}