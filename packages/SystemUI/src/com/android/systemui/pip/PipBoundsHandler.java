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

package com.android.systemui.pip;

import static android.util.TypedValue.COMPLEX_UNIT_DIP;

import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.IPinnedStackController;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import com.android.internal.policy.PipSnapAlgorithm;

import java.io.PrintWriter;

/**
 * Handles bounds calculation for PIP on Phone and other form factors, it keeps tracking variant
 * state changes originated from Window Manager and is the source of truth for PiP window bounds.
 */
public class PipBoundsHandler {

    private static final String TAG = PipBoundsHandler.class.getSimpleName();
    private static final float INVALID_SNAP_FRACTION = -1f;

    private final Context mContext;
    private final IWindowManager mWindowManager;
    private final PipSnapAlgorithm mSnapAlgorithm;
    private final DisplayInfo mDisplayInfo = new DisplayInfo();
    private final Rect mStableInsets = new Rect();
    private final Rect mTmpInsets = new Rect();
    private final Point mTmpDisplaySize = new Point();

    /**
     * Tracks the destination bounds, used for any following
     * {@link #onMovementBoundsChanged(Rect, Rect, Rect, DisplayInfo)} calculations.
     */
    private final Rect mLastDestinationBounds = new Rect();

    private IPinnedStackController mPinnedStackController;
    private ComponentName mLastPipComponentName;
    private float mReentrySnapFraction = INVALID_SNAP_FRACTION;

    private float mDefaultAspectRatio;
    private float mMinAspectRatio;
    private float mMaxAspectRatio;
    private float mAspectRatio;
    private int mDefaultStackGravity;
    private int mDefaultMinSize;
    private Point mScreenEdgeInsets;
    private int mCurrentMinSize;

    private boolean mIsMinimized;
    private boolean mIsImeShowing;
    private int mImeHeight;
    private boolean mIsShelfShowing;
    private int mShelfHeight;

    public PipBoundsHandler(Context context) {
        mContext = context;
        mSnapAlgorithm = new PipSnapAlgorithm(context);
        mWindowManager = WindowManagerGlobal.getWindowManagerService();
        reloadResources();
        // Initialize the aspect ratio to the default aspect ratio.  Don't do this in reload
        // resources as it would clobber mAspectRatio when entering PiP from fullscreen which
        // triggers a configuration change and the resources to be reloaded.
        mAspectRatio = mDefaultAspectRatio;
    }

    /**
     * TODO: move the resources to SysUI package.
     */
    private void reloadResources() {
        final Resources res = mContext.getResources();
        mDefaultAspectRatio = res.getFloat(
                com.android.internal.R.dimen.config_pictureInPictureDefaultAspectRatio);
        mDefaultStackGravity = res.getInteger(
                com.android.internal.R.integer.config_defaultPictureInPictureGravity);
        mDefaultMinSize = res.getDimensionPixelSize(
                com.android.internal.R.dimen.default_minimal_size_pip_resizable_task);
        mCurrentMinSize = mDefaultMinSize;
        final String screenEdgeInsetsDpString = res.getString(
                com.android.internal.R.string.config_defaultPictureInPictureScreenEdgeInsets);
        final Size screenEdgeInsetsDp = !screenEdgeInsetsDpString.isEmpty()
                ? Size.parseSize(screenEdgeInsetsDpString)
                : null;
        mScreenEdgeInsets = screenEdgeInsetsDp == null ? new Point()
                : new Point(dpToPx(screenEdgeInsetsDp.getWidth(), res.getDisplayMetrics()),
                        dpToPx(screenEdgeInsetsDp.getHeight(), res.getDisplayMetrics()));
        mMinAspectRatio = res.getFloat(
                com.android.internal.R.dimen.config_pictureInPictureMinAspectRatio);
        mMaxAspectRatio = res.getFloat(
                com.android.internal.R.dimen.config_pictureInPictureMaxAspectRatio);
    }

    public void setPinnedStackController(IPinnedStackController controller) {
        mPinnedStackController = controller;
    }

    public void setMinEdgeSize(int minEdgeSize) {
        mCurrentMinSize = minEdgeSize;
    }

    /**
     * Sets both shelf visibility and its height if applicable.
     * @return {@code true} if the internal shelf state is changed, {@code false} otherwise.
     */
    public boolean setShelfHeight(boolean shelfVisible, int shelfHeight) {
        final boolean shelfShowing = shelfVisible && shelfHeight > 0;
        if (shelfShowing == mIsShelfShowing && shelfHeight == mShelfHeight) {
            return false;
        }

        mIsShelfShowing = shelfVisible;
        mShelfHeight = shelfHeight;
        return true;
    }

    /**
     * Responds to IPinnedStackListener on IME visibility change.
     */
    public void onImeVisibilityChanged(boolean imeVisible, int imeHeight) {
        mIsImeShowing = imeVisible;
        mImeHeight = imeHeight;
    }

    /**
     * Responds to IPinnedStackListener on minimized state change.
     */
    public void onMinimizedStateChanged(boolean minimized) {
        mIsMinimized = minimized;
    }

