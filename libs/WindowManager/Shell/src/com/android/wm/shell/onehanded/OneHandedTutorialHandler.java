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

import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import static com.android.wm.shell.onehanded.OneHandedState.STATE_ACTIVE;
import static com.android.wm.shell.onehanded.OneHandedState.STATE_ENTERING;
import static com.android.wm.shell.onehanded.OneHandedState.STATE_EXITING;
import static com.android.wm.shell.onehanded.OneHandedState.STATE_NONE;

import android.annotation.Nullable;
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

    private final float mTutorialHeightRatio;
    private final WindowManager mWindowManager;

    private boolean mIsShowing;
    private @OneHandedState.State int mCurrentState;
    private int mTutorialAreaHeight;

    private Context mContext;
    private Rect mDisplayBounds;
    private @Nullable View mTutorialView;
    private @Nullable ViewGroup mTargetViewContainer;

    private final OneHandedAnimationCallback mAnimationCallback;

    public OneHandedTutorialHandler(Context context, WindowManager windowManager) {
        mContext = context;
        mWindowManager = windowManager;
        final float offsetPercentageConfig = context.getResources().getFraction(
                R.fraction.config_one_handed_offset, 1, 1);
        final int sysPropPercentageConfig = SystemProperties.getInt(
                ONE_HANDED_MODE_OFFSET_PERCENTAGE, Math.round(offsetPercentageConfig * 100.0f));
        mTutorialHeightRatio = sysPropPercentageConfig / 100.0f;
        mAnimationCallback = new OneHandedAnimationCallback() {
            @Override
            public void onAnimationUpdate(float xPos, float yPos) {
                if (!isShowing()) {
                    return;
                }
                mTargetViewContainer.setTransitionGroup(true);
                mTargetViewContainer.setTranslationY(yPos - mTargetViewContainer.getHeight());
            }
        };
    }

    @Override
    public void onStateChanged(int newState) {
        mCurrentState = newState;
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
        if (isShowing()) {
            return;
        }
        mTutorialView = LayoutInflater.from(context).inflate(R.layout.one_handed_tutorial, null);
        mTargetViewContainer = new FrameLayout(context);
        mTargetViewContainer.setClipChildren(false);
        mTargetViewContainer.addView(mTutorialView);

        attachTargetToWindow();
    }

    /**
     * Adds the tutorial target view to the WindowManager and update its layout.
     */
    private void attachTargetToWindow() {
        if (!mTargetViewContainer.isAttachedToWindow()) {
            try {
                mWindowManager.addView(mTargetViewContainer, getTutorialTargetLayoutParams());
                mIsShowing = true;
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
            mIsShowing = false;
        }
    }

    @Nullable OneHandedAnimationCallback getAnimationCallback() {
        return isShowing() ? mAnimationCallback : null /* Disabled */;
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
        lp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        lp.setFitInsetsTypes(0 /* types */);
        lp.setTitle("one-handed-tutorial-overlay");
        return lp;
    }

    @VisibleForTesting
    boolean isShowing() {
        return mIsShowing;
    }

    /**
     * onConfigurationChanged events for updating tutorial text.
     */
    public void onConfigurationChanged() {
        removeTutorialFromWindowManager(false /* increment */);
        if (mCurrentState == STATE_ENTERING || mCurrentState == STATE_ACTIVE) {
            createViewAndAttachToWindow(mContext);
        }
    }

    void dump(@NonNull PrintWriter pw) {
        final String innerPrefix = "  ";
        pw.println(TAG);
        pw.print(innerPrefix + "mIsShowing=");
        pw.println(mIsShowing);
        pw.print(innerPrefix + "mCurrentState=");
        pw.println(mCurrentState);
        pw.print(innerPrefix + "mDisplayBounds=");
        pw.println(mDisplayBounds);
        pw.print(innerPrefix + "mTutorialAreaHeight=");
        pw.println(mTutorialAreaHeight);
    }
}
