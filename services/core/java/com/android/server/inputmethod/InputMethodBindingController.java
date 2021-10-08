/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.inputmethod;

import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;

import static com.android.server.inputmethod.InputMethodManagerService.MSG_INITIALIZE_IME;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManagerInternal;
import android.content.res.Resources;
import android.inputmethodservice.InputMethodService;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.EventLog;
import android.util.Slog;
import android.view.IWindowManager;
import android.view.WindowManager;
import android.view.inputmethod.InputMethod;
import android.view.inputmethod.InputMethodInfo;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.inputmethod.InputBindResult;
import com.android.internal.inputmethod.UnbindReason;
import com.android.internal.view.IInputMethod;
import com.android.server.EventLogTags;
import com.android.server.wm.WindowManagerInternal;

/**
 * A controller managing the state of the input method binding.
 */
final class InputMethodBindingController {
    static final boolean DEBUG = false;
    private static final String TAG = InputMethodBindingController.class.getSimpleName();

    /** Time in milliseconds that the IME service has to bind before it is reconnected. */
    static final long TIME_TO_RECONNECT = 3 * 1000;

    @NonNull private final InputMethodManagerService mService;
    @NonNull private final Context mContext;
    @NonNull private final ArrayMap<String, InputMethodInfo> mMethodMap;
    @NonNull private final InputMethodUtils.InputMethodSettings mSettings;
    @NonNull private final PackageManagerInternal mPackageManagerInternal;
    @NonNull private final IWindowManager mIWindowManager;
    @NonNull private final WindowManagerInternal mWindowManagerInternal;
    @NonNull private final Resources mRes;

    private long mLastBindTime;
    private boolean mHasConnection;
    @Nullable private String mCurId;
    @Nullable private String mSelectedMethodId;
    @Nullable private Intent mCurIntent;
    @Nullable private IInputMethod mCurMethod;
    private int mCurMethodUid = Process.INVALID_UID;
    private IBinder mCurToken;
    private int mCurSeq;
    private boolean mVisibleBound;
    private boolean mSupportsStylusHw;

    /**
     * Binding flags for establishing connection to the {@link InputMethodService}.
     */
    private static final int IME_CONNECTION_BIND_FLAGS =
            Context.BIND_AUTO_CREATE
                    | Context.BIND_NOT_VISIBLE
                    | Context.BIND_NOT_FOREGROUND
                    | Context.BIND_IMPORTANT_BACKGROUND;
    /**
     * Binding flags for establishing connection to the {@link InputMethodService} when
     * config_killableInputMethods is enabled.
     */
    private static final int IME_CONNECTION_LOW_PRIORITY_BIND_FLAGS =
            Context.BIND_AUTO_CREATE
                    | Context.BIND_REDUCTION_FLAGS;
    /**
     * Binding flags used only while the {@link InputMethodService} is showing window.
     */
    private static final int IME_VISIBLE_BIND_FLAGS =
            Context.BIND_AUTO_CREATE
                    | Context.BIND_TREAT_LIKE_ACTIVITY
                    | Context.BIND_FOREGROUND_SERVICE
                    | Context.BIND_INCLUDE_CAPABILITIES
                    | Context.BIND_SHOWING_UI
                    | Context.BIND_SCHEDULE_LIKE_TOP_APP;

    /**
     * Binding flags for establishing connection to the {@link InputMethodService}.
     *
     * <p>
     * This defaults to {@link InputMethodBindingController#IME_CONNECTION_BIND_FLAGS} unless
     * config_killableInputMethods is enabled, in which case this takes the value of
     * {@link InputMethodBindingController#IME_CONNECTION_LOW_PRIORITY_BIND_FLAGS}.
     */
    private final int mImeConnectionBindFlags;

    InputMethodBindingController(@NonNull InputMethodManagerService service) {
        mService = service;
        mContext = mService.mContext;
        mMethodMap = mService.mMethodMap;
        mSettings = mService.mSettings;
        mPackageManagerInternal = mService.mPackageManagerInternal;
        mIWindowManager = mService.mIWindowManager;
        mWindowManagerInternal = mService.mWindowManagerInternal;
        mRes = mService.mRes;

        // If configured, use low priority flags to make the IME killable by the lowmemorykiller
        final boolean lowerIMEPriority = mRes.getBoolean(
                com.android.internal.R.bool.config_killableInputMethods);

        if (lowerIMEPriority) {
            mImeConnectionBindFlags =
                    InputMethodBindingController.IME_CONNECTION_LOW_PRIORITY_BIND_FLAGS;
        } else {
            mImeConnectionBindFlags = InputMethodBindingController.IME_CONNECTION_BIND_FLAGS;
        }
    }

