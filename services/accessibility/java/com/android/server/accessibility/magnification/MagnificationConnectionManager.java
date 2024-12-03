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

import static android.accessibilityservice.AccessibilityTrace.FLAGS_MAGNIFICATION_CONNECTION;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_MAGNIFICATION_CONNECTION_CALLBACK;
import static android.os.Build.HW_TIMEOUT_MULTIPLIER;
import static android.os.UserHandle.getCallingUserId;
import static android.view.accessibility.MagnificationAnimationCallback.STUB_ANIMATION_CALLBACK;

import static com.android.server.accessibility.AccessibilityManagerService.INVALID_SERVICE_ID;
import static com.android.server.accessibility.AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.MathUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.MotionEvent;
import android.view.accessibility.IMagnificationConnection;
import android.view.accessibility.IMagnificationConnectionCallback;
import android.view.accessibility.MagnificationAnimationCallback;

import com.android.internal.accessibility.common.MagnificationConstants;
import com.android.internal.accessibility.util.AccessibilityStatsLogUtils;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.accessibility.AccessibilityTraceManager;
import com.android.server.pm.UserManagerInternal;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

/**
 * A class to manipulate magnification through {@link MagnificationConnectionWrapper}
 * create by {@link #setConnection(IMagnificationConnection)}. To set the connection with
 * SysUI, call {@code StatusBarManagerInternal#requestMagnificationConnection(boolean)}.
 * The applied magnification scale is constrained by
 * {@link MagnificationScaleProvider#constrainScale(float)}
 */
