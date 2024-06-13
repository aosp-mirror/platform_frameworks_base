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

import static android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED;
import static android.content.Context.DEVICE_ID_DEFAULT;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.Display.INVALID_DISPLAY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManagerInternal;
import android.inputmethodservice.InputMethodService;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Slog;
import android.view.Display;
import android.view.WindowManager;
import android.view.inputmethod.InputMethod;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.inputmethod.IInputMethod;
import com.android.internal.inputmethod.InlineSuggestionsRequestCallback;
import com.android.internal.inputmethod.InlineSuggestionsRequestInfo;
import com.android.internal.inputmethod.InputBindResult;
import com.android.internal.inputmethod.UnbindReason;
import com.android.server.EventLogTags;
import com.android.server.wm.WindowManagerInternal;

import java.util.concurrent.CountDownLatch;

/**
 * A controller managing the state of the input method binding.
 */
final class InputMethodBindingController {
    static final boolean DEBUG = false;
    private static final String TAG = InputMethodBindingController.class.getSimpleName();

    /** Time in milliseconds that the IME service has to bind before it is reconnected. */
    static final long TIME_TO_RECONNECT = 3 * 1000;

    @UserIdInt final int mUserId;
    @NonNull private final InputMethodManagerService mService;
    @NonNull private final Context mContext;
    @NonNull private final AutofillSuggestionsController mAutofillController;
    @NonNull private final PackageManagerInternal mPackageManagerInternal;
    @NonNull private final WindowManagerInternal mWindowManagerInternal;

    @GuardedBy("ImfLock.class") private long mLastBindTime;
    @GuardedBy("ImfLock.class") private boolean mHasMainConnection;
    @GuardedBy("ImfLock.class") @Nullable private String mCurId;
    @GuardedBy("ImfLock.class") @Nullable private String mSelectedMethodId;
    @GuardedBy("ImfLock.class") @Nullable private Intent mCurIntent;
    @GuardedBy("ImfLock.class") @Nullable private IInputMethodInvoker mCurMethod;
    @GuardedBy("ImfLock.class") private int mCurMethodUid = Process.INVALID_UID;
    @GuardedBy("ImfLock.class") @Nullable private IBinder mCurToken;
    @GuardedBy("ImfLock.class") private int mCurTokenDisplayId = INVALID_DISPLAY;
    @GuardedBy("ImfLock.class") private int mCurSeq;
    @GuardedBy("ImfLock.class") private boolean mVisibleBound;
    @GuardedBy("ImfLock.class") private boolean mSupportsStylusHw;
    @GuardedBy("ImfLock.class") private boolean mSupportsConnectionlessStylusHw;

    /** The display id for which the latest startInput was called. */
    @GuardedBy("ImfLock.class") private int mDisplayIdToShowIme = INVALID_DISPLAY;
    @GuardedBy("ImfLock.class") private int mDeviceIdToShowIme = DEVICE_ID_DEFAULT;

    @Nullable private CountDownLatch mLatchForTesting;

    /**
     * Binding flags for establishing connection to the {@link InputMethodService}.
     */
    @VisibleForTesting
    static final int IME_CONNECTION_BIND_FLAGS =
            Context.BIND_AUTO_CREATE
                    | Context.BIND_NOT_VISIBLE
                    | Context.BIND_NOT_FOREGROUND
                    | Context.BIND_IMPORTANT_BACKGROUND
                    | Context.BIND_SCHEDULE_LIKE_TOP_APP;

    private final int mImeConnectionBindFlags;

    /**
     * Binding flags used only while the {@link InputMethodService} is showing window.
     */
    @VisibleForTesting
    static final int IME_VISIBLE_BIND_FLAGS =
            Context.BIND_AUTO_CREATE
                    | Context.BIND_TREAT_LIKE_ACTIVITY
                    | Context.BIND_FOREGROUND_SERVICE
                    | Context.BIND_INCLUDE_CAPABILITIES
                    | Context.BIND_SHOWING_UI;

    InputMethodBindingController(@UserIdInt int userId,
            @NonNull InputMethodManagerService service) {
        this(userId, service, IME_CONNECTION_BIND_FLAGS, null /* latchForTesting */);
    }

    InputMethodBindingController(@UserIdInt int userId,
            @NonNull InputMethodManagerService service, int imeConnectionBindFlags,
            CountDownLatch latchForTesting) {
        mUserId = userId;
        mService = service;
        mContext = mService.mContext;
        mAutofillController = new AutofillSuggestionsController(this);
        mPackageManagerInternal = mService.mPackageManagerInternal;
        mWindowManagerInternal = mService.mWindowManagerInternal;
        mImeConnectionBindFlags = imeConnectionBindFlags;
        mLatchForTesting = latchForTesting;
    }