    /**
     * Time that we last initiated a bind to the input method, to determine
     * if we should try to disconnect and reconnect to it.
     */
    long getLastBindTime() {
        return mLastBindTime;
    }

    /**
     * Set to true if our ServiceConnection is currently actively bound to
     * a service (whether or not we have gotten its IBinder back yet).
     */
    boolean hasConnection() {
        return mHasConnection;
    }

    /**
     * Id obtained with {@link InputMethodInfo#getId()} for the input method that we are currently
     * connected to or in the process of connecting to.
     *
     * <p>This can be {@code null} when no input method is connected.</p>
     *
     * @see #getSelectedMethodId()
     */
    @Nullable
    String getCurId() {
        return mCurId;
    }

    /**
     * Id obtained with {@link InputMethodInfo#getId()} for the currently selected input method.
     * This is to be synchronized with the secure settings keyed with
     * {@link android.provider.Settings.Secure#DEFAULT_INPUT_METHOD}.
     *
     * <p>This can be transiently {@code null} when the system is re-initializing input method
     * settings, e.g., the system locale is just changed.</p>
     *
     * <p>Note that {@link #getCurId()} is used to track which IME is being connected to
     * {@link com.android.server.inputmethod.InputMethodManagerService}.</p>
     *
     * @see #getCurId()
     */
    @Nullable
    String getSelectedMethodId() {
        return mSelectedMethodId;
    }

    void setSelectedMethodId(@Nullable String selectedMethodId) {
        mSelectedMethodId = selectedMethodId;
    }

    /**
     * The token we have made for the currently active input method, to
     * identify it in the future.
     */
    IBinder getCurToken() {
        return mCurToken;
    }

    /**
     * The Intent used to connect to the current input method.
     */
    @Nullable
    Intent getCurIntent() {
        return mCurIntent;
    }

    /**
     * The current binding sequence number, incremented every time there is
     * a new bind performed.
     */
    int getSequenceNumber() {
        return mCurSeq;
    }

    /**
     * Increase the current binding sequence number by one.
     * Reset to 1 on overflow.
     */
    void advanceSequenceNumber() {
        mCurSeq += 1;
        if (mCurSeq <= 0) {
            mCurSeq = 1;
        }
    }

    /**
     * If non-null, this is the input method service we are currently connected
     * to.
     */
    @Nullable
    IInputMethod getCurMethod() {
        return mCurMethod;
    }

    /**
     * If not {@link Process#INVALID_UID}, then the UID of {@link #getCurIntent()}.
     */
    int getCurMethodUid() {
        return mCurMethodUid;
    }

    /**
     * Indicates whether {@link #mVisibleConnection} is currently in use.
     */
    boolean isVisibleBound() {
        return mVisibleBound;
    }

    /**
     * Used to bring IME service up to visible adjustment while it is being shown.
     */
    private final ServiceConnection mVisibleConnection = new ServiceConnection() {
        @Override public void onBindingDied(ComponentName name) {
            synchronized (mMethodMap) {
                if (mVisibleBound) {
                    unbindVisibleConnectionLocked();
                }
            }
        }

        @Override public void onServiceConnected(ComponentName name, IBinder service) {
        }

        @Override public void onServiceDisconnected(ComponentName name) {
        }
    };

