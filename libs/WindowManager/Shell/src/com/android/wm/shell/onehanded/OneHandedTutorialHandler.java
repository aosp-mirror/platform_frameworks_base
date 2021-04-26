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

import static android.os.UserHandle.myUserId;

import static com.android.wm.shell.onehanded.OneHandedState.STATE_ACTIVE;
import static com.android.wm.shell.onehanded.OneHandedState.STATE_ENTERING;
import static com.android.wm.shell.onehanded.OneHandedState.STATE_EXITING;
import static com.android.wm.shell.onehanded.OneHandedState.STATE_NONE;

import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.SystemProperties;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.R;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.ShellExecutor;

import java.io.PrintWriter;

/**
 * Handles tutorial visibility and synchronized transition for One Handed operations,
 * TargetViewContainer only be created and attach to window when
 * shown counts < {@link MAX_TUTORIAL_SHOW_COUNT}, and detach TargetViewContainer from window
 * after exiting one handed mode.
 */
public class OneHandedTutorialHandler implements OneHandedTransitionCallback,
        OneHandedState.OnStateChangedListener {
    private static final String TAG = "OneHandedTutorialHandler";
    private static final String ONE_HANDED_MODE_OFFSET_PERCENTAGE =
            "persist.debug.one_handed_offset_percentage";
    private static final int MAX_TUTORIAL_SHOW_COUNT = 2;

    private final float mTutorialHeightRatio;
    private final WindowManager mWindowManager;
    private final OneHandedSettingsUtil mSettingsUtil;
    private final ShellExecutor mShellExecutor;

    private boolean mCanShow;
    private @OneHandedState.State int mCurrentState;
    private int mShownCounts;
    private int mTutorialAreaHeight;

    private Context mContext;
    private ContentResolver mContentResolver;
    private Rect mDisplayBounds;
    private @Nullable View mTutorialView;
    private @Nullable ViewGroup mTargetViewContainer;

    private final OneHandedAnimationCallback mAnimationCallback = new OneHandedAnimationCallback() {
        @Override
        public void onAnimationUpdate(float xPos, float yPos) {
            if (!canShowTutorial()) {
                return;
            }
            mTargetViewContainer.setTransitionGroup(true);
            mTargetViewContainer.setTranslationY(yPos - mTargetViewContainer.getHeight());
        }
    };

    public OneHandedTutorialHandler(Context context, DisplayLayout displayLayout,
            WindowManager windowManager, OneHandedSettingsUtil settingsUtil,
            ShellExecutor mainExecutor) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        mWindowManager = windowManager;
        mSettingsUtil = settingsUtil;
        mShellExecutor = mainExecutor;
        final float offsetPercentageConfig = context.getResources().getFraction(
                R.fraction.config_one_handed_offset, 1, 1);
        final int sysPropPercentageConfig = SystemProperties.getInt(
                ONE_HANDED_MODE_OFFSET_PERCENTAGE, Math.round(offsetPercentageConfig * 100.0f));
        mTutorialHeightRatio = sysPropPercentageConfig / 100.0f;
        mShownCounts = mSettingsUtil.getTutorialShownCounts(mContentResolver, myUserId());
    }

    @Override
    public void onStateChanged(int newState) {
        mCurrentState = newState;
        if (!canShowTutorial()) {
            return;
        }
        switch (newState) {
            case STATE_ENTERING:
                createViewAndAttachToWindow(mContext);
                break;
            case STATE_ACTIVE:
            case STATE_EXITING:
                // no - op
                break;
            case STATE_NONE:
                removeTutorialFromWindowManager(true /* increment */);
                break;
            default:
                break;
        }
    }

    /**
     * Called when onDisplayAdded() or onDisplayRemoved() callback.
     * @param displayLayout The latest {@link DisplayLayout} representing current displayId
     */
    public void onDisplayChanged(DisplayLayout displayLayout) {
        // Ensure the mDisplayBounds is portrait, due to OHM only support on portrait
        if (displayLayout.height() > displayLayout.width()) {
            mDisplayBounds = new Rect(0, 0, displayLayout.width(), displayLayout.height());
        } else {
            mDisplayBounds = new Rect(0, 0, displayLayout.height(), displayLayout.width());
        }
        mTutorialAreaHeight = Math.round(mDisplayBounds.height() * mTutorialHeightRatio);
    }

    @VisibleForTesting
    void createViewAndAttachToWindow(Context context) {
        if (!canShowTutorial()) {
            return;
        }
        mTutorialView = LayoutInflater.from(context).inflate(R.layout.one_handed_tutorial, null);
        mTargetViewContainer = new FrameLayout(context);
        mTargetViewContainer.setClipChildren(false);
        mTargetViewContainer.addView(mTutorialView);

        attachTargetToWindow();
    }

    @VisibleForTesting
    boolean setTutorialShownCountIncrement() {
        if (!canShowTutorial()) {
            return false;
        }
        mShownCounts += 1;
        return mSettingsUtil.setTutorialShownCounts(mContentResolver, mShownCounts, myUserId());
    }

    /**
     * Adds the tutorial target view to the WindowManager and update its layout.
     */
    private void attachTargetToWindow() {
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

    @VisibleForTesting
    void removeTutorialFromWindowManager(boolean increment) {
        if (mTargetViewContainer != null && mTargetViewContainer.isAttachedToWindow()) {
            mWindowManager.removeViewImmediate(mTargetViewContainer);
            if (increment) {
                setTutorialShownCountIncrement();
            }
        }
    }

    @Nullable OneHandedAnimationCallback getAnimationCallback() {
        return canShowTutorial() ? mAnimationCallback : null /* Disabled */;
    }

    /**
     * Returns layout params for the dismiss target, using the latest display metrics.
     */
    private WindowManager.LayoutParams getTutorialTargetLayoutParams() {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                mDisplayBounds.width(), mTutorialAreaHeight, 0, 0,
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

    @VisibleForTesting
    boolean canShowTutorial() {
        return mCanShow = mShownCounts < MAX_TUTORIAL_SHOW_COUNT;
    }

    /**
     * onConfigurationChanged events for updating tutorial text.
     */
    public void onConfigurationChanged() {
        if (!canShowTutorial()) {
            return;
        }
        removeTutorialFromWindowManager(false /* increment */);
        if (mCurrentState == STATE_ENTERING || mCurrentState == STATE_ACTIVE) {
            createViewAndAttachToWindow(mContext);
        }
    }

    void dump(@NonNull PrintWriter pw) {
        final String innerPrefix = "  ";
        pw.println(TAG);
        pw.print(innerPrefix + "mCanShow=");
        pw.println(mCanShow);
        pw.print(innerPrefix + "mCurrentState=");
        pw.println(mCurrentState);
        pw.print(innerPrefix + "mDisplayBounds=");
        pw.println(mDisplayBounds);
        pw.print(innerPrefix + "mShownCounts=");
        pw.println(mShownCounts);
        pw.print(innerPrefix + "mTutorialAreaHeight=");
        pw.println(mTutorialAreaHeight);
    }
}
