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

import static android.accessibilityservice.AccessibilityTrace.FLAGS_WINDOW_MANAGER_INTERNAL;
import static android.accessibilityservice.MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN;
import static android.util.TypedValue.COMPLEX_UNIT_DIP;
import static android.view.accessibility.MagnificationAnimationCallback.STUB_ANIMATION_CALLBACK;

import static com.android.server.accessibility.AccessibilityManagerService.INVALID_SERVICE_ID;

import android.accessibilityservice.MagnificationConfig;
import android.animation.Animator;
import android.animation.TimeAnimator;
import android.animation.ValueAnimator;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.CompatibilityInfo;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManagerInternal;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.MathUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.MagnificationSpec;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.MagnificationAnimationCallback;
import android.view.animation.DecelerateInterpolator;
import android.widget.Scroller;

import com.android.internal.R;
import com.android.internal.accessibility.common.MagnificationConstants;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.LocalServices;
import com.android.server.accessibility.AccessibilityManagerService;
import com.android.server.accessibility.AccessibilityTraceManager;
import com.android.server.accessibility.Flags;
import com.android.server.wm.WindowManagerInternal;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * This class is used to control and query the state of display magnification
 * from the accessibility manager and related classes. It is responsible for
 * holding the current state of magnification and animation, and it handles
 * communication between the accessibility manager and window manager.
 *
 * Magnification is limited to the range controlled by
 * {@link MagnificationScaleProvider#constrainScale(float)}, and can only occur inside the
 * magnification region. If a value is out of bounds, it will be adjusted to guarantee these
 * constraints.
 */
public class FullScreenMagnificationController implements
        WindowManagerInternal.AccessibilityControllerInternal.UiChangesForAccessibilityCallbacks {
    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "FullScreenMagnificationController";

    private static final boolean DEBUG_SET_MAGNIFICATION_SPEC = false;

    private final Object mLock;
    private final Supplier<Scroller> mScrollerSupplier;
    private final Supplier<TimeAnimator> mTimeAnimatorSupplier;

    private final ControllerContext mControllerCtx;

    private final ScreenStateObserver mScreenStateObserver;

    @GuardedBy("mLock")
    private final ArrayList<MagnificationInfoChangedCallback>
            mMagnificationInfoChangedCallbacks = new ArrayList<>();

    private final MagnificationScaleProvider mScaleProvider;

    private final long mMainThreadId;

    /** List of display Magnification, mapping from displayId -> DisplayMagnification. */
    @GuardedBy("mLock")
    private final SparseArray<DisplayMagnification> mDisplays = new SparseArray<>(0);

    private final Rect mTempRect = new Rect();
    // Whether the following typing focus feature for magnification is enabled.
    private boolean mMagnificationFollowTypingEnabled = true;
    // Whether the always on magnification feature is enabled.
    private boolean mAlwaysOnMagnificationEnabled = false;
    private final DisplayManagerInternal mDisplayManagerInternal;

    private final MagnificationThumbnailFeatureFlag mMagnificationThumbnailFeatureFlag;
    @NonNull private final Supplier<MagnificationThumbnail> mThumbnailSupplier;

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

        private final int mDisplayId;

        private int mIdOfLastServiceToMagnify = INVALID_SERVICE_ID;
        private boolean mMagnificationActivated = false;

        private boolean mZoomedOutFromService = false;

        @GuardedBy("mLock") @Nullable private MagnificationThumbnail mMagnificationThumbnail;

        DisplayMagnification(int displayId) {
            mDisplayId = displayId;
            mSpecAnimationBridge =
                    new SpecAnimationBridge(
                            mControllerCtx,
                            mLock,
                            mDisplayId,
                            mScrollerSupplier,
                            mTimeAnimatorSupplier);
        }

        /**
         * Registers magnification callback and get current magnification region from
         * window manager.
         *
         * @return true if callback registers successful.
         */
        @GuardedBy("mLock")
        boolean register() {
            if (traceEnabled()) {
                logTrace("setMagnificationCallbacks",
                        "displayID=" + mDisplayId + ";callback=" + this);
            }
            mRegistered = mControllerCtx.getWindowManager().setMagnificationCallbacks(
                    mDisplayId, this);
            if (!mRegistered) {
                Slog.w(LOG_TAG, "set magnification callbacks fail, displayId:" + mDisplayId);
                return false;
            }
            mSpecAnimationBridge.setEnabled(true);
            if (traceEnabled()) {
                logTrace("getMagnificationRegion",
                        "displayID=" + mDisplayId + ";region=" + mMagnificationRegion);
            }
            // Obtain initial state.
            mControllerCtx.getWindowManager().getMagnificationRegion(
                    mDisplayId, mMagnificationRegion);
            mMagnificationRegion.getBounds(mMagnificationBounds);

            createThumbnailIfSupported();

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
                if (traceEnabled()) {
                    logTrace("setMagnificationCallbacks",
                            "displayID=" + mDisplayId + ";callback=null");
                }
                mControllerCtx.getWindowManager().setMagnificationCallbacks(
                        mDisplayId, null);
                mMagnificationRegion.setEmpty();
                mRegistered = false;
                unregisterCallbackLocked(mDisplayId, delete);

                destroyThumbnail();
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

        boolean isActivated() {
            return mMagnificationActivated;
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
        boolean isAtEdge() {
            return isAtLeftEdge() || isAtRightEdge() || isAtTopEdge() || isAtBottomEdge();
        }

        @GuardedBy("mLock")
        boolean isAtLeftEdge() {
            return getOffsetX() == getMaxOffsetXLocked();
        }

        @GuardedBy("mLock")
        boolean isAtRightEdge() {
            return getOffsetX() == getMinOffsetXLocked();
        }

        @GuardedBy("mLock")
        boolean isAtTopEdge() {
            return getOffsetY() == getMaxOffsetYLocked();
        }

        @GuardedBy("mLock")
        boolean isAtBottomEdge() {
            return getOffsetY() == getMinOffsetYLocked();
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
        public void onDisplaySizeChanged() {
            // Treat as context change
            onUserContextChanged();
        }

        @Override
        public void onUserContextChanged() {
            final Message m = PooledLambda.obtainMessage(
                    FullScreenMagnificationController::onUserContextChanged,
                    FullScreenMagnificationController.this, mDisplayId);
            mControllerCtx.getHandler().sendMessage(m);

            synchronized (mLock) {
                refreshThumbnail();
            }
        }

        @Override
        public void onImeWindowVisibilityChanged(boolean shown) {
            final Message m = PooledLambda.obtainMessage(
                    FullScreenMagnificationController::notifyImeWindowVisibilityChanged,
                    FullScreenMagnificationController.this, mDisplayId, shown);
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

                    refreshThumbnail();

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
        }

        void startFlingAnimation(
                float xPixelsPerSecond,
                float yPixelsPerSecond,
                MagnificationAnimationCallback animationCallback
        ) {
            if (DEBUG) {
                Slog.i(LOG_TAG,
                        "startFlingAnimation(spec = " + xPixelsPerSecond + ", animationCallback = "
                                + animationCallback + ")");
            }
            if (Thread.currentThread().getId() == mMainThreadId) {
                mSpecAnimationBridge.startFlingAnimation(
                        xPixelsPerSecond,
                        yPixelsPerSecond,
                        getMinOffsetXLocked(),
                        getMaxOffsetXLocked(),
                        getMinOffsetYLocked(),
                        getMaxOffsetYLocked(),
                        animationCallback);
            } else {
                final Message m =
                        PooledLambda.obtainMessage(
                                SpecAnimationBridge::startFlingAnimation,
                                mSpecAnimationBridge,
                                xPixelsPerSecond,
                                yPixelsPerSecond,
                                getMinOffsetXLocked(),
                                getMaxOffsetXLocked(),
                                getMinOffsetYLocked(),
                                getMaxOffsetYLocked(),
                                animationCallback);
                mControllerCtx.getHandler().sendMessage(m);
            }
        }

        void cancelFlingAnimation() {
            if (DEBUG) {
                Slog.i(LOG_TAG, "cancelFlingAnimation()");
            }
            if (Thread.currentThread().getId() == mMainThreadId) {
                mSpecAnimationBridge.cancelFlingAnimation();
            } else {
                mControllerCtx.getHandler().post(mSpecAnimationBridge::cancelFlingAnimation);
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

        @GuardedBy("mLock")
        void onMagnificationChangedLocked() {
            final float scale = getScale();
            final float centerX = getCenterX();
            final float centerY = getCenterY();
            final MagnificationConfig config = new MagnificationConfig.Builder()
                    .setMode(MAGNIFICATION_MODE_FULLSCREEN)
                    .setActivated(mMagnificationActivated)
                    .setScale(scale)
                    .setCenterX(centerX)
                    .setCenterY(centerY).build();
            mMagnificationInfoChangedCallbacks.forEach(callback -> {
                callback.onFullScreenMagnificationChanged(mDisplayId,
                        mMagnificationRegion, config);
            });
            if (mUnregisterPending && !isActivated()) {
                unregister(mDeleteAfterUnregister);
            }

            if (isActivated()) {
                updateThumbnail(scale, centerX, centerY);
            } else {
                hideThumbnail();
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

        private DisplayMetrics getDisplayMetricsForId() {
            final DisplayMetrics outMetrics = new DisplayMetrics();
            final DisplayInfo displayInfo = mDisplayManagerInternal.getDisplayInfo(mDisplayId);
            if (displayInfo != null) {
                displayInfo.getLogicalMetrics(outMetrics,
                        CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null);
            } else {
                outMetrics.setToDefaults();
            }
            return outMetrics;
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
                // We offset an additional distance for a user to know the surrounding context.
                DisplayMetrics metrics = getDisplayMetricsForId();
                final float offsetViewportX = (float) magnifFrameInScreenCoords.width() / 4;
                final float offsetViewportY =
                        TypedValue.applyDimension(COMPLEX_UNIT_DIP, 10, metrics);

                if (right - left > magnifFrameInScreenCoords.width()) {
                    final int direction = TextUtils
                            .getLayoutDirectionFromLocale(Locale.getDefault());
                    if (direction == View.LAYOUT_DIRECTION_LTR) {
                        scrollX = left - magnifFrameInScreenCoords.left;
                    } else {
                        scrollX = right - magnifFrameInScreenCoords.right;
                    }
                } else if (left < magnifFrameInScreenCoords.left) {
                    scrollX = left - magnifFrameInScreenCoords.left - offsetViewportX;
                } else if (right > magnifFrameInScreenCoords.right) {
                    scrollX = right - magnifFrameInScreenCoords.right + offsetViewportX;
                } else {
                    scrollX = 0;
                }

                if (bottom - top > magnifFrameInScreenCoords.height()) {
                    scrollY = top - magnifFrameInScreenCoords.top;
                } else if (top < magnifFrameInScreenCoords.top) {
                    scrollY = top - magnifFrameInScreenCoords.top - offsetViewportY;
                } else if (bottom > magnifFrameInScreenCoords.bottom) {
                    scrollY = bottom - magnifFrameInScreenCoords.bottom + offsetViewportY;
                } else {
                    scrollY = 0;
                }

                final float scale = getScale();
                offsetMagnifiedRegion(scrollX * scale, scrollY * scale, INVALID_SERVICE_ID);
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
        private boolean setActivated(boolean activated) {
            if (DEBUG) {
                Slog.i(LOG_TAG, "setActivated(activated = " + activated + ")");
            }

            final boolean changed = (mMagnificationActivated != activated);

            if (changed) {
                mMagnificationActivated = activated;
                mMagnificationInfoChangedCallbacks.forEach(callback -> {
                    callback.onFullScreenMagnificationActivationState(
                            mDisplayId, mMagnificationActivated);
                });
                mControllerCtx.getWindowManager().setForceShowMagnifiableBounds(
                        mDisplayId, activated);
            }

            return changed;
        }

        /**
         * Directly Zooms out the scale to 1f with animating the transition. This method is
         * triggered only by service automatically, such as when user context changed.
         */
        void zoomOutFromService() {
            setScaleAndCenter(1.0f, Float.NaN, Float.NaN,
                    transformToStubCallback(true),
                    AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);
            mZoomedOutFromService = true;
        }

        /**
         * Whether the zooming out is triggered by {@link #zoomOutFromService}.
         */
        boolean isZoomedOutFromService() {
            return mZoomedOutFromService;
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
            final boolean changed = isActivated();
            setActivated(false);
            if (changed) {
                spec.clear();
                onMagnificationChangedLocked();
            }
            mIdOfLastServiceToMagnify = INVALID_SERVICE_ID;
            sendSpecToAnimation(spec, animationCallback);

            hideThumbnail();

            return changed;
        }

        @GuardedBy("mLock")
        boolean setScale(float scale, float pivotX, float pivotY,
                boolean animate, int id) {
            if (!mRegistered) {
                return false;
            }
            // Constrain scale immediately for use in the pivot calculations.
            scale = MagnificationScaleProvider.constrainScale(scale);

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
            boolean changed = setActivated(true);
            changed |= updateMagnificationSpecLocked(scale, centerX, centerY);
            sendSpecToAnimation(mCurrentMagnificationSpec, animationCallback);
            if (isActivated() && (id != INVALID_SERVICE_ID)) {
                mIdOfLastServiceToMagnify = id;
                mMagnificationInfoChangedCallbacks.forEach(callback -> {
                    callback.onRequestMagnificationSpec(mDisplayId,
                            mIdOfLastServiceToMagnify);
                });
            }
            // the zoom scale would be changed so we reset the flag
            mZoomedOutFromService = false;
            return changed;
        }

        @GuardedBy("mLock")
        void updateThumbnail(float scale, float centerX, float centerY) {
            if (mMagnificationThumbnail != null) {
                mMagnificationThumbnail.updateThumbnail(scale, centerX, centerY);
            }
        }

        @GuardedBy("mLock")
        void refreshThumbnail() {
            if (mMagnificationThumbnail != null) {
                mMagnificationThumbnail.setThumbnailBounds(
                        mMagnificationBounds,
                        getScale(),
                        getCenterX(),
                        getCenterY()
                );
            }
        }

        @GuardedBy("mLock")
        void hideThumbnail() {
            if (mMagnificationThumbnail != null) {
                mMagnificationThumbnail.hideThumbnail();
            }
        }

        @GuardedBy("mLock")
        void createThumbnailIfSupported() {
            if (mMagnificationThumbnail == null) {
                mMagnificationThumbnail = mThumbnailSupplier.get();
                // We call refreshThumbnail when the thumbnail is just created to set current
                // magnification bounds to thumbnail. It to prevent the thumbnail size has not yet
                // updated properly and thus shows with huge size. (b/276314641)
                refreshThumbnail();
            }
        }

        @GuardedBy("mLock")
        void destroyThumbnail() {
            if (mMagnificationThumbnail != null) {
                hideThumbnail();
                mMagnificationThumbnail = null;
            }
        }

        void onThumbnailFeatureFlagChanged() {
            synchronized (mLock) {
                destroyThumbnail();
                createThumbnailIfSupported();
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

            final float normScale = MagnificationScaleProvider.constrainScale(scale);
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
            if (id != INVALID_SERVICE_ID) {
                mIdOfLastServiceToMagnify = id;
            }
            sendSpecToAnimation(mCurrentMagnificationSpec, null);
        }

        @GuardedBy("mLock")
        void startFling(float xPixelsPerSecond, float yPixelsPerSecond, int id) {
            if (!mRegistered) {
                return;
            }
            if (!isActivated()) {
                return;
            }

            if (id != INVALID_SERVICE_ID) {
                mIdOfLastServiceToMagnify = id;
            }

            startFlingAnimation(
                    xPixelsPerSecond,
                    yPixelsPerSecond,
                    new MagnificationAnimationCallback() {
                        @Override
                        public void onResult(boolean success) {
                            // never called
                        }

                        @Override
                        public void onResult(boolean success, MagnificationSpec lastSpecSent) {
                            if (DEBUG) {
                                Slog.i(
                                        LOG_TAG,
                                        "startFlingAnimation finished( "
                                                + success
                                                + " = "
                                                + lastSpecSent.offsetX
                                                + ", "
                                                + lastSpecSent.offsetY
                                                + ")");
                            }
                            synchronized (mLock) {
                                mCurrentMagnificationSpec.setTo(lastSpecSent);
                                onMagnificationChangedLocked();
                            }
                        }
                    });
        }


        @GuardedBy("mLock")
        void cancelFling(int id) {
            if (!mRegistered) {
                return;
            }

            if (id != INVALID_SERVICE_ID) {
                mIdOfLastServiceToMagnify = id;
            }

            cancelFlingAnimation();
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
            @NonNull AccessibilityTraceManager traceManager, @NonNull Object lock,
            @NonNull MagnificationInfoChangedCallback magnificationInfoChangedCallback,
            @NonNull MagnificationScaleProvider scaleProvider,
            @NonNull Executor backgroundExecutor) {
        this(
                new ControllerContext(
                        context,
                        traceManager,
                        LocalServices.getService(WindowManagerInternal.class),
                        new Handler(context.getMainLooper()),
                        context.getResources().getInteger(R.integer.config_longAnimTime)),
                lock,
                magnificationInfoChangedCallback,
                scaleProvider,
                /* thumbnailSupplier= */ null,
                backgroundExecutor,
                () -> new Scroller(context),
                TimeAnimator::new);
    }

    /** Constructor for tests */
    @VisibleForTesting
    public FullScreenMagnificationController(
            @NonNull ControllerContext ctx,
            @NonNull Object lock,
            @NonNull MagnificationInfoChangedCallback magnificationInfoChangedCallback,
            @NonNull MagnificationScaleProvider scaleProvider,
            Supplier<MagnificationThumbnail> thumbnailSupplier,
            @NonNull Executor backgroundExecutor,
            Supplier<Scroller> scrollerSupplier,
            Supplier<TimeAnimator> timeAnimatorSupplier) {
        mControllerCtx = ctx;
        mLock = lock;
        mScrollerSupplier = scrollerSupplier;
        mTimeAnimatorSupplier = timeAnimatorSupplier;
        mMainThreadId = mControllerCtx.getContext().getMainLooper().getThread().getId();
        mScreenStateObserver = new ScreenStateObserver(mControllerCtx.getContext(), this);
        addInfoChangedCallback(magnificationInfoChangedCallback);
        mScaleProvider = scaleProvider;
        mDisplayManagerInternal = LocalServices.getService(DisplayManagerInternal.class);
        mMagnificationThumbnailFeatureFlag = new MagnificationThumbnailFeatureFlag();
        mMagnificationThumbnailFeatureFlag.addOnChangedListener(
                backgroundExecutor, this::onMagnificationThumbnailFeatureFlagChanged);
        if (thumbnailSupplier != null) {
            mThumbnailSupplier = thumbnailSupplier;
        } else {
            mThumbnailSupplier = () -> {
                if (mMagnificationThumbnailFeatureFlag.isFeatureFlagEnabled()) {
                    return new MagnificationThumbnail(
                            ctx.getContext(),
                            ctx.getContext().getSystemService(WindowManager.class),
                            new Handler(ctx.getContext().getMainLooper())
                    );
                }
                return null;
            };
        }
    }

    private void onMagnificationThumbnailFeatureFlagChanged() {
        synchronized (mLock) {
            for (int i = 0; i < mDisplays.size(); i++) {
                onMagnificationThumbnailFeatureFlagChanged(mDisplays.keyAt(i));
            }
        }
    }

    private void onMagnificationThumbnailFeatureFlagChanged(int displayId) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return;
            }
            display.onThumbnailFeatureFlagChanged();
        }
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

    @Override
    public void onRectangleOnScreenRequested(int displayId, int left, int top, int right,
            int bottom) {
        synchronized (mLock) {
            if (!mMagnificationFollowTypingEnabled) {
                return;
            }
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return;
            }
            if (!display.isActivated()) {
                return;
            }
            final Rect magnifiedRegionBounds = mTempRect;
            display.getMagnifiedFrameInContentCoordsLocked(magnifiedRegionBounds);
            if (magnifiedRegionBounds.contains(left, top, right, bottom)) {
                return;
            }
            display.onRectangleOnScreenRequested(left, top, right, bottom);
        }
    }

    void setMagnificationFollowTypingEnabled(boolean enabled) {
        mMagnificationFollowTypingEnabled = enabled;
    }

    boolean isMagnificationFollowTypingEnabled() {
        return mMagnificationFollowTypingEnabled;
    }

    void setAlwaysOnMagnificationEnabled(boolean enabled) {
        mAlwaysOnMagnificationEnabled = enabled;
    }

    boolean isAlwaysOnMagnificationEnabled() {
        return mAlwaysOnMagnificationEnabled;
    }

    /**
     * if the magnifier with given displayId is activated:
     * 1. if {@link #isAlwaysOnMagnificationEnabled()}, zoom the magnifier to 100%,
     * 2. otherwise, reset the magnification.
     *
     * @param displayId The logical display id.
     */
    void onUserContextChanged(int displayId) {
        synchronized (mLock) {
            if (!isActivated(displayId)) {
                return;
            }

            if (isAlwaysOnMagnificationEnabled()) {
                zoomOutFromService(displayId);
            } else {
                reset(displayId, true);
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
     * @return {@code true} if magnification is activated,
     *         {@code false} otherwise
     */
    public boolean isActivated(int displayId) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return false;
            }
            return display.isActivated();
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

    protected float getLastActivatedScale(int displayId) {
        return getScale(displayId);
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
     * Returns whether the user is at one of the edges (left, right, top, bottom)
     * of the magnification viewport
     *
     * @param displayId
     * @return if user is at the edge of the view
     */
    public boolean isAtEdge(int displayId) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return false;
            }
            return display.isAtEdge();
        }
    }

    /**
     * Returns whether the user is at the left edge of the viewport
     *
     * @param displayId
     * @return if user is at left edge of view
     */
    public boolean isAtLeftEdge(int displayId) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return false;
            }
            return display.isAtLeftEdge();
        }
    }

    /**
     * Returns whether the user is at the right edge of the viewport
     *
     * @param displayId
     * @return if user is at right edge of view
     */
    public boolean isAtRightEdge(int displayId) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return false;
            }
            return display.isAtRightEdge();
        }
    }

    /**
     * Returns whether the user is at the top edge of the viewport
     *
     * @param displayId
     * @return if user is at top edge of view
     */
    public boolean isAtTopEdge(int displayId) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return false;
            }
            return display.isAtTopEdge();
        }
    }

    /**
     * Returns whether the user is at the bottom edge of the viewport
     *
     * @param displayId
     * @return if user is at bottom edge of view
     */
    public boolean isAtBottomEdge(int displayId) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return false;
            }
            return display.isAtBottomEdge();
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
     * Call after a pan ends, if the velocity has passed the threshold, to start a fling animation.
     *
     * @param displayId The logical display id.
     * @param xPixelsPerSecond the velocity of the last pan gesture in the X direction, in current
     *     screen pixels per second.
     * @param yPixelsPerSecond the velocity of the last pan gesture in the Y direction, in current
     *     screen pixels per second.
     * @param id the ID of the service requesting the change
     */
    public void startFling(int displayId, float xPixelsPerSecond, float yPixelsPerSecond, int id) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return;
            }
            display.startFling(xPixelsPerSecond, yPixelsPerSecond, id);
        }
    }

    /**
     * Call to cancel the fling animation if it is running. Call this on any ACTION_DOWN event.
     *
     * @param displayId The logical display id.
     * @param id the ID of the service requesting the change
     */
    public void cancelFling(int displayId, int id) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null) {
                return;
            }
            display.cancelFling(id);
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
     * Persists the default display magnification scale to the current user's settings
     * <strong>if scale is >= {@link MagnificationConstants.PERSISTED_SCALE_MIN_VALUE}</strong>.
     * We assume if the scale is < {@link MagnificationConstants.PERSISTED_SCALE_MIN_VALUE}, there
     * will be no obvious magnification effect.
     */
    public void persistScale(int displayId) {
        final float scale = getScale(Display.DEFAULT_DISPLAY);
        if (scale < MagnificationConstants.PERSISTED_SCALE_MIN_VALUE) {
            return;
        }
        mScaleProvider.putScale(scale, displayId);
    }

    /**
     * Retrieves a previously persisted magnification scale from the current
     * user's settings.
     *
     * @return the previously persisted magnification scale, or the default
     *         scale if none is available
     */
    public float getPersistedScale(int displayId) {
        return MathUtils.constrain(mScaleProvider.getScale(displayId),
                MagnificationConstants.PERSISTED_SCALE_MIN_VALUE,
                MagnificationScaleProvider.MAX_SCALE);
    }

    /**
     * Directly Zooms out the scale to 1f with animating the transition. This method is
     * triggered only by service automatically, such as when user context changed.
     *
     * @param displayId The logical display id.
     */
    private void zoomOutFromService(int displayId) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null || !display.isActivated()) {
                return;
            }
            display.zoomOutFromService();
        }
    }

    /**
     * Whether the magnification is zoomed out by {@link #zoomOutFromService(int)}.
     *
     * @param displayId The logical display id.
     */
    public boolean isZoomedOutFromService(int displayId) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null || !display.isActivated()) {
                return false;
            }
            return display.isZoomedOutFromService();
        }
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
     * @return whether was {@link #isActivated(int)}  activated}
     */
    boolean resetIfNeeded(int displayId, boolean animate) {
        synchronized (mLock) {
            final DisplayMagnification display = mDisplays.get(displayId);
            if (display == null || !display.isActivated()) {
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
            if (display == null || !display.isActivated()
                    || connectionId != display.getIdOfLastServiceToMagnify()) {
                return false;
            }
            display.reset(true);
            return true;
        }
    }

    /**
     * Notifies that the IME window visibility changed.
     *
     * @param displayId the logical display id
     * @param shown {@code true} means the IME window shows on the screen. Otherwise it's
     *                           hidden.
     */
    void notifyImeWindowVisibilityChanged(int displayId, boolean shown) {
        synchronized (mLock) {
            mMagnificationInfoChangedCallbacks.forEach(callback -> {
                callback.onImeWindowVisibilityChanged(displayId, shown);
            });
        }
    }

    private void onScreenTurnedOff() {
        final Message m = PooledLambda.obtainMessage(
                FullScreenMagnificationController::resetAllIfNeeded, this, false);
        mControllerCtx.getHandler().sendMessage(m);
    }

    /**
     * Resets magnification on all displays.
     * @param animate reset the magnification with animation
     */
    void resetAllIfNeeded(boolean animate) {
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
        if (!display.isActivated()) {
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

    void addInfoChangedCallback(@NonNull MagnificationInfoChangedCallback callback) {
        synchronized (mLock) {
            mMagnificationInfoChangedCallbacks.add(callback);
        }
    }

    void removeInfoChangedCallback(@NonNull MagnificationInfoChangedCallback callback) {
        synchronized (mLock) {
            mMagnificationInfoChangedCallbacks.remove(callback);
        }
    }

    private boolean traceEnabled() {
        return mControllerCtx.getTraceManager().isA11yTracingEnabledForTypes(
                FLAGS_WINDOW_MANAGER_INTERNAL);
    }

    private void logTrace(String methodName, String params) {
        mControllerCtx.getTraceManager().logTrace(
                "WindowManagerInternal." + methodName, FLAGS_WINDOW_MANAGER_INTERNAL, params);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("MagnificationController[");
        builder.append(", mDisplays=").append(mDisplays);
        builder.append(", mScaleProvider=").append(mScaleProvider);
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

        private final Scroller mScroller;
        private final TimeAnimator mScrollAnimator;

        private SpecAnimationBridge(
                ControllerContext ctx,
                Object lock,
                int displayId,
                Supplier<Scroller> scrollerSupplier,
                Supplier<TimeAnimator> timeAnimatorSupplier) {
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

            if (Flags.fullscreenFlingGesture()) {
                mScroller = scrollerSupplier.get();
                mScrollAnimator = timeAnimatorSupplier.get();
                mScrollAnimator.addListener(this);
                mScrollAnimator.setTimeListener(
                        (animation, totalTime, deltaTime) -> {
                            synchronized (mLock) {
                                if (DEBUG) {
                                    Slog.v(
                                            LOG_TAG,
                                            "onScrollAnimationUpdate: "
                                                    + mEnabled + " : " + totalTime);
                                }

                                if (mEnabled) {
                                    if (!mScroller.computeScrollOffset()) {
                                        animation.end();
                                        return;
                                    }

                                    mEndMagnificationSpec.offsetX = mScroller.getCurrX();
                                    mEndMagnificationSpec.offsetY = mScroller.getCurrY();
                                    setMagnificationSpecLocked(mEndMagnificationSpec);
                                }
                            }
                        });
            } else {
                mScroller = null;
                mScrollAnimator = null;
            }
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
                        if (mControllerCtx.getTraceManager().isA11yTracingEnabledForTypes(
                                FLAGS_WINDOW_MANAGER_INTERNAL)) {
                            mControllerCtx.getTraceManager().logTrace(
                                    "WindowManagerInternal.setMagnificationSpec",
                                    FLAGS_WINDOW_MANAGER_INTERNAL,
                                    "displayID=" + mDisplayId + ";spec=" + mSentMagnificationSpec);
                        }
                        mControllerCtx.getWindowManager().setMagnificationSpec(
                                mDisplayId, mSentMagnificationSpec);
                    }
                }
            }
        }

        @MainThread
        void updateSentSpecMainThread(
                MagnificationSpec spec, MagnificationAnimationCallback animationCallback) {
            cancelAnimations();

            mAnimationCallback = animationCallback;
            // If the current and sent specs don't match, update the sent spec.
            synchronized (mLock) {
                final boolean changed = !mSentMagnificationSpec.equals(spec);
                if (DEBUG_SET_MAGNIFICATION_SPEC) {
                    Slog.d(
                            LOG_TAG,
                            "updateSentSpecMainThread: " + mEnabled + " : changed: " + changed);
                }
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

        @MainThread
        private void sendEndCallbackMainThread(boolean success) {
            if (mAnimationCallback != null) {
                if (DEBUG) {
                    Slog.d(LOG_TAG, "sendEndCallbackMainThread: " + success);
                }
                mAnimationCallback.onResult(success, mSentMagnificationSpec);
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
                if (mControllerCtx.getTraceManager().isA11yTracingEnabledForTypes(
                        FLAGS_WINDOW_MANAGER_INTERNAL)) {
                    mControllerCtx.getTraceManager().logTrace(
                            "WindowManagerInternal.setMagnificationSpec",
                            FLAGS_WINDOW_MANAGER_INTERNAL,
                            "displayID=" + mDisplayId + ";spec=" + mSentMagnificationSpec);
                }
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

        /**
         * Call after a pan ends, if the velocity has passed the threshold, to start a fling
         * animation.
         */
        @MainThread
        public void startFlingAnimation(
                float xPixelsPerSecond,
                float yPixelsPerSecond,
                float minX,
                float maxX,
                float minY,
                float maxY,
                MagnificationAnimationCallback animationCallback
        ) {
            if (!Flags.fullscreenFlingGesture()) {
                return;
            }
            cancelAnimations();

            mAnimationCallback = animationCallback;

            // We use this as a temp object to send updates every animation frame, so make sure it
            // matches the current spec before we start.
            mEndMagnificationSpec.setTo(mSentMagnificationSpec);

            if (DEBUG) {
                Slog.d(LOG_TAG, "startFlingAnimation: "
                        + "offsetX " + mSentMagnificationSpec.offsetX
                        + "offsetY " + mSentMagnificationSpec.offsetY
                        + "xPixelsPerSecond " + xPixelsPerSecond
                        + "yPixelsPerSecond " + yPixelsPerSecond
                        + "minX " + minX
                        + "maxX " + maxX
                        + "minY " + minY
                        + "maxY " + maxY
                );
            }

            mScroller.fling(
                    (int) mSentMagnificationSpec.offsetX,
                    (int) mSentMagnificationSpec.offsetY,
                    (int) xPixelsPerSecond,
                    (int) yPixelsPerSecond,
                    (int) minX,
                    (int) maxX,
                    (int) minY,
                    (int) maxY);

            mScrollAnimator.start();
        }

        @MainThread
        void cancelAnimations() {
            if (mValueAnimator.isRunning()) {
                mValueAnimator.cancel();
            }

            cancelFlingAnimation();
        }

        @MainThread
        void cancelFlingAnimation() {
            if (!Flags.fullscreenFlingGesture()) {
                return;
            }
            if (mScrollAnimator.isRunning()) {
                mScrollAnimator.cancel();
            }
            mScroller.forceFinished(true);
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
        private final AccessibilityTraceManager mTrace;
        private final WindowManagerInternal mWindowManager;
        private final Handler mHandler;
        private final Long mAnimationDuration;

        /**
         * Constructor for ControllerContext.
         */
        public ControllerContext(@NonNull Context context,
                @NonNull AccessibilityTraceManager traceManager,
                @NonNull WindowManagerInternal windowManager,
                @NonNull Handler handler,
                long animationDuration) {
            mContext = context;
            mTrace = traceManager;
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
         * @return AccessibilityTraceManager
         */
        @NonNull
        public AccessibilityTraceManager getTraceManager() {
            return mTrace;
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

    interface MagnificationInfoChangedCallback {

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
         *
         * @param displayId the logical display id
         * @param activated {@code true} if the magnification is activated, otherwise {@code false}.
         */
        void onFullScreenMagnificationActivationState(int displayId, boolean activated);

        /**
         * Called when the IME window visibility changed.
         *
         * @param displayId the logical display id
         * @param shown {@code true} means the IME window shows on the screen. Otherwise it's
         *                           hidden.
         */
        void onImeWindowVisibilityChanged(int displayId, boolean shown);

        /**
         * Called when the magnification spec changed.
         *
         * @param displayId The logical display id
         * @param region    The region of the screen currently active for magnification.
         *                  The returned region will be empty if the magnification is not active.
         * @param config    The magnification config. That has magnification mode, the new scale and
         *                  the new screen-relative center position
         */
        void onFullScreenMagnificationChanged(int displayId, @NonNull Region region,
                @NonNull MagnificationConfig config);
    }
}