    /**
     * Used to bind the IME while it is not currently being shown.
     */
    private final ServiceConnection mMainConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.onServiceConnected");
            synchronized (mMethodMap) {
                if (mCurIntent != null && name.equals(mCurIntent.getComponent())) {
                    mCurMethod = IInputMethod.Stub.asInterface(service);
                    updateCurrentMethodUidLocked();
                    if (mCurToken == null) {
                        Slog.w(TAG, "Service connected without a token!");
                        unbindCurrentMethodLocked();
                        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
                        return;
                    }
                    if (DEBUG) Slog.v(TAG, "Initiating attach with token: " + mCurToken);
                    // Dispatch display id for InputMethodService to update context display.
                    mService.executeOrSendMessage(mCurMethod,
                            mService.mCaller.obtainMessageIOO(MSG_INITIALIZE_IME,
                                    mMethodMap.get(mSelectedMethodId).getConfigChanges(),
                                    mCurMethod, mCurToken));
                    mService.scheduleNotifyImeUidToAudioService(mCurMethodUid);
                    mService.reRequestCurrentClientSessionLocked();
                }
                mSupportsStylusHw = mMethodMap.get(mSelectedMethodId).supportsStylusHandwriting();
                if (mSupportsStylusHw) {
                    // TODO init Handwriting spy.
                }
            }
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }

        @GuardedBy("mMethodMap")
        private void updateCurrentMethodUidLocked() {
            final String curMethodPackage = mCurIntent.getComponent().getPackageName();
            final int curMethodUid = mPackageManagerInternal.getPackageUid(
                    curMethodPackage, 0 /* flags */, mSettings.getCurrentUserId());
            if (curMethodUid < 0) {
                Slog.e(TAG, "Failed to get UID for package=" + curMethodPackage);
                mCurMethodUid = Process.INVALID_UID;
            } else {
                mCurMethodUid = curMethodUid;
            }
        }

        @Override
        public void onServiceDisconnected(@NonNull ComponentName name) {
            // Note that mContext.unbindService(this) does not trigger this.  Hence if we are
            // here the
            // disconnection is not intended by IMMS (e.g. triggered because the current IMS
            // crashed),
            // which is irregular but can eventually happen for everyone just by continuing
            // using the
            // device.  Thus it is important to make sure that all the internal states are
            // properly
            // refreshed when this method is called back.  Running
            //    adb install -r <APK that implements the current IME>
            // would be a good way to trigger such a situation.
            synchronized (mMethodMap) {
                if (DEBUG) {
                    Slog.v(TAG, "Service disconnected: " + name + " mCurIntent=" + mCurIntent);
                }
                if (mCurMethod != null && mCurIntent != null
                        && name.equals(mCurIntent.getComponent())) {
                    // We consider this to be a new bind attempt, since the system
                    // should now try to restart the service for us.
                    mLastBindTime = SystemClock.uptimeMillis();
                    clearCurMethodAndSessionsLocked();
                    mService.clearInputShowRequestLocked();
                    mService.unbindCurrentClientLocked(UnbindReason.DISCONNECT_IME);
                }
            }
        }
    };

    @GuardedBy("mMethodMap")
    void unbindCurrentMethodLocked() {
        if (mVisibleBound) {
            unbindVisibleConnectionLocked();
        }

        if (mHasConnection) {
            unbindMainConnectionLocked();
        }

        if (mCurToken != null) {
            removeCurrentTokenLocked();
            mService.resetSystemUiLocked();
        }

        mCurId = null;
        clearCurMethodAndSessionsLocked();
    }

    @GuardedBy("mMethodMap")
    private void clearCurMethodAndSessionsLocked() {
        mService.clearClientSessionsLocked();
        mCurMethod = null;
        mCurMethodUid = Process.INVALID_UID;
    }

    @GuardedBy("mMethodMap")
    private void removeCurrentTokenLocked() {
        int curTokenDisplayId = mService.getCurTokenDisplayId();

        if (DEBUG) {
            Slog.v(TAG,
                    "Removing window token: " + mCurToken + " for display: " + curTokenDisplayId);
        }
        mWindowManagerInternal.removeWindowToken(mCurToken, false /* removeWindows */,
                false /* animateExit */, curTokenDisplayId);
        mCurToken = null;
    }

    @GuardedBy("mMethodMap")
    @NonNull
    InputBindResult bindCurrentMethodLocked() {
        InputMethodInfo info = mMethodMap.get(mSelectedMethodId);
        if (info == null) {
            throw new IllegalArgumentException("Unknown id: " + mSelectedMethodId);
        }

        mCurIntent = createImeBindingIntent(info.getComponent());

        if (bindCurrentInputMethodServiceMainConnectionLocked()) {
            mCurId = info.getId();
            mLastBindTime = SystemClock.uptimeMillis();

            addFreshWindowTokenLocked();
            return new InputBindResult(
                    InputBindResult.ResultCode.SUCCESS_WAITING_IME_BINDING,
                    null, null, mCurId, mCurSeq, false);
        }

        Slog.w(InputMethodManagerService.TAG,
                "Failure connecting to input method service: " + mCurIntent);
        mCurIntent = null;
        return InputBindResult.IME_NOT_CONNECTED;
    }

    @NonNull
    private Intent createImeBindingIntent(ComponentName component) {
        Intent intent = new Intent(InputMethod.SERVICE_INTERFACE);
        intent.setComponent(component);
        intent.putExtra(Intent.EXTRA_CLIENT_LABEL,
                com.android.internal.R.string.input_method_binding_label);
        intent.putExtra(Intent.EXTRA_CLIENT_INTENT, PendingIntent.getActivity(
                mContext, 0, new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS),
                PendingIntent.FLAG_IMMUTABLE));
        return intent;
    }

    @GuardedBy("mMethodMap")
    private void addFreshWindowTokenLocked() {
        int displayIdToShowIme = mService.getDisplayIdToShowIme();
        mCurToken = new Binder();

        mService.setCurTokenDisplayId(displayIdToShowIme);

        try {
            if (DEBUG) {
                Slog.v(TAG, "Adding window token: " + mCurToken + " for display: "
                        + displayIdToShowIme);
            }
            mIWindowManager.addWindowToken(mCurToken, WindowManager.LayoutParams.TYPE_INPUT_METHOD,
                    displayIdToShowIme, null /* options */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Could not add window token " + mCurToken + " for display "
                    + displayIdToShowIme, e);
        }
    }

    @GuardedBy("mMethodMap")
    private void unbindMainConnectionLocked() {
        mContext.unbindService(mMainConnection);
        mHasConnection = false;
    }

    @GuardedBy("mMethodMap")
    void unbindVisibleConnectionLocked() {
        mContext.unbindService(mVisibleConnection);
        mVisibleBound = false;
    }

    @GuardedBy("mMethodMap")
    private boolean bindCurrentInputMethodServiceLocked(ServiceConnection conn, int flags) {
        if (mCurIntent == null || conn == null) {
            Slog.e(TAG, "--- bind failed: service = " + mCurIntent + ", conn = " + conn);
            return false;
        }
        return mContext.bindServiceAsUser(mCurIntent, conn, flags,
                new UserHandle(mSettings.getCurrentUserId()));
    }

    @GuardedBy("mMethodMap")
    private boolean bindCurrentInputMethodServiceVisibleConnectionLocked() {
        mVisibleBound = bindCurrentInputMethodServiceLocked(mVisibleConnection,
                IME_VISIBLE_BIND_FLAGS);
        return mVisibleBound;
    }

    @GuardedBy("mMethodMap")
    private boolean bindCurrentInputMethodServiceMainConnectionLocked() {
        mHasConnection = bindCurrentInputMethodServiceLocked(mMainConnection,
                mImeConnectionBindFlags);
        return mHasConnection;
    }

    /**
     * Bind the IME so that it can be shown.
     *
     * <p>
     * Performs a rebind if no binding is achieved in {@link #TIME_TO_RECONNECT} milliseconds.
     */
    @GuardedBy("mMethodMap")
    void setCurrentMethodVisibleLocked() {
        if (mCurMethod != null) {
            if (DEBUG) Slog.d(TAG, "setCurrentMethodVisibleLocked: mCurToken=" + mCurToken);
            if (mHasConnection && !mVisibleBound) {
                bindCurrentInputMethodServiceVisibleConnectionLocked();
            }
            return;
        }

        // No IME is currently connected. Reestablish the main connection.
        if (!mHasConnection) {
            if (DEBUG) {
                Slog.d(TAG, "Cannot show input: no IME bound. Rebinding.");
            }
            bindCurrentMethodLocked();
            return;
        }

        long bindingDuration = SystemClock.uptimeMillis() - mLastBindTime;
        if (bindingDuration >= TIME_TO_RECONNECT) {
            // The client has asked to have the input method shown, but
            // we have been sitting here too long with a connection to the
            // service and no interface received, so let's disconnect/connect
            // to try to prod things along.
            EventLog.writeEvent(EventLogTags.IMF_FORCE_RECONNECT_IME, getSelectedMethodId(),
                    bindingDuration, 1);
            Slog.w(TAG, "Force disconnect/connect to the IME in setCurrentMethodVisibleLocked()");
            unbindMainConnectionLocked();
            bindCurrentInputMethodServiceMainConnectionLocked();
        } else {
            if (DEBUG) {
                Slog.d(TAG, "Can't show input: connection = " + mHasConnection + ", time = "
                        + (TIME_TO_RECONNECT - bindingDuration));
            }
        }
    }

    /**
     * Remove the binding needed for the IME to be shown.
     */
    @GuardedBy("mMethodMap")
    void setCurrentMethodNotVisibleLocked() {
        if (mVisibleBound) {
            unbindVisibleConnectionLocked();
        }
    }
}
