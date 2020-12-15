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

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.wm.shell.onehanded.OneHandedAnimationController.TRANSITION_DIRECTION_EXIT;
import static com.android.wm.shell.onehanded.OneHandedAnimationController.TRANSITION_DIRECTION_TRIGGER;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.Log;
import android.view.SurfaceControl;
import android.window.DisplayAreaAppearedInfo;
import android.window.DisplayAreaInfo;
import android.window.DisplayAreaOrganizer;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.os.SomeArgs;
import com.android.wm.shell.R;
import com.android.wm.shell.common.DisplayController;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

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

    @VisibleForTesting
    static final int MSG_RESET_IMMEDIATE = 1;
    @VisibleForTesting
    static final int MSG_OFFSET_ANIMATE = 2;
    @VisibleForTesting
    static final int MSG_OFFSET_FINISH = 3;

    private final Rect mLastVisualDisplayBounds = new Rect();
    private final Rect mDefaultDisplayBounds = new Rect();

    private Handler mUpdateHandler;
    private boolean mIsInOneHanded;
    private int mEnterExitAnimationDurationMs;

    @VisibleForTesting
    ArrayMap<DisplayAreaInfo, SurfaceControl> mDisplayAreaMap = new ArrayMap();
    private DisplayController mDisplayController;
    private OneHandedAnimationController mAnimationController;
    private OneHandedSurfaceTransactionHelper.SurfaceControlTransactionFactory
            mSurfaceControlTransactionFactory;
    private OneHandedTutorialHandler mTutorialHandler;
    private List<OneHandedTransitionCallback> mTransitionCallbacks = new ArrayList<>();

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
                    mAnimationController.removeAnimator(animator.getLeash());
                    if (mAnimationController.isAnimatorsConsumed()) {
                        mUpdateHandler.sendMessage(mUpdateHandler.obtainMessage(MSG_OFFSET_FINISH,
                                obtainArgsFromAnimator(animator)));
                    }
                }

                @Override
                public void onOneHandedAnimationCancel(
                        OneHandedAnimationController.OneHandedTransitionAnimator animator) {
                    mAnimationController.removeAnimator(animator.getLeash());
                    if (mAnimationController.isAnimatorsConsumed()) {
                        mUpdateHandler.sendMessage(mUpdateHandler.obtainMessage(MSG_OFFSET_FINISH,
                                obtainArgsFromAnimator(animator)));
                    }
                }
            };

    @SuppressWarnings("unchecked")
    private Handler.Callback mUpdateCallback = (msg) -> {
        SomeArgs args = (SomeArgs) msg.obj;
        final Rect currentBounds = args.arg1 != null ? (Rect) args.arg1 : mDefaultDisplayBounds;
        final WindowContainerTransaction wctFromRotate = (WindowContainerTransaction) args.arg2;
        final int yOffset = args.argi2;
        final int direction = args.argi3;

        switch (msg.what) {
            case MSG_RESET_IMMEDIATE:
                resetWindowsOffset(wctFromRotate);
                mDefaultDisplayBounds.set(currentBounds);
                mLastVisualDisplayBounds.set(currentBounds);
                finishOffset(0, TRANSITION_DIRECTION_EXIT);
                break;
            case MSG_OFFSET_ANIMATE:
                final Rect toBounds = new Rect(mDefaultDisplayBounds.left,
                        mDefaultDisplayBounds.top + yOffset,
                        mDefaultDisplayBounds.right,
                        mDefaultDisplayBounds.bottom + yOffset);
                offsetWindows(currentBounds, toBounds, direction, mEnterExitAnimationDurationMs);
                break;
            case MSG_OFFSET_FINISH:
                finishOffset(yOffset, direction);
                break;
        }
        args.recycle();
        return true;
    };

    /**
     * Constructor of OneHandedDisplayAreaOrganizer
     */
    public OneHandedDisplayAreaOrganizer(Context context,
            DisplayController displayController,
            OneHandedAnimationController animationController,
            OneHandedTutorialHandler tutorialHandler, Executor executor) {
        super(executor);
        mUpdateHandler = new Handler(OneHandedThread.get().getLooper(), mUpdateCallback);
        mAnimationController = animationController;
        mDisplayController = displayController;
        mDefaultDisplayBounds.set(getDisplayBounds());
        mLastVisualDisplayBounds.set(getDisplayBounds());
        final int animationDurationConfig = context.getResources().getInteger(
                R.integer.config_one_handed_translate_animation_duration);
        mEnterExitAnimationDurationMs =
                SystemProperties.getInt(ONE_HANDED_MODE_TRANSLATE_ANIMATION_DURATION,
                        animationDurationConfig);
        mSurfaceControlTransactionFactory = SurfaceControl.Transaction::new;
        mTutorialHandler = tutorialHandler;
    }

    @Override
    public void onDisplayAreaAppeared(@NonNull DisplayAreaInfo displayAreaInfo,
            @NonNull SurfaceControl leash) {
        Objects.requireNonNull(displayAreaInfo, "displayAreaInfo must not be null");
        Objects.requireNonNull(leash, "leash must not be null");
        synchronized (this) {
            if (mDisplayAreaMap.get(displayAreaInfo) == null) {
                // mDefaultDisplayBounds may out of date after removeDisplayChangingController()
                mDefaultDisplayBounds.set(getDisplayBounds());
                mDisplayAreaMap.put(displayAreaInfo, leash);
            }
        }
    }

    @Override
    public void onDisplayAreaVanished(@NonNull DisplayAreaInfo displayAreaInfo) {
        Objects.requireNonNull(displayAreaInfo,
                "Requires valid displayArea, and displayArea must not be null");
        synchronized (this) {
            if (!mDisplayAreaMap.containsKey(displayAreaInfo)) {
                Log.w(TAG, "Unrecognized token: " + displayAreaInfo.token);
                return;
            }
            mDisplayAreaMap.remove(displayAreaInfo);
        }
    }

    @Override
    public List<DisplayAreaAppearedInfo> registerOrganizer(int displayAreaFeature) {
        final List<DisplayAreaAppearedInfo> displayAreaInfos =
                super.registerOrganizer(displayAreaFeature);
        for (int i = 0; i < displayAreaInfos.size(); i++) {
            final DisplayAreaAppearedInfo info = displayAreaInfos.get(i);
            onDisplayAreaAppeared(info.getDisplayAreaInfo(), info.getLeash());
        }
        return displayAreaInfos;
    }

    @Override
    public void unregisterOrganizer() {
        super.unregisterOrganizer();
        mUpdateHandler.post(() -> resetWindowsOffset(null));
    }

    /**
     * Handler for display rotation changes by below policy which
     * handles 90 degree display rotation changes {@link Surface.Rotation}.
     *
     * @param fromRotation starting rotation of the display.
     * @param toRotation target rotation of the display (after rotating).
     * @param wct A task transaction {@link WindowContainerTransaction} from
     *        {@link DisplayChangeController} to populate.
     */
    public void onRotateDisplay(int fromRotation, int toRotation, WindowContainerTransaction wct) {
        // Stop one handed without animation and reset cropped size immediately
        final Rect newBounds = new Rect(mDefaultDisplayBounds);
        final boolean isOrientationDiff = Math.abs(fromRotation - toRotation) % 2 == 1;

        if (isOrientationDiff) {
            newBounds.set(newBounds.left, newBounds.top, newBounds.bottom, newBounds.right);
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = newBounds;
            args.arg2 = wct;
            args.argi1 = 0 /* xOffset */;
            args.argi2 = 0 /* yOffset */;
            args.argi3 = TRANSITION_DIRECTION_EXIT;
            mUpdateHandler.sendMessage(mUpdateHandler.obtainMessage(MSG_RESET_IMMEDIATE, args));
        }
    }

    /**
     * Offset the windows by a given offset on Y-axis, triggered also from screen rotation.
     * Directly perform manipulation/offset on the leash.
     */
    public void scheduleOffset(int xOffset, int yOffset) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = getLastVisualDisplayBounds();
        args.argi1 = xOffset;
        args.argi2 = yOffset;
        args.argi3 = yOffset > 0 ? TRANSITION_DIRECTION_TRIGGER : TRANSITION_DIRECTION_EXIT;
        mUpdateHandler.sendMessage(mUpdateHandler.obtainMessage(MSG_OFFSET_ANIMATE, args));
    }

    private void offsetWindows(Rect fromBounds, Rect toBounds, int direction, int durationMs) {
        if (Looper.myLooper() != mUpdateHandler.getLooper()) {
            throw new RuntimeException("Callers should call scheduleOffset() instead of this "
                    + "directly");
        }
        synchronized (this) {
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            mDisplayAreaMap.forEach(
                    (key, leash) -> {
                        animateWindows(leash, fromBounds, toBounds, direction, durationMs);
                        wct.setBounds(key.token, toBounds);
                    });
            applyTransaction(wct);
        }
    }

    private void resetWindowsOffset(WindowContainerTransaction wct) {
        synchronized (this) {
            final SurfaceControl.Transaction tx =
                    mSurfaceControlTransactionFactory.getTransaction();
            mDisplayAreaMap.forEach(
                    (key, leash) -> {
                        final OneHandedAnimationController.OneHandedTransitionAnimator animator =
                                mAnimationController.getAnimatorMap().remove(leash);
                        if (animator != null && animator.isRunning()) {
                            animator.cancel();
                        }
                        tx.setPosition(leash, 0, 0)
                                .setWindowCrop(leash, -1/* reset */, -1/* reset */);
                        // DisplayRotationController will applyTransaction() after finish rotating
                        if (wct != null) {
                            wct.setBounds(key.token, null/* reset */);
                        }
                    });
            tx.apply();
        }
    }

    private void animateWindows(SurfaceControl leash, Rect fromBounds, Rect toBounds,
            @OneHandedAnimationController.TransitionDirection int direction, int durationMs) {
        if (Looper.myLooper() != mUpdateHandler.getLooper()) {
            throw new RuntimeException("Callers should call scheduleOffset() instead of "
                    + "this directly");
        }
        mUpdateHandler.post(() -> {
            final OneHandedAnimationController.OneHandedTransitionAnimator animator =
                    mAnimationController.getAnimator(leash, fromBounds, toBounds);
            if (animator != null) {
                animator.setTransitionDirection(direction)
                        .setOneHandedAnimationCallbacks(mOneHandedAnimationCallback)
                        .setOneHandedAnimationCallbacks(mTutorialHandler.getAnimationCallback())
                        .setDuration(durationMs)
                        .start();
            }
        });
    }

    private void finishOffset(int offset,
            @OneHandedAnimationController.TransitionDirection int direction) {
        if (Looper.myLooper() != mUpdateHandler.getLooper()) {
            throw new RuntimeException(
                    "Callers should call scheduleOffset() instead of this directly.");
        }
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

    @Nullable
    private Rect getDisplayBounds() {
        Point realSize = new Point(0, 0);
        if (mDisplayController != null && mDisplayController.getDisplay(DEFAULT_DISPLAY) != null) {
            mDisplayController.getDisplay(DEFAULT_DISPLAY).getRealSize(realSize);
        }
        return new Rect(0, 0, realSize.x, realSize.y);
    }

    @VisibleForTesting
    void setUpdateHandler(Handler updateHandler) {
        mUpdateHandler = updateHandler;
    }

    /**
     * Register transition callback
     */
    public void registerTransitionCallback(OneHandedTransitionCallback callback) {
        mTransitionCallbacks.add(callback);
    }

    private SomeArgs obtainArgsFromAnimator(
            OneHandedAnimationController.OneHandedTransitionAnimator animator) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = animator.getDestinationBounds();
        args.argi1 = 0 /* xOffset */;
        args.argi2 = animator.getDestinationOffset();
        args.argi3 = animator.getTransitionDirection();
        return args;
    }

    void dump(@NonNull PrintWriter pw) {
        final String innerPrefix = "  ";
        pw.println(TAG + "states: ");
        pw.print(innerPrefix + "mIsInOneHanded=");
        pw.println(mIsInOneHanded);
        pw.print(innerPrefix + "mDisplayAreaMap=");
        pw.println(mDisplayAreaMap);
        pw.print(innerPrefix + "mDefaultDisplayBounds=");
        pw.println(mDefaultDisplayBounds);
        pw.print(innerPrefix + "mLastVisualDisplayBounds=");
        pw.println(mLastVisualDisplayBounds);
        pw.print(innerPrefix + "getDisplayBounds()=");
        pw.println(getDisplayBounds());
    }
}
