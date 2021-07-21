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

package com.android.server.accessibility.magnification;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.MathUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.accessibility.IWindowMagnificationConnection;
import android.view.accessibility.IWindowMagnificationConnectionCallback;
import android.view.accessibility.MagnificationAnimationCallback;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.server.LocalServices;
import com.android.server.statusbar.StatusBarManagerInternal;

/**
 * A class to manipulate window magnification through {@link WindowMagnificationConnectionWrapper}
 * create by {@link #setConnection(IWindowMagnificationConnection)}. To set the connection with
 * SysUI, call {@code StatusBarManagerInternal#requestWindowMagnificationConnection(boolean)}.
 */
public class WindowMagnificationManager implements
        PanningScalingHandler.MagnificationDelegate {

    private static final boolean DBG = false;

    private static final String TAG = "WindowMagnificationMgr";

    //Ensure the range has consistency with full screen.
    static final float MAX_SCALE = FullScreenMagnificationController.MAX_SCALE;
    static final float MIN_SCALE = FullScreenMagnificationController.MIN_SCALE;

    private final Object mLock = new Object();
    private final Context mContext;
    @VisibleForTesting
    @GuardedBy("mLock")
    @Nullable
    WindowMagnificationConnectionWrapper mConnectionWrapper;
    @GuardedBy("mLock")
    private ConnectionCallback mConnectionCallback;
    @GuardedBy("mLock")
    private SparseArray<WindowMagnifier> mWindowMagnifiers = new SparseArray<>();
    private int mUserId;

    private boolean mReceiverRegistered = false;
    @VisibleForTesting
    protected final BroadcastReceiver mScreenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int displayId = context.getDisplayId();
            removeMagnificationButton(displayId);
            disableWindowMagnification(displayId, false);
        }
    };

    /**
     * Callback to handle magnification actions from system UI.
     */
    public interface Callback {

        /**
         * Called when the accessibility action of scale requests to be performed.
         * It is invoked from System UI. And the action is provided by the mirror window.
         *
         * @param displayId The logical display id.
         * @param scale the target scale, or {@link Float#NaN} to leave unchanged
         */
        void onPerformScaleAction(int displayId, float scale);

        /**
         * Called when the accessibility action is performed.
         *
         * @param displayId The logical display id.
         */
        void onAccessibilityActionPerformed(int displayId);

        /**
         * Called when the state of the magnification activation is changed.
         *
         * @param displayId The logical display id.
         * @param activated {@code true} if the magnification is activated, otherwise {@code false}.
         */
        void onWindowMagnificationActivationState(int displayId, boolean activated);
    }

    private final Callback mCallback;

    public WindowMagnificationManager(Context context, int userId, @NonNull Callback callback) {
        mContext = context;
        mUserId = userId;
        mCallback = callback;
    }

    /**
     * Sets {@link IWindowMagnificationConnection}.
     *
     * @param connection {@link IWindowMagnificationConnection}
     */
    public void setConnection(@Nullable IWindowMagnificationConnection connection) {
        synchronized (mLock) {
            // Reset connectionWrapper.
            if (mConnectionWrapper != null) {
                mConnectionWrapper.setConnectionCallback(null);
                if (mConnectionCallback != null) {
                    mConnectionCallback.mExpiredDeathRecipient = true;
                }
                mConnectionWrapper.unlinkToDeath(mConnectionCallback);
                mConnectionWrapper = null;
            }
            if (connection != null) {
                mConnectionWrapper = new WindowMagnificationConnectionWrapper(connection);
            }

            if (mConnectionWrapper != null) {
                try {
                    mConnectionCallback = new ConnectionCallback();
                    mConnectionWrapper.linkToDeath(mConnectionCallback);
                    mConnectionWrapper.setConnectionCallback(mConnectionCallback);
                } catch (RemoteException e) {
                    Slog.e(TAG, "setConnection failed", e);
                    mConnectionWrapper = null;
                }
            }
        }
    }

    /**
     * Sets the currently active user ID.
     *
     * @param userId the currently active user ID
     */
    public void setUserId(int userId) {
        mUserId = userId;
    }

    /**
     * @return {@code true} if {@link IWindowMagnificationConnection} is available
     */
    public boolean isConnected() {
        synchronized (mLock) {
            return mConnectionWrapper != null;
        }
    }

    /**
     * Requests {@link IWindowMagnificationConnection} through
     * {@link StatusBarManagerInternal#requestWindowMagnificationConnection(boolean)} and
     * destroys all window magnifications if necessary.
     *
     * @param connect {@code true} if needs connection, otherwise set the connection to null and
     *                destroy all window magnifications.
     * @return {@code true} if {@link IWindowMagnificationConnection} state is going to change.
     */
    public boolean requestConnection(boolean connect) {
        synchronized (mLock) {
            if (connect == isConnected()) {
                return false;
            }
            if (connect) {
                final IntentFilter intentFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
                if (!mReceiverRegistered) {
                    mContext.registerReceiver(mScreenStateReceiver, intentFilter);
                    mReceiverRegistered = true;
                }
            } else {
                disableAllWindowMagnifiers();
                if (mReceiverRegistered) {
                    mContext.unregisterReceiver(mScreenStateReceiver);
                    mReceiverRegistered = false;
                }
            }
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            final StatusBarManagerInternal service = LocalServices.getService(
                    StatusBarManagerInternal.class);
            service.requestWindowMagnificationConnection(connect);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return true;
    }

    @GuardedBy("mLock")
    private void disableAllWindowMagnifiers() {
        for (int i = 0; i < mWindowMagnifiers.size(); i++) {
            final WindowMagnifier magnifier = mWindowMagnifiers.valueAt(i);
            magnifier.disableWindowMagnificationInternal(null);
        }
        mWindowMagnifiers.clear();
    }

    private void resetWindowMagnifiers() {
        synchronized (mLock) {
            for (int i = 0; i < mWindowMagnifiers.size(); i++) {
                WindowMagnifier magnifier = mWindowMagnifiers.valueAt(i);
                magnifier.reset();
            }
        }
    }

    @Override
    public boolean processScroll(int displayId, float distanceX, float distanceY) {
        moveWindowMagnification(displayId, -distanceX, -distanceY);
        return /* event consumed: */ true;
    }

    /**
     * Scales the magnified region on the specified display if window magnification is initiated.
     *
     * @param displayId The logical display id.
     * @param scale The target scale, must be >= 1
     */
    @Override
    public void setScale(int displayId, float scale) {
        synchronized (mLock) {
            WindowMagnifier magnifier = mWindowMagnifiers.get(displayId);
            if (magnifier == null) {
                return;
            }
            magnifier.setScale(scale);
        }
    }

    /**
     * Enables window magnification with specified center and scale on the given display and
     * animating the transition.
     *
     * @param displayId The logical display id.
     * @param scale The target scale, must be >= 1.
     * @param centerX The screen-relative X coordinate around which to center,
     *                or {@link Float#NaN} to leave unchanged.
     * @param centerY The screen-relative Y coordinate around which to center,
     *                or {@link Float#NaN} to leave unchanged.
     */
    void enableWindowMagnification(int displayId, float scale, float centerX, float centerY) {
        enableWindowMagnification(displayId, scale, centerX, centerY, null);
    }

    /**
     * Enables window magnification with specified center and scale on the specified display and
     * animating the transition.
     *
     * @param displayId The logical display id.
     * @param scale The target scale, must be >= 1.
     * @param centerX The screen-relative X coordinate around which to center,
     *                or {@link Float#NaN} to leave unchanged.
     * @param centerY The screen-relative Y coordinate around which to center,
     *                or {@link Float#NaN} to leave unchanged.
     * @param animationCallback Called when the animation result is valid.
     */
    void enableWindowMagnification(int displayId, float scale, float centerX, float centerY,
            @Nullable MagnificationAnimationCallback animationCallback) {
        final boolean enabled;
        synchronized (mLock) {
            if (mConnectionWrapper == null) {
                return;
            }
            WindowMagnifier magnifier = mWindowMagnifiers.get(displayId);
            if (magnifier == null) {
                magnifier = createWindowMagnifier(displayId);
            }
            enabled = magnifier.enableWindowMagnificationInternal(scale, centerX, centerY,
                    animationCallback);
        }

        if (enabled) {
            mCallback.onWindowMagnificationActivationState(displayId, true);
        }
    }

    /**
     * Disables window magnification on the given display.
     *
     * @param displayId The logical display id.
     * @param clear {@true} Clears the state of window magnification.
     */
    void disableWindowMagnification(int displayId, boolean clear) {
        disableWindowMagnification(displayId, clear, null);
    }

    /**
     * Disables window magnification on the specified display and animating the transition.
     *
     * @param displayId The logical display id.
     * @param clear {@true} Clears the state of window magnification.
     * @param animationCallback Called when the animation result is valid.
     */
    void disableWindowMagnification(int displayId, boolean clear,
            MagnificationAnimationCallback animationCallback) {
        final boolean disabled;
        synchronized (mLock) {
            WindowMagnifier magnifier = mWindowMagnifiers.get(displayId);
            if (magnifier == null || mConnectionWrapper == null) {
                return;
            }
            disabled = magnifier.disableWindowMagnificationInternal(animationCallback);
            if (clear) {
                mWindowMagnifiers.delete(displayId);
            }
        }

        if (disabled) {
            mCallback.onWindowMagnificationActivationState(displayId, false);
        }
    }

    /**
     * Calculates the number of fingers in the window.
     *
     * @param displayId The logical display id.
     * @param motionEvent The motion event
     * @return the number of fingers in the window.
     */
    int pointersInWindow(int displayId, MotionEvent motionEvent) {
        synchronized (mLock) {
            WindowMagnifier magnifier = mWindowMagnifiers.get(displayId);
            if (magnifier == null) {
                return 0;
            }
            return magnifier.pointersInWindow(motionEvent);
        }
    }

    /**
     * Indicates whether window magnification is enabled on specified display.
     *
     * @param displayId The logical display id.
     * @return {@code true} if the window magnification is enabled.
     */
    @VisibleForTesting
    public boolean isWindowMagnifierEnabled(int displayId) {
        synchronized (mLock) {
            WindowMagnifier magnifier = mWindowMagnifiers.get(displayId);
            if (magnifier == null) {
                return false;
            }
            return magnifier.isEnabled();
        }
    }

    /**
     * Retrieves a previously persisted magnification scale from the current
     * user's settings.
     *
     * @return the previously persisted magnification scale, or the default
     *         scale if none is available
     */
    float getPersistedScale() {
        return Settings.Secure.getFloatForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE,
                MIN_SCALE, mUserId);
    }

    /**
     * Persists the default display magnification scale to the current user's settings.
     */
    void persistScale(int displayId) {

        float scale = getScale(displayId);
        if (scale != 1.0f) {
            BackgroundThread.getHandler().post(() -> {
                Settings.Secure.putFloatForUser(mContext.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE, scale, mUserId);
            });
        }
    }

    /**
     * Returns the magnification scale.
     *
     * @param displayId The logical display id.
     * @return the scale
     */
    public float getScale(int displayId) {
        synchronized (mLock) {
            WindowMagnifier magnifier = mWindowMagnifiers.get(displayId);
            if (magnifier == null) {
                return 1.0f;
            }
            return magnifier.getScale();
        }
    }

    /**
     * Moves window magnification on the specified display with the specified offset.
     *
     * @param displayId The logical display id.
     * @param offsetX the amount in pixels to offset the region in the X direction, in current
     *                screen pixels.
     * @param offsetY the amount in pixels to offset the region in the Y direction, in current
     *                screen pixels.
     */
    void moveWindowMagnification(int displayId, float offsetX, float offsetY) {
        synchronized (mLock) {
            WindowMagnifier magnifier = mWindowMagnifiers.get(displayId);
            if (magnifier == null) {
                return;
            }
            magnifier.move(offsetX, offsetY);
        }
    }

    /**
     * Requests System UI show magnification mode button UI on the specified display.
     *
     * @param displayId The logical display id.
     * @param magnificationMode the current magnification mode.
     * @return {@code true} if the event was handled, {@code false} otherwise
     */
    public boolean showMagnificationButton(int displayId, int magnificationMode) {
        return mConnectionWrapper != null && mConnectionWrapper.showMagnificationButton(
                displayId, magnificationMode);
    }

    /**
     * Requests System UI remove magnification mode button UI on the specified display.
     *
     * @param displayId The logical display id.
     * @return {@code true} if the event was handled, {@code false} otherwise
     */
    public boolean removeMagnificationButton(int displayId) {
        return mConnectionWrapper != null && mConnectionWrapper.removeMagnificationButton(
                displayId);
    }

    /**
     * Returns the screen-relative X coordinate of the center of the magnified bounds.
     *
     * @param displayId The logical display id
     * @return the X coordinate. {@link Float#NaN} if the window magnification is not enabled.
     */
    float getCenterX(int displayId) {
        synchronized (mLock) {
            WindowMagnifier magnifier = mWindowMagnifiers.get(displayId);
            if (magnifier == null) {
                return Float.NaN;
            }
            return magnifier.getCenterX();
        }
    }

    /**
     * Returns the screen-relative Y coordinate of the center of the magnified bounds.
     *
     * @param displayId The logical display id
     * @return the Y coordinate. {@link Float#NaN} if the window magnification is not enabled.
     */
    float getCenterY(int displayId) {
        synchronized (mLock) {
            WindowMagnifier magnifier = mWindowMagnifiers.get(displayId);
            if (magnifier == null) {
                return Float.NaN;
            }
            return magnifier.getCenterY();
        }
    }

    /**
     * Creates the windowMagnifier based on the specified display and stores it.
     *
     * @param displayId logical display id.
     */
    @GuardedBy("mLock")
    private WindowMagnifier createWindowMagnifier(int displayId) {
        final WindowMagnifier magnifier = new WindowMagnifier(displayId, this);
        mWindowMagnifiers.put(displayId, magnifier);
        return magnifier;
    }

    /**
     * Removes the window magnifier with given id.
     *
     * @param displayId The logical display id.
     */
    void onDisplayRemoved(int displayId) {
        disableWindowMagnification(displayId, true);
    }

    private class ConnectionCallback extends IWindowMagnificationConnectionCallback.Stub implements
            IBinder.DeathRecipient {
        private boolean mExpiredDeathRecipient = false;

        @Override
        public void onWindowMagnifierBoundsChanged(int displayId, Rect bounds) {
            synchronized (mLock) {
                WindowMagnifier magnifier = mWindowMagnifiers.get(displayId);
                if (magnifier == null) {
                    magnifier = createWindowMagnifier(displayId);
                }
                if (DBG) {
                    Slog.i(TAG,
                            "onWindowMagnifierBoundsChanged -" + displayId + " bounds = " + bounds);
                }
                magnifier.setMagnifierLocation(bounds);
            }
        }

        @Override
        public void onChangeMagnificationMode(int displayId, int magnificationMode)
                throws RemoteException {
            //TODO: Uses this method to change the magnification mode on non-default display.
        }

        @Override
        public void onSourceBoundsChanged(int displayId, Rect sourceBounds) {
            synchronized (mLock) {
                WindowMagnifier magnifier = mWindowMagnifiers.get(displayId);
                if (magnifier == null) {
                    magnifier = createWindowMagnifier(displayId);
                }
                magnifier.onSourceBoundsChanged(sourceBounds);
            }
        }

        @Override
        public void onPerformScaleAction(int displayId, float scale) {
            mCallback.onPerformScaleAction(displayId, scale);
        }

        @Override
        public void onAccessibilityActionPerformed(int displayId) {
            mCallback.onAccessibilityActionPerformed(displayId);
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                Slog.w(TAG, "binderDied DeathRecipient :" + mExpiredDeathRecipient);
                if (mExpiredDeathRecipient) {
                    return;
                }
                mConnectionWrapper.unlinkToDeath(this);
                mConnectionWrapper = null;
                mConnectionCallback = null;
                resetWindowMagnifiers();
            }
        }
    }

    /**
     * A class manipulates window magnification per display and contains the magnification
     * information.
     */
    private static class WindowMagnifier {

        private final int mDisplayId;
        private float mScale = MIN_SCALE;
        private boolean mEnabled;

        private final WindowMagnificationManager mWindowMagnificationManager;
        // Records the bounds of window magnification.
        private final Rect mBounds = new Rect();
        // The magnified bounds on the screen.
        private final Rect mSourceBounds = new Rect();

        WindowMagnifier(int displayId, WindowMagnificationManager windowMagnificationManager) {
            mDisplayId = displayId;
            mWindowMagnificationManager = windowMagnificationManager;
        }

        @GuardedBy("mLock")
        boolean enableWindowMagnificationInternal(float scale, float centerX, float centerY,
                @Nullable MagnificationAnimationCallback animationCallback) {
            if (mEnabled) {
                return false;
            }
            final float normScale = MathUtils.constrain(scale, MIN_SCALE, MAX_SCALE);
            if (mWindowMagnificationManager.enableWindowMagnificationInternal(mDisplayId, normScale,
                    centerX, centerY, animationCallback)) {
                mScale = normScale;
                mEnabled = true;

                return true;
            }
            return false;
        }

        @GuardedBy("mLock")
        boolean disableWindowMagnificationInternal(
                @Nullable MagnificationAnimationCallback animationResultCallback) {
            if (!mEnabled) {
                return false;
            }
            if (mWindowMagnificationManager.disableWindowMagnificationInternal(
                    mDisplayId, animationResultCallback)) {
                mEnabled = false;

                return true;
            }
            return false;
        }

        @GuardedBy("mLock")
        void setScale(float scale) {
            if (!mEnabled) {
                return;
            }
            final float normScale = MathUtils.constrain(scale, MIN_SCALE, MAX_SCALE);
            if (Float.compare(mScale, normScale) != 0
                    && mWindowMagnificationManager.setScaleInternal(mDisplayId, scale)) {
                mScale = normScale;
            }
        }

        @GuardedBy("mLock")
        float getScale() {
            return mScale;
        }

        @GuardedBy("mLock")
        void setMagnifierLocation(Rect rect) {
            mBounds.set(rect);
        }

        @GuardedBy("mLock")
        int pointersInWindow(MotionEvent motionEvent) {
            int count = 0;
            final int pointerCount = motionEvent.getPointerCount();
            for (int i = 0; i < pointerCount; i++) {
                final float x = motionEvent.getX(i);
                final float y = motionEvent.getY(i);
                if (mBounds.contains((int) x, (int) y)) {
                    count++;
                }
            }
            return count;
        }

        @GuardedBy("mLock")
        boolean isEnabled() {
            return mEnabled;
        }

        @GuardedBy("mLock")
        void move(float offsetX, float offsetY) {
            mWindowMagnificationManager.moveWindowMagnifierInternal(mDisplayId, offsetX, offsetY);
        }

        @GuardedBy("mLock")
        void reset() {
            mEnabled = false;
        }

        @GuardedBy("mLock")
        public void onSourceBoundsChanged(Rect sourceBounds) {
            mSourceBounds.set(sourceBounds);
        }

        @GuardedBy("mLock")
        float getCenterX() {
            return mEnabled ? mSourceBounds.exactCenterX() : Float.NaN;
        }

        @GuardedBy("mLock")
        float getCenterY() {
            return mEnabled ? mSourceBounds.exactCenterY() : Float.NaN;
        }
    }

    private boolean enableWindowMagnificationInternal(int displayId, float scale, float centerX,
            float centerY, MagnificationAnimationCallback animationCallback) {
        return mConnectionWrapper != null && mConnectionWrapper.enableWindowMagnification(
                displayId, scale, centerX, centerY, animationCallback);
    }

    private boolean setScaleInternal(int displayId, float scale) {
        return mConnectionWrapper != null && mConnectionWrapper.setScale(displayId, scale);
    }

    private boolean disableWindowMagnificationInternal(int displayId,
            MagnificationAnimationCallback animationCallback) {
        return mConnectionWrapper != null && mConnectionWrapper.disableWindowMagnification(
                displayId, animationCallback);
    }

    private boolean moveWindowMagnifierInternal(int displayId, float offsetX, float offsetY) {
        return mConnectionWrapper != null && mConnectionWrapper.moveWindowMagnifier(
                displayId, offsetX, offsetY);
    }
}
