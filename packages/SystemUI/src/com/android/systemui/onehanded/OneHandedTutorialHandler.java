/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.onehanded;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemProperties;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.android.systemui.R;

import java.io.PrintWriter;

/**
 * Manages the user tutorial handling for One Handed operations, including animations synchronized
 * with one-handed translation.
 * Refer {@link OneHandedGestureHandler} and {@link OneHandedTouchHandler} to see start and stop
 * one handed gesture
 */
public class OneHandedTutorialHandler implements OneHandedTransitionCallback {
    private static final String TAG = "OneHandedTutorialHandler";
    private static final String ONE_HANDED_MODE_OFFSET_PERCENTAGE =
            "persist.debug.one_handed_offset_percentage";
    private static final int MAX_TUTORIAL_SHOW_COUNT = 2;
    private final Rect mLastUpdatedBounds = new Rect();
    private final WindowManager mWindowManager;

    private View mTutorialView;
    private Point mDisplaySize = new Point();
    private Handler mUpdateHandler;
    private ContentResolver mContentResolver;
    private boolean mCanShowTutorial;

    /**
     * Container of the tutorial panel showing at outside region when one handed starting
     */
    private ViewGroup mTargetViewContainer;
    private int mTutorialAreaHeight;

    private final OneHandedAnimationCallback mAnimationCallback = new OneHandedAnimationCallback() {
        @Override
        public void onTutorialAnimationUpdate(int offset) {
            mUpdateHandler.post(() -> onAnimationUpdate(offset));
        }
    };

    public OneHandedTutorialHandler(Context context) {
        context.getDisplay().getRealSize(mDisplaySize);
        mContentResolver = context.getContentResolver();
        mUpdateHandler = new Handler();
        mWindowManager = context.getSystemService(WindowManager.class);
        mTargetViewContainer = new FrameLayout(context);
        mTargetViewContainer.setClipChildren(false);
        mTutorialAreaHeight = Math.round(mDisplaySize.y
                * (SystemProperties.getInt(ONE_HANDED_MODE_OFFSET_PERCENTAGE, 50) / 100.0f));
        mTutorialView = LayoutInflater.from(context).inflate(R.xml.one_handed_tutorial, null);
        mTargetViewContainer.addView(mTutorialView);
        mCanShowTutorial = (Settings.Secure.getInt(mContentResolver,
                Settings.Secure.ONE_HANDED_TUTORIAL_SHOW_COUNT, 0) >= MAX_TUTORIAL_SHOW_COUNT)
                ? false : true;
        if (mCanShowTutorial) {
            createOrUpdateTutorialTarget();
        }
    }

    @Override
    public void onStartFinished(Rect bounds) {
        mUpdateHandler.post(() -> {
            updateFinished(View.VISIBLE, 0f);
            updateTutorialCount();
        });
    }

    @Override
    public void onStopFinished(Rect bounds) {
        mUpdateHandler.post(() -> updateFinished(
                View.INVISIBLE, -mTargetViewContainer.getHeight()));
    }

    private void updateFinished(int visible, float finalPosition) {
        if (!canShowTutorial()) {
            return;
        }

        mTargetViewContainer.setVisibility(visible);
        mTargetViewContainer.setTranslationY(finalPosition);
    }

    private void updateTutorialCount() {
        int showCount = Settings.Secure.getInt(mContentResolver,
                Settings.Secure.ONE_HANDED_TUTORIAL_SHOW_COUNT, 0);
        showCount = Math.min(MAX_TUTORIAL_SHOW_COUNT, showCount + 1);
        mCanShowTutorial = showCount < MAX_TUTORIAL_SHOW_COUNT;
        Settings.Secure.putInt(mContentResolver,
                Settings.Secure.ONE_HANDED_TUTORIAL_SHOW_COUNT, showCount);
    }

    /**
     * Adds the tutorial target view to the WindowManager and update its layout, so it's ready
     * to be animated in.
     */
    private void createOrUpdateTutorialTarget() {
        mUpdateHandler.post(() -> {
            if (!mTargetViewContainer.isAttachedToWindow()) {
                mTargetViewContainer.setVisibility(View.INVISIBLE);

                try {
                    mWindowManager.addView(mTargetViewContainer, getTutorialTargetLayoutParams());
                } catch (IllegalStateException e) {
                    // This shouldn't happen, but if the target is already added, just update its
                    // layout params.
                    mWindowManager.updateViewLayout(
                            mTargetViewContainer, getTutorialTargetLayoutParams());
                }
            } else {
                mWindowManager.updateViewLayout(mTargetViewContainer,
                        getTutorialTargetLayoutParams());
            }
        });
    }

    OneHandedAnimationCallback getAnimationCallback() {
        return mAnimationCallback;
    }

    /**
     * Returns layout params for the dismiss target, using the latest display metrics.
     */
    private WindowManager.LayoutParams getTutorialTargetLayoutParams() {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                mDisplaySize.x, mTutorialAreaHeight, 0, 0,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        lp.setFitInsetsTypes(0 /* types */);
        lp.setTitle("one-handed-tutorial-overlay");

        return lp;
    }

    void dump(@NonNull PrintWriter pw) {
        final String innerPrefix = "  ";
        pw.println(TAG + "states: ");
        pw.print(innerPrefix + "mLastUpdatedBounds=");
        pw.println(mLastUpdatedBounds);
    }

    private boolean canShowTutorial() {
        if (!mCanShowTutorial) {
            mTargetViewContainer.setVisibility(View.GONE);
            return false;
        }

        return true;
    }

    private void onAnimationUpdate(float value) {
        if (!canShowTutorial()) {
            return;
        }
        mTargetViewContainer.setVisibility(View.VISIBLE);
        mTargetViewContainer.setTransitionGroup(true);
        mTargetViewContainer.setTranslationY(value - mTargetViewContainer.getHeight());
    }
}
