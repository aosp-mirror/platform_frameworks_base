/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.power;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.android.systemui.res.R;

/**
 * View that shows a warning shortly before the device goes into sleep
 * after prolonged user inactivity when bound to.
 */
public class InattentiveSleepWarningView extends FrameLayout {
    private final IBinder mWindowToken = new Binder();
    private final WindowManager mWindowManager;
    private Animator mFadeOutAnimator;
    private boolean mDismissing;

    InattentiveSleepWarningView(Context context) {
        super(context);
        mWindowManager = mContext.getSystemService(WindowManager.class);

        final LayoutInflater layoutInflater = LayoutInflater.from(mContext);
        layoutInflater.inflate(R.layout.inattentive_sleep_warning, this, true /* attachToRoot */);

        setFocusable(true);
        setOnKeyListener((v, keyCode, event) -> {
            // overlay consumes key presses
            return true;
        });

        mFadeOutAnimator = AnimatorInflater.loadAnimator(getContext(),
                com.android.internal.R.animator.fade_out);
        mFadeOutAnimator.setTarget(this);
        mFadeOutAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                removeView();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mDismissing = false;
                setAlpha(1f);
                setVisibility(View.VISIBLE);
            }
        });
    }

    private void removeView() {
        if (mDismissing) {
            setVisibility(View.INVISIBLE);
            mWindowManager.removeView(InattentiveSleepWarningView.this);
        }
    }

    /**
     * Show the warning.
     */
    public void show() {
        if (getParent() != null) {
            if (mFadeOutAnimator.isStarted()) {
                mFadeOutAnimator.cancel();
            }
            return;
        }

        setAlpha(1f);
        setVisibility(View.VISIBLE);
        mWindowManager.addView(this, getLayoutParams(mWindowToken));
        announceForAccessibility(
                getContext().getString(R.string.inattentive_sleep_warning_message));
    }

    /**
     * Dismiss the warning.
     */
    public void dismiss(boolean animated) {
        if (getParent() == null) {
            return;
        }

        mDismissing = true;

        if (animated) {
            postOnAnimation(mFadeOutAnimator::start);
        } else {
            removeView();
        }
    }

    /**
     * @param windowToken token for the window
     */
    private WindowManager.LayoutParams getLayoutParams(IBinder windowToken) {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        lp.setTitle("InattentiveSleepWarning");
        lp.token = windowToken;
        return lp;
    }
}
