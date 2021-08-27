/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.accessibility.magnification;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.MathUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.MagnificationSpec;
import android.view.View;
import android.view.accessibility.MagnificationAnimationCallback;
import android.view.animation.DecelerateInterpolator;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.LocalServices;
import com.android.server.accessibility.AccessibilityManagerService;
import com.android.server.wm.WindowManagerInternal;

import java.util.Locale;

/**
 * This class is used to control and query the state of display magnification
 * from the accessibility manager and related classes. It is responsible for
 * holding the current state of magnification and animation, and it handles
 * communication between the accessibility manager and window manager.
 *
 * Magnification is limited to the range [MIN_SCALE, MAX_SCALE], and can only occur inside the
 * magnification region. If a value is out of bounds, it will be adjusted to guarantee these
 * constraints.
 */
public class FullScreenMagnificationController {
    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "FullScreenMagnificationController";

    private static final MagnificationAnimationCallback STUB_ANIMATION_CALLBACK = success -> {
    };
    public static final float MIN_SCALE = 1.0f;
    public static final float MAX_SCALE = 8.0f;

    private static final boolean DEBUG_SET_MAGNIFICATION_SPEC = false;

    private static final float DEFAULT_MAGNIFICATION_SCALE = 2.0f;

    private final Object mLock;

    private final ControllerContext mControllerCtx;

    private final ScreenStateObserver mScreenStateObserver;

    private final MagnificationInfoChangedCallback mMagnificationInfoChangedCallback;

    private int mUserId;

    private final long mMainThreadId;

    /** List of display Magnification, mapping from displayId -> DisplayMagnification. */
    @GuardedBy("mLock")
    private final SparseArray<DisplayMagnification> mDisplays = new SparseArray<>(0);

