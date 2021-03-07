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

package com.android.wm.shell.onehanded;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.SystemProperties;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.android.wm.shell.R;
import com.android.wm.shell.common.ShellExecutor;

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
    private final WindowManager mWindowManager;
    private final AccessibilityManager mAccessibilityManager;
    private final String mPackageName;
    private final Rect mDisplaySize;

    private Context mContext;
    private View mTutorialView;
    private ContentResolver mContentResolver;
    private boolean mCanShowTutorial;
    private String mStartOneHandedDescription;
    private String mStopOneHandedDescription;
    private boolean mIsOneHandedMode;

    private enum ONE_HANDED_TRIGGER_STATE {
        UNSET, ENTERING, EXITING
    }
    /**
     * Current One-Handed trigger state.
     * Note: This is a dynamic state, whenever last state has been confirmed
     * (i.e. onStartFinished() or onStopFinished()), the state should be set "UNSET" at final.
     */
    private ONE_HANDED_TRIGGER_STATE mTriggerState = ONE_HANDED_TRIGGER_STATE.UNSET;

    /**
     * Container of the tutorial panel showing at outside region when one handed starting
     */
    private ViewGroup mTargetViewContainer;
    private int mTutorialAreaHeight;

    private final OneHandedAnimationCallback mAnimationCallback = new OneHandedAnimationCallback() {
        @Override
        public void onTutorialAnimationUpdate(int offset) {
            onAnimationUpdate(offset);
        }

        @Override
        public void onOneHandedAnimationStart(
                OneHandedAnimationController.OneHandedTransitionAnimator animator) {
            final Rect startValue = (Rect) animator.getStartValue();
            if (mTriggerState == ONE_HANDED_TRIGGER_STATE.UNSET) {
                mTriggerState = (startValue.top == 0)
                        ? ONE_HANDED_TRIGGER_STATE.ENTERING : ONE_HANDED_TRIGGER_STATE.EXITING;
                if (mCanShowTutorial && mTriggerState == ONE_HANDED_TRIGGER_STATE.ENTERING) {
                    attachTurtorialTarget();
                }
            }
        }
    };

    public OneHandedTutorialHandler(Context context, WindowManager windowManager,
            ShellExecutor mainExecutor) {
        mContext = context;
        mWindowManager = windowManager;
        mDisplaySize = windowManager.getCurrentWindowMetrics().getBounds();
        mPackageName = context.getPackageName();
        mContentResolver = context.getContentResolver();
        mAccessibilityManager = AccessibilityManager.getInstance(context);
        mStartOneHandedDescription = context.getResources().getString(
                R.string.accessibility_action_start_one_handed);
        mStopOneHandedDescription = context.getResources().getString(
                R.string.accessibility_action_stop_one_handed);
        mCanShowTutorial = (Settings.Secure.getInt(mContentResolver,
                Settings.Secure.ONE_HANDED_TUTORIAL_SHOW_COUNT, 0) >= MAX_TUTORIAL_SHOW_COUNT)
                ? false : true;
        mIsOneHandedMode = false;
        final float offsetPercentageConfig = context.getResources().getFraction(
                R.fraction.config_one_handed_offset, 1, 1);
        final int sysPropPercentageConfig = SystemProperties.getInt(
                ONE_HANDED_MODE_OFFSET_PERCENTAGE, Math.round(offsetPercentageConfig * 100.0f));
        mTutorialAreaHeight = Math.round(
                mDisplaySize.height() * (sysPropPercentageConfig / 100.0f));

        mainExecutor.execute(() -> {
            recreateTutorialView(mContext);
        });
    }

    @Override
    public void onStartFinished(Rect bounds) {
        updateFinished(View.VISIBLE, 0f);
        updateTutorialCount();
        announcementForScreenReader(true);
        mTriggerState = ONE_HANDED_TRIGGER_STATE.UNSET;
    }

    @Override
    public void onStopFinished(Rect bounds) {
        updateFinished(View.INVISIBLE, -mTargetViewContainer.getHeight());
        announcementForScreenReader(false);
        removeTutorialFromWindowManager();
        mTriggerState = ONE_HANDED_TRIGGER_STATE.UNSET;
    }

    private void recreateTutorialView(Context context) {
        mTutorialView = LayoutInflater.from(context).inflate(R.layout.one_handed_tutorial,
                null);
        mTargetViewContainer = new FrameLayout(context);
        mTargetViewContainer.setClipChildren(false);
        mTargetViewContainer.addView(mTutorialView);
        mTargetViewContainer.setVisibility(mIsOneHandedMode ? View.VISIBLE : View.GONE);
    }

    private void updateFinished(int visible, float finalPosition) {
        if (!canShowTutorial()) {
            return;
        }
        mIsOneHandedMode = (finalPosition == 0f) ? true : false;
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

    private void announcementForScreenReader(boolean isStartOneHanded) {
        if (mAccessibilityManager.isTouchExplorationEnabled()) {
            final AccessibilityEvent event = AccessibilityEvent.obtain();
            event.setPackageName(mPackageName);
            event.setEventType(AccessibilityEvent.TYPE_ANNOUNCEMENT);
            event.getText().add(isStartOneHanded
                    ? mStartOneHandedDescription : mStopOneHandedDescription);
            mAccessibilityManager.sendAccessibilityEvent(event);
        }
    }

    /**
     * Adds the tutorial target view to the WindowManager and update its layout, so it's ready
     * to be animated in.
     */
    private void attachTurtorialTarget() {
        if (!mTargetViewContainer.isAttachedToWindow()) {
            try {
                mWindowManager.addView(mTargetViewContainer, getTutorialTargetLayoutParams());
            } catch (IllegalStateException e) {
                // This shouldn't happen, but if the target is already added, just update its
                // layout params.
                mWindowManager.updateViewLayout(
                        mTargetViewContainer, getTutorialTargetLayoutParams());
            }
        }
    }

    private void removeTutorialFromWindowManager() {
        if (mTargetViewContainer.isAttachedToWindow()) {
            mWindowManager.removeViewImmediate(mTargetViewContainer);
        }
    }

    OneHandedAnimationCallback getAnimationCallback() {
        return mAnimationCallback;
    }

    /**
     * Returns layout params for the dismiss target, using the latest display metrics.
     */
    private WindowManager.LayoutParams getTutorialTargetLayoutParams() {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                mDisplaySize.width(), mTutorialAreaHeight, 0, 0,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
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
        pw.println(TAG + " states: ");
        pw.print(innerPrefix + "mTriggerState=");
        pw.println(mTriggerState);
        pw.print(innerPrefix + "mDisplaySize=");
        pw.println(mDisplaySize);
        pw.print(innerPrefix + "mTutorialAreaHeight=");
        pw.println(mTutorialAreaHeight);
    }

    private boolean canShowTutorial() {
        if (!mCanShowTutorial) {
            // Since canSHowTutorial() will be called in onAnimationUpdate() and we still need to
            // hide Tutorial text in the period of continuously onAnimationUpdate() API call,
            // so we have to hide mTargetViewContainer here.
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

    /**
     * onConfigurationChanged events for updating tutorial text.
     * @param newConfig
     */
    public void onConfigurationChanged(Configuration newConfig) {
        if (!mCanShowTutorial) {
            return;
        }
        removeTutorialFromWindowManager();
        recreateTutorialView(mContext.createConfigurationContext(newConfig));
        attachTurtorialTarget();
    }
}