    /**
     * Responds to IPinnedStackListener on movement bounds change.
     * Note that both inset and normal bounds will be calculated here rather than in the caller.
     */
    public void onMovementBoundsChanged(Rect insetBounds, Rect normalBounds,
            Rect animatingBounds, DisplayInfo displayInfo) {
        getInsetBounds(insetBounds);
        final Rect defaultBounds = getDefaultBounds(INVALID_SNAP_FRACTION);
        normalBounds.set(defaultBounds);
        if (animatingBounds.isEmpty()) {
            animatingBounds.set(defaultBounds);
        }
        if (isValidPictureInPictureAspectRatio(mAspectRatio)) {
            transformBoundsToAspectRatio(normalBounds, mAspectRatio,
                    false /* useCurrentMinEdgeSize */);
        }
        displayInfo.copyFrom(mDisplayInfo);
    }

    /**
     * Responds to IPinnedStackListener on saving reentry snap fraction
     * for a given {@link ComponentName}.
     */
    public void onSaveReentrySnapFraction(ComponentName componentName, Rect bounds) {
        mReentrySnapFraction = getSnapFraction(bounds);
        mLastPipComponentName = componentName;
    }

    /**
     * Responds to IPinnedStackListener on resetting reentry snap fraction
     * for a given {@link ComponentName}.
     */
    public void onResetReentrySnapFraction(ComponentName componentName) {
        if (componentName.equals(mLastPipComponentName)) {
            onResetReentrySnapFractionUnchecked();
        }
    }

    private void onResetReentrySnapFractionUnchecked() {
        mReentrySnapFraction = INVALID_SNAP_FRACTION;
        mLastPipComponentName = null;
    }

    public Rect getLastDestinationBounds() {
        return mLastDestinationBounds;
    }

    /**
     * Responds to IPinnedStackListener on {@link DisplayInfo} change.
     * It will normally follow up with a
     * {@link #onMovementBoundsChanged(Rect, Rect, Rect, DisplayInfo)} callback.
     */
    public void onDisplayInfoChanged(DisplayInfo displayInfo) {
        mDisplayInfo.copyFrom(displayInfo);
    }

    /**
     * Responds to IPinnedStackListener on configuration change.
     */
    public void onConfigurationChanged() {
        reloadResources();
    }

    /**
     * Responds to IPinnedStackListener on resetting aspect ratio for the pinned window.
     * It will normally follow up with a
     * {@link #onMovementBoundsChanged(Rect, Rect, Rect, DisplayInfo)} callback.
     */
    public void onAspectRatioChanged(float aspectRatio) {
        mAspectRatio = aspectRatio;
    }

    /**
     * Responds to IPinnedStackListener on preparing the pinned stack animation.
     */
    public void onPrepareAnimation(Rect sourceRectHint, float aspectRatio, Rect bounds) {
        final Rect destinationBounds;
        if (bounds == null) {
            destinationBounds = getDefaultBounds(mReentrySnapFraction);
        } else {
            destinationBounds = new Rect(bounds);
        }
        if (isValidPictureInPictureAspectRatio(aspectRatio)) {
            transformBoundsToAspectRatio(destinationBounds, aspectRatio,
                    false /* useCurrentMinEdgeSize */);
        }
        if (destinationBounds.equals(bounds)) {
            return;
        }
        mAspectRatio = aspectRatio;
        onResetReentrySnapFractionUnchecked();
        try {
            mPinnedStackController.startAnimation(destinationBounds, sourceRectHint,
                    -1 /* animationDuration */);
            mLastDestinationBounds.set(destinationBounds);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to start PiP animation from SysUI", e);
        }
    }

    /**
     * @return whether the given {@param aspectRatio} is valid.
     */
    private boolean isValidPictureInPictureAspectRatio(float aspectRatio) {
        return Float.compare(mMinAspectRatio, aspectRatio) <= 0
                && Float.compare(aspectRatio, mMaxAspectRatio) <= 0;
    }

    /**
     * Set the current bounds (or the default bounds if there are no current bounds) with the
     * specified aspect ratio.
     */
    private void transformBoundsToAspectRatio(Rect stackBounds, float aspectRatio,
            boolean useCurrentMinEdgeSize) {
        // Save the snap fraction, calculate the aspect ratio based on screen size
        final float snapFraction = mSnapAlgorithm.getSnapFraction(stackBounds,
                getMovementBounds(stackBounds));

        final int minEdgeSize = useCurrentMinEdgeSize ? mCurrentMinSize : mDefaultMinSize;
        final Size size = mSnapAlgorithm.getSizeForAspectRatio(aspectRatio, minEdgeSize,
                mDisplayInfo.logicalWidth, mDisplayInfo.logicalHeight);
        final int left = (int) (stackBounds.centerX() - size.getWidth() / 2f);
        final int top = (int) (stackBounds.centerY() - size.getHeight() / 2f);
        stackBounds.set(left, top, left + size.getWidth(), top + size.getHeight());
        mSnapAlgorithm.applySnapFraction(stackBounds, getMovementBounds(stackBounds), snapFraction);
        if (mIsMinimized) {
            applyMinimizedOffset(stackBounds, getMovementBounds(stackBounds));
        }
    }