    /**
     * Time that we last initiated a bind to the input method, to determine
     * if we should try to disconnect and reconnect to it.
     */
    @GuardedBy("ImfLock.class")
    long getLastBindTime() {
        return mLastBindTime;
    }

    /**
     * Set to true if our ServiceConnection is currently actively bound to
     * a service (whether or not we have gotten its IBinder back yet).
     */
    @GuardedBy("ImfLock.class")
    boolean hasMainConnection() {
        return mHasMainConnection;
    }

    /**
     * Id obtained with {@link InputMethodInfo#getId()} for the input method that we are currently
     * connected to or in the process of connecting to.
     *
     * <p>This can be {@code null} when no input method is connected.</p>
     *
     * @see #getSelectedMethodId()
     */
    @GuardedBy("ImfLock.class")
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
    @GuardedBy("ImfLock.class")
    @Nullable
    String getSelectedMethodId() {
        return mSelectedMethodId;
    }

    @GuardedBy("ImfLock.class")
    void setSelectedMethodId(@Nullable String selectedMethodId) {
        mSelectedMethodId = selectedMethodId;
    }

    /**
     * Returns {@link InputMethodInfo} that is queried from {@link #getSelectedMethodId()}.
     *
     * @return {@link InputMethodInfo} whose IME ID is the same as {@link #getSelectedMethodId()}.
     *         {@code null} otherwise
     */
    @GuardedBy("ImfLock.class")
    @Nullable
    InputMethodInfo getSelectedMethod() {
        return InputMethodSettingsRepository.get(mUserId).getMethodMap().get(mSelectedMethodId);
    }

    /**
     * The token we have made for the currently active input method, to
     * identify it in the future.
     */
    @GuardedBy("ImfLock.class")
    @Nullable
    IBinder getCurToken() {
        return mCurToken;
    }

    /**
     * Returns the displayId associated with {@link #getCurToken()}.
     *
     * @return the displayId associated with {@link #getCurToken()}. {@link Display#INVALID_DISPLAY}
     *         while {@link #getCurToken()} returns {@code null}
     */
    @GuardedBy("ImfLock.class")
    int getCurTokenDisplayId() {
        return mCurTokenDisplayId;
    }

    /**
     * The Intent used to connect to the current input method.
     */
    @GuardedBy("ImfLock.class")
    @Nullable
    Intent getCurIntent() {
        return mCurIntent;
    }

    /**
     * The current binding sequence number, incremented every time there is
     * a new bind performed.
     */
    @GuardedBy("ImfLock.class")
    int getSequenceNumber() {
        return mCurSeq;
    }

    /**
     * Increase the current binding sequence number by one.
     * Reset to 1 on overflow.
     */
    @GuardedBy("ImfLock.class")
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
    @GuardedBy("ImfLock.class")
    @Nullable
    IInputMethodInvoker getCurMethod() {
        return mCurMethod;
    }

    /**
     * If not {@link Process#INVALID_UID}, then the UID of {@link #getCurIntent()}.
     */
    @GuardedBy("ImfLock.class")
    int getCurMethodUid() {
        return mCurMethodUid;
    }

    /**
     * Indicates whether {@link #mVisibleConnection} is currently in use.
     */
    @GuardedBy("ImfLock.class")
    boolean isVisibleBound() {
        return mVisibleBound;
    }

    /**
     * Returns {@code true} if current IME supports Stylus Handwriting.
     */
    @GuardedBy("ImfLock.class")
    boolean supportsStylusHandwriting() {
        return mSupportsStylusHw;
    }

    /** Returns whether the current IME supports connectionless stylus handwriting sessions. */
    @GuardedBy("ImfLock.class")
    boolean supportsConnectionlessStylusHandwriting() {
        return mSupportsConnectionlessStylusHw;
    }

    /**
     * Used to bring IME service up to visible adjustment while it is being shown.
     */
    @GuardedBy("ImfLock.class")
    private final ServiceConnection mVisibleConnection = new ServiceConnection() {
        @Override public void onBindingDied(ComponentName name) {
            synchronized (ImfLock.class) {
                mAutofillController.invalidateAutofillSession();
                if (isVisibleBound()) {
                    unbindVisibleConnection();
                }
            }
        }

        @Override public void onServiceConnected(ComponentName name, IBinder service) {
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            synchronized (ImfLock.class) {
                mAutofillController.invalidateAutofillSession();
            }
        }
    };

