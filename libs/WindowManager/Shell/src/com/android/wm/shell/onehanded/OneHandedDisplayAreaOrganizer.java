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

import static com.android.wm.shell.onehanded.OneHandedAnimationController.TRANSITION_DIRECTION_EXIT;
import static com.android.wm.shell.onehanded.OneHandedAnimationController.TRANSITION_DIRECTION_TRIGGER;

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.view.SurfaceControl;
import android.window.DisplayAreaAppearedInfo;
import android.window.DisplayAreaInfo;
import android.window.DisplayAreaOrganizer;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.wm.shell.R;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.ShellExecutor;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages OneHanded display areas such as offset.
 *
 * This class listens on {@link DisplayAreaOrganizer} callbacks for windowing mode change
 * both to and from OneHanded and issues corresponding animation if applicable.
 * Normally, we apply series of {@link SurfaceControl.Transaction} when the animator is running
 * and files a final {@link WindowContainerTransaction} at the end of the transition.
 *
 * This class is also responsible for translating one handed operations within SysUI component
 */
public class OneHandedDisplayAreaOrganizer extends DisplayAreaOrganizer {
    private static final String TAG = "OneHandedDisplayAreaOrganizer";
    private static final String ONE_HANDED_MODE_TRANSLATE_ANIMATION_DURATION =
            "persist.debug.one_handed_translate_animation_duration";

    private DisplayLayout mDisplayLayout = new DisplayLayout();

    private final Rect mLastVisualDisplayBounds = new Rect();
    private final Rect mDefaultDisplayBounds = new Rect();

    private boolean mIsInOneHanded;
    private int mEnterExitAnimationDurationMs;

    private ArrayMap<WindowContainerToken, SurfaceControl> mDisplayAreaTokenMap = new ArrayMap();
    private OneHandedAnimationController mAnimationController;
    private OneHandedSurfaceTransactionHelper.SurfaceControlTransactionFactory
            mSurfaceControlTransactionFactory;
    private OneHandedTutorialHandler mTutorialHandler;
    private List<OneHandedTransitionCallback> mTransitionCallbacks = new ArrayList<>();
    private OneHandedBackgroundPanelOrganizer mBackgroundPanelOrganizer;

    @VisibleForTesting
    OneHandedAnimationCallback mOneHandedAnimationCallback =
            new OneHandedAnimationCallback() {
                @Override
                public void onOneHandedAnimationStart(
                        OneHandedAnimationController.OneHandedTransitionAnimator animator) {
                }

                @Override
                public void onOneHandedAnimationEnd(SurfaceControl.Transaction tx,
                        OneHandedAnimationController.OneHandedTransitionAnimator animator) {
                    mAnimationController.removeAnimator(animator.getToken());
                    if (mAnimationController.isAnimatorsConsumed()) {
                        resetWindowsOffsetInternal(animator.getTransitionDirection());
                        finishOffset(animator.getDestinationOffset(),
                                animator.getTransitionDirection());
                    }
                }

                @Override
                public void onOneHandedAnimationCancel(
                        OneHandedAnimationController.OneHandedTransitionAnimator animator) {
                    mAnimationController.removeAnimator(animator.getToken());
                    if (mAnimationController.isAnimatorsConsumed()) {
                        resetWindowsOffsetInternal(animator.getTransitionDirection());
                        finishOffset(animator.getDestinationOffset(),
                                animator.getTransitionDirection());
                    }
                }
            };

    /**
     * Constructor of OneHandedDisplayAreaOrganizer
     */
    public OneHandedDisplayAreaOrganizer(Context context,
            DisplayLayout displayLayout,
            OneHandedAnimationController animationController,
            OneHandedTutorialHandler tutorialHandler,
            OneHandedBackgroundPanelOrganizer oneHandedBackgroundGradientOrganizer,
            ShellExecutor mainExecutor) {
        super(mainExecutor);
        mDisplayLayout.set(displayLayout);
        updateDisplayBounds();
        mAnimationController = animationController;
        final int animationDurationConfig = context.getResources().getInteger(
                R.integer.config_one_handed_translate_animation_duration);
        mEnterExitAnimationDurationMs =
                SystemProperties.getInt(ONE_HANDED_MODE_TRANSLATE_ANIMATION_DURATION,
                        animationDurationConfig);
        mSurfaceControlTransactionFactory = SurfaceControl.Transaction::new;
        mTutorialHandler = tutorialHandler;
        mBackgroundPanelOrganizer = oneHandedBackgroundGradientOrganizer;
    }

