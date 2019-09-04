package com.example.audio_consumer.custom_progress_bar;

import java.util.ArrayList;
import java.util.HashMap;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;

import androidx.appcompat.widget.AppCompatSeekBar;

import com.example.audio_consumer.R;

import net.named_data.jndn.Name;

public class CustomProgressBar extends AppCompatSeekBar {

    private static final String TAG = "CustomSeekBar";

    // Private constants
    public static final int DEFAULT_TOTAL_SEGMENTS = 30;

    Name currentStreamName_;
    private int totalSegments_ = DEFAULT_TOTAL_SEGMENTS;
    private ArrayList<ProgressItem> progressItemsList_;
    private HashMap<Long, Integer> segmentColors_;

    private class ProgressItem {
        private int color;
        private float percentage;
    }

    public void setStreamName(Name streamName) {
        currentStreamName_ = streamName;
    }

    public Name getStreamName() {
        return currentStreamName_;
    }

    public void setTotalSegments(int totalSegments) {
        Log.d(TAG, "total segments changed (new value " + totalSegments + ")");
        totalSegments_ = totalSegments;

        ArrayList<Integer> oldColors = new ArrayList<>();
        for (int i = 0; i < progressItemsList_.size() && i < totalSegments_; i++) {
            oldColors.add(progressItemsList_.get(i).color);
        }

        updateMultipleSegmentColors(oldColors);
    }

    public int getTotalSegments() {
        return totalSegments_;
    }

    public void updateSingleSegmentColor(int segNum, int color) {
        if (segNum >= totalSegments_) return;
        progressItemsList_.get(segNum).color = color;
        invalidate();
    }

    private void updateMultipleSegmentColors(ArrayList<Integer> segColors) {
        float singleSegPercentage = (100f / (float) totalSegments_);

        Log.d(TAG, "updating multiple segment colors (" +
                "totalSegments_ " + totalSegments_ + ", " +
                "segColors.size() " + segColors.size() + ", " +
                "singleSegPercentage " + singleSegPercentage +
                ")");

        ArrayList<ProgressItem> progressItemsList = new ArrayList<>();
        ProgressItem progressItem;

        for (int i = 0; i < segColors.size(); i++) {
            progressItem = new ProgressItem();
            progressItem.percentage = singleSegPercentage;
            progressItem.color = segColors.get(i);
            progressItemsList.add(progressItem);
        }

        if (segColors.size() < totalSegments_) {
            for (int i = 0; i < totalSegments_ - segColors.size(); i++) {
                progressItem = new ProgressItem();
                progressItem.percentage = singleSegPercentage;
                progressItem.color = R.color.grey;
                progressItemsList.add(progressItem);
            }
        }

        initData(progressItemsList);
        invalidate();
    }

    public void reset() {
        segmentColors_.clear();

        ArrayList<Integer> segmentColors = new ArrayList<>();
        for (int i = 0; i < DEFAULT_TOTAL_SEGMENTS; i++) {
            segmentColors.add(R.color.grey);
        }

        totalSegments_ = DEFAULT_TOTAL_SEGMENTS;
        updateMultipleSegmentColors(segmentColors);
    }

    public void init() {
        segmentColors_ = new HashMap<>();
        reset();
    }

    public CustomProgressBar(Context context) {
        super(context);
    }

    public CustomProgressBar(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CustomProgressBar(Context context, AttributeSet attrs, int defStyle) {
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

                progressItemWidth = (int) (progressItem.percentage
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