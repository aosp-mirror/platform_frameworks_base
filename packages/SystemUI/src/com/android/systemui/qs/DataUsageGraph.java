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

import com.android.settingslib.Utils;
import com.android.systemui.R;

public class DataUsageGraph extends View {

    private final int mTrackColor;
    private final int mUsageColor;
    private final int mOverlimitColor;
    private final int mWarningColor;
    private final int mMarkerWidth;
    private final RectF mTmpRect = new RectF();
    private final Paint mTmpPaint = new Paint();

    private long mLimitLevel;
    private long mWarningLevel;
    private long mUsageLevel;
    private long mMaxLevel;

    public DataUsageGraph(Context context, AttributeSet attrs) {
        super(context, attrs);
        final Resources res = context.getResources();
        mTrackColor = Utils.getColorStateListDefaultColor(context,
                R.color.data_usage_graph_track);
        mWarningColor = Utils.getColorStateListDefaultColor(context,
                R.color.data_usage_graph_warning);
        mUsageColor = Utils.getColorAccentDefaultColor(context);
        mOverlimitColor = Utils.getColorErrorDefaultColor(context);
        mMarkerWidth = res.getDimensionPixelSize(R.dimen.data_usage_graph_marker_width);
    }

    public void setLevels(long limitLevel, long warningLevel, long usageLevel) {
        mLimitLevel = Math.max(0, limitLevel);
        mWarningLevel = Math.max(0, warningLevel);
        mUsageLevel = Math.max(0, usageLevel);
        mMaxLevel = Math.max(Math.max(Math.max(mLimitLevel, mWarningLevel), mUsageLevel), 1);
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        final RectF r = mTmpRect;
        final Paint p = mTmpPaint;
        final int w = getWidth();
        final int h = getHeight();

        final boolean overLimit = mLimitLevel > 0 && mUsageLevel > mLimitLevel;
        float usageRight = w * (mUsageLevel / (float) mMaxLevel);
        if (overLimit) {
            // compute the gap
            usageRight = w * (mLimitLevel / (float) mMaxLevel) - (mMarkerWidth / 2);
            usageRight = Math.min(Math.max(usageRight, mMarkerWidth), w - mMarkerWidth * 2);

            // draw overlimit
            r.set(usageRight + mMarkerWidth, 0, w, h);
            p.setColor(mOverlimitColor);
            canvas.drawRect(r, p);
        } else {
            // draw track
            r.set(0, 0, w, h);
            p.setColor(mTrackColor);
            canvas.drawRect(r, p);
        }

        // draw usage
        r.set(0, 0, usageRight, h);
        p.setColor(mUsageColor);
        canvas.drawRect(r, p);

        // draw warning marker
        float warningLeft = w * (mWarningLevel / (float) mMaxLevel) - mMarkerWidth / 2;
        warningLeft = Math.min(Math.max(warningLeft, 0), w - mMarkerWidth);
        r.set(warningLeft, 0, warningLeft + mMarkerWidth, h);
        p.setColor(mWarningColor);
        canvas.drawRect(r, p);
    }
}
