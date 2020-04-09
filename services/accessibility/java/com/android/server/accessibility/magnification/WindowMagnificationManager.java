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

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.MathUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.accessibility.IWindowMagnificationConnection;
import android.view.accessibility.IWindowMagnificationConnectionCallback;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.server.accessibility.MagnificationController;

/**
 * A class to manipulate window magnification through {@link WindowMagnificationConnectionWrapper}
 * create by {@link #setConnection(IWindowMagnificationConnection)}. To set the connection with
 * SysUI, call {@code StatusBarManagerInternal#requestWindowMagnificationConnection(boolean)}.
 */
public final class WindowMagnificationManager implements
        PanningScalingHandler.MagnificationDelegate {

    private static final boolean DBG = false;

    private static final String TAG = "WindowMagnificationMgr";

    //Ensure the range has consistency with full screen.
    static final float MAX_SCALE = MagnificationController.MAX_SCALE;
    static final float MIN_SCALE = MagnificationController.MIN_SCALE;

    private final Object mLock = new Object();;
    private final Context mContext;
    @VisibleForTesting
    @GuardedBy("mLock")
    @Nullable WindowMagnificationConnectionWrapper mConnectionWrapper;
    @GuardedBy("mLock")
    private ConnectionCallback mConnectionCallback;
    @GuardedBy("mLock")
    private SparseArray<WindowMagnifier> mWindowMagnifiers = new SparseArray<>();
    private int mUserId;

    public WindowMagnificationManager(Context context, int userId) {
        mContext = context;
        mUserId = userId;
    }

    /**
     * Sets {@link IWindowMagnificationConnection}.
     * @param connection {@link IWindowMagnificationConnection}
     */
    public void setConnection(@Nullable IWindowMagnificationConnection connection) {
        synchronized (mLock) {
            //Reset connectionWrapper.
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
     *
     * @return {@code true} if {@link IWindowMagnificationConnection} is available
     */
    public boolean isConnected() {
        return mConnectionWrapper != null;
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
        moveWindowMagnifier(displayId, -distanceX, -distanceY);
        return /* event consumed: */ true;
    }

    /**
     * Scales the magnified region on the specified display if the window magnifier is enabled.
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
     * Enables the window magnifier with specified center and scale on the specified display.
     *  @param displayId The logical display id.
     * @param scale The target scale, must be >= 1.
     * @param centerX The screen-relative X coordinate around which to center,
     *                or {@link Float#NaN} to leave unchanged.
     * @param centerY The screen-relative Y coordinate around which to center,
     *                or {@link Float#NaN} to leave unchanged.
     */
    void enableWindowMagnifier(int displayId, float scale, float centerX, float centerY) {
        synchronized (mLock) {
            WindowMagnifier magnifier = mWindowMagnifiers.get(displayId);
            if (magnifier == null) {
                magnifier = createWindowMagnifier(displayId);
            }
            magnifier.enable(scale, centerX, centerY);
        }
    }

    /**
     * Disables the window magnifier on the specified display.
     *
     * @param displayId The logical display id.
     * @param clear {@true} Clears the state of the window magnifier
     */
    void disableWindowMagnifier(int displayId, boolean clear) {
        synchronized (mLock) {
            WindowMagnifier magnifier = mWindowMagnifiers.get(displayId);
            if (magnifier == null) {
                return;
            }
            magnifier.disable();
            if (clear) {
                mWindowMagnifiers.delete(displayId);
            }
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
     * Indicates whether this window magnifier is enabled on specified display.
     *
     * @param displayId The logical display id.
     * @return {@code true} if the window magnifier is enabled.
     */
    boolean isWindowMagnifierEnabled(int displayId) {
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
     * Moves the window magnifier with specified offset.
     *
     * @param displayId The logical display id.
     * @param offsetX the amount in pixels to offset the region in the X direction, in current
     *                screen pixels.
     * @param offsetY the amount in pixels to offset the region in the Y direction, in current
     *                screen pixels.
     */
    void moveWindowMagnifier(int displayId, float offsetX, float offsetY) {
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
     * Creates the windowMagnifier based on the specified display and stores it.
     * @param displayId logical display id.
     */
    @GuardedBy("mLock")
    private WindowMagnifier createWindowMagnifier(int displayId) {
        final WindowMagnifier magnifier = new WindowMagnifier(displayId, this);
        mWindowMagnifiers.put(displayId, magnifier);
        return magnifier;
    }

    private class ConnectionCallback extends IWindowMagnificationConnectionCallback.Stub implements
            IBinder.DeathRecipient {
        private boolean mExpiredDeathRecipient = false;

        @Override
        public void onWindowMagnifierBoundsChanged(int displayId, Rect bounds) {
            synchronized (mLock) {
                WindowMagnifier magnifier = mWindowMagnifiers.get(displayId);
                if (magnifier == null) {
                    return;
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
        public void binderDied() {
            synchronized (mLock) {
                if (mExpiredDeathRecipient) {
                    Slog.w(TAG, "binderDied DeathRecipient is expired");
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
     * A class to  manipulate the window magnifier and contains the relevant information.
     */
    private static class WindowMagnifier {

        private final int mDisplayId;
        private float mScale = MIN_SCALE;
        private boolean mEnabled;

        private final WindowMagnificationManager mWindowMagnificationManager;
        //Records the bounds of window magnifier.
        private final Rect mBounds = new Rect();
        WindowMagnifier(int displayId, WindowMagnificationManager windowMagnificationManager) {
            mDisplayId = displayId;
            mWindowMagnificationManager = windowMagnificationManager;
        }

        @GuardedBy("mLock")
        void enable(float scale, float centerX, float centerY) {
            if (mEnabled) {
                return;
            }
            final float normScale = MathUtils.constrain(scale, MIN_SCALE, MAX_SCALE);
            if (mWindowMagnificationManager.enableWindowMagnification(mDisplayId, normScale,
                    centerX, centerY)) {
                mScale = normScale;
                mEnabled = true;
            }
        }

        @GuardedBy("mLock")
        void disable() {
            if (mEnabled && mWindowMagnificationManager.disableWindowMagnification(mDisplayId)) {
                mEnabled = false;
            }
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
    }

    private boolean enableWindowMagnification(int displayId, float scale, float centerX,
            float centerY) {
        return mConnectionWrapper != null && mConnectionWrapper.enableWindowMagnification(
                displayId, scale, centerX, centerY);
    }

    private boolean setScaleInternal(int displayId, float scale) {
        return mConnectionWrapper != null && mConnectionWrapper.setScale(displayId, scale);
    }

    private boolean disableWindowMagnification(int displayId) {
        return mConnectionWrapper != null && mConnectionWrapper.disableWindowMagnification(
                displayId);
    }

    private boolean moveWindowMagnifierInternal(int displayId, float offsetX, float offsetY) {
        return mConnectionWrapper != null && mConnectionWrapper.moveWindowMagnifier(
                displayId, offsetX, offsetY);
    }
}
