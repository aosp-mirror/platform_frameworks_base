/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.android.systemui.R;

public class DataUsageGraph extends View {

    private final int mBackgroundColor;
    private final int mTrackColor;
    private final int mUsageColor;
    private final int mOverlimitColor;
    private final int mMarkerWidth;
    private final RectF mTmpRect = new RectF();
    private final Paint mTmpPaint = new Paint();

    private long mMaxLevel = 1;
    private long mLimitLevel;
    private long mWarningLevel;
    private long mUsageLevel;

    public DataUsageGraph(Context context, AttributeSet attrs) {
        super(context, attrs);
        final Resources res = context.getResources();
        mBackgroundColor = res.getColor(R.color.system_primary_color);
        mTrackColor = res.getColor(R.color.data_usage_graph_track);
        mUsageColor = res.getColor(R.color.system_accent_color);
        mOverlimitColor = res.getColor(R.color.system_warning_color);
        mMarkerWidth = res.getDimensionPixelSize(R.dimen.data_usage_graph_marker_width);
    }

    public void setLevels(long maxLevel, long limitLevel, long warningLevel, long usageLevel) {
        mMaxLevel = Math.max(maxLevel, 1);
        mLimitLevel = limitLevel;
        mWarningLevel = warningLevel;
        mUsageLevel = usageLevel;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        final RectF r = mTmpRect;
        final Paint p = mTmpPaint;
        final int w = getWidth();
        final int h = getHeight();

        // draw track
        r.set(0, 0, w, h);
        p.setColor(mTrackColor);
        canvas.drawRect(r, p);

        final boolean hasLimit = mLimitLevel > 0;
        final boolean overLimit = hasLimit && mUsageLevel > mLimitLevel;

        final long maxLevel = hasLimit ? Math.max(mUsageLevel, mLimitLevel) : mMaxLevel;
        final long usageLevel = hasLimit ? Math.min(mUsageLevel, mLimitLevel) : mUsageLevel;
        float usageRight = w * (usageLevel / (float) maxLevel);
        if (overLimit) {
            usageRight -= (mMarkerWidth / 2);
            usageRight = Math.min(usageRight, w - mMarkerWidth * 2);
            usageRight = Math.max(usageRight, mMarkerWidth);
        }

        // draw usage
        r.set(0, 0, usageRight, h);
        p.setColor(mUsageColor);
        canvas.drawRect(r, p);

        if (overLimit) {
            // draw gap
            r.set(usageRight, 0, usageRight + mMarkerWidth, h);
            p.setColor(mBackgroundColor);
            canvas.drawRect(r, p);

            // draw overlimit
            r.set(usageRight + mMarkerWidth, 0, w, h);
            p.setColor(mOverlimitColor);
            canvas.drawRect(r, p);
        }
    }
}