    @Override
    public void onDisplayAreaAppeared(@NonNull DisplayAreaInfo displayAreaInfo,
            @NonNull SurfaceControl leash) {
        mDisplayAreaTokenMap.put(displayAreaInfo.token, leash);
    }

    @Override
    public void onDisplayAreaVanished(@NonNull DisplayAreaInfo displayAreaInfo) {
        mDisplayAreaTokenMap.remove(displayAreaInfo.token);
    }

    @Override
    public List<DisplayAreaAppearedInfo> registerOrganizer(int displayAreaFeature) {
        final List<DisplayAreaAppearedInfo> displayAreaInfos =
                super.registerOrganizer(displayAreaFeature);
        for (int i = 0; i < displayAreaInfos.size(); i++) {
            final DisplayAreaAppearedInfo info = displayAreaInfos.get(i);
            onDisplayAreaAppeared(info.getDisplayAreaInfo(), info.getLeash());
        }
        updateDisplayBounds();
        return displayAreaInfos;
    }

    @Override
    public void unregisterOrganizer() {
        super.unregisterOrganizer();
        resetWindowsOffset(null);
    }

    /**
     * Handler for display rotation changes by {@link DisplayLayout}
     *
     * @param context       Any context
     * @param toRotation    target rotation of the display (after rotating).
     * @param wct           A task transaction {@link WindowContainerTransaction} from
     *                      {@link DisplayChangeController} to populate.
     */
    public void onRotateDisplay(Context context, int toRotation, WindowContainerTransaction wct) {
        if (mDisplayLayout.rotation() == toRotation) {
            return;
        }
        mDisplayLayout.rotateTo(context.getResources(), toRotation);
        resetWindowsOffset(wct);
        updateDisplayBounds();
        finishOffset(0, TRANSITION_DIRECTION_EXIT);
    }

    /**
     * Offset the windows by a given offset on Y-axis, triggered also from screen rotation.
     * Directly perform manipulation/offset on the leash.
     */
    public void scheduleOffset(int xOffset, int yOffset) {
        final Rect toBounds = new Rect(mDefaultDisplayBounds.left,
                mDefaultDisplayBounds.top + yOffset,
                mDefaultDisplayBounds.right,
                mDefaultDisplayBounds.bottom + yOffset);
        final Rect fromBounds = getLastVisualDisplayBounds();
        final int direction = yOffset > 0
                ? TRANSITION_DIRECTION_TRIGGER
                : TRANSITION_DIRECTION_EXIT;

        final WindowContainerTransaction wct = new WindowContainerTransaction();
        mDisplayAreaTokenMap.forEach(
                (token, leash) -> {
                    animateWindows(token, leash, fromBounds, toBounds, direction,
                            mEnterExitAnimationDurationMs);
                    wct.setBounds(token, toBounds);
                    wct.setAppBounds(token, toBounds);
                });
        applyTransaction(wct);
    }

    private void resetWindowsOffsetInternal(
            @OneHandedAnimationController.TransitionDirection int td) {
        if (td == TRANSITION_DIRECTION_TRIGGER) {
            return;
        }
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        resetWindowsOffset(wct);
        applyTransaction(wct);
    }

    @VisibleForTesting
    void resetWindowsOffset(WindowContainerTransaction wct) {
        final SurfaceControl.Transaction tx =
                mSurfaceControlTransactionFactory.getTransaction();
        mDisplayAreaTokenMap.forEach(
                (token, leash) -> {
                    final OneHandedAnimationController.OneHandedTransitionAnimator animator =
                            mAnimationController.getAnimatorMap().remove(token);
                    if (animator != null && animator.isRunning()) {
                        animator.cancel();
                    }
                    tx.setPosition(leash, 0, 0)
                            .setWindowCrop(leash, -1/* reset */, -1/* reset */);
                    // DisplayRotationController will applyTransaction() after finish rotating
                    if (wct != null) {
                        wct.setBounds(token, null/* reset */);
                        wct.setAppBounds(token, null/* reset */);
                    }
                });
        tx.apply();
    }

