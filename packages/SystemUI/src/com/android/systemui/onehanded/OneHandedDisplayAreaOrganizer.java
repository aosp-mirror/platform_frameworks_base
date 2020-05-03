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

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.systemui.onehanded.OneHandedAnimationController.TRANSITION_DIRECTION_EXIT;
import static com.android.systemui.onehanded.OneHandedAnimationController.TRANSITION_DIRECTION_TRIGGER;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceControl;
import android.window.DisplayAreaInfo;
import android.window.DisplayAreaOrganizer;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.os.SomeArgs;
import com.android.systemui.Dumpable;
import com.android.systemui.wm.DisplayController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.inject.Inject;

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
public class OneHandedDisplayAreaOrganizer extends DisplayAreaOrganizer implements Dumpable {
    private static final String TAG = "OneHandedDisplayAreaOrganizer";

    @VisibleForTesting
    static final int MSG_OFFSET_IMMEDIATE = 1;
    @VisibleForTesting
    static final int MSG_OFFSET_ANIMATE = 2;
    @VisibleForTesting
    static final int MSG_OFFSET_FINISH = 3;
    @VisibleForTesting
    static final int MSG_RESIZE_IMMEDIATE = 4;

    private final Handler mUpdateHandler;

    private boolean mIsInOneHanded;
    private int mEnterExitAnimationDurationMs;

    @VisibleForTesting
    DisplayAreaInfo mDisplayAreaInfo;
    private DisplayController mDisplayController;
    private OneHandedAnimationController mAnimationController;
    private SurfaceControl mLeash;
    private List<OneHandedTransitionCallback> mTransitionCallbacks = new ArrayList<>();
    private OneHandedSurfaceTransactionHelper mSurfaceTransactionHelper;
    private OneHandedSurfaceTransactionHelper.SurfaceControlTransactionFactory
            mSurfaceControlTransactionFactory;
    private Rect mLastVisualDisplayBounds;
    private Rect mDefaultDisplayBounds;

    @VisibleForTesting
    OneHandedAnimationCallback mOneHandedAnimationCallback =
            new OneHandedAnimationCallback() {
                @Override
                public void onOneHandedAnimationStart(
                        OneHandedAnimationController.OneHandedTransitionAnimator animator) {
                }

                @Override
                public void onOneHandedAnimationEnd(
                        OneHandedAnimationController.OneHandedTransitionAnimator animator) {
                    mUpdateHandler.sendMessage(mUpdateHandler.obtainMessage(MSG_OFFSET_FINISH,
                            obtainArgsFromAnimator(animator)));
                }

                @Override
                public void onOneHandedAnimationCancel(
                        OneHandedAnimationController.OneHandedTransitionAnimator animator) {
                    mUpdateHandler.sendMessage(mUpdateHandler.obtainMessage(MSG_OFFSET_FINISH,
                            obtainArgsFromAnimator(animator)));
                }
            };

    @SuppressWarnings("unchecked")
    private Handler.Callback mUpdateCallback = (msg) -> {
        SomeArgs args = (SomeArgs) msg.obj;
        final Rect currentBounds = args.arg1 != null ? (Rect) args.arg1 : mDefaultDisplayBounds;
        final int xOffset = args.argi1;
        final int yOffset = args.argi2;
        final int direction = args.argi3;

        switch (msg.what) {
            case MSG_RESIZE_IMMEDIATE:
            case MSG_OFFSET_IMMEDIATE:
                final OneHandedAnimationController.OneHandedTransitionAnimator animator =
                        mAnimationController.getAnimator(mLeash, currentBounds,
                                mDefaultDisplayBounds);
                if (animator != null && animator.isRunning()) {
                    animator.cancel();
                }
                final SurfaceControl.Transaction tx =
                        mSurfaceControlTransactionFactory.getTransaction();
                mSurfaceTransactionHelper.translate(tx, mLeash, yOffset)
                        .crop(tx, mLeash, currentBounds);
                tx.apply();
                finishOffset(currentBounds, yOffset, direction);
                break;
            case MSG_OFFSET_ANIMATE:
                offsetWindows(currentBounds, 0, yOffset, direction, mEnterExitAnimationDurationMs);
                break;
            case MSG_OFFSET_FINISH:
                finishOffset(currentBounds, yOffset, direction);
                break;
        }
        args.recycle();
        return true;
    };