public class MagnificationConnectionManager implements
        PanningScalingHandler.MagnificationDelegate,
        WindowManagerInternal.AccessibilityControllerInternal.UiChangesForAccessibilityCallbacks {

    private static final boolean DBG = false;

    private static final String TAG = "MagnificationConnectionManager";

    /**
     * Indicate that the magnification window is at the magnification center.
     */
    public static final int WINDOW_POSITION_AT_CENTER = 0;

    /**
     * Indicate that the magnification window is at the top-left side of the magnification
     * center. The offset is equal to a half of MirrorSurfaceView. So, the bottom-right corner
     * of the window is at the magnification center.
     */
    public static final int WINDOW_POSITION_AT_TOP_LEFT = 1;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "WINDOW_POSITION_AT_" }, value = {
            WINDOW_POSITION_AT_CENTER,
            WINDOW_POSITION_AT_TOP_LEFT
    })
    public @interface WindowPosition {}

    /** Magnification connection is connecting. */
    private static final int CONNECTING = 0;
    /** Magnification connection is connected. */
    private static final int CONNECTED = 1;
    /** Magnification connection is disconnecting. */
    private static final int DISCONNECTING = 2;
    /** Magnification connection is disconnected. */
    private static final int DISCONNECTED = 3;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"CONNECTION_STATE"}, value = {
            CONNECTING,
            CONNECTED,
            DISCONNECTING,
            DISCONNECTED
    })
    private @interface ConnectionState {
    }

    private static String connectionStateToString(@ConnectionState int state) {
        switch (state) {
            case CONNECTING: return "CONNECTING";
            case CONNECTED: return "CONNECTED";
            case DISCONNECTING: return "DISCONNECTING";
            case DISCONNECTED: return "DISCONNECTED";
            default:
                return "UNKNOWN:" + state;
        }
    }

    @ConnectionState
    private int mConnectionState = DISCONNECTED;

    private static final int WAIT_CONNECTION_TIMEOUT_MILLIS = 200 * HW_TIMEOUT_MULTIPLIER;

    private final Object mLock;
    private final Context mContext;
    @VisibleForTesting
    @GuardedBy("mLock")
    @Nullable
    MagnificationConnectionWrapper mConnectionWrapper;
    @GuardedBy("mLock")
    private ConnectionCallback mConnectionCallback;
    @GuardedBy("mLock")
    private SparseArray<WindowMagnifier> mWindowMagnifiers = new SparseArray<>();
    // Whether the following typing focus feature for magnification is enabled.
    private boolean mMagnificationFollowTypingEnabled = true;
    @GuardedBy("mLock")
    private final SparseBooleanArray mIsImeVisibleArray = new SparseBooleanArray();
    @GuardedBy("mLock")
    private final SparseArray<Float> mLastActivatedScale = new SparseArray<>();

    private boolean mReceiverRegistered = false;
    @VisibleForTesting
    protected final BroadcastReceiver mScreenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int displayId = context.getDisplayId();
            removeMagnificationButton(displayId);
            disableWindowMagnification(displayId, false, null);
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
         * @param updatePersistence whether the scale should be persisted
         */
        void onPerformScaleAction(int displayId, float scale, boolean updatePersistence);

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

        /**
         * Called when the magnification source bounds are changed.
         *
         * @param displayId The logical display id.
         * @param bounds    The magnified source bounds on the display.
         */
        void onSourceBoundsChanged(int displayId, Rect bounds);

        /**
         * Called from {@link IMagnificationConnection} to request changing the magnification
         * mode on the given display.
         *
         * @param displayId the logical display id
         * @param magnificationMode the target magnification mode
         */
        void onChangeMagnificationMode(int displayId, int magnificationMode);
    }

    private final Callback mCallback;
    private final AccessibilityTraceManager mTrace;
    private final MagnificationScaleProvider mScaleProvider;
    private final UserManagerInternal mUserManagerInternal;

    public MagnificationConnectionManager(Context context, Object lock, @NonNull Callback callback,
            AccessibilityTraceManager trace, MagnificationScaleProvider scaleProvider) {
        mContext = context;
        mLock = lock;
        mCallback = callback;
        mTrace = trace;
        mScaleProvider = scaleProvider;
        mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
    }

    /**
     * Sets {@link IMagnificationConnection}.
     *
     * @param connection {@link IMagnificationConnection}
     */
    public void setConnection(@Nullable IMagnificationConnection connection) {
        if (DBG) {
            Slog.d(TAG, "setConnection :" + connection + ", mConnectionState="
                    + connectionStateToString(mConnectionState));
        }
        synchronized (mLock) {
            // Reset connectionWrapper.
            if (mConnectionWrapper != null) {
                mConnectionWrapper.setConnectionCallback(null);
                if (mConnectionCallback != null) {
                    mConnectionCallback.mExpiredDeathRecipient = true;
                }
                mConnectionWrapper.unlinkToDeath(mConnectionCallback);
                mConnectionWrapper = null;
                // The connection is still connecting so it is no need to reset the
                // connection state to disconnected.
                // TODO b/220086369 will reset the connection immediately when requestConnection
                //  is called
                if (mConnectionState != CONNECTING) {
                    setConnectionState(DISCONNECTED);
                }
            }
            if (connection != null) {
                mConnectionWrapper = new MagnificationConnectionWrapper(connection, mTrace);
            }

            if (mConnectionWrapper != null) {
                try {
                    mConnectionCallback = new ConnectionCallback();
                    mConnectionWrapper.linkToDeath(mConnectionCallback);
                    mConnectionWrapper.setConnectionCallback(mConnectionCallback);
                    setConnectionState(CONNECTED);
                } catch (RemoteException e) {
                    Slog.e(TAG, "setConnection failed", e);
                    mConnectionWrapper = null;
                    setConnectionState(DISCONNECTED);
                } finally {
                    mLock.notify();
                }
            }
        }
    }

    /**
     * @return {@code true} if {@link IMagnificationConnection} is available
     */
    public boolean isConnected() {
        synchronized (mLock) {
            return mConnectionWrapper != null;
        }
    }

    /**
     * Requests {@link IMagnificationConnection} through
     * {@link StatusBarManagerInternal#requestMagnificationConnection(boolean)} and
     * destroys all window magnifications if necessary.
     * NOTE: Currently, this is not allowed to call from visible background users.(b/332222893)
     *
     * @param connect {@code true} if needs connection, otherwise set the connection to null and
     *                destroy all window magnifications.
     * @return {@code true} if {@link IMagnificationConnection} state is going to change.
     */
    public boolean requestConnection(boolean connect) {
        final int callingUserId = getCallingUserId();
        if (mUserManagerInternal.isVisibleBackgroundFullUser(callingUserId)) {
            throw new SecurityException("Visible background user(u" + callingUserId
                    + " is not permitted to request magnification connection.");
        }
        if (DBG) {
            Slog.d(TAG, "requestConnection :" + connect);
        }
        if (mTrace.isA11yTracingEnabledForTypes(FLAGS_MAGNIFICATION_CONNECTION)) {
            mTrace.logTrace(TAG + ".requestMagnificationConnection",
                    FLAGS_MAGNIFICATION_CONNECTION, "connect=" + connect);
        }
        synchronized (mLock) {
            if ((connect && (mConnectionState == CONNECTED || mConnectionState == CONNECTING))
                    || (!connect && (mConnectionState == DISCONNECTED
                    || mConnectionState == DISCONNECTING))) {
                Slog.w(TAG, "requestConnection duplicated request: connect=" + connect
                        + ", mConnectionState=" + connectionStateToString(mConnectionState));
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
        if (requestConnectionInternal(connect)) {
            setConnectionState(connect ? CONNECTING : DISCONNECTING);
            return true;
        } else {
            setConnectionState(DISCONNECTED);
            return false;
        }
    }

    private boolean requestConnectionInternal(boolean connect) {
        final long identity = Binder.clearCallingIdentity();
        try {
            final StatusBarManagerInternal service = LocalServices.getService(
                    StatusBarManagerInternal.class);
            if (service != null) {
                return service.requestMagnificationConnection(connect);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return false;
    }

    /**
     * Returns window magnification connection state.
     */
    public String getConnectionState() {
        return connectionStateToString(mConnectionState);
    }

    private void setConnectionState(@ConnectionState int state) {
        if (DBG) {
            Slog.d(TAG, "setConnectionState : state=" + state + ", mConnectionState="
                    + connectionStateToString(mConnectionState));
        }
        mConnectionState = state;
    }

    /**
     * Disables window magnifier on all displays without animation.
     */
    void disableAllWindowMagnifiers() {
        synchronized (mLock) {
            for (int i = 0; i < mWindowMagnifiers.size(); i++) {
                final WindowMagnifier magnifier = mWindowMagnifiers.valueAt(i);
                magnifier.disableWindowMagnificationInternal(null);
            }
            mWindowMagnifiers.clear();
        }
    }

    /**
     * Resets the window magnifier on all displays that had been controlled by the
     * specified service connection. Called when the service connection is unbound
     * or binder died.
     *
     * @param connectionId The connection id
     */
    public void resetAllIfNeeded(int connectionId) {
        synchronized (mLock) {
            for (int i = 0; i < mWindowMagnifiers.size(); i++) {
                final WindowMagnifier magnifier = mWindowMagnifiers.valueAt(i);
                if (magnifier != null
                        && magnifier.mEnabled
                        && connectionId == magnifier.getIdOfLastServiceToControl()) {
                    magnifier.disableWindowMagnificationInternal(null);
                }
            }
        }
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
    public void onRectangleOnScreenRequested(int displayId, int left, int top, int right,
            int bottom) {
        if (!mMagnificationFollowTypingEnabled) {
            return;
        }

        float toCenterX = (float) (left + right) / 2;
        float toCenterY = (float) (top + bottom) / 2;

        synchronized (mLock) {
            if (mIsImeVisibleArray.get(displayId, false)
                    && !isPositionInSourceBounds(displayId, toCenterX, toCenterY)
                    && isTrackingTypingFocusEnabled(displayId)) {
                moveWindowMagnifierToPositionInternal(displayId, toCenterX, toCenterY,
                        STUB_ANIMATION_CALLBACK);
            }
        }
    }

    void setMagnificationFollowTypingEnabled(boolean enabled) {
        mMagnificationFollowTypingEnabled = enabled;
    }

    boolean isMagnificationFollowTypingEnabled() {
        return mMagnificationFollowTypingEnabled;
    }

    /**
     * Get the ID of the last service that changed the magnification config.
     *
     * @param displayId The logical display id.
     * @return The id
     */
    public int getIdOfLastServiceToMagnify(int displayId) {
        synchronized (mLock) {
            final WindowMagnifier magnifier = mWindowMagnifiers.get(displayId);
            if (magnifier != null) {
                return magnifier.mIdOfLastServiceToControl;
            }
        }
        return INVALID_SERVICE_ID;
    }

    /**
     * Enable or disable tracking typing focus for the specific magnification window.
     *
     * The tracking typing focus should be set to enabled with the following conditions:
     * 1. IME is shown.
     *
     * The tracking typing focus should be set to disabled with the following conditions:
     * 1. A user drags the magnification window by 1 finger.
     * 2. A user scroll the magnification window by 2 fingers.
     *
     * @param displayId The logical display id.
     * @param trackingTypingFocusEnabled Enabled or disable the function of tracking typing focus.
     */
    void setTrackingTypingFocusEnabled(int displayId, boolean trackingTypingFocusEnabled) {
        synchronized (mLock) {
            WindowMagnifier magnifier = mWindowMagnifiers.get(displayId);
            if (magnifier == null) {
                return;
            }
            magnifier.setTrackingTypingFocusEnabled(trackingTypingFocusEnabled);
        }
    }

    /**
     * Enable tracking typing focus function for all magnifications.
     */
    private void enableAllTrackingTypingFocus() {
        synchronized (mLock) {
            for (int i = 0; i < mWindowMagnifiers.size(); i++) {
                WindowMagnifier magnifier = mWindowMagnifiers.valueAt(i);
                magnifier.setTrackingTypingFocusEnabled(true);
            }
        }
    }

    private void pauseTrackingTypingFocusRecord(int displayId) {
        WindowMagnifier magnifier;
        synchronized (mLock) {
            magnifier = mWindowMagnifiers.get(displayId);
            if (magnifier == null) {
                return;
            }
        }
        magnifier.pauseTrackingTypingFocusRecord();
    }

    /**
     * Called when the IME window visibility changed.
     *
     * @param shown {@code true} means the IME window shows on the screen. Otherwise, it's hidden.
     */
    void onImeWindowVisibilityChanged(int displayId, boolean shown) {
        synchronized (mLock) {
            mIsImeVisibleArray.put(displayId, shown);
        }
        if (shown) {
            enableAllTrackingTypingFocus();
        } else {
            pauseTrackingTypingFocusRecord(displayId);
        }
    }

    boolean isImeVisible(int displayId) {
        synchronized (mLock) {
            return mIsImeVisibleArray.get(displayId);
        }
    }

    void logTrackingTypingFocus(long duration) {
        AccessibilityStatsLogUtils.logMagnificationFollowTypingFocusSession(duration);
    }

    @Override
    public boolean processScroll(int displayId, float distanceX, float distanceY) {
        moveWindowMagnification(displayId, -distanceX, -distanceY);
        setTrackingTypingFocusEnabled(displayId, false);
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
            mLastActivatedScale.put(displayId, scale);
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
     * @return {@code true} if the magnification is enabled successfully.
     */
    public boolean enableWindowMagnification(int displayId, float scale, float centerX,
            float centerY) {
        return enableWindowMagnification(displayId, scale, centerX, centerY,
                STUB_ANIMATION_CALLBACK, MAGNIFICATION_GESTURE_HANDLER_ID);
    }

    /**
     * Enables window magnification with specified center and scale on the given display and
     * animating the transition.
     *
     * @param displayId The logical display id.
     * @param scale The target scale, must be >= 1.
     * @param centerX The screen-relative X coordinate around which to center for magnification,
     *                or {@link Float#NaN} to leave unchanged.
     * @param centerY The screen-relative Y coordinate around which to center for magnification,
     *                or {@link Float#NaN} to leave unchanged.
     * @param animationCallback Called when the animation result is valid.
     * @param id The connection ID
     * @return {@code true} if the magnification is enabled successfully.
     */
    public boolean enableWindowMagnification(int displayId, float scale, float centerX,
            float centerY, @Nullable MagnificationAnimationCallback animationCallback, int id) {
        return enableWindowMagnification(displayId, scale, centerX, centerY, animationCallback,
                WINDOW_POSITION_AT_CENTER, id);
    }

    /**
     * Enables window magnification with specified center and scale on the given display and
     * animating the transition.
     *
     * @param displayId The logical display id.
     * @param scale The target scale, must be >= 1.
     * @param centerX The screen-relative X coordinate around which to center for magnification,
     *                or {@link Float#NaN} to leave unchanged.
     * @param centerY The screen-relative Y coordinate around which to center for magnification,
     *                or {@link Float#NaN} to leave unchanged.
     * @param windowPosition Indicate the offset between window position and (centerX, centerY).
     * @return {@code true} if the magnification is enabled successfully.
     */
    public boolean enableWindowMagnification(int displayId, float scale, float centerX,
            float centerY, @WindowPosition int windowPosition) {
        return enableWindowMagnification(displayId, scale, centerX, centerY,
                STUB_ANIMATION_CALLBACK, windowPosition, MAGNIFICATION_GESTURE_HANDLER_ID);
    }

    /**
     * Enables window magnification with specified center and scale on the given display and
     * animating the transition.
     *
     * @param displayId         The logical display id.
     * @param scale             The target scale, must be >= 1.
     * @param centerX           The screen-relative X coordinate around which to center for
     *                          magnification, or {@link Float#NaN} to leave unchanged.
     * @param centerY           The screen-relative Y coordinate around which to center for
     *                          magnification, or {@link Float#NaN} to leave unchanged.
     * @param animationCallback Called when the animation result is valid.
     * @param windowPosition    Indicate the offset between window position and (centerX, centerY).
     * @return {@code true} if the magnification is enabled successfully.
     */
    public boolean enableWindowMagnification(int displayId, float scale, float centerX,
            float centerY, @Nullable MagnificationAnimationCallback animationCallback,
            @WindowPosition int windowPosition, int id) {
        final boolean enabled;
        boolean previousEnabled;
        synchronized (mLock) {
            WindowMagnifier magnifier = mWindowMagnifiers.get(displayId);
            if (magnifier == null) {
                magnifier = createWindowMagnifier(displayId);
            }
            previousEnabled = magnifier.mEnabled;
            enabled = magnifier.enableWindowMagnificationInternal(scale, centerX, centerY,
                    animationCallback, windowPosition, id);
            if (enabled) {
                mLastActivatedScale.put(displayId, getScale(displayId));
            }
        }

        if (enabled) {
            setTrackingTypingFocusEnabled(displayId, true);
            if (!previousEnabled) {
                mCallback.onWindowMagnificationActivationState(displayId, true);
            }
        }
        return enabled;
    }

    /**
     * Disables window magnification on the given display.
     *
     * @param displayId The logical display id.
     * @param clear {@true} Clears the state of window magnification.
     * @return {@code true} if the magnification is turned to be disabled successfully
     */
    public boolean disableWindowMagnification(int displayId, boolean clear) {
        return disableWindowMagnification(displayId, clear, STUB_ANIMATION_CALLBACK);
    }

    /**
     * Disables window magnification on the specified display and animating the transition.
     *
     * @param displayId The logical display id.
     * @param clear {@true} Clears the state of window magnification.
     * @param animationCallback Called when the animation result is valid.
     * @return {@code true} if the magnification is turned to be disabled successfully
     */
    public boolean disableWindowMagnification(int displayId, boolean clear,
            MagnificationAnimationCallback animationCallback) {
        final boolean disabled;
        synchronized (mLock) {
            WindowMagnifier magnifier = mWindowMagnifiers.get(displayId);
            if (magnifier == null) {
                return false;
            }

            disabled = magnifier.disableWindowMagnificationInternal(animationCallback);
            if (clear) {
                mWindowMagnifiers.delete(displayId);
            }
        }

        if (disabled) {
            mCallback.onWindowMagnificationActivationState(displayId, false);
        }
        return disabled;
    }

    /**
     * Notify Fullscreen magnification activation changes.
     */
    public boolean onFullscreenMagnificationActivationChanged(int displayId, boolean activated) {
        synchronized (mLock) {
            if (!waitConnectionWithTimeoutIfNeeded()) {
                Slog.w(TAG,
                        "onFullscreenMagnificationActivationChanged mConnectionWrapper is null. "
                                + "mConnectionState=" + connectionStateToString(mConnectionState));
                return false;
            }
            return mConnectionWrapper
                    .onFullscreenMagnificationActivationChanged(displayId, activated);
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

    @GuardedBy("mLock")
    boolean isPositionInSourceBounds(int displayId, float x, float y) {
        WindowMagnifier magnifier = mWindowMagnifiers.get(displayId);
        if (magnifier == null) {
            return false;
        }
        return magnifier.isPositionInSourceBounds(x, y);
    }

    /**
     * Indicates whether window magnification is enabled on specified display.
     *
     * @param displayId The logical display id.
     * @return {@code true} if the window magnification is enabled.
     */
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
     * Retrieves a previously magnification scale from the current
     * user's settings. Only the value of the default display is persisted.
     *
     * @return the previously magnification scale, or the default
     *         scale if none is available
     */
    float getPersistedScale(int displayId) {
        return MathUtils.constrain(mScaleProvider.getScale(displayId),
                MagnificationConstants.PERSISTED_SCALE_MIN_VALUE,
                MagnificationScaleProvider.MAX_SCALE);
    }

    /**
     * Persists the default display magnification scale to the current user's settings
     * <strong>if scale is >= {@link MagnificationConstants.PERSISTED_SCALE_MIN_VALUE}</strong>.
     * We assume if the scale is < {@link MagnificationConstants.PERSISTED_SCALE_MIN_VALUE}, there
     * will be no obvious magnification effect.
     * Only the value of the default display is persisted in user's settings.
     */
    void persistScale(int displayId) {
        float scale = getScale(displayId);
        if (scale < MagnificationConstants.PERSISTED_SCALE_MIN_VALUE) {
            return;
        }
        mScaleProvider.putScale(scale, displayId);
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
            if (magnifier == null || !magnifier.mEnabled) {
                return 1.0f;
            }
            return magnifier.getScale();
        }
    }

    protected float getLastActivatedScale(int displayId) {
        synchronized (mLock) {
            if (!mLastActivatedScale.contains(displayId)) {
                return -1.0f;
            }
            return mLastActivatedScale.get(displayId);
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
        synchronized (mLock) {
            return mConnectionWrapper != null
                    && mConnectionWrapper.showMagnificationButton(displayId, magnificationMode);
        }
    }

    /**
     * Requests System UI remove magnification mode button UI on the specified display.
     *
     * @param displayId The logical display id.
     * @return {@code true} if the event was handled, {@code false} otherwise
     */
    public boolean removeMagnificationButton(int displayId) {
        synchronized (mLock) {
            return mConnectionWrapper != null
                    && mConnectionWrapper.removeMagnificationButton(displayId);
        }
    }

    /**
     * Requests System UI remove magnification settings panel on the specified display.
     *
     * @param displayId The logical display id.
     * @return {@code true} if the event was handled, {@code false} otherwise
     */
    public boolean removeMagnificationSettingsPanel(int displayId) {
        synchronized (mLock) {
            return mConnectionWrapper != null
                    && mConnectionWrapper.removeMagnificationSettingsPanel(displayId);
        }
    }

    /**
     * Notify System UI the magnification scale on the specified display for userId is changed.
     *
     * @param userId the user id.
     * @param displayId the logical display id.
     * @param scale magnification scale.
     */
    public boolean onUserMagnificationScaleChanged(int userId, int displayId, float scale) {
        synchronized (mLock) {
            return mConnectionWrapper != null
                    && mConnectionWrapper.onUserMagnificationScaleChanged(userId, displayId, scale);
        }
    }

    /**
     * Returns the screen-relative X coordinate of the center of the magnified bounds.
     *
     * @param displayId The logical display id
     * @return the X coordinate. {@link Float#NaN} if the window magnification is not enabled.
     */
    public float getCenterX(int displayId) {
        synchronized (mLock) {
            WindowMagnifier magnifier = mWindowMagnifiers.get(displayId);
            if (magnifier == null || !magnifier.mEnabled) {
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
    public float getCenterY(int displayId) {
        synchronized (mLock) {
            WindowMagnifier magnifier = mWindowMagnifiers.get(displayId);
            if (magnifier == null || !magnifier.mEnabled) {
                return Float.NaN;
            }
            return magnifier.getCenterY();
        }
    }

    boolean isTrackingTypingFocusEnabled(int displayId) {
        synchronized (mLock) {
            WindowMagnifier magnifier = mWindowMagnifiers.get(displayId);
            if (magnifier == null) {
                return false;
            }
            return magnifier.isTrackingTypingFocusEnabled();
        }
    }

    /**
     * Populates magnified bounds on the screen. And the populated magnified bounds would be
     * empty If window magnifier is not activated.
     *
     * @param displayId The logical display id.
     * @param outRegion the region to populate
     */
    public void getMagnificationSourceBounds(int displayId, @NonNull Region outRegion) {
        synchronized (mLock) {
            WindowMagnifier magnifier = mWindowMagnifiers.get(displayId);
            if (magnifier == null || !magnifier.mEnabled) {
                outRegion.setEmpty();
            } else {
                outRegion.set(magnifier.mSourceBounds);
            }
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
    public void onDisplayRemoved(int displayId) {
        disableWindowMagnification(displayId, true);
    }

    private class ConnectionCallback extends IMagnificationConnectionCallback.Stub implements
            IBinder.DeathRecipient {
        private boolean mExpiredDeathRecipient = false;

        @RequiresNoPermission
        @Override
        public void onWindowMagnifierBoundsChanged(int displayId, Rect bounds) {
            if (mTrace.isA11yTracingEnabledForTypes(
                    FLAGS_MAGNIFICATION_CONNECTION_CALLBACK)) {
                mTrace.logTrace(TAG + "ConnectionCallback.onWindowMagnifierBoundsChanged",
                        FLAGS_MAGNIFICATION_CONNECTION_CALLBACK,
                        "displayId=" + displayId + ";bounds=" + bounds);
            }
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

        @RequiresNoPermission
        @Override
        public void onChangeMagnificationMode(int displayId, int magnificationMode)
                throws RemoteException {
            if (mTrace.isA11yTracingEnabledForTypes(
                    FLAGS_MAGNIFICATION_CONNECTION_CALLBACK)) {
                mTrace.logTrace(TAG + "ConnectionCallback.onChangeMagnificationMode",
                        FLAGS_MAGNIFICATION_CONNECTION_CALLBACK,
                        "displayId=" + displayId + ";mode=" + magnificationMode);
            }
            mCallback.onChangeMagnificationMode(displayId, magnificationMode);
        }

        @RequiresNoPermission
        @Override
        public void onSourceBoundsChanged(int displayId, Rect sourceBounds) {
            if (mTrace.isA11yTracingEnabledForTypes(
                    FLAGS_MAGNIFICATION_CONNECTION_CALLBACK)) {
                mTrace.logTrace(TAG + "ConnectionCallback.onSourceBoundsChanged",
                        FLAGS_MAGNIFICATION_CONNECTION_CALLBACK,
                        "displayId=" + displayId + ";source=" + sourceBounds);
            }
            synchronized (mLock) {
                WindowMagnifier magnifier = mWindowMagnifiers.get(displayId);
                if (magnifier == null) {
                    magnifier = createWindowMagnifier(displayId);
                }
                magnifier.onSourceBoundsChanged(sourceBounds);
            }
            mCallback.onSourceBoundsChanged(displayId, sourceBounds);
        }

        @RequiresNoPermission
        @Override
        public void onPerformScaleAction(int displayId, float scale, boolean updatePersistence) {
            if (mTrace.isA11yTracingEnabledForTypes(
                    FLAGS_MAGNIFICATION_CONNECTION_CALLBACK)) {
                mTrace.logTrace(TAG + "ConnectionCallback.onPerformScaleAction",
                        FLAGS_MAGNIFICATION_CONNECTION_CALLBACK,
                        "displayId=" + displayId + ";scale=" + scale
                                + ";updatePersistence=" + updatePersistence);
            }
            mCallback.onPerformScaleAction(displayId, scale, updatePersistence);
        }

        @RequiresNoPermission
        @Override
        public void onAccessibilityActionPerformed(int displayId) {
            if (mTrace.isA11yTracingEnabledForTypes(
                    FLAGS_MAGNIFICATION_CONNECTION_CALLBACK)) {
                mTrace.logTrace(TAG + "ConnectionCallback.onAccessibilityActionPerformed",
                        FLAGS_MAGNIFICATION_CONNECTION_CALLBACK,
                        "displayId=" + displayId);
            }
            mCallback.onAccessibilityActionPerformed(displayId);
        }

        @RequiresNoPermission
        @Override
        public void onMove(int displayId) {
            if (mTrace.isA11yTracingEnabledForTypes(
                    FLAGS_MAGNIFICATION_CONNECTION_CALLBACK)) {
                mTrace.logTrace(TAG + "ConnectionCallback.onMove",
                        FLAGS_MAGNIFICATION_CONNECTION_CALLBACK,
                        "displayId=" + displayId);
            }
            setTrackingTypingFocusEnabled(displayId, false);
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
                setConnectionState(DISCONNECTED);
                resetWindowMagnifiers();
            }
        }
    }

    /**
     * A class manipulates window magnification per display and contains the magnification
     * information.
     * <p>
     * This class requires to hold the lock when controlling the magnifier.
     * </p>
     */
    private static class WindowMagnifier {

        private final int mDisplayId;
        private float mScale = MagnificationScaleProvider.MIN_SCALE;
        private boolean mEnabled;

        private final MagnificationConnectionManager mMagnificationConnectionManager;
        // Records the bounds of window magnification.
        private final Rect mBounds = new Rect();
        // The magnified bounds on the screen.
        private final Rect mSourceBounds = new Rect();

        private int mIdOfLastServiceToControl = INVALID_SERVICE_ID;

        private final PointF mMagnificationFrameOffsetRatio = new PointF(0f, 0f);

        private boolean mTrackingTypingFocusEnabled = true;

        private volatile long mTrackingTypingFocusStartTime = 0;
        private static final AtomicLongFieldUpdater<WindowMagnifier> SUM_TIME_UPDATER =
                AtomicLongFieldUpdater.newUpdater(WindowMagnifier.class,
                        "mTrackingTypingFocusSumTime");
        private volatile long mTrackingTypingFocusSumTime = 0;

        WindowMagnifier(int displayId,
                MagnificationConnectionManager magnificationConnectionManager) {
            mDisplayId = displayId;
            mMagnificationConnectionManager = magnificationConnectionManager;
        }

        // TODO(b/312324808): Investigating whether
        //  mMagnificationConnectionManager#enableWindowMagnificationInternal requires a sync lock
        @SuppressWarnings("GuardedBy")
        boolean enableWindowMagnificationInternal(float scale, float centerX, float centerY,
                @Nullable MagnificationAnimationCallback animationCallback,
                @WindowPosition int windowPosition, int id) {
            // Handle defaults. The scale may be NAN when just updating magnification center.
            if (Float.isNaN(scale)) {
                scale = getScale();
            }
            final float normScale = MagnificationScaleProvider.constrainScale(scale);
            setMagnificationFrameOffsetRatioByWindowPosition(windowPosition);
            if (mMagnificationConnectionManager.enableWindowMagnificationInternal(mDisplayId,
                    normScale, centerX, centerY, mMagnificationFrameOffsetRatio.x,
                    mMagnificationFrameOffsetRatio.y, animationCallback)) {
                mScale = normScale;
                mEnabled = true;
                mIdOfLastServiceToControl = id;
                return true;
            }
            return false;
        }

        void setMagnificationFrameOffsetRatioByWindowPosition(@WindowPosition int windowPosition) {
            switch (windowPosition) {
                case WINDOW_POSITION_AT_CENTER: {
                    mMagnificationFrameOffsetRatio.set(0f, 0f);
                }
                break;
                case WINDOW_POSITION_AT_TOP_LEFT: {
                    mMagnificationFrameOffsetRatio.set(-1f, -1f);
                }
                break;
            }
        }

        // TODO(b/312324808): Investigating whether
        //  mMagnificationConnectionManager#disableWindowMagnificationInternal requires a sync lock
        @SuppressWarnings("GuardedBy")
        boolean disableWindowMagnificationInternal(
                @Nullable MagnificationAnimationCallback animationResultCallback) {
            if (!mEnabled) {
                return false;
            }
            if (mMagnificationConnectionManager.disableWindowMagnificationInternal(
                    mDisplayId, animationResultCallback)) {
                mEnabled = false;
                mIdOfLastServiceToControl = INVALID_SERVICE_ID;
                mTrackingTypingFocusEnabled = false;
                pauseTrackingTypingFocusRecord();
                return true;
            }
            return false;
        }

        // ErrorProne says the access of mMagnificationConnectionManager#setScaleInternal should
        // be guarded by 'this.mMagnificationConnectionManager.mLock' which is the same one as
        // 'mLock'. Therefore, we'll put @SuppressWarnings here.
        @SuppressWarnings("GuardedBy")
        @GuardedBy("mLock")
        void setScale(float scale) {
            if (!mEnabled) {
                return;
            }
            final float normScale = MagnificationScaleProvider.constrainScale(scale);
            if (Float.compare(mScale, normScale) != 0
                    && mMagnificationConnectionManager
                        .setScaleForWindowMagnificationInternal(mDisplayId, scale)) {
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

        /**
         * Returns the ID of the last service that changed the magnification config.
         */
        int getIdOfLastServiceToControl() {
            return mIdOfLastServiceToControl;
        }

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

        boolean isPositionInSourceBounds(float x, float y) {
            return mSourceBounds.contains((int) x, (int) y);
        }

        void setTrackingTypingFocusEnabled(boolean trackingTypingFocusEnabled) {
            if (mMagnificationConnectionManager.isWindowMagnifierEnabled(mDisplayId)
                    && mMagnificationConnectionManager.isImeVisible(mDisplayId)
                    && trackingTypingFocusEnabled) {
                startTrackingTypingFocusRecord();
            }
            if (mTrackingTypingFocusEnabled && !trackingTypingFocusEnabled) {
                stopAndLogTrackingTypingFocusRecordIfNeeded();
            }
            mTrackingTypingFocusEnabled = trackingTypingFocusEnabled;
        }

        boolean isTrackingTypingFocusEnabled() {
            return mTrackingTypingFocusEnabled;
        }

        void startTrackingTypingFocusRecord() {
            if (mTrackingTypingFocusStartTime == 0) {
                mTrackingTypingFocusStartTime = SystemClock.uptimeMillis();
                if (DBG) {
                    Slog.d(TAG, "start: mTrackingTypingFocusStartTime = "
                            + mTrackingTypingFocusStartTime);
                }
            }
        }

        void pauseTrackingTypingFocusRecord() {
            if (mTrackingTypingFocusStartTime != 0) {
                final long elapsed = (SystemClock.uptimeMillis() - mTrackingTypingFocusStartTime);
                // update mTrackingTypingFocusSumTime value in an atomic operation
                SUM_TIME_UPDATER.addAndGet(this, elapsed);
                mTrackingTypingFocusStartTime = 0;
                if (DBG) {
                    Slog.d(TAG, "pause: mTrackingTypingFocusSumTime = "
                            + mTrackingTypingFocusSumTime + ", elapsed = " + elapsed);
                }
            }
        }

        void stopAndLogTrackingTypingFocusRecordIfNeeded() {
            if (mTrackingTypingFocusStartTime != 0 || mTrackingTypingFocusSumTime != 0) {
                final long elapsed = mTrackingTypingFocusStartTime != 0
                        ? (SystemClock.uptimeMillis() - mTrackingTypingFocusStartTime) : 0;
                final long duration = mTrackingTypingFocusSumTime + elapsed;
                if (DBG) {
                    Slog.d(TAG, "stop and log: session duration = " + duration
                            + ", elapsed = " + elapsed);
                }
                mMagnificationConnectionManager.logTrackingTypingFocus(duration);
                mTrackingTypingFocusStartTime = 0;
                mTrackingTypingFocusSumTime = 0;
            }
        }

        boolean isEnabled() {
            return mEnabled;
        }

        // ErrorProne says the access of mMagnificationConnectionManager#moveWindowMagnifierInternal
        // should be guarded by 'this.mMagnificationConnectionManager.mLock' which is the same one
        // as 'mLock'. Therefore, we'll put @SuppressWarnings here.
        @SuppressWarnings("GuardedBy")
        @GuardedBy("mLock")
        void move(float offsetX, float offsetY) {
            mMagnificationConnectionManager.moveWindowMagnifierInternal(
                    mDisplayId, offsetX, offsetY);
        }

        @GuardedBy("mLock")
        void reset() {
            mEnabled = false;
            mIdOfLastServiceToControl = INVALID_SERVICE_ID;
            mSourceBounds.setEmpty();
        }

        @GuardedBy("mLock")
        public void onSourceBoundsChanged(Rect sourceBounds) {
            mSourceBounds.set(sourceBounds);
        }

        @GuardedBy("mLock")
        float getCenterX() {
            return mSourceBounds.exactCenterX();
        }

        @GuardedBy("mLock")
        float getCenterY() {
            return mSourceBounds.exactCenterY();
        }
    }

    @GuardedBy("mLock")
    private boolean enableWindowMagnificationInternal(int displayId, float scale, float centerX,
            float centerY, float magnificationFrameOffsetRatioX,
            float magnificationFrameOffsetRatioY,
            MagnificationAnimationCallback animationCallback) {
        if (!waitConnectionWithTimeoutIfNeeded()) {
            Slog.w(TAG,
                    "enableWindowMagnificationInternal mConnectionWrapper is null. "
                            + "mConnectionState=" + connectionStateToString(mConnectionState));
            return false;
        }
        return mConnectionWrapper.enableWindowMagnification(
                displayId, scale, centerX, centerY,
                magnificationFrameOffsetRatioX, magnificationFrameOffsetRatioY,
                animationCallback);
    }

    @GuardedBy("mLock")
    private boolean setScaleForWindowMagnificationInternal(int displayId, float scale) {
        return mConnectionWrapper != null
                && mConnectionWrapper.setScaleForWindowMagnification(displayId, scale);
    }

    @GuardedBy("mLock")
    private boolean disableWindowMagnificationInternal(int displayId,
            MagnificationAnimationCallback animationCallback) {
        if (mConnectionWrapper == null) {
            Slog.w(TAG, "mConnectionWrapper is null");
            return false;
        }
        return mConnectionWrapper.disableWindowMagnification(
                displayId, animationCallback);
    }

    @GuardedBy("mLock")
    private boolean moveWindowMagnifierInternal(int displayId, float offsetX, float offsetY) {
        return mConnectionWrapper != null && mConnectionWrapper.moveWindowMagnifier(
                displayId, offsetX, offsetY);
    }

    @GuardedBy("mLock")
    private boolean moveWindowMagnifierToPositionInternal(int displayId, float positionX,
            float positionY, MagnificationAnimationCallback animationCallback) {
        return mConnectionWrapper != null && mConnectionWrapper.moveWindowMagnifierToPosition(
                displayId, positionX, positionY, animationCallback);
    }

    boolean waitConnectionWithTimeoutIfNeeded() {
        // Wait for the connection with a timeout.
        final long endMillis = SystemClock.uptimeMillis() + WAIT_CONNECTION_TIMEOUT_MILLIS;
        while (mConnectionState == CONNECTING && (SystemClock.uptimeMillis() < endMillis)) {
            try {
                mLock.wait(endMillis - SystemClock.uptimeMillis());
            } catch (InterruptedException ie) {
                /* ignore */
            }
        }
        return isConnected();
    }
}
