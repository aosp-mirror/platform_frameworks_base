/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.qs;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

import com.android.systemui.Prefs;

import java.util.concurrent.TimeUnit;


/**
 * Tooltip/header view for the Quick Settings panel.
 */
public class QSTooltipView extends LinearLayout {

    private static final int FADE_ANIMATION_DURATION_MS = 300;
    private static final long AUTO_FADE_OUT_DELAY_MS = TimeUnit.SECONDS.toMillis(6);
    private static final int TOOLTIP_NOT_YET_SHOWN_COUNT = 0;
    public static final int MAX_TOOLTIP_SHOWN_COUNT = 3;

    private final Handler mHandler = new Handler();
    private final Runnable mAutoFadeOutRunnable = () -> fadeOut();

    private int mShownCount;

    public QSTooltipView(Context context) {
        this(context, null);
    }

    public QSTooltipView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mShownCount = getStoredShownCount();
    }

    /** Returns the latest stored tooltip shown count from SharedPreferences. */
    private int getStoredShownCount() {
        return Prefs.getInt(
                mContext,
                Prefs.Key.QS_LONG_PRESS_TOOLTIP_SHOWN_COUNT,
                TOOLTIP_NOT_YET_SHOWN_COUNT);
    }

    /**
     * Fades in the header view if we can show the tooltip - short circuits any running animation.
     */
    public void fadeIn() {
        if (mShownCount < MAX_TOOLTIP_SHOWN_COUNT) {
            animate().cancel();
            setVisibility(View.VISIBLE);
            animate()
                    .alpha(1f)
                    .setDuration(FADE_ANIMATION_DURATION_MS)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            mHandler.postDelayed(mAutoFadeOutRunnable, AUTO_FADE_OUT_DELAY_MS);
                        }
                    })
                    .start();

            // Increment and drop the shown count in prefs for the next time we're deciding to
            // fade in the tooltip. We first sanity check that the tooltip count hasn't changed yet
            // in prefs (say, from a long press).
            if (getStoredShownCount() <= mShownCount) {
                Prefs.putInt(mContext, Prefs.Key.QS_LONG_PRESS_TOOLTIP_SHOWN_COUNT, ++mShownCount);
            }
        }
    }

    /**
     * Fades out the header view if it's partially visible - short circuits any running animation.
     */
    public void fadeOut() {
        animate().cancel();
        if (getVisibility() == View.VISIBLE && getAlpha() != 0f) {
            mHandler.removeCallbacks(mAutoFadeOutRunnable);
            animate()
                    .alpha(0f)
                    .setDuration(FADE_ANIMATION_DURATION_MS)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            perhapsMakeViewInvisible();
                        }
                    })
                    .start();
        } else {
            perhapsMakeViewInvisible();
        }
    }

    /**
     * Only update visibility if the view is currently being shown. Otherwise, it's already been
     * hidden by some other manner.
     */
    private void perhapsMakeViewInvisible() {
        if (getVisibility() == View.VISIBLE) {
            setVisibility(View.INVISIBLE);
        }
    }
}