    /**
     * @return the default bounds to show the PIP, if a {@param snapFraction} is provided, then it
     * will apply the default bounds to the provided snap fraction.
     */
    private Rect getDefaultBounds(float snapFraction) {
        final Rect insetBounds = new Rect();
        getInsetBounds(insetBounds);

        final Rect defaultBounds = new Rect();
        final Size size = mSnapAlgorithm.getSizeForAspectRatio(mDefaultAspectRatio,
                mDefaultMinSize, mDisplayInfo.logicalWidth, mDisplayInfo.logicalHeight);
        if (snapFraction != INVALID_SNAP_FRACTION) {
            defaultBounds.set(0, 0, size.getWidth(), size.getHeight());
            final Rect movementBounds = getMovementBounds(defaultBounds);
            mSnapAlgorithm.applySnapFraction(defaultBounds, movementBounds, snapFraction);
        } else {
            Gravity.apply(mDefaultStackGravity, size.getWidth(), size.getHeight(), insetBounds,
                    0, Math.max(mIsImeShowing ? mImeHeight : 0,
                            mIsShelfShowing ? mShelfHeight : 0),
                    defaultBounds);
        }
        return defaultBounds;
    }

    /**
     * Populates the bounds on the screen that the PIP can be visible in.
     */
    private void getInsetBounds(Rect outRect) {
        try {
            mWindowManager.getStableInsets(mContext.getDisplayId(), mTmpInsets);
            outRect.set(mTmpInsets.left + mScreenEdgeInsets.x,
                    mTmpInsets.top + mScreenEdgeInsets.y,
                    mDisplayInfo.logicalWidth - mTmpInsets.right - mScreenEdgeInsets.x,
                    mDisplayInfo.logicalHeight - mTmpInsets.bottom - mScreenEdgeInsets.y);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get stable insets from WM", e);
        }
    }

    /**
     * @return the movement bounds for the given {@param stackBounds} and the current state of the
     *         controller.
     */
    private Rect getMovementBounds(Rect stackBounds) {
        return getMovementBounds(stackBounds, true /* adjustForIme */);
    }

    /**
     * @return the movement bounds for the given {@param stackBounds} and the current state of the
     *         controller.
     */
    private Rect getMovementBounds(Rect stackBounds, boolean adjustForIme) {
        final Rect movementBounds = new Rect();
        getInsetBounds(movementBounds);

        // Apply the movement bounds adjustments based on the current state.
        mSnapAlgorithm.getMovementBounds(stackBounds, movementBounds, movementBounds,
                (adjustForIme && mIsImeShowing) ? mImeHeight : 0);
        return movementBounds;
    }

    /**
     * Applies the minimized offsets to the given stack bounds.
     */
    private void applyMinimizedOffset(Rect stackBounds, Rect movementBounds) {
        mTmpDisplaySize.set(mDisplayInfo.logicalWidth, mDisplayInfo.logicalHeight);
        try {
            mWindowManager.getStableInsets(mContext.getDisplayId(), mStableInsets);
            mSnapAlgorithm.applyMinimizedOffset(stackBounds, movementBounds, mTmpDisplaySize,
                    mStableInsets);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get stable insets from WM", e);
        }
    }

    /**
     * @return the default snap fraction to apply instead of the default gravity when calculating
     *         the default stack bounds when first entering PiP.
     */
    private float getSnapFraction(Rect stackBounds) {
        return mSnapAlgorithm.getSnapFraction(stackBounds, getMovementBounds(stackBounds));
    }

    /**
     * @return the pixels for a given dp value.
     */
    private int dpToPx(float dpValue, DisplayMetrics dm) {
        return (int) TypedValue.applyDimension(COMPLEX_UNIT_DIP, dpValue, dm);
    }

    /**
     * Dumps internal states.
     */
    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mLastPipComponentName=" + mLastPipComponentName);
        pw.println(innerPrefix + "mReentrySnapFraction=" + mReentrySnapFraction);
        pw.println(innerPrefix + "mDisplayInfo=" + mDisplayInfo);
        pw.println(innerPrefix + "mDefaultAspectRatio=" + mDefaultAspectRatio);
        pw.println(innerPrefix + "mMinAspectRatio=" + mMinAspectRatio);
        pw.println(innerPrefix + "mMaxAspectRatio=" + mMaxAspectRatio);
        pw.println(innerPrefix + "mAspectRatio=" + mAspectRatio);
        pw.println(innerPrefix + "mDefaultStackGravity=" + mDefaultStackGravity);
        pw.println(innerPrefix + "mIsMinimized=" + mIsMinimized);
        pw.println(innerPrefix + "mIsImeShowing=" + mIsImeShowing);
        pw.println(innerPrefix + "mImeHeight=" + mImeHeight);
        pw.println(innerPrefix + "mIsShelfShowing=" + mIsShelfShowing);
        pw.println(innerPrefix + "mShelfHeight=" + mShelfHeight);
        mSnapAlgorithm.dump(pw, innerPrefix);
    }
}