    /**
     * Constructor of OneHandedDisplayAreaOrganizer
     */
    @Inject
    public OneHandedDisplayAreaOrganizer(Context context,
            DisplayController displayController,
            OneHandedAnimationController animationController,
            OneHandedSurfaceTransactionHelper surfaceTransactionHelper) {

        mUpdateHandler = new Handler(OneHandedThread.get().getLooper(), mUpdateCallback);
        mAnimationController = animationController;
        mDisplayController = displayController;
        mEnterExitAnimationDurationMs = context.getResources().getInteger(
                com.android.systemui.R.integer.config_one_handed_translate_animation_duration);
        mDefaultDisplayBounds = new Rect(0, 0, getDisplayBounds().x, getDisplayBounds().y);
        mLastVisualDisplayBounds = new Rect(mDefaultDisplayBounds);
        mSurfaceTransactionHelper = surfaceTransactionHelper;
        mSurfaceControlTransactionFactory = SurfaceControl.Transaction::new;
    }

    @Override
    public void onDisplayAreaAppeared(@NonNull DisplayAreaInfo displayAreaInfo,
            @NonNull SurfaceControl leash) {
        Objects.requireNonNull(displayAreaInfo, "displayAreaInfo must not be null");
        Objects.requireNonNull(leash, "leash must not be null");
        mDisplayAreaInfo = displayAreaInfo;
        mLeash = leash;
    }

    @Override
    public void onDisplayAreaInfoChanged(@NonNull DisplayAreaInfo displayAreaInfo) {
        Objects.requireNonNull(displayAreaInfo, "displayArea must not be null");
        mDisplayAreaInfo = displayAreaInfo;
    }

    @Override
    public void onDisplayAreaVanished(@NonNull DisplayAreaInfo displayAreaInfo) {
        Objects.requireNonNull(displayAreaInfo,
                "Requires valid displayArea, and displayArea must not be null");
        if (displayAreaInfo.token.asBinder() != mDisplayAreaInfo.token.asBinder()) {
            Log.w(TAG, "Unrecognized token: " + displayAreaInfo.token);
            return;
        }
        mDisplayAreaInfo = displayAreaInfo;

        // Stop one handed immediately
        if (isInOneHanded()) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = getLastVisualDisplayBounds();
            args.argi1 = 0 /* xOffset */;
            args.argi2 = 0 /* yOffset */;
            args.argi3 = TRANSITION_DIRECTION_EXIT;
            mUpdateHandler.sendMessage(mUpdateHandler.obtainMessage(MSG_OFFSET_IMMEDIATE, args));
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

    /**
     * Offset DisplayArea immediately calling from OneHandedManager when screen going to rotate
     */
    public void offsetImmediately(int xOffset, int yOffset) {
        if (mDisplayAreaInfo != null && mLeash != null) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = getLastVisualDisplayBounds();
            args.argi1 = xOffset;
            args.argi2 = yOffset;
            args.argi3 = yOffset > 0 ? TRANSITION_DIRECTION_TRIGGER : TRANSITION_DIRECTION_EXIT;
            mUpdateHandler.sendMessage(mUpdateHandler.obtainMessage(MSG_OFFSET_ANIMATE, args));
        }
    }