    /**
     * This class implements {@link WindowManagerInternal.MagnificationCallbacks} and holds
     * magnification information per display.
     */
    private final class DisplayMagnification implements
            WindowManagerInternal.MagnificationCallbacks {
        /**
         * The current magnification spec. If an animation is running, this
         * reflects the end state.
         */
        private final MagnificationSpec mCurrentMagnificationSpec = new MagnificationSpec();

        private final Region mMagnificationRegion = Region.obtain();
        private final Rect mMagnificationBounds = new Rect();

        private final Rect mTempRect = new Rect();
        private final Rect mTempRect1 = new Rect();

        private final SpecAnimationBridge mSpecAnimationBridge;

        // Flag indicating that we are registered with window manager.
        private boolean mRegistered;
        private boolean mUnregisterPending;
        private boolean mDeleteAfterUnregister;

        private boolean mForceShowMagnifiableBounds;

        private final int mDisplayId;

        private static final int INVALID_ID = -1;
        private int mIdOfLastServiceToMagnify = INVALID_ID;
        private boolean mMagnificationActivated = false;

        DisplayMagnification(int displayId) {
            mDisplayId = displayId;
            mSpecAnimationBridge = new SpecAnimationBridge(mControllerCtx, mLock, mDisplayId);
        }

        /**
         * Registers magnification callback and get current magnification region from
         * window manager.
         *
         * @return true if callback registers successful.
         */
        @GuardedBy("mLock")
        boolean register() {
            mRegistered = mControllerCtx.getWindowManager().setMagnificationCallbacks(
                    mDisplayId, this);
            if (!mRegistered) {
                Slog.w(LOG_TAG, "set magnification callbacks fail, displayId:" + mDisplayId);
                return false;
            }
            mSpecAnimationBridge.setEnabled(true);
            // Obtain initial state.
            mControllerCtx.getWindowManager().getMagnificationRegion(
                    mDisplayId, mMagnificationRegion);
            mMagnificationRegion.getBounds(mMagnificationBounds);
            return true;
        }

        /**
         * Unregisters magnification callback from window manager. Callbacks to
         * {@link FullScreenMagnificationController#unregisterCallbackLocked(int, boolean)} after
         * unregistered.
         *
         * @param delete true if this instance should be removed from the SparseArray in
         *               FullScreenMagnificationController after unregistered, for example,
         *               display removed.
         */
        @GuardedBy("mLock")
        void unregister(boolean delete) {
            if (mRegistered) {
                mSpecAnimationBridge.setEnabled(false);
                mControllerCtx.getWindowManager().setMagnificationCallbacks(
                        mDisplayId, null);
                mMagnificationRegion.setEmpty();
                mRegistered = false;
                unregisterCallbackLocked(mDisplayId, delete);
            }
            mUnregisterPending = false;
        }

        /**
         * Reset magnification status with animation enabled. {@link #unregister(boolean)} will be
         * called after animation finished.
         *
         * @param delete true if this instance should be removed from the SparseArray in
         *               FullScreenMagnificationController after unregistered, for example,
         *               display removed.
         */
        @GuardedBy("mLock")
        void unregisterPending(boolean delete) {
            mDeleteAfterUnregister = delete;
            mUnregisterPending = true;
            reset(true);
        }

        boolean isRegistered() {
            return mRegistered;
        }

        boolean isMagnifying() {
            return mCurrentMagnificationSpec.scale > 1.0f;
        }

        float getScale() {
            return mCurrentMagnificationSpec.scale;
        }

        float getOffsetX() {
            return mCurrentMagnificationSpec.offsetX;
        }

        float getOffsetY() {
            return mCurrentMagnificationSpec.offsetY;
        }

        @GuardedBy("mLock")
        float getCenterX() {
            return (mMagnificationBounds.width() / 2.0f
                    + mMagnificationBounds.left - getOffsetX()) / getScale();
        }

        @GuardedBy("mLock")
        float getCenterY() {
            return (mMagnificationBounds.height() / 2.0f
                    + mMagnificationBounds.top - getOffsetY()) / getScale();
        }

        /**
         * Returns the scale currently used by the window manager. If an
         * animation is in progress, this reflects the current state of the
         * animation.
         *
         * @return the scale currently used by the window manager
         */
        float getSentScale() {
            return mSpecAnimationBridge.mSentMagnificationSpec.scale;
        }

        /**
         * Returns the X offset currently used by the window manager. If an
         * animation is in progress, this reflects the current state of the
         * animation.
         *
         * @return the X offset currently used by the window manager
         */
        float getSentOffsetX() {
            return mSpecAnimationBridge.mSentMagnificationSpec.offsetX;
        }

        /**
         * Returns the Y offset currently used by the window manager. If an
         * animation is in progress, this reflects the current state of the
         * animation.
         *
         * @return the Y offset currently used by the window manager
         */
        float getSentOffsetY() {
            return mSpecAnimationBridge.mSentMagnificationSpec.offsetY;
        }

        @Override
        public void onMagnificationRegionChanged(Region magnificationRegion) {
            final Message m = PooledLambda.obtainMessage(
                    DisplayMagnification::updateMagnificationRegion, this,
                    Region.obtain(magnificationRegion));
            mControllerCtx.getHandler().sendMessage(m);
        }

        @Override
        public void onRectangleOnScreenRequested(int left, int top, int right, int bottom) {
            final Message m = PooledLambda.obtainMessage(
                    DisplayMagnification::requestRectangleOnScreen, this,
                    left, top, right, bottom);
            mControllerCtx.getHandler().sendMessage(m);
        }

        @Override
        public void onRotationChanged(int rotation) {
            // Treat as context change and reset
            final Message m = PooledLambda.obtainMessage(
                    FullScreenMagnificationController::resetIfNeeded,
                    FullScreenMagnificationController.this, mDisplayId, true);
            mControllerCtx.getHandler().sendMessage(m);
        }

        @Override
        public void onUserContextChanged() {
            final Message m = PooledLambda.obtainMessage(
                    FullScreenMagnificationController::resetIfNeeded,
                    FullScreenMagnificationController.this, mDisplayId, true);
            mControllerCtx.getHandler().sendMessage(m);
        }

        @Override
        public void onImeWindowVisibilityChanged(boolean shown) {
            final Message m = PooledLambda.obtainMessage(
                    FullScreenMagnificationController::notifyImeWindowVisibilityChanged,
                    FullScreenMagnificationController.this, shown);
            mControllerCtx.getHandler().sendMessage(m);
        }

        /**
         * Update our copy of the current magnification region
         *
         * @param magnified the magnified region
         */
        void updateMagnificationRegion(Region magnified) {
            synchronized (mLock) {
                if (!mRegistered) {
                    // Don't update if we've unregistered
                    return;
                }
                if (!mMagnificationRegion.equals(magnified)) {
                    mMagnificationRegion.set(magnified);
                    mMagnificationRegion.getBounds(mMagnificationBounds);
                    // It's possible that our magnification spec is invalid with the new bounds.
                    // Adjust the current spec's offsets if necessary.
                    if (updateCurrentSpecWithOffsetsLocked(
                            mCurrentMagnificationSpec.offsetX, mCurrentMagnificationSpec.offsetY)) {
                        sendSpecToAnimation(mCurrentMagnificationSpec, null);
                    }
                    onMagnificationChangedLocked();
                }
                magnified.recycle();
            }
        }

        void sendSpecToAnimation(MagnificationSpec spec,
                MagnificationAnimationCallback animationCallback) {
            if (DEBUG) {
                Slog.i(LOG_TAG,
                        "sendSpecToAnimation(spec = " + spec + ", animationCallback = "
                                + animationCallback + ")");
            }
            if (Thread.currentThread().getId() == mMainThreadId) {
                mSpecAnimationBridge.updateSentSpecMainThread(spec, animationCallback);
            } else {
                final Message m = PooledLambda.obtainMessage(
                        SpecAnimationBridge::updateSentSpecMainThread,
                        mSpecAnimationBridge, spec, animationCallback);
                mControllerCtx.getHandler().sendMessage(m);
            }

            final boolean lastMagnificationActivated = mMagnificationActivated;
            mMagnificationActivated = spec.scale > 1.0f;
            if (mMagnificationActivated != lastMagnificationActivated) {
                mMagnificationInfoChangedCallback.onFullScreenMagnificationActivationState(
                        mMagnificationActivated);
            }
        }

        /**
         * Get the ID of the last service that changed the magnification spec.
         *
         * @return The id
         */
        int getIdOfLastServiceToMagnify() {
            return mIdOfLastServiceToMagnify;
        }

        void onMagnificationChangedLocked() {
            mControllerCtx.getAms().notifyMagnificationChanged(mDisplayId, mMagnificationRegion,
                    getScale(), getCenterX(), getCenterY());
            if (mUnregisterPending && !isMagnifying()) {
                unregister(mDeleteAfterUnregister);
            }
        }

        @GuardedBy("mLock")
        boolean magnificationRegionContains(float x, float y) {
            return mMagnificationRegion.contains((int) x, (int) y);
        }

        @GuardedBy("mLock")
        void getMagnificationBounds(@NonNull Rect outBounds) {
            outBounds.set(mMagnificationBounds);
        }

        @GuardedBy("mLock")
        void getMagnificationRegion(@NonNull Region outRegion) {
            outRegion.set(mMagnificationRegion);
        }

        void requestRectangleOnScreen(int left, int top, int right, int bottom) {
            synchronized (mLock) {
                final Rect magnifiedFrame = mTempRect;
                getMagnificationBounds(magnifiedFrame);
                if (!magnifiedFrame.intersects(left, top, right, bottom)) {
                    return;
                }

                final Rect magnifFrameInScreenCoords = mTempRect1;
                getMagnifiedFrameInContentCoordsLocked(magnifFrameInScreenCoords);

                final float scrollX;
                final float scrollY;
                if (right - left > magnifFrameInScreenCoords.width()) {
                    final int direction = TextUtils
                            .getLayoutDirectionFromLocale(Locale.getDefault());
                    if (direction == View.LAYOUT_DIRECTION_LTR) {
                        scrollX = left - magnifFrameInScreenCoords.left;
                    } else {
                        scrollX = right - magnifFrameInScreenCoords.right;
                    }
                } else if (left < magnifFrameInScreenCoords.left) {
                    scrollX = left - magnifFrameInScreenCoords.left;
                } else if (right > magnifFrameInScreenCoords.right) {
                    scrollX = right - magnifFrameInScreenCoords.right;
                } else {
                    scrollX = 0;
                }

                if (bottom - top > magnifFrameInScreenCoords.height()) {
                    scrollY = top - magnifFrameInScreenCoords.top;
                } else if (top < magnifFrameInScreenCoords.top) {
                    scrollY = top - magnifFrameInScreenCoords.top;
                } else if (bottom > magnifFrameInScreenCoords.bottom) {
                    scrollY = bottom - magnifFrameInScreenCoords.bottom;
                } else {
                    scrollY = 0;
                }

                final float scale = getScale();
                offsetMagnifiedRegion(scrollX * scale, scrollY * scale, INVALID_ID);
            }
        }

        void getMagnifiedFrameInContentCoordsLocked(Rect outFrame) {
            final float scale = getSentScale();
            final float offsetX = getSentOffsetX();
            final float offsetY = getSentOffsetY();
            getMagnificationBounds(outFrame);
            outFrame.offset((int) -offsetX, (int) -offsetY);
            outFrame.scale(1.0f / scale);
        }

        @GuardedBy("mLock")
        void setForceShowMagnifiableBounds(boolean show) {
            if (mRegistered) {
                mForceShowMagnifiableBounds = show;
                mControllerCtx.getWindowManager().setForceShowMagnifiableBounds(
                        mDisplayId, show);
            }
        }

        @GuardedBy("mLock")
        boolean isForceShowMagnifiableBounds() {
            return mRegistered && mForceShowMagnifiableBounds;
        }

        @GuardedBy("mLock")
        boolean reset(boolean animate) {
            return reset(transformToStubCallback(animate));
        }

        @GuardedBy("mLock")
        boolean reset(MagnificationAnimationCallback animationCallback) {
            if (!mRegistered) {
                return false;
            }
            final MagnificationSpec spec = mCurrentMagnificationSpec;
            final boolean changed = !spec.isNop();
            if (changed) {
                spec.clear();
                onMagnificationChangedLocked();
            }
            mIdOfLastServiceToMagnify = INVALID_ID;
            mForceShowMagnifiableBounds = false;
            sendSpecToAnimation(spec, animationCallback);
            return changed;
        }

        @GuardedBy("mLock")
        boolean setScale(float scale, float pivotX, float pivotY,
                boolean animate, int id) {
            if (!mRegistered) {
                return false;
            }
            // Constrain scale immediately for use in the pivot calculations.
            scale = MathUtils.constrain(scale, MIN_SCALE, MAX_SCALE);

            final Rect viewport = mTempRect;
            mMagnificationRegion.getBounds(viewport);
            final MagnificationSpec spec = mCurrentMagnificationSpec;
            final float oldScale = spec.scale;
            final float oldCenterX =
                    (viewport.width() / 2.0f - spec.offsetX + viewport.left) / oldScale;
            final float oldCenterY =
                    (viewport.height() / 2.0f - spec.offsetY + viewport.top) / oldScale;
            final float normPivotX = (pivotX - spec.offsetX) / oldScale;
            final float normPivotY = (pivotY - spec.offsetY) / oldScale;
            final float offsetX = (oldCenterX - normPivotX) * (oldScale / scale);
            final float offsetY = (oldCenterY - normPivotY) * (oldScale / scale);
            final float centerX = normPivotX + offsetX;
            final float centerY = normPivotY + offsetY;
            mIdOfLastServiceToMagnify = id;
            return setScaleAndCenter(scale, centerX, centerY, transformToStubCallback(animate), id);
        }

        @GuardedBy("mLock")
        boolean setScaleAndCenter(float scale, float centerX, float centerY,
                MagnificationAnimationCallback animationCallback, int id) {
            if (!mRegistered) {
                return false;
            }
            if (DEBUG) {
                Slog.i(LOG_TAG,
                        "setScaleAndCenterLocked(scale = " + scale + ", centerX = " + centerX
                                + ", centerY = " + centerY + ", endCallback = "
                                + animationCallback + ", id = " + id + ")");
            }
            final boolean changed = updateMagnificationSpecLocked(scale, centerX, centerY);
            sendSpecToAnimation(mCurrentMagnificationSpec, animationCallback);
            if (isMagnifying() && (id != INVALID_ID)) {
                mIdOfLastServiceToMagnify = id;
                mMagnificationInfoChangedCallback.onRequestMagnificationSpec(mDisplayId,
                        mIdOfLastServiceToMagnify);
            }
            return changed;
        }

        /**
         * Updates the current magnification spec.
         *
         * @param scale the magnification scale
         * @param centerX the unscaled, screen-relative X coordinate of the center
         *                of the viewport, or {@link Float#NaN} to leave unchanged
         * @param centerY the unscaled, screen-relative Y coordinate of the center
         *                of the viewport, or {@link Float#NaN} to leave unchanged
         * @return {@code true} if the magnification spec changed or {@code false}
         *         otherwise
         */
        boolean updateMagnificationSpecLocked(float scale, float centerX, float centerY) {
            // Handle defaults.
            if (Float.isNaN(centerX)) {
                centerX = getCenterX();
            }
            if (Float.isNaN(centerY)) {
                centerY = getCenterY();
            }
            if (Float.isNaN(scale)) {
                scale = getScale();
            }

            // Compute changes.
            boolean changed = false;

            final float normScale = MathUtils.constrain(scale, MIN_SCALE, MAX_SCALE);
            if (Float.compare(mCurrentMagnificationSpec.scale, normScale) != 0) {
                mCurrentMagnificationSpec.scale = normScale;
                changed = true;
            }

            final float nonNormOffsetX = mMagnificationBounds.width() / 2.0f
                    + mMagnificationBounds.left - centerX * normScale;
            final float nonNormOffsetY = mMagnificationBounds.height() / 2.0f
                    + mMagnificationBounds.top - centerY * normScale;
            changed |= updateCurrentSpecWithOffsetsLocked(nonNormOffsetX, nonNormOffsetY);

            if (changed) {
                onMagnificationChangedLocked();
            }

            return changed;
        }

        @GuardedBy("mLock")
        void offsetMagnifiedRegion(float offsetX, float offsetY, int id) {
            if (!mRegistered) {
                return;
            }

            final float nonNormOffsetX = mCurrentMagnificationSpec.offsetX - offsetX;
            final float nonNormOffsetY = mCurrentMagnificationSpec.offsetY - offsetY;
            if (updateCurrentSpecWithOffsetsLocked(nonNormOffsetX, nonNormOffsetY)) {
                onMagnificationChangedLocked();
            }
            if (id != INVALID_ID) {
                mIdOfLastServiceToMagnify = id;
            }
            sendSpecToAnimation(mCurrentMagnificationSpec, null);
        }

        boolean updateCurrentSpecWithOffsetsLocked(float nonNormOffsetX, float nonNormOffsetY) {
            if (DEBUG) {
                Slog.i(LOG_TAG,
                        "updateCurrentSpecWithOffsetsLocked(nonNormOffsetX = " + nonNormOffsetX
                                + ", nonNormOffsetY = " + nonNormOffsetY + ")");
            }
            boolean changed = false;
            final float offsetX = MathUtils.constrain(
                    nonNormOffsetX, getMinOffsetXLocked(), getMaxOffsetXLocked());
            if (Float.compare(mCurrentMagnificationSpec.offsetX, offsetX) != 0) {
                mCurrentMagnificationSpec.offsetX = offsetX;
                changed = true;
            }
            final float offsetY = MathUtils.constrain(
                    nonNormOffsetY, getMinOffsetYLocked(), getMaxOffsetYLocked());
            if (Float.compare(mCurrentMagnificationSpec.offsetY, offsetY) != 0) {
                mCurrentMagnificationSpec.offsetY = offsetY;
                changed = true;
            }
            return changed;
        }

        float getMinOffsetXLocked() {
            final float viewportWidth = mMagnificationBounds.width();
            final float viewportLeft = mMagnificationBounds.left;
            return (viewportLeft + viewportWidth)
                    - (viewportLeft + viewportWidth) * mCurrentMagnificationSpec.scale;
        }

        float getMaxOffsetXLocked() {
            return mMagnificationBounds.left
                    - mMagnificationBounds.left * mCurrentMagnificationSpec.scale;
        }

        float getMinOffsetYLocked() {
            final float viewportHeight = mMagnificationBounds.height();
            final float viewportTop = mMagnificationBounds.top;
            return (viewportTop + viewportHeight)
                    - (viewportTop + viewportHeight) * mCurrentMagnificationSpec.scale;
        }

        float getMaxOffsetYLocked() {
            return mMagnificationBounds.top
                    - mMagnificationBounds.top * mCurrentMagnificationSpec.scale;
        }

        @Override
        public String toString() {
            return "DisplayMagnification["
                    + "mCurrentMagnificationSpec=" + mCurrentMagnificationSpec
                    + ", mMagnificationRegion=" + mMagnificationRegion
                    + ", mMagnificationBounds=" + mMagnificationBounds
                    + ", mDisplayId=" + mDisplayId
                    + ", mIdOfLastServiceToMagnify=" + mIdOfLastServiceToMagnify
                    + ", mRegistered=" + mRegistered
                    + ", mUnregisterPending=" + mUnregisterPending
                    + ']';
        }
    }

