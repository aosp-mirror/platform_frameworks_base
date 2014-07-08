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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.android.systemui.R;

public class DataUsageGraph extends View {

    private final int mBackgroundColor;
    private final int mUsageColor;
    private final RectF mTmpRect = new RectF();
    private final Paint mTmpPaint = new Paint();

    private long mMaxLevel = 1;
    private long mLimitLevel;
    private long mWarningLevel;
    private long mUsageLevel;

    public DataUsageGraph(Context context, AttributeSet attrs) {
        super(context, attrs);
        mBackgroundColor = context.getResources().getColor(R.color.data_usage_graph_track);
        mUsageColor = context.getResources().getColor(R.color.system_accent_color);
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

        // draw background
        r.set(0, 0, w, h);
        p.setColor(mBackgroundColor);
        canvas.drawRect(r, p);

        // draw usage
        r.set(0, 0, w * mUsageLevel / (float) mMaxLevel, h);
        p.setColor(mUsageColor);
        canvas.drawRect(r, p);
    }
}
