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
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile.SignalState;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.qs.tileimpl.QSIconViewImpl;
import com.android.systemui.qs.tileimpl.SlashImageView;

/** View that represents a custom quick settings tile for displaying signal info (wifi/cell). **/
public class SignalTileView extends QSIconViewImpl {
    private static final long DEFAULT_DURATION = new ValueAnimator().getDuration();
    private static final long SHORT_DURATION = DEFAULT_DURATION / 3;

    protected FrameLayout mIconFrame;
    protected ImageView mSignal;
    private ImageView mOverlay;
    private ImageView mIn;
    private ImageView mOut;

    private int mWideOverlayIconStartPadding;
    private int mSignalIndicatorToIconFrameSpacing;

    public SignalTileView(Context context) {
        super(context);

        mIn = addTrafficView(R.drawable.ic_qs_signal_in);
        mOut = addTrafficView(R.drawable.ic_qs_signal_out);

        setClipChildren(false);
        setClipToPadding(false);

        mWideOverlayIconStartPadding = context.getResources().getDimensionPixelSize(
                R.dimen.wide_type_icon_start_padding_qs);
        mSignalIndicatorToIconFrameSpacing = context.getResources().getDimensionPixelSize(
                R.dimen.signal_indicator_to_icon_frame_spacing);
    }

    private ImageView addTrafficView(int icon) {
        final ImageView traffic = new ImageView(mContext);
        traffic.setImageResource(icon);
        traffic.setAlpha(0f);
        addView(traffic);
        return traffic;
    }

    @Override
    protected View createIcon() {
        mIconFrame = new FrameLayout(mContext);
        mSignal = createSlashImageView(mContext);
        mIconFrame.addView(mSignal);
        mOverlay = new ImageView(mContext);
        mIconFrame.addView(mOverlay, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        return mIconFrame;
    }

    protected SlashImageView createSlashImageView(Context context) {
        return new SlashImageView(context);
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

    @Override
    protected int getIconMeasureMode() {
        return MeasureSpec.AT_MOST;
    }

    private void layoutIndicator(View indicator) {
        boolean isRtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int left, right;
        if (isRtl) {
            right = getLeft() - mSignalIndicatorToIconFrameSpacing;
            left = right - indicator.getMeasuredWidth();
        } else {
            left = getRight() + mSignalIndicatorToIconFrameSpacing;
            right = left + indicator.getMeasuredWidth();
        }
        indicator.layout(
                left,
                mIconFrame.getBottom() - indicator.getMeasuredHeight(),
                right,
                mIconFrame.getBottom());
    }

    @Override
    public void setIcon(State state, boolean allowAnimations) {
        final SignalState s = (SignalState) state;
        setIcon(mSignal, s, allowAnimations);

        if (s.overlayIconId > 0) {
            mOverlay.setVisibility(VISIBLE);
            mOverlay.setImageResource(s.overlayIconId);
        } else {
            mOverlay.setVisibility(GONE);
        }
        if (s.overlayIconId > 0 && s.isOverlayIconWide) {
            mSignal.setPaddingRelative(mWideOverlayIconStartPadding, 0, 0, 0);
        } else {
            mSignal.setPaddingRelative(0, 0, 0, 0);
        }
        final boolean shouldAnimate = allowAnimations && isShown();
        // Do not show activity indicators
//        setVisibility(mIn, shouldAnimate, s.activityIn);
//        setVisibility(mOut, shouldAnimate, s.activityOut);
    }

    private void setVisibility(View view, boolean shown, boolean visible) {
        final float newAlpha = shown && visible ? 1 : 0;
        if (view.getAlpha() == newAlpha) return;
        if (shown) {
            view.animate()
                .setDuration(visible ? SHORT_DURATION : DEFAULT_DURATION)
                .alpha(newAlpha)
                .start();
        } else {
            view.setAlpha(newAlpha);
        }
    }
}
