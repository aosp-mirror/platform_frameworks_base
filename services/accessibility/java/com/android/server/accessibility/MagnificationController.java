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

package com.android.server.accessibility;

import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
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
import android.view.Display;
import android.view.MagnificationSpec;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.LocalServices;
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
public class MagnificationController {
    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "MagnificationController";

    public static final float MIN_SCALE = 1.0f;
    public static final float MAX_SCALE = 8.0f;

    private static final boolean DEBUG_SET_MAGNIFICATION_SPEC = false;

    private static final float DEFAULT_MAGNIFICATION_SCALE = 2.0f;

    private final Object mLock;

    private final AccessibilityManagerService mAms;

    private final SettingsBridge mSettingsBridge;

    private final ScreenStateObserver mScreenStateObserver;

    private int mUserId;

    private final long mMainThreadId;

    private Handler mHandler;

    private final WindowManagerInternal mWindowManager;

    private final DisplayMagnification mDisplay;

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
        private final MagnificationSpec mCurrentMagnificationSpec = MagnificationSpec.obtain();

        private final Region mMagnificationRegion = Region.obtain();
        private final Rect mMagnificationBounds = new Rect();

        private final Rect mTempRect = new Rect();
        private final Rect mTempRect1 = new Rect();

        private final SpecAnimationBridge mSpecAnimationBridge;

        // Flag indicating that we are registered with window manager.
        private boolean mRegistered;
        private boolean mUnregisterPending;

        private final int mDisplayId;

        private static final int INVALID_ID = -1;
        private int mIdOfLastServiceToMagnify = INVALID_ID;


        DisplayMagnification(int displayId, SpecAnimationBridge specAnimation) {
            mDisplayId = displayId;
            mSpecAnimationBridge = specAnimation;
        }

        void register() {
            synchronized (mLock) {
                if (!mRegistered) {
                    mWindowManager.setMagnificationCallbacks(this);
                    mSpecAnimationBridge.setEnabled(true);
                    // Obtain initial state.
                    mWindowManager.getMagnificationRegion(mMagnificationRegion);
                    mMagnificationRegion.getBounds(mMagnificationBounds);
                    mRegistered = true;
                }
            }
        }

        void unregister() {
            synchronized (mLock) {
                if (!isMagnifying()) {
                    unregisterInternalLocked();
                } else {
                    mUnregisterPending = true;
                    reset(true);
                }
            }
        }

        boolean isRegisteredLocked() {
            return mRegistered;
        }


        float getScale() {
            return mCurrentMagnificationSpec.scale;
        }

        float getOffsetX() {
            return mCurrentMagnificationSpec.offsetX;
        }

        float getCenterX() {
            synchronized (mLock) {
                return (mMagnificationBounds.width() / 2.0f
                        + mMagnificationBounds.left - getOffsetX()) / getScale();
            }
        }