    /**
     * Used to bind the IME while it is not currently being shown.
     */
    @GuardedBy("ImfLock.class")
    private final ServiceConnection mMainConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.onServiceConnected");
            synchronized (ImfLock.class) {
                if (mCurIntent != null && name.equals(mCurIntent.getComponent())) {
                    mCurMethod = IInputMethodInvoker.create(IInputMethod.Stub.asInterface(service));
                    updateCurrentMethodUid();
                    if (mCurToken == null) {
                        Slog.w(TAG, "Service connected without a token!");
                        unbindCurrentMethod();
                        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
                        return;
                    }
                    if (DEBUG) Slog.v(TAG, "Initiating attach with token: " + mCurToken);
                    final InputMethodInfo info =
                            InputMethodSettingsRepository.get(mUserId).getMethodMap().get(
                                    mSelectedMethodId);
                    boolean supportsStylusHwChanged =
                            mSupportsStylusHw != info.supportsStylusHandwriting();
                    mSupportsStylusHw = info.supportsStylusHandwriting();
                    if (supportsStylusHwChanged) {
                        InputMethodManager.invalidateLocalStylusHandwritingAvailabilityCaches();
                    }
                    boolean supportsConnectionlessStylusHwChanged =
                            mSupportsConnectionlessStylusHw
                                    != info.supportsConnectionlessStylusHandwriting();
                    if (supportsConnectionlessStylusHwChanged) {
                        mSupportsConnectionlessStylusHw =
                                info.supportsConnectionlessStylusHandwriting();
                        InputMethodManager
                                .invalidateLocalConnectionlessStylusHandwritingAvailabilityCaches();
                    }
                    mService.initializeImeLocked(mCurMethod, mCurToken);
                    mService.scheduleNotifyImeUidToAudioService(mCurMethodUid);
                    mService.reRequestCurrentClientSessionLocked();
                    mAutofillController.performOnCreateInlineSuggestionsRequest();
                }

                // reset Handwriting event receiver.
                // always call this as it handles changes in mSupportsStylusHw. It is a noop
                // if unchanged.
                mService.scheduleResetStylusHandwriting();
            }
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);

            if (mLatchForTesting != null) {
                mLatchForTesting.countDown(); // Notify the finish to tests
            }
        }

        @GuardedBy("ImfLock.class")
        private void updateCurrentMethodUid() {
            final String curMethodPackage = mCurIntent.getComponent().getPackageName();
            final int curMethodUid = mPackageManagerInternal.getPackageUid(
                    curMethodPackage, 0 /* flags */, mUserId);
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
            synchronized (ImfLock.class) {
                if (DEBUG) {
                    Slog.v(TAG, "Service disconnected: " + name + " mCurIntent=" + mCurIntent);
                }
                if (mCurMethod != null && mCurIntent != null
                        && name.equals(mCurIntent.getComponent())) {
                    // We consider this to be a new bind attempt, since the system
                    // should now try to restart the service for us.
                    mLastBindTime = SystemClock.uptimeMillis();
                    clearCurMethodAndSessions();
                    mService.clearInputShownLocked();
                    mService.unbindCurrentClientLocked(UnbindReason.DISCONNECT_IME);
                }
            }
        }
    };

    @GuardedBy("ImfLock.class")
    void invalidateAutofillSession() {
        mAutofillController.invalidateAutofillSession();
    }

    @GuardedBy("ImfLock.class")
    void onCreateInlineSuggestionsRequest(InlineSuggestionsRequestInfo requestInfo,
            InlineSuggestionsRequestCallback callback, boolean touchExplorationEnabled) {
        mAutofillController.onCreateInlineSuggestionsRequest(requestInfo, callback,
                touchExplorationEnabled);
    }

    @GuardedBy("ImfLock.class")
    @Nullable
    IBinder getCurHostInputToken() {
        return mAutofillController.getCurHostInputToken();
    }

    @GuardedBy("ImfLock.class")
    void unbindCurrentMethod() {
        if (isVisibleBound()) {
            unbindVisibleConnection();
        }

        if (hasMainConnection()) {
            unbindMainConnection();
        }

        if (getCurToken() != null) {
            removeCurrentToken();
            mService.resetSystemUiLocked();
            mAutofillController.onResetSystemUi();
        }

        mCurId = null;
        clearCurMethodAndSessions();
    }

    @GuardedBy("ImfLock.class")
    private void clearCurMethodAndSessions() {
        mService.clearClientSessionsLocked();
        mCurMethod = null;
        mCurMethodUid = Process.INVALID_UID;
    }

    @GuardedBy("ImfLock.class")
    private void removeCurrentToken() {
        if (DEBUG) {
            Slog.v(TAG,
                    "Removing window token: " + mCurToken + " for display: " + mCurTokenDisplayId);
        }
        mWindowManagerInternal.removeWindowToken(mCurToken, true /* removeWindows */,
                false /* animateExit */, mCurTokenDisplayId);
        mCurToken = null;
        mCurTokenDisplayId = INVALID_DISPLAY;
    }

    @GuardedBy("ImfLock.class")
    @NonNull
    InputBindResult bindCurrentMethod() {
        if (mSelectedMethodId == null) {
            Slog.e(TAG, "mSelectedMethodId is null!");
            return InputBindResult.NO_IME;
        }

        InputMethodInfo info = InputMethodSettingsRepository.get(mUserId).getMethodMap().get(
                mSelectedMethodId);
        if (info == null) {
            throw new IllegalArgumentException("Unknown id: " + mSelectedMethodId);
        }

        mCurIntent = createImeBindingIntent(info.getComponent());

        if (bindCurrentInputMethodServiceMainConnection()) {
            mCurId = info.getId();
            mLastBindTime = SystemClock.uptimeMillis();

            mCurToken = new Binder();
            mCurTokenDisplayId = mDisplayIdToShowIme;
            if (DEBUG) {
                Slog.v(TAG, "Adding window token: " + mCurToken + " for display: "
                        + mDisplayIdToShowIme);
            }
            mWindowManagerInternal.addWindowToken(mCurToken,
                    WindowManager.LayoutParams.TYPE_INPUT_METHOD,
                    mDisplayIdToShowIme, null /* options */);
            return new InputBindResult(
                    InputBindResult.ResultCode.SUCCESS_WAITING_IME_BINDING,
                    null, null, null, mCurId, mCurSeq, false);
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
        var options = ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(
                        MODE_BACKGROUND_ACTIVITY_START_DENIED);
        intent.putExtra(Intent.EXTRA_CLIENT_INTENT, PendingIntent.getActivity(
                mContext, 0, new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS),
                PendingIntent.FLAG_IMMUTABLE, options.toBundle()));
        return intent;
    }

    @GuardedBy("ImfLock.class")
    private void unbindMainConnection() {
        mContext.unbindService(mMainConnection);
        mHasMainConnection = false;
    }

    @GuardedBy("ImfLock.class")
    void unbindVisibleConnection() {
        mContext.unbindService(mVisibleConnection);
        mVisibleBound = false;
    }

    @GuardedBy("ImfLock.class")
    private boolean bindCurrentInputMethodService(ServiceConnection conn, int flags) {
        if (mCurIntent == null || conn == null) {
            Slog.e(TAG, "--- bind failed: service = " + mCurIntent + ", conn = " + conn);
            return false;
        }
        return mContext.bindServiceAsUser(mCurIntent, conn, flags, new UserHandle(mUserId));
    }

    @GuardedBy("ImfLock.class")
    private boolean bindCurrentInputMethodServiceMainConnection() {
        mHasMainConnection = bindCurrentInputMethodService(mMainConnection,
                mImeConnectionBindFlags);
        return mHasMainConnection;
    }

    /**
     * Bind the IME so that it can be shown.
     *
     * <p>
     * Performs a rebind if no binding is achieved in {@link #TIME_TO_RECONNECT} milliseconds.
     */
    @GuardedBy("ImfLock.class")
    void setCurrentMethodVisible() {
        if (mCurMethod != null) {
            if (DEBUG) Slog.d(TAG, "setCurrentMethodVisible: mCurToken=" + mCurToken);
            if (hasMainConnection() && !isVisibleBound()) {
                mVisibleBound = bindCurrentInputMethodService(mVisibleConnection,
                        IME_VISIBLE_BIND_FLAGS);
            }
            return;
        }

        // No IME is currently connected. Reestablish the main connection.
        if (!hasMainConnection()) {
            if (DEBUG) {
                Slog.d(TAG, "Cannot show input: no IME bound. Rebinding.");
            }
            bindCurrentMethod();
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
            Slog.w(TAG, "Force disconnect/connect to the IME in setCurrentMethodVisible()");
            unbindMainConnection();
            bindCurrentInputMethodServiceMainConnection();
        } else {
            if (DEBUG) {
                Slog.d(TAG, "Can't show input: connection = " + mHasMainConnection + ", time = "
                        + (TIME_TO_RECONNECT - bindingDuration));
            }
        }
    }

    /**
     * Remove the binding needed for the IME to be shown.
     */
    @GuardedBy("ImfLock.class")
    void setCurrentMethodNotVisible() {
        if (isVisibleBound()) {
            unbindVisibleConnection();
        }
    }

    @GuardedBy("ImfLock.class")
    void setDisplayIdToShowIme(int displayId) {
        mDisplayIdToShowIme = displayId;
    }

    @GuardedBy("ImfLock.class")
    int getDisplayIdToShowIme() {
        return mDisplayIdToShowIme;
    }

    @GuardedBy("ImfLock.class")
    void setDeviceIdToShowIme(int deviceId) {
        mDeviceIdToShowIme = deviceId;
    }

    @GuardedBy("ImfLock.class")
    int getDeviceIdToShowIme() {
        return mDeviceIdToShowIme;
    }
}
