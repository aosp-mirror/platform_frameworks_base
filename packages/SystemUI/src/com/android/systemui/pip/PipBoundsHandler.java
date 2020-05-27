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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.util.TypedValue.COMPLEX_UNIT_DIP;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
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
import android.window.WindowContainerTransaction;

import com.android.systemui.wm.DisplayController;
import com.android.systemui.wm.DisplayLayout;

import java.io.PrintWriter;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Handles bounds calculation for PIP on Phone and other form factors, it keeps tracking variant
 * state changes originated from Window Manager and is the source of truth for PiP window bounds.
 */
@Singleton
public class PipBoundsHandler {

    private static final String TAG = PipBoundsHandler.class.getSimpleName();
    private static final float INVALID_SNAP_FRACTION = -1f;

    private final Context mContext;
    private final PipSnapAlgorithm mSnapAlgorithm;
    private final DisplayInfo mDisplayInfo = new DisplayInfo();
    private final DisplayController mDisplayController;
    private final DisplayLayout mDisplayLayout;

    private ComponentName mLastPipComponentName;
    private float mReentrySnapFraction = INVALID_SNAP_FRACTION;
    private Size mReentrySize;

    private float mDefaultAspectRatio;
    private float mMinAspectRatio;
    private float mMaxAspectRatio;
    private float mAspectRatio;
    private int mDefaultStackGravity;
    private int mDefaultMinSize;
    private Point mScreenEdgeInsets;
    private int mCurrentMinSize;
    private Size mOverrideMinimalSize;

    private boolean mIsImeShowing;
    private int mImeHeight;
    private boolean mIsShelfShowing;
    private int mShelfHeight;

    private final DisplayController.OnDisplaysChangedListener mDisplaysChangedListener =
            new DisplayController.OnDisplaysChangedListener() {
        @Override
        public void onDisplayAdded(int displayId) {
            if (displayId == mContext.getDisplayId()) {
                mDisplayLayout.set(mDisplayController.getDisplayLayout(displayId));
            }
        }
    };

