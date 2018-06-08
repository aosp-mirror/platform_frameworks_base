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
import android.view.MagnificationSpec;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;
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
public class MagnificationController implements Handler.Callback {
    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "MagnificationController";

    public static final float MIN_SCALE = 1.0f;
    public static final float MAX_SCALE = 5.0f;

    private static final boolean DEBUG_SET_MAGNIFICATION_SPEC = false;

    private static final int INVALID_ID = -1;

    private static final float DEFAULT_MAGNIFICATION_SCALE = 2.0f;

    // Messages
    private static final int MSG_SEND_SPEC_TO_ANIMATION = 1;
    private static final int MSG_SCREEN_TURNED_OFF = 2;
    private static final int MSG_ON_MAGNIFIED_BOUNDS_CHANGED = 3;
    private static final int MSG_ON_RECTANGLE_ON_SCREEN_REQUESTED = 4;
    private static final int MSG_ON_USER_CONTEXT_CHANGED = 5;

    private final Object mLock;

    /**
     * The current magnification spec. If an animation is running, this
     * reflects the end state.
     */
    private final MagnificationSpec mCurrentMagnificationSpec = MagnificationSpec.obtain();

    private final Region mMagnificationRegion = Region.obtain();
    private final Rect mMagnificationBounds = new Rect();

    private final Rect mTempRect = new Rect();
    private final Rect mTempRect1 = new Rect();

    private final AccessibilityManagerService mAms;

    private final SettingsBridge mSettingsBridge;

    private final ScreenStateObserver mScreenStateObserver;

    private final SpecAnimationBridge mSpecAnimationBridge;

    private final WindowManagerInternal.MagnificationCallbacks mWMCallbacks =
            new WindowManagerInternal.MagnificationCallbacks () {
                @Override
                public void onMagnificationRegionChanged(Region region) {
                    final SomeArgs args = SomeArgs.obtain();
                    args.arg1 = Region.obtain(region);
                    mHandler.obtainMessage(MSG_ON_MAGNIFIED_BOUNDS_CHANGED, args).sendToTarget();
                }

                @Override
                public void onRectangleOnScreenRequested(int left, int top, int right, int bottom) {
                    final SomeArgs args = SomeArgs.obtain();
                    args.argi1 = left;
                    args.argi2 = top;
                    args.argi3 = right;
                    args.argi4 = bottom;
                    mHandler.obtainMessage(MSG_ON_RECTANGLE_ON_SCREEN_REQUESTED, args)
                            .sendToTarget();
                }

                @Override
                public void onRotationChanged(int rotation) {
                    // Treat as context change and reset
                    mHandler.sendEmptyMessage(MSG_ON_USER_CONTEXT_CHANGED);
                }

                @Override
                public void onUserContextChanged() {
                    mHandler.sendEmptyMessage(MSG_ON_USER_CONTEXT_CHANGED);
                }
            };

    private int mUserId;

    private final long mMainThreadId;

    private Handler mHandler;

    private int mIdOfLastServiceToMagnify = INVALID_ID;

    private final WindowManagerInternal mWindowManager;

    // Flag indicating that we are registered with window manager.
    @VisibleForTesting boolean mRegistered;

    private boolean mUnregisterPending;

