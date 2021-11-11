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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManagerInternal;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.inputmethod.InputMethodInfo;

import com.android.internal.inputmethod.UnbindReason;
import com.android.internal.view.IInputMethod;
import com.android.server.inputmethod.InputMethodManagerService.ClientState;

/**
 * A controller managing the state of the input method binding.
 */
final class InputMethodBindingController {
    static final boolean DEBUG = false;
    private static final String TAG = InputMethodBindingController.class.getSimpleName();

    @NonNull private final InputMethodManagerService mService;
    @NonNull private final Context mContext;
    @NonNull private final ArrayMap<String, InputMethodInfo> mMethodMap;
    @NonNull private final InputMethodUtils.InputMethodSettings mSettings;
    @NonNull private final PackageManagerInternal mPackageManagerInternal;

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


    InputMethodBindingController(@NonNull InputMethodManagerService service) {
        mService = service;
        mContext = mService.mContext;
        mMethodMap = mService.mMethodMap;
        mSettings = mService.mSettings;
        mPackageManagerInternal = mService.mPackageManagerInternal;
    }

    /**
     * Time that we last initiated a bind to the input method, to determine
     * if we should try to disconnect and reconnect to it.
     */
    long getLastBindTime() {
        return mLastBindTime;
    }

    void setLastBindTime(long lastBindTime) {
        mLastBindTime = lastBindTime;
    }

    /**
     * Set to true if our ServiceConnection is currently actively bound to
     * a service (whether or not we have gotten its IBinder back yet).
     */
    boolean hasConnection() {
        return mHasConnection;
    }

    void setHasConnection(boolean hasConnection) {
        mHasConnection = hasConnection;
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

    void setCurId(@Nullable String curId) {
        mCurId = curId;
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

    void setCurToken(IBinder curToken) {
        mCurToken = curToken;
    }

    /**
     * The Intent used to connect to the current input method.
     */
    @Nullable
    Intent getCurIntent() {
        return mCurIntent;
    }

    void setCurIntent(@Nullable Intent curIntent) {
        mCurIntent = curIntent;
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

    void setCurMethod(@Nullable IInputMethod curMethod) {
        mCurMethod = curMethod;
    }

    /**
     * If not {@link Process#INVALID_UID}, then the UID of {@link #getCurIntent()}.
     */
    int getCurMethodUid() {
        return mCurMethodUid;
    }

    void setCurMethodUid(int curMethodUid) {
        mCurMethodUid = curMethodUid;
    }

    /**
     * Indicates whether {@link #getVisibleConnection} is currently in use.
     */
    boolean isVisibleBound() {
        return mVisibleBound;
    }

    void setVisibleBound(boolean visibleBound) {
        mVisibleBound = visibleBound;
    }

    /**
     * Used to bring IME service up to visible adjustment while it is being shown.
     */
    @NonNull
    ServiceConnection getVisibleConnection() {
        return mVisibleConnection;
    }

    private final ServiceConnection mVisibleConnection = new ServiceConnection() {
        @Override public void onBindingDied(ComponentName name) {
            synchronized (mMethodMap) {
                if (mVisibleBound) {
                    mContext.unbindService(mVisibleConnection);
                    mVisibleBound = false;
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
    @NonNull
    ServiceConnection getMainConnection() {
        return mMainConnection;
    }

    private final ServiceConnection mMainConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.onServiceConnected");
            synchronized (mMethodMap) {
                if (mCurIntent != null && name.equals(mCurIntent.getComponent())) {
                    mCurMethod = IInputMethod.Stub.asInterface(service);
                    final String curMethodPackage = mCurIntent.getComponent().getPackageName();
                    final int curMethodUid = mPackageManagerInternal.getPackageUid(
                            curMethodPackage, 0 /* flags */, mSettings.getCurrentUserId());
                    if (curMethodUid < 0) {
                        Slog.e(TAG, "Failed to get UID for package=" + curMethodPackage);
                        mCurMethodUid = Process.INVALID_UID;
                    } else {
                        mCurMethodUid = curMethodUid;
                    }
                    if (mCurToken == null) {
                        Slog.w(TAG, "Service connected without a token!");
                        mService.unbindCurrentMethodLocked();
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
                    ClientState curClient = mService.getCurClient();
                    if (curClient != null) {
                        mService.clearClientSessionLocked(curClient);
                        mService.requestClientSessionLocked(curClient);
                    }
                }
            }
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
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
                    mService.clearCurMethodLocked();
                    // We consider this to be a new bind attempt, since the system
                    // should now try to restart the service for us.
                    mLastBindTime = SystemClock.uptimeMillis();
                    mService.setShowRequested(mService.isInputShown());
                    mService.setInputShown(false);
                    mService.unbindCurrentClientLocked(UnbindReason.DISCONNECT_IME);
                }
            }
        }
    };

}