    private void animateWindows(WindowContainerToken token, SurfaceControl leash, Rect fromBounds,
            Rect toBounds, @OneHandedAnimationController.TransitionDirection int direction,
            int durationMs) {
        final OneHandedAnimationController.OneHandedTransitionAnimator animator =
                mAnimationController.getAnimator(token, leash, fromBounds, toBounds);
        if (animator != null) {
            animator.setTransitionDirection(direction)
                    .addOneHandedAnimationCallback(mOneHandedAnimationCallback)
                    .addOneHandedAnimationCallback(mTutorialHandler.getAnimationCallback())
                    .addOneHandedAnimationCallback(
                            mBackgroundPanelOrganizer.getOneHandedAnimationCallback())
                    .setDuration(durationMs)
                    .start();
        }
    }

    @VisibleForTesting
    void finishOffset(int offset,
            @OneHandedAnimationController.TransitionDirection int direction) {
        // Only finishOffset() can update mIsInOneHanded to ensure the state is handle in sequence,
        // the flag *MUST* be updated before dispatch mTransitionCallbacks
        mIsInOneHanded = (offset > 0 || direction == TRANSITION_DIRECTION_TRIGGER);
        mLastVisualDisplayBounds.offsetTo(0,
                direction == TRANSITION_DIRECTION_TRIGGER ? offset : 0);
        for (int i = mTransitionCallbacks.size() - 1; i >= 0; i--) {
            final OneHandedTransitionCallback callback = mTransitionCallbacks.get(i);
            if (direction == TRANSITION_DIRECTION_TRIGGER) {
                callback.onStartFinished(getLastVisualDisplayBounds());
            } else {
                callback.onStopFinished(getLastVisualDisplayBounds());
            }
        }
    }

    /**
     * The latest state of one handed mode
     *
     * @return true Currently is in one handed mode, otherwise is not in one handed mode
     */
    public boolean isInOneHanded() {
        return mIsInOneHanded;
    }

    /**
     * The latest visual bounds of displayArea translated
     *
     * @return Rect latest finish_offset
     */
    public Rect getLastVisualDisplayBounds() {
        return mLastVisualDisplayBounds;
    }

    @VisibleForTesting
    @Nullable
    Rect getLastDisplayBounds() {
        return mLastVisualDisplayBounds;
    }

    public DisplayLayout getDisplayLayout() {
        return mDisplayLayout;
    }

    @VisibleForTesting
    void setDisplayLayout(@NonNull DisplayLayout displayLayout) {
        mDisplayLayout.set(displayLayout);
    }

    @VisibleForTesting
    ArrayMap<WindowContainerToken, SurfaceControl> getDisplayAreaTokenMap() {
        return mDisplayAreaTokenMap;
    }

    void updateDisplayBounds() {
        mDefaultDisplayBounds.set(0, 0, mDisplayLayout.width(), mDisplayLayout.height());
        mLastVisualDisplayBounds.set(mDefaultDisplayBounds);
    }

    /**
     * Register transition callback
     */
    public void registerTransitionCallback(OneHandedTransitionCallback callback) {
        mTransitionCallbacks.add(callback);
    }

    void dump(@NonNull PrintWriter pw) {
        final String innerPrefix = "  ";
        pw.println(TAG + "states: ");
        pw.print(innerPrefix + "mIsInOneHanded=");
        pw.println(mIsInOneHanded);
        pw.print(innerPrefix + "mDisplayLayout.rotation()=");
        pw.println(mDisplayLayout.rotation());
        pw.print(innerPrefix + "mDisplayAreaTokenMap=");
        pw.println(mDisplayAreaTokenMap);
        pw.print(innerPrefix + "mDefaultDisplayBounds=");
        pw.println(mDefaultDisplayBounds);
        pw.print(innerPrefix + "mLastVisualDisplayBounds=");
        pw.println(mLastVisualDisplayBounds);
    }
}