    public MagnificationController(Context context, AccessibilityManagerService ams, Object lock) {
        this(context, ams, lock, null, LocalServices.getService(WindowManagerInternal.class),
                new ValueAnimator(), new SettingsBridge(context.getContentResolver()));
        mHandler = new Handler(context.getMainLooper(), this);
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
        mSpecAnimationBridge = new SpecAnimationBridge(
                context, mLock, mWindowManager, valueAnimator);
        mSettingsBridge = settingsBridge;
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
            if (!mRegistered) {
                mScreenStateObserver.register();
                mWindowManager.setMagnificationCallbacks(mWMCallbacks);
                mSpecAnimationBridge.setEnabled(true);
                // Obtain initial state.
                mWindowManager.getMagnificationRegion(mMagnificationRegion);
                mMagnificationRegion.getBounds(mMagnificationBounds);
                mRegistered = true;
            }
        }
    }

    /**
     * Stop requiring tracking the magnification region. We may remain registered while we
     * reset magnification.
     */
    public void unregister() {
        synchronized (mLock) {
            if (!isMagnifying()) {
                unregisterInternalLocked();
            } else {
                mUnregisterPending = true;
                resetLocked(true);
            }
        }
    }

    /**
     * Check if we are registered. Note that we may be planning to unregister at any moment.
     *
     * @return {@code true} if the controller is registered. {@code false} otherwise.
     */
    public boolean isRegisteredLocked() {
        return mRegistered;
    }

    private void unregisterInternalLocked() {
        if (mRegistered) {
            mSpecAnimationBridge.setEnabled(false);
            mScreenStateObserver.unregister();
            mWindowManager.setMagnificationCallbacks(null);
            mMagnificationRegion.setEmpty();
            mRegistered = false;
        }
        mUnregisterPending = false;
    }

    /**
     * @return {@code true} if magnification is active, e.g. the scale
     *         is > 1, {@code false} otherwise
     */
    public boolean isMagnifying() {
        return mCurrentMagnificationSpec.scale > 1.0f;
    }

    /**
     * Update our copy of the current magnification region
     *
     * @param magnified the magnified region
     */
    private void onMagnificationRegionChanged(Region magnified) {
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
        }
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
        synchronized (mLock) {
            return mMagnificationRegion.contains((int) x, (int) y);
        }
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
        synchronized (mLock) {
            outBounds.set(mMagnificationBounds);
        }
    }

    /**
     * Populates the specified region with the screen-relative magnification
     * region. If magnification is not enabled, then the returned region
     * will be empty.
     *
     * @param outRegion the region to populate
     */
    public void getMagnificationRegion(@NonNull Region outRegion) {
        synchronized (mLock) {
            outRegion.set(mMagnificationRegion);
        }
    }

    /**
     * Returns the magnification scale. If an animation is in progress,
     * this reflects the end state of the animation.
     *
     * @return the scale
     */
    public float getScale() {
        return mCurrentMagnificationSpec.scale;
    }

    /**
     * Returns the X offset of the magnification viewport. If an animation
     * is in progress, this reflects the end state of the animation.
     *
     * @return the X offset
     */
    public float getOffsetX() {
        return mCurrentMagnificationSpec.offsetX;
    }


    /**
     * Returns the screen-relative X coordinate of the center of the
     * magnification viewport.
     *
     * @return the X coordinate
     */
    public float getCenterX() {
        synchronized (mLock) {
            return (mMagnificationBounds.width() / 2.0f
                    + mMagnificationBounds.left - getOffsetX()) / getScale();
        }
    }

    /**
     * Returns the Y offset of the magnification viewport. If an animation
     * is in progress, this reflects the end state of the animation.
     *
     * @return the Y offset
     */
    public float getOffsetY() {
        return mCurrentMagnificationSpec.offsetY;
    }

    /**
     * Returns the screen-relative Y coordinate of the center of the
     * magnification viewport.
     *
     * @return the Y coordinate
     */
    public float getCenterY() {
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
    private float getSentScale() {
        return mSpecAnimationBridge.mSentMagnificationSpec.scale;
    }

    /**
     * Returns the X offset currently used by the window manager. If an
     * animation is in progress, this reflects the current state of the
     * animation.
     *
     * @return the X offset currently used by the window manager
     */
    private float getSentOffsetX() {
        return mSpecAnimationBridge.mSentMagnificationSpec.offsetX;
    }

    /**
     * Returns the Y offset currently used by the window manager. If an
     * animation is in progress, this reflects the current state of the
     * animation.
     *
     * @return the Y offset currently used by the window manager
     */
    private float getSentOffsetY() {
        return mSpecAnimationBridge.mSentMagnificationSpec.offsetY;
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
        synchronized (mLock) {
            return resetLocked(animate);
        }
    }

    private boolean resetLocked(boolean animate) {
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
            return setScaleAndCenterLocked(scale, centerX, centerY, animate, id);
        }
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
     * @param id the ID of the service requesting the change
     * @return {@code true} if the magnification spec changed, {@code false} if
     *         the spec did not change
     */
    public boolean setCenter(float centerX, float centerY, boolean animate, int id) {
        synchronized (mLock) {
            if (!mRegistered) {
                return false;
            }
            return setScaleAndCenterLocked(Float.NaN, centerX, centerY, animate, id);
        }
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
        synchronized (mLock) {
            if (!mRegistered) {
                return false;
            }
            return setScaleAndCenterLocked(scale, centerX, centerY, animate, id);
        }
    }

    private boolean setScaleAndCenterLocked(float scale, float centerX, float centerY,
            boolean animate, int id) {
        if (DEBUG) {
            Slog.i(LOG_TAG,
                    "setScaleAndCenterLocked(scale = " + scale + ", centerX = " + centerX
                            + ", centerY = " + centerY + ", animate = " + animate + ", id = " + id
                            + ")");
        }
        final boolean changed = updateMagnificationSpecLocked(scale, centerX, centerY);
        sendSpecToAnimation(mCurrentMagnificationSpec, animate);
        if (isMagnifying() && (id != INVALID_ID)) {
            mIdOfLastServiceToMagnify = id;
        }
        return changed;
    }

    /**
     * Offsets the magnified region. Note that the offsetX and offsetY values actually move in the
     * opposite direction as the offsets passed in here.
     *
     * @param offsetX the amount in pixels to offset the region in the X direction, in current
     * screen pixels.
     * @param offsetY the amount in pixels to offset the region in the Y direction, in current
     * screen pixels.
     * @param id the ID of the service requesting the change
     */
    public void offsetMagnifiedRegion(float offsetX, float offsetY, int id) {
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

    /**
     * Get the ID of the last service that changed the magnification spec.
     *
     * @return The id
     */
    public int getIdOfLastServiceToMagnify() {
        return mIdOfLastServiceToMagnify;
    }

    private void onMagnificationChangedLocked() {
        mAms.notifyMagnificationChanged(mMagnificationRegion,
                getScale(), getCenterX(), getCenterY());
        if (mUnregisterPending && !isMagnifying()) {
            unregisterInternalLocked();
        }
    }

    /**
     * Persists the current magnification scale to the current user's settings.
     */
    public void persistScale() {
        final float scale = mCurrentMagnificationSpec.scale;
        final int userId = mUserId;

        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                mSettingsBridge.putMagnificationScale(scale, userId);
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
        return mSettingsBridge.getMagnificationScale(mUserId);
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
    private boolean updateMagnificationSpecLocked(float scale, float centerX, float centerY) {
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

    private boolean updateCurrentSpecWithOffsetsLocked(float nonNormOffsetX, float nonNormOffsetY) {
        if (DEBUG) {
            Slog.i(LOG_TAG,
                    "updateCurrentSpecWithOffsetsLocked(nonNormOffsetX = " + nonNormOffsetX
                            + ", nonNormOffsetY = " + nonNormOffsetY + ")");
        }
        boolean changed = false;
        final float offsetX = MathUtils.constrain(nonNormOffsetX, getMinOffsetXLocked(), 0);
        if (Float.compare(mCurrentMagnificationSpec.offsetX, offsetX) != 0) {
            mCurrentMagnificationSpec.offsetX = offsetX;
            changed = true;
        }
        final float offsetY = MathUtils.constrain(nonNormOffsetY, getMinOffsetYLocked(), 0);
        if (Float.compare(mCurrentMagnificationSpec.offsetY, offsetY) != 0) {
            mCurrentMagnificationSpec.offsetY = offsetY;
            changed = true;
        }
        return changed;
    }

    private float getMinOffsetXLocked() {
        final float viewportWidth = mMagnificationBounds.width();
        return viewportWidth - viewportWidth * mCurrentMagnificationSpec.scale;
    }

    private float getMinOffsetYLocked() {
        final float viewportHeight = mMagnificationBounds.height();
        return viewportHeight - viewportHeight * mCurrentMagnificationSpec.scale;
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
    boolean resetIfNeeded(boolean animate) {
        synchronized (mLock) {
            if (isMagnifying()) {
                reset(animate);
                return true;
            }
            return false;
        }
    }

    void setForceShowMagnifiableBounds(boolean show) {
        if (mRegistered) {
            mWindowManager.setForceShowMagnifiableBounds(show);
        }
    }

    private void getMagnifiedFrameInContentCoordsLocked(Rect outFrame) {
        final float scale = getSentScale();
        final float offsetX = getSentOffsetX();
        final float offsetY = getSentOffsetY();
        getMagnificationBounds(outFrame);
        outFrame.offset((int) -offsetX, (int) -offsetY);
        outFrame.scale(1.0f / scale);
    }

    private void requestRectangleOnScreen(int left, int top, int right, int bottom) {
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

    private void sendSpecToAnimation(MagnificationSpec spec, boolean animate) {
        if (DEBUG) {
            Slog.i(LOG_TAG, "sendSpecToAnimation(spec = " + spec + ", animate = " + animate + ")");
        }
        if (Thread.currentThread().getId() == mMainThreadId) {
            mSpecAnimationBridge.updateSentSpecMainThread(spec, animate);
        } else {
            mHandler.obtainMessage(MSG_SEND_SPEC_TO_ANIMATION,
                    animate ? 1 : 0, 0, spec).sendToTarget();
        }
    }

    private void onScreenTurnedOff() {
        mHandler.sendEmptyMessage(MSG_SCREEN_TURNED_OFF);
    }

    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_SEND_SPEC_TO_ANIMATION:
                final boolean animate = msg.arg1 == 1;
                final MagnificationSpec spec = (MagnificationSpec) msg.obj;
                mSpecAnimationBridge.updateSentSpecMainThread(spec, animate);
                break;
            case MSG_SCREEN_TURNED_OFF:
                resetIfNeeded(false);
                break;
            case MSG_ON_MAGNIFIED_BOUNDS_CHANGED: {
                final SomeArgs args = (SomeArgs) msg.obj;
                final Region magnifiedBounds = (Region) args.arg1;
                onMagnificationRegionChanged(magnifiedBounds);
                magnifiedBounds.recycle();
                args.recycle();
            } break;
            case MSG_ON_RECTANGLE_ON_SCREEN_REQUESTED: {
                final SomeArgs args = (SomeArgs) msg.obj;
                final int left = args.argi1;
                final int top = args.argi2;
                final int right = args.argi3;
                final int bottom = args.argi4;
                requestRectangleOnScreen(left, top, right, bottom);
                args.recycle();
            } break;
            case MSG_ON_USER_CONTEXT_CHANGED:
                resetIfNeeded(true);
                break;
        }
        return true;
    }

    @Override
    public String toString() {
        return "MagnificationController{" +
                "mCurrentMagnificationSpec=" + mCurrentMagnificationSpec +
                ", mMagnificationRegion=" + mMagnificationRegion +
                ", mMagnificationBounds=" + mMagnificationBounds +
                ", mUserId=" + mUserId +
                ", mIdOfLastServiceToMagnify=" + mIdOfLastServiceToMagnify +
                ", mRegistered=" + mRegistered +
                ", mUnregisterPending=" + mUnregisterPending +
                '}';
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

        public ScreenStateObserver(Context context, MagnificationController controller) {
            mContext = context;
            mController = controller;
        }

        public void register() {
            mContext.registerReceiver(this, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        }

        public void unregister() {
            mContext.unregisterReceiver(this);
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

        public void putMagnificationScale(float value, int userId) {
            Settings.Secure.putFloatForUser(mContentResolver,
                    Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE, value, userId);
        }

        public float getMagnificationScale(int userId) {
            return Settings.Secure.getFloatForUser(mContentResolver,
                    Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE,
                    DEFAULT_MAGNIFICATION_SCALE, userId);
        }
    }
}
