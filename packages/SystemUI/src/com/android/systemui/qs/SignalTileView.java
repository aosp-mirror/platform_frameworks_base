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

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.ColorFilter;
import android.graphics.LightingColorFilter;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile.SignalState;

/** View that represents a custom quick settings tile for displaying signal info (wifi/cell). **/
public final class SignalTileView extends QSTileView {
    private static final long DEFAULT_DURATION = new ValueAnimator().getDuration();
    private static final long SHORT_DURATION = DEFAULT_DURATION / 3;
    private static final ColorFilter FILTER = new LightingColorFilter(0xffffffff, 0xff283034);

    private FrameLayout mIconFrame;
    private ImageView mSignal;
    private ImageView mOverlay;
    private ImageView mIn;
    private ImageView mOut;

    public SignalTileView(Context context) {
        super(context);

        mIn = new ImageView(context);
        mIn.setImageResource(R.drawable.ic_qs_signal_in);
        mIn.setColorFilter(FILTER);
        addView(mIn);

        mOut = new ImageView(context);
        mOut.setImageResource(R.drawable.ic_qs_signal_out);
        mOut.setColorFilter(FILTER);
        addView(mOut);
    }

    @Override
    protected View createIcon() {
        mIconFrame = new FrameLayout(mContext);
        mSignal = new ImageView(mContext);
        mIconFrame.addView(mSignal);
        mOverlay = new ImageView(mContext);
        mIconFrame.addView(mOverlay);
        return mIconFrame;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int hs = MeasureSpec.makeMeasureSpec(mIconFrame.getMeasuredHeight(), MeasureSpec.EXACTLY);
        int ws = MeasureSpec.makeMeasureSpec(mIconFrame.getMeasuredHeight(), MeasureSpec.AT_MOST);
        mIn.measure(ws, hs);
        mOut.measure(ws, hs);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        layoutIndicator(mIn);
        layoutIndicator(mOut);
    }

    private void layoutIndicator(View indicator) {
        indicator.layout(
                mIconFrame.getRight(),
                mIconFrame.getBottom() - indicator.getMeasuredHeight(),
                mIconFrame.getRight() + indicator.getMeasuredWidth(),
                mIconFrame.getBottom());
    }

    @Override
    protected void handleStateChanged(QSTile.State state) {
        super.handleStateChanged(state);
        final SignalState s = (SignalState) state;
        mSignal.setImageDrawable(null);  // force refresh
        mSignal.setImageResource(s.iconId);
        mSignal.setColorFilter(s.filter ? FILTER : null);
        if (s.overlayIconId > 0) {
            mOverlay.setVisibility(VISIBLE);
            mOverlay.setImageDrawable(null);  // force refresh
            mOverlay.setImageResource(s.overlayIconId);
            mOverlay.setColorFilter(s.filter ? FILTER : null);
        } else {
            mOverlay.setVisibility(GONE);
        }
        setVisibility(mIn, s.activityIn);
        setVisibility(mOut, s.activityOut);
    }

    private void setVisibility(View view, boolean visible) {
        final float newAlpha = visible ? 1 : 0;
        if (view.getAlpha() != newAlpha) {
            view.animate()
                .setDuration(visible ? SHORT_DURATION : DEFAULT_DURATION)
                .alpha(newAlpha)
                .withLayer()
                .start();
        }
    }
}