    @Inject
    public PipBoundsHandler(Context context, PipSnapAlgorithm pipSnapAlgorithm,
            DisplayController displayController) {
        mContext = context;
        mSnapAlgorithm = pipSnapAlgorithm;
        mDisplayLayout = new DisplayLayout();
        mDisplayController = displayController;
        mDisplayController.addDisplayWindowListener(mDisplaysChangedListener);
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

    public void setMinEdgeSize(int minEdgeSize) {
        mCurrentMinSize = minEdgeSize;
    }

    protected float getAspectRatio() {
        return mAspectRatio;
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
     * Responds to IPinnedStackListener on movement bounds change.
     * Note that both inset and normal bounds will be calculated here rather than in the caller.
     */
    public void onMovementBoundsChanged(Rect insetBounds, Rect normalBounds,
            Rect animatingBounds, DisplayInfo displayInfo) {
        getInsetBounds(insetBounds);
        final Rect defaultBounds = getDefaultBounds(INVALID_SNAP_FRACTION, null);
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
     * Responds to IPinnedStackListener on saving reentry snap fraction and size
     * for a given {@link ComponentName}.
     */
    public void onSaveReentryBounds(ComponentName componentName, Rect bounds) {
        mReentrySnapFraction = getSnapFraction(bounds);
        mReentrySize = new Size(bounds.width(), bounds.height());
        mLastPipComponentName = componentName;
    }

    /**
     * Responds to IPinnedStackListener on resetting reentry snap fraction and size
     * for a given {@link ComponentName}.
     */
    public void onResetReentryBounds(ComponentName componentName) {
        if (componentName.equals(mLastPipComponentName)) {
            onResetReentryBoundsUnchecked();
        }
    }

    private void onResetReentryBoundsUnchecked() {
        mReentrySnapFraction = INVALID_SNAP_FRACTION;
        mReentrySize = null;
        mLastPipComponentName = null;
    }

    /**
     * Returns ture if there's a valid snap fraction. This is used with {@link EXTRA_IS_FIRST_ENTRY}
     * to see if this is the first time user has entered PIP for the component.
     */
    public boolean hasSaveReentryBounds() {
        return mReentrySnapFraction != INVALID_SNAP_FRACTION;
    }

    public Rect getDisplayBounds() {
        return new Rect(0, 0, mDisplayInfo.logicalWidth, mDisplayInfo.logicalHeight);
    }

    public int getDisplayRotation() {
        return mDisplayInfo.rotation;
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
     * @return {@link Rect} of the destination PiP window bounds.
     */
    Rect getDestinationBounds(ComponentName componentName, float aspectRatio, Rect bounds,
            Size minimalSize) {
        if (!componentName.equals(mLastPipComponentName)) {
            onResetReentryBoundsUnchecked();
            mLastPipComponentName = componentName;
        }
        final Rect destinationBounds;
        if (bounds == null) {
            final Rect defaultBounds = getDefaultBounds(mReentrySnapFraction, mReentrySize);
            destinationBounds = new Rect(defaultBounds);
            if (mReentrySnapFraction == INVALID_SNAP_FRACTION && mReentrySize == null) {
                mOverrideMinimalSize = minimalSize;
            }
        } else {
            destinationBounds = new Rect(bounds);
        }
        if (isValidPictureInPictureAspectRatio(aspectRatio)) {
            transformBoundsToAspectRatio(destinationBounds, aspectRatio,
                    false /* useCurrentMinEdgeSize */);
        }
        mAspectRatio = aspectRatio;
        return destinationBounds;
    }

    float getDefaultAspectRatio() {
        return mDefaultAspectRatio;
    }

    /**
     * Updates the display info, calculating and returning the new stack and movement bounds in the
     * new orientation of the device if necessary.
     *
     * @return {@code true} if internal {@link DisplayInfo} is rotated, {@code false} otherwise.
     */
    public boolean onDisplayRotationChanged(Rect outBounds, Rect oldBounds, Rect outInsetBounds,
            int displayId, int fromRotation, int toRotation, WindowContainerTransaction t) {
        // Bail early if the event is not sent to current {@link #mDisplayInfo}
        if ((displayId != mDisplayInfo.displayId) || (fromRotation == toRotation)) {
            return false;
        }

        // Bail early if the pinned stack is staled.
        final ActivityManager.StackInfo pinnedStackInfo;
        try {
            pinnedStackInfo = ActivityTaskManager.getService()
                    .getStackInfo(WINDOWING_MODE_PINNED, ACTIVITY_TYPE_UNDEFINED);
            if (pinnedStackInfo == null) return false;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get StackInfo for pinned stack", e);
            return false;
        }

        // Calculate the snap fraction of the current stack along the old movement bounds
        final Rect postChangeStackBounds = new Rect(oldBounds);
        final float snapFraction = getSnapFraction(postChangeStackBounds);

        // Update the display layout
        mDisplayLayout.rotateTo(mContext.getResources(), toRotation);

        // Populate the new {@link #mDisplayInfo}.
        // The {@link DisplayInfo} queried from DisplayManager would be the one before rotation,
        // therefore, the width/height may require a swap first.
        // Moving forward, we should get the new dimensions after rotation from DisplayLayout.
        mDisplayInfo.rotation = toRotation;
        updateDisplayInfoIfNeeded();

        // Calculate the stack bounds in the new orientation based on same fraction along the
        // rotated movement bounds.
        final Rect postChangeMovementBounds = getMovementBounds(postChangeStackBounds,
                false /* adjustForIme */);
        mSnapAlgorithm.applySnapFraction(postChangeStackBounds, postChangeMovementBounds,
                snapFraction);

        getInsetBounds(outInsetBounds);
        outBounds.set(postChangeStackBounds);
        t.setBounds(pinnedStackInfo.stackToken, outBounds);
        return true;
    }

    private void updateDisplayInfoIfNeeded() {
        final boolean updateNeeded;
        if ((mDisplayInfo.rotation == ROTATION_0) || (mDisplayInfo.rotation == ROTATION_180)) {
            updateNeeded = (mDisplayInfo.logicalWidth > mDisplayInfo.logicalHeight);
        } else {
            updateNeeded = (mDisplayInfo.logicalWidth < mDisplayInfo.logicalHeight);
        }
        if (updateNeeded) {
            final int newLogicalHeight = mDisplayInfo.logicalWidth;
            mDisplayInfo.logicalWidth = mDisplayInfo.logicalHeight;
            mDisplayInfo.logicalHeight = newLogicalHeight;
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
     * Sets the current bound with the currently store aspect ratio.
     * @param stackBounds
     */
    public void transformBoundsToAspectRatio(Rect stackBounds) {
        transformBoundsToAspectRatio(stackBounds, mAspectRatio, true);
    }

    /**
     * Set the current bounds (or the default bounds if there are no current bounds) with the
     * specified aspect ratio.
     */
    private void transformBoundsToAspectRatio(Rect stackBounds, float aspectRatio,
            boolean useCurrentMinEdgeSize) {
        // Save the snap fraction and adjust the size based on the new aspect ratio.
        final float snapFraction = mSnapAlgorithm.getSnapFraction(stackBounds,
                getMovementBounds(stackBounds));
        final int minEdgeSize;
        final Size size;
        if (useCurrentMinEdgeSize) {
            minEdgeSize = mCurrentMinSize;
            size = mSnapAlgorithm.getSizeForAspectRatio(
                    new Size(stackBounds.width(), stackBounds.height()), aspectRatio, minEdgeSize);
        } else {
            minEdgeSize = mDefaultMinSize;
            size = mSnapAlgorithm.getSizeForAspectRatio(aspectRatio, minEdgeSize,
                    mDisplayInfo.logicalWidth, mDisplayInfo.logicalHeight);
        }

        final int left = (int) (stackBounds.centerX() - size.getWidth() / 2f);
        final int top = (int) (stackBounds.centerY() - size.getHeight() / 2f);
        stackBounds.set(left, top, left + size.getWidth(), top + size.getHeight());
        // apply the override minimal size if applicable, this minimal size is specified by app
        if (mOverrideMinimalSize != null) {
            transformBoundsToMinimalSize(stackBounds, aspectRatio, mOverrideMinimalSize);
        }
        mSnapAlgorithm.applySnapFraction(stackBounds, getMovementBounds(stackBounds), snapFraction);
    }

    /**
     * Transforms a given bounds to meet the minimal size constraints.
     * This function assumes the given {@param stackBounds} qualifies {@param aspectRatio}.
     */
    private void transformBoundsToMinimalSize(Rect stackBounds, float aspectRatio,
            Size minimalSize) {
        if (minimalSize == null) return;
        final Size adjustedMinimalSize;
        final float minimalSizeAspectRatio =
                minimalSize.getWidth() / (float) minimalSize.getHeight();
        if (minimalSizeAspectRatio > aspectRatio) {
            // minimal size is wider, fixed the width and increase the height
            adjustedMinimalSize = new Size(
                    minimalSize.getWidth(), (int) (minimalSize.getWidth() / aspectRatio));
        } else {
            adjustedMinimalSize = new Size(
                    (int) (minimalSize.getHeight() * aspectRatio), minimalSize.getHeight());
        }
        final Rect containerBounds = new Rect(stackBounds);
        Gravity.apply(mDefaultStackGravity,
                adjustedMinimalSize.getWidth(), adjustedMinimalSize.getHeight(),
                containerBounds, stackBounds);
    }

    /**
     * @return the default bounds to show the PIP, if a {@param snapFraction} and {@param size} are
     * provided, then it will apply the default bounds to the provided snap fraction and size.
     */
    private Rect getDefaultBounds(float snapFraction, Size size) {
        final Rect defaultBounds = new Rect();
        if (snapFraction != INVALID_SNAP_FRACTION && size != null) {
            defaultBounds.set(0, 0, size.getWidth(), size.getHeight());
            final Rect movementBounds = getMovementBounds(defaultBounds);
            mSnapAlgorithm.applySnapFraction(defaultBounds, movementBounds, snapFraction);
        } else {
            final Rect insetBounds = new Rect();
            getInsetBounds(insetBounds);
            size = mSnapAlgorithm.getSizeForAspectRatio(mDefaultAspectRatio,
                    mDefaultMinSize, mDisplayInfo.logicalWidth, mDisplayInfo.logicalHeight);
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
    protected void getInsetBounds(Rect outRect) {
        Rect insets = mDisplayLayout.stableInsets();
        outRect.set(insets.left + mScreenEdgeInsets.x,
                insets.top + mScreenEdgeInsets.y,
                mDisplayInfo.logicalWidth - insets.right - mScreenEdgeInsets.x,
                mDisplayInfo.logicalHeight - insets.bottom - mScreenEdgeInsets.y);
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
     * @return the default snap fraction to apply instead of the default gravity when calculating
     *         the default stack bounds when first entering PiP.
     */
    public float getSnapFraction(Rect stackBounds) {
        return mSnapAlgorithm.getSnapFraction(stackBounds, getMovementBounds(stackBounds));
    }

    /**
     * Applies the given snap fraction to the given stack bounds.
     */
    public void applySnapFraction(Rect stackBounds, float snapFraction) {
        final Rect movementBounds = getMovementBounds(stackBounds);
        mSnapAlgorithm.applySnapFraction(stackBounds, movementBounds, snapFraction);
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
        pw.println(innerPrefix + "mReentrySize=" + mReentrySize);
        pw.println(innerPrefix + "mDisplayInfo=" + mDisplayInfo);
        pw.println(innerPrefix + "mDefaultAspectRatio=" + mDefaultAspectRatio);
        pw.println(innerPrefix + "mMinAspectRatio=" + mMinAspectRatio);
        pw.println(innerPrefix + "mMaxAspectRatio=" + mMaxAspectRatio);
        pw.println(innerPrefix + "mAspectRatio=" + mAspectRatio);
        pw.println(innerPrefix + "mDefaultStackGravity=" + mDefaultStackGravity);
        pw.println(innerPrefix + "mIsImeShowing=" + mIsImeShowing);
        pw.println(innerPrefix + "mImeHeight=" + mImeHeight);
        pw.println(innerPrefix + "mIsShelfShowing=" + mIsShelfShowing);
        pw.println(innerPrefix + "mShelfHeight=" + mShelfHeight);
    }
}