    /**
     * Resize DisplayArea immediately calling from OneHandedManager when screen going to rotate
     */
    public void resizeImmediately(Rect newDisplayBounds) {
        updateDisplayBounds(newDisplayBounds);
        if (mDisplayAreaInfo != null && mLeash != null) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = newDisplayBounds;
            args.argi1 = 0 /* xOffset */;
            args.argi2 = 0 /* yOffset */;
            args.argi3 = TRANSITION_DIRECTION_EXIT;
            mUpdateHandler.sendMessage(mUpdateHandler.obtainMessage(MSG_RESIZE_IMMEDIATE, args));
        }
    }

    private void offsetWindows(Rect currentbounds, int xOffset, int yOffset, int direction,
            int durationMs) {
        if (Looper.myLooper() != mUpdateHandler.getLooper()) {
            throw new RuntimeException("Callers should call scheduleOffset() instead of this "
                    + "directly");
        }
        if (mDisplayAreaInfo == null || mLeash == null) {
            Log.w(TAG, "mToken is not set");
            return;
        }

        Rect toBounds = new Rect(mDefaultDisplayBounds.left,
                mDefaultDisplayBounds.top + yOffset,
                mDefaultDisplayBounds.right,
                mDefaultDisplayBounds.bottom + yOffset);
        animateWindows(currentbounds, toBounds, direction, durationMs);
    }

    private void animateWindows(Rect fromBounds, Rect toBounds,
            @OneHandedAnimationController.TransitionDirection int direction, int durationMs) {
        if (Looper.myLooper() != mUpdateHandler.getLooper()) {
            throw new RuntimeException("Callers should call scheduleOffset() instead of "
                    + "this directly");
        }
        // Could happen when exit one handed
        if (mDisplayAreaInfo == null || mLeash == null) {
            Log.w(TAG, "Abort animation, invalid leash");
            return;
        }

        mUpdateHandler.post(() -> {
            final OneHandedAnimationController.OneHandedTransitionAnimator animator =
                    mAnimationController.getAnimator(mLeash, fromBounds, toBounds);
            if (animator != null) {
                animator.setTransitionDirection(direction)
                        .setOneHandedAnimationCallback(mOneHandedAnimationCallback)
                        .setDuration(durationMs)
                        .start();
            }
        });
    }

    private void finishOffset(Rect currentBounds, int offset,
            @OneHandedAnimationController.TransitionDirection int direction) {
        if (Looper.myLooper() != mUpdateHandler.getLooper()) {
            throw new RuntimeException(
                    "Callers should call scheduleOffset() instead of this directly.");
        }
        // Only finishOffset() can update mIsInOneHanded to ensure the state is handle in sequence,
        // the flag *MUST* be updated before dispatch mTransitionCallbacks
        mIsInOneHanded = (offset > 0 || direction == TRANSITION_DIRECTION_TRIGGER);
        mLastVisualDisplayBounds.set(currentBounds);
        if (direction == TRANSITION_DIRECTION_TRIGGER) {
            mLastVisualDisplayBounds.offsetTo(0, offset);
            for (int i = mTransitionCallbacks.size() - 1; i >= 0; i--) {
                final OneHandedTransitionCallback callback = mTransitionCallbacks.get(i);
                callback.onStartFinished(mLastVisualDisplayBounds);
            }
        } else {
            mLastVisualDisplayBounds.offsetTo(0, 0);
            for (int i = mTransitionCallbacks.size() - 1; i >= 0; i--) {
                final OneHandedTransitionCallback callback = mTransitionCallbacks.get(i);
                callback.onStopFinished(mLastVisualDisplayBounds);
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
    private Point getDisplayBounds() {
        Point realSize = new Point(0, 0);
        if (mDisplayController != null && mDisplayController.getDisplay(DEFAULT_DISPLAY) != null) {
            mDisplayController.getDisplay(DEFAULT_DISPLAY).getRealSize(realSize);
        }
        return realSize;
    }

    @VisibleForTesting
    Handler getUpdateHandler() {
        return mUpdateHandler;
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

    private void updateDisplayBounds(Rect newDisplayBounds) {
        if (newDisplayBounds == null) {
            mDefaultDisplayBounds.set(0, 0, getDisplayBounds().x, getDisplayBounds().y);
        } else {
            mDefaultDisplayBounds.set(newDisplayBounds);
        }
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        final String innerPrefix = "  ";
        pw.println(TAG + "states: ");
        pw.print(innerPrefix + "mIsInOneHanded=");
        pw.println(mIsInOneHanded);
        pw.print(innerPrefix + "mDisplayAreaInfo=");
        pw.println(mDisplayAreaInfo);
        pw.print(innerPrefix + "mLeash=");
        pw.println(mLeash);
        pw.print(innerPrefix + "mDefaultDisplayBounds=");
        pw.println(mDefaultDisplayBounds);
        pw.print(innerPrefix + "mLastVisualDisplayBounds=");
        pw.println(mLastVisualDisplayBounds);
        pw.print(innerPrefix + "getDisplayBounds()=");
        pw.println(getDisplayBounds());
    }
}