    /**
     * FullScreenMagnificationController Constructor
     */
    public FullScreenMagnificationController(@NonNull Context context,
            @NonNull AccessibilityManagerService ams, @NonNull Object lock,
            @NonNull MagnificationInfoChangedCallback magnificationInfoChangedCallback) {
        this(new ControllerContext(context, ams,
                LocalServices.getService(WindowManagerInternal.class),
                new Handler(context.getMainLooper()),
                context.getResources().getInteger(R.integer.config_longAnimTime)), lock,
                magnificationInfoChangedCallback);
    }

    /**
     * Constructor for tests
     */
    @VisibleForTesting
    public FullScreenMagnificationController(@NonNull ControllerContext ctx,
            @NonNull Object lock,
            @NonNull MagnificationInfoChangedCallback magnificationInfoChangedCallback) {
        mControllerCtx = ctx;
        mLock = lock;
        mMainThreadId = mControllerCtx.getContext().getMainLooper().getThread().getId();
        mScreenStateObserver = new ScreenStateObserver(mControllerCtx.getContext(), this);
        mMagnificationInfoChangedCallback = magnificationInfoChangedCallback;
    }

    /**
     * Start tracking the magnification region for services that control magnification and the
     * magnification gesture handler.
     *
     * This tracking imposes a cost on the system, so we avoid tracking this data unless it's
     * required.
     *
     * @param displayId The logical display id.
     */
    public void register(int displayId) {
        synchronized (mLock) {
            DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                display = new DisplayMagnification(displayId);
            }
            if (display.isRegistered()) {
                return;
            }
            if (display.register()) {
                mDisplays.put(displayId, display);
                mScreenStateObserver.registerIfNecessary();
            }
        }
    }

    /**
     * Stop requiring tracking the magnification region. We may remain registered while we
     * reset magnification.
     *
     * @param displayId The logical display id.
     */
    public void unregister(int displayId) {
        synchronized (mLock) {
            unregisterLocked(displayId, false);
        }
    }

    /**
     * Stop tracking all displays' magnification region.
     */
    public void unregisterAll() {
        synchronized (mLock) {
            // display will be removed from array after unregister, we need to clone it to
            // prevent error.
            final SparseArray<DisplayMagnification> displays = mDisplays.clone();
            for (int i = 0; i < displays.size(); i++) {
                unregisterLocked(displays.keyAt(i), false);
            }
        }
    }

    /**
     * Remove the display magnification with given id.
     *
     * @param displayId The logical display id.
     */
    public void onDisplayRemoved(int displayId) {
        synchronized (mLock) {
            unregisterLocked(displayId, true);
        }
    }

    /**
     * Check if we are registered on specified display. Note that we may be planning to unregister
     * at any moment.
     *
     * @return {@code true} if the controller is registered on specified display.
     * {@code false} otherwise.
     *
     * @param displayId The logical display id.
     */
    public boolean isRegistered(int displayId) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return false;
            }
            return display.isRegistered();
        }
    }

    /**
     * @param displayId The logical display id.
     * @return {@code true} if magnification is active, e.g. the scale
     *         is > 1, {@code false} otherwise
     */
    public boolean isMagnifying(int displayId) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return false;
            }
            return display.isMagnifying();
        }
    }

    /**
     * Returns whether the magnification region contains the specified
     * screen-relative coordinates.
     *
     * @param displayId The logical display id.
     * @param x the screen-relative X coordinate to check
     * @param y the screen-relative Y coordinate to check
     * @return {@code true} if the coordinate is contained within the
     *         magnified region, or {@code false} otherwise
     */
    public boolean magnificationRegionContains(int displayId, float x, float y) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return false;
            }
            return display.magnificationRegionContains(x, y);
        }
    }

    /**
     * Populates the specified rect with the screen-relative bounds of the
     * magnification region. If magnification is not enabled, the returned
     * bounds will be empty.
     *
     * @param displayId The logical display id.
     * @param outBounds rect to populate with the bounds of the magnified
     *                  region
     */
    public void getMagnificationBounds(int displayId, @NonNull Rect outBounds) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return;
            }
            display.getMagnificationBounds(outBounds);
        }
    }

    /**
     * Populates the specified region with the screen-relative magnification
     * region. If magnification is not enabled, then the returned region
     * will be empty.
     *
     * @param displayId The logical display id.
     * @param outRegion the region to populate
     */
    public void getMagnificationRegion(int displayId, @NonNull Region outRegion) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return;
            }
            display.getMagnificationRegion(outRegion);
        }
    }

    /**
     * Returns the magnification scale. If an animation is in progress,
     * this reflects the end state of the animation.
     *
     * @param displayId The logical display id.
     * @return the scale
     */
    public float getScale(int displayId) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return 1.0f;
            }
            return display.getScale();
        }
    }

    /**
     * Returns the X offset of the magnification viewport. If an animation
     * is in progress, this reflects the end state of the animation.
     *
     * @param displayId The logical display id.
     * @return the X offset
     */
    public float getOffsetX(int displayId) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return 0.0f;
            }
            return display.getOffsetX();
        }
    }

    /**
     * Returns the screen-relative X coordinate of the center of the
     * magnification viewport.
     *
     * @param displayId The logical display id.
     * @return the X coordinate
     */
    public float getCenterX(int displayId) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return 0.0f;
            }
            return display.getCenterX();
        }
    }

    /**
     * Returns the Y offset of the magnification viewport. If an animation
     * is in progress, this reflects the end state of the animation.
     *
     * @param displayId The logical display id.
     * @return the Y offset
     */
    public float getOffsetY(int displayId) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return 0.0f;
            }
            return display.getOffsetY();
        }
    }

    /**
     * Returns the screen-relative Y coordinate of the center of the
     * magnification viewport.
     *
     * @param displayId The logical display id.
     * @return the Y coordinate
     */
    public float getCenterY(int displayId) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return 0.0f;
            }
            return display.getCenterY();
        }
    }

    /**
     * Resets the magnification scale and center, optionally animating the
     * transition.
     *
     * @param displayId The logical display id.
     * @param animate {@code true} to animate the transition, {@code false}
     *                to transition immediately
     * @return {@code true} if the magnification spec changed, {@code false} if
     *         the spec did not change
     */
    public boolean reset(int displayId, boolean animate) {
        return reset(displayId, animate ? STUB_ANIMATION_CALLBACK : null);
    }

    /**
     * Resets the magnification scale and center, optionally animating the
     * transition.
     *
     * @param displayId The logical display id.
     * @param animationCallback Called when the animation result is valid.
     *                    {@code null} to transition immediately
     * @return {@code true} if the magnification spec changed, {@code false} if
     *         the spec did not change
     */
    public boolean reset(int displayId,
            MagnificationAnimationCallback animationCallback) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return false;
            }
            return display.reset(animationCallback);
        }
    }

    /**
     * Scales the magnified region around the specified pivot point,
     * optionally animating the transition. If animation is disabled, the
     * transition is immediate.
     *
     * @param displayId The logical display id.
     * @param scale the target scale, must be >= 1
     * @param pivotX the screen-relative X coordinate around which to scale
     * @param pivotY the screen-relative Y coordinate around which to scale
     * @param animate {@code true} to animate the transition, {@code false}
     *                to transition immediately
     * @param id the ID of the service requesting the change
     * @return {@code true} if the magnification spec changed, {@code false} if
     *         the spec did not change
     */
    public boolean setScale(int displayId, float scale, float pivotX, float pivotY,
            boolean animate, int id) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return false;
            }
            return display.setScale(scale, pivotX, pivotY, animate, id);
        }
    }

    /**
     * Sets the center of the magnified region, optionally animating the
     * transition. If animation is disabled, the transition is immediate.
     *
     * @param displayId The logical display id.
     * @param centerX the screen-relative X coordinate around which to
     *                center
     * @param centerY the screen-relative Y coordinate around which to
     *                center
     * @param animate {@code true} to animate the transition, {@code false}
     *                to transition immediately
     * @param id      the ID of the service requesting the change
     * @return {@code true} if the magnification spec changed, {@code false} if
     * the spec did not change
     */
    public boolean setCenter(int displayId, float centerX, float centerY, boolean animate, int id) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return false;
            }
            return display.setScaleAndCenter(Float.NaN, centerX, centerY,
                    animate ? STUB_ANIMATION_CALLBACK : null, id);
        }
    }

    /**
     * Sets the scale and center of the magnified region, optionally
     * animating the transition. If animation is disabled, the transition
     * is immediate.
     *
     * @param displayId The logical display id.
     * @param scale the target scale, or {@link Float#NaN} to leave unchanged
     * @param centerX the screen-relative X coordinate around which to
     *                center and scale, or {@link Float#NaN} to leave unchanged
     * @param centerY the screen-relative Y coordinate around which to
     *                center and scale, or {@link Float#NaN} to leave unchanged
     * @param animate {@code true} to animate the transition, {@code false}
     *                to transition immediately
     * @param id the ID of the service requesting the change
     * @return {@code true} if the magnification spec changed, {@code false} if
     *         the spec did not change
     */
    public boolean setScaleAndCenter(int displayId, float scale, float centerX, float centerY,
            boolean animate, int id) {
        return setScaleAndCenter(displayId, scale, centerX, centerY,
                transformToStubCallback(animate), id);
    }

    /**
     * Sets the scale and center of the magnified region, optionally
     * animating the transition. If animation is disabled, the transition
     * is immediate.
     *
     * @param displayId The logical display id.
     * @param scale the target scale, or {@link Float#NaN} to leave unchanged
     * @param centerX the screen-relative X coordinate around which to
     *                center and scale, or {@link Float#NaN} to leave unchanged
     * @param centerY the screen-relative Y coordinate around which to
     *                center and scale, or {@link Float#NaN} to leave unchanged
     * @param animationCallback Called when the animation result is valid.
     *                           {@code null} to transition immediately
     * @param id the ID of the service requesting the change
     * @return {@code true} if the magnification spec changed, {@code false} if
     *         the spec did not change
     */
    public boolean setScaleAndCenter(int displayId, float scale, float centerX, float centerY,
            MagnificationAnimationCallback animationCallback, int id) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return false;
            }
            return display.setScaleAndCenter(scale, centerX, centerY, animationCallback, id);
        }
    }

    /**
     * Offsets the magnified region. Note that the offsetX and offsetY values actually move in the
     * opposite direction as the offsets passed in here.
     *
     * @param displayId The logical display id.
     * @param offsetX the amount in pixels to offset the region in the X direction, in current
     *                screen pixels.
     * @param offsetY the amount in pixels to offset the region in the Y direction, in current
     *                screen pixels.
     * @param id      the ID of the service requesting the change
     */
    public void offsetMagnifiedRegion(int displayId, float offsetX, float offsetY, int id) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return;
            }
            display.offsetMagnifiedRegion(offsetX, offsetY, id);
        }
    }

    /**
     * Get the ID of the last service that changed the magnification spec.
     *
     * @param displayId The logical display id.
     * @return The id
     */
    public int getIdOfLastServiceToMagnify(int displayId) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return -1;
            }
            return display.getIdOfLastServiceToMagnify();
        }
    }

    /**
     * Persists the default display magnification scale to the current user's settings.
     */
    public void persistScale() {
        // TODO: b/123047354, Need support multi-display?
        final float scale = getScale(Display.DEFAULT_DISPLAY);
        final int userId = mUserId;

        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                mControllerCtx.putMagnificationScale(scale, userId);
                return null;
            }
        }.execute();
    }

    /**
     * Retrieves a previously persisted magnification scale from the current
     * user's settings.
     *
     * @return the previously persisted magnification scale, or the default
     *         scale if none is available
     */
    public float getPersistedScale() {
        return mControllerCtx.getMagnificationScale(mUserId);
    }

    /**
     * Sets the currently active user ID.
     *
     * @param userId the currently active user ID
     */
    public void setUserId(int userId) {
        if (mUserId == userId) {
            return;
        }
        mUserId = userId;
        resetAllIfNeeded(false);
    }

    /**
     * Resets all displays' magnification if last magnifying service is disabled.
     *
     * @param connectionId
     */
    public void resetAllIfNeeded(int connectionId) {
        synchronized (mLock) {
            for (int i = 0; i < mDisplays.size(); i++) {
                resetIfNeeded(mDisplays.keyAt(i), connectionId);
            }
        }
    }

    /**
     * Resets magnification if magnification and auto-update are both enabled.
     *
     * @param displayId The logical display id.
     * @param animate whether the animate the transition
     * @return whether was {@link #isMagnifying(int) magnifying}
     */
    boolean resetIfNeeded(int displayId, boolean animate) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null || !display.isMagnifying()) {
                return false;
            }
            display.reset(animate);
            return true;
        }
    }

    /**
     * Resets magnification if last magnifying service is disabled.
     *
     * @param displayId The logical display id.
     * @param connectionId the connection ID be disabled.
     * @return {@code true} on success, {@code false} on failure
     */
    boolean resetIfNeeded(int displayId, int connectionId) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null || !display.isMagnifying()
                    || connectionId != display.getIdOfLastServiceToMagnify()) {
                return false;
            }
            display.reset(true);
            return true;
        }
    }

    void setForceShowMagnifiableBounds(int displayId, boolean show) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return;
            }
            display.setForceShowMagnifiableBounds(show);
        }
    }

    /**
     * Notifies that the IME window visibility changed.
     *
     * @param shown {@code true} means the IME window shows on the screen. Otherwise it's
     *                           hidden.
     */
    void notifyImeWindowVisibilityChanged(boolean shown) {
        mMagnificationInfoChangedCallback.onImeWindowVisibilityChanged(shown);
    }

    /**
     * Returns {@code true} if the magnifiable regions of the display is forced to be shown.
     *
     * @param displayId The logical display id.
     */
    public boolean isForceShowMagnifiableBounds(int displayId) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return false;
            }
            return display.isForceShowMagnifiableBounds();
        }
    }

    private void onScreenTurnedOff() {
        final Message m = PooledLambda.obtainMessage(
                FullScreenMagnificationController::resetAllIfNeeded, this, false);
        mControllerCtx.getHandler().sendMessage(m);
    }

    private void resetAllIfNeeded(boolean animate) {
        synchronized (mLock) {
            for (int i = 0; i < mDisplays.size(); i++) {
                resetIfNeeded(mDisplays.keyAt(i), animate);
            }
        }
    }

    private void unregisterLocked(int displayId, boolean delete) {
        final DisplayMagnification display = mDisplays.get(displayId);
        if (display == null) {
            return;
        }
        if (!display.isRegistered()) {
            if (delete) {
                mDisplays.remove(displayId);
            }
            return;
        }
        if (!display.isMagnifying()) {
            display.unregister(delete);
        } else {
            display.unregisterPending(delete);
        }
    }

    /**
     * Callbacks from DisplayMagnification after display magnification unregistered. It will remove
     * DisplayMagnification instance if delete is true, and unregister screen state if
     * there is no registered display magnification.
     */
    private void unregisterCallbackLocked(int displayId, boolean delete) {
        if (delete) {
            mDisplays.remove(displayId);
        }
        // unregister screen state if necessary
        boolean hasRegister = false;
        for (int i = 0; i < mDisplays.size(); i++) {
            final DisplayMagnification display = mDisplays.valueAt(i);
            hasRegister = display.isRegistered();
            if (hasRegister) {
                break;
            }
        }
        if (!hasRegister) {
            mScreenStateObserver.unregister();
        }
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("MagnificationController[");
        builder.append("mUserId=").append(mUserId);
        builder.append(", mDisplays=").append(mDisplays);
        builder.append("]");
        return builder.toString();
    }

    /**
     * Class responsible for animating spec on the main thread and sending spec
     * updates to the window manager.
     */
    private static class SpecAnimationBridge implements ValueAnimator.AnimatorUpdateListener,
            Animator.AnimatorListener {
        private final ControllerContext mControllerCtx;

        /**
         * The magnification spec that was sent to the window manager. This should
         * only be accessed with the lock held.
         */
        private final MagnificationSpec mSentMagnificationSpec = new MagnificationSpec();

        private final MagnificationSpec mStartMagnificationSpec = new MagnificationSpec();

        private final MagnificationSpec mEndMagnificationSpec = new MagnificationSpec();

        /**
         * The animator should only be accessed and modified on the main (e.g. animation) thread.
         */
        private final ValueAnimator mValueAnimator;

        // Called when the callee wants animating and the sent spec matches the target spec.
        private MagnificationAnimationCallback mAnimationCallback;
        private final Object mLock;

        private final int mDisplayId;

        @GuardedBy("mLock")
        private boolean mEnabled = false;

        private SpecAnimationBridge(ControllerContext ctx, Object lock, int displayId) {
            mControllerCtx = ctx;
            mLock = lock;
            mDisplayId = displayId;
            final long animationDuration = mControllerCtx.getAnimationDuration();
            mValueAnimator = mControllerCtx.newValueAnimator();
            mValueAnimator.setDuration(animationDuration);
            mValueAnimator.setInterpolator(new DecelerateInterpolator(2.5f));
            mValueAnimator.setFloatValues(0.0f, 1.0f);
            mValueAnimator.addUpdateListener(this);
            mValueAnimator.addListener(this);
        }

        /**
         * Enabled means the bridge will accept input. When not enabled, the output of the animator
         * will be ignored
         */
        public void setEnabled(boolean enabled) {
            synchronized (mLock) {
                if (enabled != mEnabled) {
                    mEnabled = enabled;
                    if (!mEnabled) {
                        mSentMagnificationSpec.clear();
                        mControllerCtx.getWindowManager().setMagnificationSpec(
                                mDisplayId, mSentMagnificationSpec);
                    }
                }
            }
        }

        void updateSentSpecMainThread(MagnificationSpec spec,
                MagnificationAnimationCallback animationCallback) {
            if (mValueAnimator.isRunning()) {
                mValueAnimator.cancel();
            }

            mAnimationCallback = animationCallback;
            // If the current and sent specs don't match, update the sent spec.
            synchronized (mLock) {
                final boolean changed = !mSentMagnificationSpec.equals(spec);
                if (changed) {
                    if (mAnimationCallback != null) {
                        animateMagnificationSpecLocked(spec);
                    } else {
                        setMagnificationSpecLocked(spec);
                    }
                } else {
                    sendEndCallbackMainThread(true);
                }
            }
        }

        private void sendEndCallbackMainThread(boolean success) {
            if (mAnimationCallback != null) {
                if (DEBUG) {
                    Slog.d(LOG_TAG, "sendEndCallbackMainThread: " + success);
                }
                mAnimationCallback.onResult(success);
                mAnimationCallback = null;
            }
        }

        @GuardedBy("mLock")
        private void setMagnificationSpecLocked(MagnificationSpec spec) {
            if (mEnabled) {
                if (DEBUG_SET_MAGNIFICATION_SPEC) {
                    Slog.i(LOG_TAG, "Sending: " + spec);
                }

                mSentMagnificationSpec.setTo(spec);
                mControllerCtx.getWindowManager().setMagnificationSpec(
                        mDisplayId, mSentMagnificationSpec);
            }
        }

        private void animateMagnificationSpecLocked(MagnificationSpec toSpec) {
            mEndMagnificationSpec.setTo(toSpec);
            mStartMagnificationSpec.setTo(mSentMagnificationSpec);
            mValueAnimator.start();
        }

        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            synchronized (mLock) {
                if (mEnabled) {
                    float fract = animation.getAnimatedFraction();
                    MagnificationSpec magnificationSpec = new MagnificationSpec();
                    magnificationSpec.scale = mStartMagnificationSpec.scale
                            + (mEndMagnificationSpec.scale - mStartMagnificationSpec.scale) * fract;
                    magnificationSpec.offsetX = mStartMagnificationSpec.offsetX
                            + (mEndMagnificationSpec.offsetX - mStartMagnificationSpec.offsetX)
                            * fract;
                    magnificationSpec.offsetY = mStartMagnificationSpec.offsetY
                            + (mEndMagnificationSpec.offsetY - mStartMagnificationSpec.offsetY)
                            * fract;
                    setMagnificationSpecLocked(magnificationSpec);
                }
            }
        }

        @Override
        public void onAnimationStart(Animator animation) {
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            sendEndCallbackMainThread(true);
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            sendEndCallbackMainThread(false);
        }

        @Override
        public void onAnimationRepeat(Animator animation) {

        }
    }

    private static class ScreenStateObserver extends BroadcastReceiver {
        private final Context mContext;
        private final FullScreenMagnificationController mController;
        private boolean mRegistered = false;

        ScreenStateObserver(Context context, FullScreenMagnificationController controller) {
            mContext = context;
            mController = controller;
        }

        public void registerIfNecessary() {
            if (!mRegistered) {
                mContext.registerReceiver(this, new IntentFilter(Intent.ACTION_SCREEN_OFF));
                mRegistered = true;
            }
        }

        public void unregister() {
            if (mRegistered) {
                mContext.unregisterReceiver(this);
                mRegistered = false;
            }
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            mController.onScreenTurnedOff();
        }
    }

    /**
     * This class holds resources used between the classes in MagnificationController, and
     * functions for tests to mock it.
     */
    @VisibleForTesting
    public static class ControllerContext {
        private final Context mContext;
        private final AccessibilityManagerService mAms;
        private final WindowManagerInternal mWindowManager;
        private final Handler mHandler;
        private final Long mAnimationDuration;

        /**
         * Constructor for ControllerContext.
         */
        public ControllerContext(@NonNull Context context,
                @NonNull AccessibilityManagerService ams,
                @NonNull WindowManagerInternal windowManager,
                @NonNull Handler handler,
                long animationDuration) {
            mContext = context;
            mAms = ams;
            mWindowManager = windowManager;
            mHandler = handler;
            mAnimationDuration = animationDuration;
        }

        /**
         * @return A context.
         */
        @NonNull
        public Context getContext() {
            return mContext;
        }

        /**
         * @return AccessibilityManagerService
         */
        @NonNull
        public AccessibilityManagerService getAms() {
            return mAms;
        }

        /**
         * @return WindowManagerInternal
         */
        @NonNull
        public WindowManagerInternal getWindowManager() {
            return mWindowManager;
        }

        /**
         * @return Handler for main looper
         */
        @NonNull
        public Handler getHandler() {
            return mHandler;
        }

        /**
         * Create a new ValueAnimator.
         *
         * @return ValueAnimator
         */
        @NonNull
        public ValueAnimator newValueAnimator() {
            return new ValueAnimator();
        }

        /**
         * Write Settings of magnification scale.
         */
        public void putMagnificationScale(float value, int userId) {
            Settings.Secure.putFloatForUser(mContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE, value, userId);
        }

        /**
         * Get Settings of magnification scale.
         */
        public float getMagnificationScale(int userId) {
            return Settings.Secure.getFloatForUser(mContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE,
                    DEFAULT_MAGNIFICATION_SCALE, userId);
        }

        /**
         * @return Configuration of animation duration.
         */
        public long getAnimationDuration() {
            return mAnimationDuration;
        }
    }

    @Nullable
    private static MagnificationAnimationCallback transformToStubCallback(boolean animate) {
        return animate ? STUB_ANIMATION_CALLBACK : null;
    }

    interface  MagnificationInfoChangedCallback {

        /**
         * Called when the {@link MagnificationSpec} is changed with non-default
         * scale by the service.
         *
         * @param displayId the logical display id
         * @param serviceId the ID of the service requesting the change
         */
        void onRequestMagnificationSpec(int displayId, int serviceId);

        /**
         * Called when the state of the magnification activation is changed.
         * It is for the logging data of the magnification activation state.
         *
         * @param activated {@code true} if the magnification is activated, otherwise {@code false}.
         */
        void onFullScreenMagnificationActivationState(boolean activated);

        /**
         * Called when the IME window visibility changed.
         * @param shown {@code true} means the IME window shows on the screen. Otherwise it's
         *                           hidden.
         */
        void onImeWindowVisibilityChanged(boolean shown);
    }
}