        float getCenterY() {
            synchronized (mLock) {
                return (mMagnificationBounds.height() / 2.0f
                        + mMagnificationBounds.top - getOffsetY()) / getScale();
            }
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

        boolean resetIfNeeded(boolean animate) {
            synchronized (mLock) {
                if (isMagnifying()) {
                    reset(animate);
                    return true;
                }
                return false;
            }
        }

        float getOffsetY() {
            return mCurrentMagnificationSpec.offsetY;
        }

        boolean isMagnifying() {
            return mCurrentMagnificationSpec.scale > 1.0f;
        }

        void unregisterInternalLocked() {
            if (mRegistered) {
                mSpecAnimationBridge.setEnabled(false);
                mWindowManager.setMagnificationCallbacks(null);
                mMagnificationRegion.setEmpty();

                mRegistered = false;
            }
            mUnregisterPending = false;
        }


        @Override
        public void onMagnificationRegionChanged(Region magnificationRegion) {
            final Message m = PooledLambda.obtainMessage(
                    DisplayMagnification.this::updateMagnificationRegion,
                    Region.obtain(magnificationRegion));
            mHandler.sendMessage(m);
        }

        @Override
        public void onRectangleOnScreenRequested(int left, int top, int right, int bottom) {
            final Message m = PooledLambda.obtainMessage(
                    DisplayMagnification.this::requestRectangleOnScreen, left, top, right, bottom);
            mHandler.sendMessage(m);
        }

        @Override
        public void onRotationChanged(int rotation) {
            // Treat as context change and reset
            final Message m = PooledLambda.obtainMessage(DisplayMagnification.this::resetIfNeeded,
                    true);
            mHandler.sendMessage(m);
        }

        @Override
        public void onUserContextChanged() {
            final Message m = PooledLambda.obtainMessage(DisplayMagnification.this::resetIfNeeded,
                    true);
            mHandler.sendMessage(m);
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
                        sendSpecToAnimation(mCurrentMagnificationSpec, false);
                    }
                    onMagnificationChangedLocked();
                }
                magnified.recycle();
            }
        }

        void sendSpecToAnimation(MagnificationSpec spec, boolean animate) {
            if (DEBUG) {
                Slog.i(LOG_TAG,
                        "sendSpecToAnimation(spec = " + spec + ", animate = " + animate + ")");
            }
            if (Thread.currentThread().getId() == mMainThreadId) {
                mSpecAnimationBridge.updateSentSpecMainThread(spec, animate);
            } else {
                final Message m = PooledLambda.obtainMessage(
                        this.mSpecAnimationBridge::updateSentSpecMainThread, spec, animate);
                mHandler.sendMessage(m);
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
            mAms.notifyMagnificationChanged(mMagnificationRegion,
                    getScale(), getCenterX(), getCenterY());
            if (mUnregisterPending && !isMagnifying()) {
                unregisterInternalLocked();
            }
        }

        boolean magnificationRegionContains(float x, float y) {
            synchronized (mLock) {
                return mMagnificationRegion.contains((int) x, (int) y);

            }
        }

        void getMagnificationBounds(@NonNull Rect outBounds) {
            synchronized (mLock) {
                outBounds.set(mMagnificationBounds);
            }
        }

        void getMagnificationRegion(@NonNull Region outRegion) {
            synchronized (mLock) {
                outRegion.set(mMagnificationRegion);
            }
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

        /**
         * Resets magnification if last magnifying service is disabled.
         *
         * @param connectionId the connection ID be disabled.
         * @return {@code true} on success, {@code false} on failure
         */
        boolean resetIfNeeded(int connectionId) {
            if (mIdOfLastServiceToMagnify == connectionId) {
                return resetIfNeeded(true /*animate*/);
            }
            return false;
        }

        void setForceShowMagnifiableBounds(boolean show) {
            if (mRegistered) {
                mWindowManager.setForceShowMagnifiableBounds(show);
            }
        }

        boolean reset(boolean animate) {
            synchronized (mLock) {
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
                sendSpecToAnimation(spec, animate);
                return changed;
            }
        }


        boolean setScale(float scale, float pivotX, float pivotY,
                boolean animate, int id) {

            synchronized (mLock) {
                if (!mRegistered) {
                    return false;
                }
                // Constrain scale immediately for use in the pivot calculations.
                scale = MathUtils.constrain(scale, MIN_SCALE, MAX_SCALE);

                final Rect viewport = mTempRect;
                mMagnificationRegion.getBounds(viewport);
                final MagnificationSpec spec = mCurrentMagnificationSpec;
                final float oldScale = spec.scale;
                final float oldCenterX
                        = (viewport.width() / 2.0f - spec.offsetX + viewport.left) / oldScale;
                final float oldCenterY
                        = (viewport.height() / 2.0f - spec.offsetY + viewport.top) / oldScale;
                final float normPivotX = (pivotX - spec.offsetX) / oldScale;
                final float normPivotY = (pivotY - spec.offsetY) / oldScale;
                final float offsetX = (oldCenterX - normPivotX) * (oldScale / scale);
                final float offsetY = (oldCenterY - normPivotY) * (oldScale / scale);
                final float centerX = normPivotX + offsetX;
                final float centerY = normPivotY + offsetY;
                mIdOfLastServiceToMagnify = id;

                return setScaleAndCenter(scale, centerX, centerY, animate, id);
            }
        }

        boolean setScaleAndCenter(float scale, float centerX, float centerY,
                boolean animate, int id) {

            synchronized (mLock) {
                if (!mRegistered) {
                    return false;
                }
                if (DEBUG) {
                    Slog.i(LOG_TAG,
                            "setScaleAndCenterLocked(scale = " + scale + ", centerX = " + centerX
                                    + ", centerY = " + centerY + ", animate = " + animate
                                    + ", id = " + id
                                    + ")");
                }
                final boolean changed = updateMagnificationSpecLocked(scale, centerX, centerY);
                sendSpecToAnimation(mCurrentMagnificationSpec, animate);
                if (isMagnifying() && (id != INVALID_ID)) {
                    mIdOfLastServiceToMagnify = id;
                }
                return changed;
            }
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

        void offsetMagnifiedRegion(float offsetX, float offsetY, int id) {
            synchronized (mLock) {
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
                sendSpecToAnimation(mCurrentMagnificationSpec, false);
            }
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
            return (viewportLeft + viewportWidth) -
                    (viewportLeft + viewportWidth) * mCurrentMagnificationSpec.scale;
        }

        float getMaxOffsetXLocked() {
            return mMagnificationBounds.left -
                    mMagnificationBounds.left * mCurrentMagnificationSpec.scale;
        }

        float getMinOffsetYLocked() {
            final float viewportHeight = mMagnificationBounds.height();
            final float viewportTop = mMagnificationBounds.top;
            return (viewportTop + viewportHeight) -
                    (viewportTop + viewportHeight) * mCurrentMagnificationSpec.scale;
        }

        float getMaxOffsetYLocked() {
            return mMagnificationBounds.top -
                    mMagnificationBounds.top * mCurrentMagnificationSpec.scale;
        }

        @Override
        public String toString() {
            return "DisplayMagnification{" +
                    "mCurrentMagnificationSpec=" + mCurrentMagnificationSpec +
                    ", mMagnificationRegion=" + mMagnificationRegion +
                    ", mMagnificationBounds=" + mMagnificationBounds +
                    ", mDisplayId=" + mDisplayId +
                    ", mUserId=" + mUserId +
                    ", mIdOfLastServiceToMagnify=" + mIdOfLastServiceToMagnify +
                    ", mRegistered=" + mRegistered +
                    ", mUnregisterPending=" + mUnregisterPending +
                    '}';
        }

    }

    public MagnificationController(Context context, AccessibilityManagerService ams, Object lock) {
        this(context, ams, lock, null, LocalServices.getService(WindowManagerInternal.class),
                new ValueAnimator(), new SettingsBridge(context.getContentResolver()));
        mHandler = new Handler(context.getMainLooper());
    }

    public MagnificationController(
            Context context,
            AccessibilityManagerService ams,
            Object lock,
            Handler handler,
            WindowManagerInternal windowManagerInternal,
            ValueAnimator valueAnimator,
            SettingsBridge settingsBridge) {
        mHandler = handler;
        mWindowManager = windowManagerInternal;
        mMainThreadId = context.getMainLooper().getThread().getId();
        mAms = ams;
        mScreenStateObserver = new ScreenStateObserver(context, this);
        mLock = lock;
        mSettingsBridge = settingsBridge;
        //TODO (multidisplay): Magnification is supported only for the default display.
        mDisplay =  new DisplayMagnification(Display.DEFAULT_DISPLAY,
                new SpecAnimationBridge(context, mLock, mWindowManager, valueAnimator));
    }

    /**
     * Start tracking the magnification region for services that control magnification and the
     * magnification gesture handler.
     *
     * This tracking imposes a cost on the system, so we avoid tracking this data unless it's
     * required.
     */
    public void register() {
        synchronized (mLock) {
            mScreenStateObserver.register();
        }
        mDisplay.register();
    }

    /**
     * Stop requiring tracking the magnification region. We may remain registered while we
     * reset magnification.
     */
    public void unregister() {
        synchronized (mLock) {
            mScreenStateObserver.unregister();
        }
        mDisplay.unregister();
    }
    
    /**
     * Check if we are registered. Note that we may be planning to unregister at any moment.
     *
     * @return {@code true} if the controller is registered. {@code false} otherwise.
     */
    public boolean isRegisteredLocked() {
        return mDisplay.isRegisteredLocked();
    }

    /**
     * @return {@code true} if magnification is active, e.g. the scale
     *         is > 1, {@code false} otherwise
     */
    public boolean isMagnifying() {
        return mDisplay.isMagnifying();
    }

    /**
     * Returns whether the magnification region contains the specified
     * screen-relative coordinates.
     *
     * @param x the screen-relative X coordinate to check
     * @param y the screen-relative Y coordinate to check
     * @return {@code true} if the coordinate is contained within the
     *         magnified region, or {@code false} otherwise
     */
    public boolean magnificationRegionContains(float x, float y) {
        return mDisplay.magnificationRegionContains(x, y);

    }

    /**
     * Populates the specified rect with the screen-relative bounds of the
     * magnification region. If magnification is not enabled, the returned
     * bounds will be empty.
     *
     * @param outBounds rect to populate with the bounds of the magnified
     *                  region
     */
    public void getMagnificationBounds(@NonNull Rect outBounds) {
        mDisplay.getMagnificationBounds(outBounds);
    }

    /**
     * Populates the specified region with the screen-relative magnification
     * region. If magnification is not enabled, then the returned region
     * will be empty.
     *
     * @param outRegion the region to populate
     */
    public void getMagnificationRegion(@NonNull Region outRegion) {
        mDisplay.getMagnificationRegion(outRegion);
    }

    /**
     * Returns the magnification scale. If an animation is in progress,
     * this reflects the end state of the animation.
     *
     * @return the scale
     */
    public float getScale() {
        return mDisplay.getScale();
    }

    /**
     * Returns the X offset of the magnification viewport. If an animation
     * is in progress, this reflects the end state of the animation.
     *
     * @return the X offset
     */
    public float getOffsetX() {
        return mDisplay.getOffsetX();
    }


    /**
     * Returns the screen-relative X coordinate of the center of the
     * magnification viewport.
     *
     * @return the X coordinate
     */
    public float getCenterX() {
        return mDisplay.getCenterX();
    }

    /**
     * Returns the Y offset of the magnification viewport. If an animation
     * is in progress, this reflects the end state of the animation.
     *
     * @return the Y offset
     */
    public float getOffsetY() {
        return mDisplay.getOffsetY();
    }

    /**
     * Returns the screen-relative Y coordinate of the center of the
     * magnification viewport.
     *
     * @return the Y coordinate
     */
    public float getCenterY() {
        return mDisplay.getCenterY();
    }

    /**
     * Resets the magnification scale and center, optionally animating the
     * transition.
     *
     * @param animate {@code true} to animate the transition, {@code false}
     *                to transition immediately
     * @return {@code true} if the magnification spec changed, {@code false} if
     *         the spec did not change
     */
    public boolean reset(boolean animate) {

        return mDisplay.reset(animate);

    }

    /**
     * Scales the magnified region around the specified pivot point,
     * optionally animating the transition. If animation is disabled, the
     * transition is immediate.
     *
     * @param scale the target scale, must be >= 1
     * @param pivotX the screen-relative X coordinate around which to scale
     * @param pivotY the screen-relative Y coordinate around which to scale
     * @param animate {@code true} to animate the transition, {@code false}
     *                to transition immediately
     * @param id the ID of the service requesting the change
     * @return {@code true} if the magnification spec changed, {@code false} if
     *         the spec did not change
     */
    public boolean setScale(float scale, float pivotX, float pivotY, boolean animate, int id) {
            return mDisplay.
                    setScale(scale, pivotX, pivotY, animate, id);
    }

    /**
     * Sets the center of the magnified region, optionally animating the
     * transition. If animation is disabled, the transition is immediate.
     *
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
    public boolean setCenter(float centerX, float centerY, boolean animate, int id) {
            return mDisplay.
                    setScaleAndCenter(Float.NaN, centerX, centerY, animate, id);
    }

    /**
     * Sets the scale and center of the magnified region, optionally
     * animating the transition. If animation is disabled, the transition
     * is immediate.
     *
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
    public boolean setScaleAndCenter(
            float scale, float centerX, float centerY, boolean animate, int id) {
        return mDisplay.
                setScaleAndCenter(scale, centerX, centerY, animate, id);
    }

    /**
     * Offsets the magnified region. Note that the offsetX and offsetY values actually move in the
     * opposite direction as the offsets passed in here.
     *
     * @param offsetX the amount in pixels to offset the region in the X direction, in current
     *                screen pixels.
     * @param offsetY the amount in pixels to offset the region in the Y direction, in current
     *                screen pixels.
     * @param id      the ID of the service requesting the change
     */
    public void offsetMagnifiedRegion(float offsetX, float offsetY, int id) {
        mDisplay.offsetMagnifiedRegion(offsetX, offsetY,
                id);
    }

    /**
     * Get the ID of the last service that changed the magnification spec.
     *
     * @return The id
     */
    public int getIdOfLastServiceToMagnify() {
        return mDisplay.getIdOfLastServiceToMagnify();
    }

    /**
     * Persists the current magnification scale to the current user's settings.
     */
    public void persistScale() {
        persistScale(Display.DEFAULT_DISPLAY);
    }
    /**
     * Persists the current magnification scale to the current user's settings.
     */
    public void persistScale(int displayId) {
        final float scale = mDisplay.getScale();
        final int userId = mUserId;

        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                mSettingsBridge.putMagnificationScale(scale, displayId, userId);
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
        return mSettingsBridge.getMagnificationScale(Display.DEFAULT_DISPLAY, mUserId);
    }

    /**
     * Sets the currently active user ID.
     *
     * @param userId the currently active user ID
     */
    public void setUserId(int userId) {
        if (mUserId != userId) {
            mUserId = userId;

            synchronized (mLock) {
                if (isMagnifying()) {
                    reset(false);
                }
            }
        }
    }

   /**
     * Resets magnification if magnification and auto-update are both enabled.
     *
     * @param animate whether the animate the transition
     * @return whether was {@link #isMagnifying magnifying}
     */
    public boolean resetIfNeeded(boolean animate) {
        return mDisplay.resetIfNeeded(animate);
    }

    /**
     * Resets magnification if last magnifying service is disabled.
     *
     * @param connectionId the connection ID be disabled.
     * @return {@code true} on success, {@code false} on failure
     */
    public boolean resetIfNeeded(int connectionId) {
        return mDisplay.resetIfNeeded(connectionId);
    }

    void setForceShowMagnifiableBounds(boolean show) {
        mDisplay.setForceShowMagnifiableBounds(show);
    }

    private void onScreenTurnedOff() {
        final Message m = PooledLambda.obtainMessage(
                mDisplay::resetIfNeeded, false);
        mHandler.sendMessage(m);
    }

    @Override
    public String toString() {
        return mDisplay.toString();
    }

    /**
     * Class responsible for animating spec on the main thread and sending spec
     * updates to the window manager.
     */
    private static class SpecAnimationBridge implements ValueAnimator.AnimatorUpdateListener {
        private final WindowManagerInternal mWindowManager;

        /**
         * The magnification spec that was sent to the window manager. This should
         * only be accessed with the lock held.
         */
        private final MagnificationSpec mSentMagnificationSpec = MagnificationSpec.obtain();

        private final MagnificationSpec mStartMagnificationSpec = MagnificationSpec.obtain();

        private final MagnificationSpec mEndMagnificationSpec = MagnificationSpec.obtain();

        private final MagnificationSpec mTmpMagnificationSpec = MagnificationSpec.obtain();

        /**
         * The animator should only be accessed and modified on the main (e.g. animation) thread.
         */
        private final ValueAnimator mValueAnimator;

        private final Object mLock;

        @GuardedBy("mLock")
        private boolean mEnabled = false;

        private SpecAnimationBridge(Context context, Object lock, WindowManagerInternal wm,
                ValueAnimator animator) {
            mLock = lock;
            mWindowManager = wm;
            final long animationDuration = context.getResources().getInteger(
                    R.integer.config_longAnimTime);
            mValueAnimator = animator;
            mValueAnimator.setDuration(animationDuration);
            mValueAnimator.setInterpolator(new DecelerateInterpolator(2.5f));
            mValueAnimator.setFloatValues(0.0f, 1.0f);
            mValueAnimator.addUpdateListener(this);
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
                        mWindowManager.setMagnificationSpec(mSentMagnificationSpec);
                    }
                }
            }
        }

        public void updateSentSpecMainThread(MagnificationSpec spec, boolean animate) {
            if (mValueAnimator.isRunning()) {
                mValueAnimator.cancel();
            }

            // If the current and sent specs don't match, update the sent spec.
            synchronized (mLock) {
                final boolean changed = !mSentMagnificationSpec.equals(spec);
                if (changed) {
                    if (animate) {
                        animateMagnificationSpecLocked(spec);
                    } else {
                        setMagnificationSpecLocked(spec);
                    }
                }
            }
        }

        @GuardedBy("mLock")
        private void setMagnificationSpecLocked(MagnificationSpec spec) {
            if (mEnabled) {
                if (DEBUG_SET_MAGNIFICATION_SPEC) {
                    Slog.i(LOG_TAG, "Sending: " + spec);
                }

                mSentMagnificationSpec.setTo(spec);
                mWindowManager.setMagnificationSpec(spec);
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
                    mTmpMagnificationSpec.scale = mStartMagnificationSpec.scale +
                            (mEndMagnificationSpec.scale - mStartMagnificationSpec.scale) * fract;
                    mTmpMagnificationSpec.offsetX = mStartMagnificationSpec.offsetX +
                            (mEndMagnificationSpec.offsetX - mStartMagnificationSpec.offsetX)
                                    * fract;
                    mTmpMagnificationSpec.offsetY = mStartMagnificationSpec.offsetY +
                            (mEndMagnificationSpec.offsetY - mStartMagnificationSpec.offsetY)
                                    * fract;
                    synchronized (mLock) {
                        setMagnificationSpecLocked(mTmpMagnificationSpec);
                    }
                }
            }
        }
    }

    private static class ScreenStateObserver extends BroadcastReceiver {
        private final Context mContext;
        private final MagnificationController mController;
        private boolean mRegistered = false;

        public ScreenStateObserver(Context context, MagnificationController controller) {
            mContext = context;
            mController = controller;
        }

        public void register() {
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

    // Extra class to get settings so tests can mock it
    public static class SettingsBridge {
        private final ContentResolver mContentResolver;

        public SettingsBridge(ContentResolver contentResolver) {
            mContentResolver = contentResolver;
        }

        public void putMagnificationScale(float value, int displayId, int userId) {
            Settings.Secure.putFloatForUser(mContentResolver,
                    Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE + (
                            Display.DEFAULT_DISPLAY == displayId ? "" : displayId),
                    value, userId);
        }

        public float getMagnificationScale(int displayId, int userId) {
            return Settings.Secure.getFloatForUser(mContentResolver,
                    Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE
                            + (Display.DEFAULT_DISPLAY == displayId ? "" : displayId),
                    DEFAULT_MAGNIFICATION_SCALE, userId);
        }
    }
}
