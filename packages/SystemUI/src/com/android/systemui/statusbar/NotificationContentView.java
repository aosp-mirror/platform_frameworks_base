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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import com.android.systemui.R;

/**
 * A frame layout containing the actual payload of the notification, including the contracted and
 * expanded layout. This class is responsible for clipping the content and and switching between the
 * expanded and contracted view depending on its clipped size.
 */
public class NotificationContentView extends FrameLayout {

    private static final long ANIMATION_DURATION_LENGTH = 170;

    private final Rect mClipBounds = new Rect();

    private View mContractedChild;
    private View mExpandedChild;

    private int mSmallHeight;
    private int mClipTopAmount;
    private int mActualHeight;

    private final Interpolator mLinearInterpolator = new LinearInterpolator();

    private boolean mContractedVisible = true;

    private final Paint mFadePaint = new Paint();

    public NotificationContentView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mFadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        reset();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateClipping();
    }

    public void reset() {
        if (mContractedChild != null) {
            mContractedChild.animate().cancel();
        }
        if (mExpandedChild != null) {
            mExpandedChild.animate().cancel();
        }
        removeAllViews();
        mContractedChild = null;
        mExpandedChild = null;
        mSmallHeight = getResources().getDimensionPixelSize(R.dimen.notification_min_height);
        mActualHeight = mSmallHeight;
        mContractedVisible = true;
    }

    public void setContractedChild(View child) {
        if (mContractedChild != null) {
            mContractedChild.animate().cancel();
            removeView(mContractedChild);
        }
        sanitizeContractedLayoutParams(child);
        addView(child);
        mContractedChild = child;
        selectLayout(false /* animate */, true /* force */);
    }

    public void setExpandedChild(View child) {
        if (mExpandedChild != null) {
            mExpandedChild.animate().cancel();
            removeView(mExpandedChild);
        }
        addView(child);
        mExpandedChild = child;
        selectLayout(false /* animate */, true /* force */);
    }

    public void setActualHeight(int actualHeight) {
        mActualHeight = actualHeight;
        selectLayout(true /* animate */, false /* force */);
        updateClipping();
    }

    public int getMaxHeight() {

        // The maximum height is just the laid out height.
        return getHeight();
    }

    public int getMinHeight() {
        return mSmallHeight;
    }

    public void setClipTopAmount(int clipTopAmount) {
        mClipTopAmount = clipTopAmount;
        updateClipping();
    }

    private void updateClipping() {
        mClipBounds.set(0, mClipTopAmount, getWidth(), mActualHeight);
        setClipBounds(mClipBounds);
    }

    private void sanitizeContractedLayoutParams(View contractedChild) {
        LayoutParams lp = (LayoutParams) contractedChild.getLayoutParams();
        lp.height = mSmallHeight;
        contractedChild.setLayoutParams(lp);
    }

    private void selectLayout(boolean animate, boolean force) {
        if (mContractedChild == null) {
            return;
        }
        boolean showContractedChild = showContractedChild();
        if (showContractedChild != mContractedVisible || force) {
            if (animate && mExpandedChild != null) {
                runSwitchAnimation(showContractedChild);
            } else if (mExpandedChild != null) {
                mContractedChild.setVisibility(showContractedChild ? View.VISIBLE : View.INVISIBLE);
                mContractedChild.setAlpha(showContractedChild ? 1f : 0f);
                mExpandedChild.setVisibility(showContractedChild ? View.INVISIBLE : View.VISIBLE);
                mExpandedChild.setAlpha(showContractedChild ? 0f : 1f);
            }
        }
        mContractedVisible = showContractedChild;
    }

    private void runSwitchAnimation(final boolean showContractedChild) {
        mContractedChild.setVisibility(View.VISIBLE);
        mExpandedChild.setVisibility(View.VISIBLE);
        mContractedChild.setLayerType(LAYER_TYPE_HARDWARE, mFadePaint);
        mExpandedChild.setLayerType(LAYER_TYPE_HARDWARE, mFadePaint);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        mContractedChild.animate()
                .alpha(showContractedChild ? 1f : 0f)
                .setDuration(ANIMATION_DURATION_LENGTH)
                .setInterpolator(mLinearInterpolator);
        mExpandedChild.animate()
                .alpha(showContractedChild ? 0f : 1f)
                .setDuration(ANIMATION_DURATION_LENGTH)
                .setInterpolator(mLinearInterpolator)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        mContractedChild.setLayerType(LAYER_TYPE_NONE, null);
                        mExpandedChild.setLayerType(LAYER_TYPE_NONE, null);
                        setLayerType(LAYER_TYPE_NONE, null);
                        mContractedChild.setVisibility(showContractedChild
                                ? View.VISIBLE
                                : View.INVISIBLE);
                        mExpandedChild.setVisibility(showContractedChild
                                ? View.INVISIBLE
                                : View.VISIBLE);
                    }
                });
    }

    private boolean showContractedChild() {
        return mActualHeight <= mSmallHeight || mExpandedChild == null;
    }

    public void notifyContentUpdated() {
        selectLayout(false /* animate */, true /* force */);
    }

    public boolean isContentExpandable() {
        return mExpandedChild != null;
    }
}
