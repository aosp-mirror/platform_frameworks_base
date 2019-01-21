/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.AnyThread;
import android.annotation.BinderThread;
import android.annotation.IntDef;
import android.annotation.MainThread;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.inputmethodservice.MultiClientInputMethodServiceDelegate;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.view.InputChannel;
import android.view.WindowManager.LayoutParams.SoftInputModeFlags;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnectionInspector.MissingMethodFlags;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.view.inputmethod.InputMethodSystemProperty;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.inputmethod.IMultiClientInputMethod;
import com.android.internal.inputmethod.IMultiClientInputMethodPrivilegedOperations;
import com.android.internal.inputmethod.IMultiClientInputMethodSession;
import com.android.internal.inputmethod.StartInputFlags;
import com.android.internal.inputmethod.StartInputReason;
import com.android.internal.inputmethod.UnbindReason;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethodClient;
import com.android.internal.view.IInputMethodManager;
import com.android.internal.view.IInputMethodSession;
import com.android.internal.view.InputBindResult;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.wm.WindowManagerInternal;

import java.io.FileDescriptor;
import java.lang.annotation.Retention;
import java.util.Collections;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Actual implementation of multi-client InputMethodManagerService.
 *
 * <p>This system service is intentionally compatible with {@link InputMethodManagerService} so that
 * we can switch the implementation at the boot time.</p>
 */
public final class MultiClientInputMethodManagerService {
    static final String TAG = "MultiClientInputMethodManagerService";
    static final boolean DEBUG = false;

    private static final long RECONNECT_DELAY_MSEC = 1000;

    /**
     * Unlike {@link InputMethodManagerService}, {@link MultiClientInputMethodManagerService}
     * always binds to the IME with {@link Context#BIND_FOREGROUND_SERVICE} for now for simplicity.
     */
    private static final int IME_CONNECTION_UNIFIED_BIND_FLAGS =
            Context.BIND_AUTO_CREATE
                    | Context.BIND_NOT_VISIBLE
                    | Context.BIND_NOT_FOREGROUND
                    | Context.BIND_FOREGROUND_SERVICE;

    private static final ComponentName sImeComponentName =
            InputMethodSystemProperty.sMultiClientImeComponentName;

    private static void reportNotSupported() {
        if (DEBUG) {
            Slog.d(TAG, "non-supported operation. callers=" + Debug.getCallers(3));
        }
    }

    /**
     * {@link MultiClientInputMethodManagerService} is not intended to be instantiated.
     */
    private MultiClientInputMethodManagerService() {
    }

    /**
     * The implementation of {@link SystemService} for multi-client IME.
     */
    public static final class Lifecycle extends SystemService {
        private final ApiCallbacks mApiCallbacks;
        private final OnWorkerThreadCallback mOnWorkerThreadCallback;

        @MainThread
        public Lifecycle(Context context) {
            super(context);

            final UserToInputMethodInfoMap userIdToInputMethodInfoMapper =
                    new UserToInputMethodInfoMap();
            final UserDataMap userDataMap = new UserDataMap();
            final HandlerThread workerThread = new HandlerThread(TAG);
            workerThread.start();
            mApiCallbacks = new ApiCallbacks(context, userDataMap, userIdToInputMethodInfoMapper);
            mOnWorkerThreadCallback = new OnWorkerThreadCallback(
                    context, userDataMap, userIdToInputMethodInfoMapper,
                    new Handler(workerThread.getLooper(), msg -> false, true));

            LocalServices.addService(InputMethodManagerInternal.class,
                    new InputMethodManagerInternal() {
                        @Override
                        public void setInteractive(boolean interactive) {
                            reportNotSupported();
                        }

                        @Override
                        public void hideCurrentInputMethod() {
                            reportNotSupported();
                        }

                        @Override
                        public void startVrInputMethodNoCheck(ComponentName componentName) {
                            reportNotSupported();
                        }

                        @Override
                        public List<InputMethodInfo> getInputMethodListAsUser(
                                @UserIdInt int userId) {
                            return userIdToInputMethodInfoMapper.getAsList(userId);
                        }

                        @Override
                        public List<InputMethodInfo> getEnabledInputMethodListAsUser(
                                @UserIdInt int userId) {
                            return userIdToInputMethodInfoMapper.getAsList(userId);
                        }
                    });
        }

        @MainThread
        @Override
        public void onBootPhase(int phase) {
            mOnWorkerThreadCallback.getHandler().sendMessage(PooledLambda.obtainMessage(
                    OnWorkerThreadCallback::onBootPhase, mOnWorkerThreadCallback, phase));
        }

        @MainThread
        @Override
        public void onStart() {
            publishBinderService(Context.INPUT_METHOD_SERVICE, mApiCallbacks);
        }

        @MainThread
        @Override
        public void onStartUser(@UserIdInt int userId) {
            mOnWorkerThreadCallback.getHandler().sendMessage(PooledLambda.obtainMessage(
                    OnWorkerThreadCallback::onStartUser, mOnWorkerThreadCallback, userId));
        }

        @MainThread
        @Override
        public void onUnlockUser(@UserIdInt int userId) {
            mOnWorkerThreadCallback.getHandler().sendMessage(PooledLambda.obtainMessage(
                    OnWorkerThreadCallback::onUnlockUser, mOnWorkerThreadCallback, userId));
        }

        @MainThread
        @Override
        public void onStopUser(@UserIdInt int userId) {
            mOnWorkerThreadCallback.getHandler().sendMessage(PooledLambda.obtainMessage(
                    OnWorkerThreadCallback::onStopUser, mOnWorkerThreadCallback, userId));
        }
    }

    private static final class OnWorkerThreadCallback {
        private final Context mContext;
        private final UserDataMap mUserDataMap;
        private final UserToInputMethodInfoMap mInputMethodInfoMap;
        private final Handler mHandler;

        OnWorkerThreadCallback(Context context, UserDataMap userDataMap,
                UserToInputMethodInfoMap inputMethodInfoMap, Handler handler) {
            mContext = context;
            mUserDataMap = userDataMap;
            mInputMethodInfoMap = inputMethodInfoMap;
            mHandler = handler;
        }

        @AnyThread
        Handler getHandler() {
            return mHandler;
        }

        @WorkerThread
        private void tryBindInputMethodService(@UserIdInt int userId) {
            final PerUserData data = mUserDataMap.get(userId);
            if (data == null) {
                Slog.i(TAG, "tryBindInputMethodService is called for an unknown user=" + userId);
                return;
            }

            final InputMethodInfo imi = queryInputMethod(mContext, userId, sImeComponentName);
            if (imi == null) {
                Slog.w(TAG, "Multi-client InputMethod is not found. component="
                        + sImeComponentName);
                synchronized (data.mLock) {
                    switch (data.mState) {
                        case PerUserState.USER_LOCKED:
                        case PerUserState.SERVICE_NOT_QUERIED:
                        case PerUserState.SERVICE_RECOGNIZED:
                        case PerUserState.UNBIND_CALLED:
                            // Safe to clean up.
                            mInputMethodInfoMap.remove(userId);
                            break;
                    }
                }
                return;
            }

            synchronized (data.mLock) {
                switch (data.mState) {
                    case PerUserState.USER_LOCKED:
                        // If the user is still locked, we currently do not try to start IME.
                        return;
                    case PerUserState.SERVICE_NOT_QUERIED:
                    case PerUserState.SERVICE_RECOGNIZED:
                    case PerUserState.UNBIND_CALLED:
                        break;
                    case PerUserState.WAITING_SERVICE_CONNECTED:
                    case PerUserState.SERVICE_CONNECTED:
                        // OK, nothing to do.
                        return;
                    default:
                        Slog.wtf(TAG, "Unknown state=" + data.mState);
                        return;
                }
                data.mState = PerUserState.SERVICE_RECOGNIZED;
                data.mCurrentInputMethodInfo = imi;
                mInputMethodInfoMap.put(userId, imi);
                final boolean bindResult = data.bindServiceLocked(mContext, userId);
                if (!bindResult) {
                    Slog.e(TAG, "Failed to bind Multi-client InputMethod.");
                    return;
                }
                data.mState = PerUserState.WAITING_SERVICE_CONNECTED;
            }
        }

        @WorkerThread
        void onStartUser(@UserIdInt int userId) {
            if (DEBUG) {
                Slog.v(TAG, "onStartUser userId=" + userId);
            }
            final PerUserData data = new PerUserData(userId, null, PerUserState.USER_LOCKED, this);
            mUserDataMap.put(userId, data);
        }

        @WorkerThread
        void onUnlockUser(@UserIdInt int userId) {
            if (DEBUG) {
                Slog.v(TAG, "onUnlockUser() userId=" + userId);
            }
            final PerUserData data = mUserDataMap.get(userId);
            if (data == null) {
                Slog.i(TAG, "onUnlockUser is called for an unknown user=" + userId);
                return;
            }
            synchronized (data.mLock) {
                switch (data.mState) {
                    case PerUserState.USER_LOCKED:
                        data.mState = PerUserState.SERVICE_NOT_QUERIED;
                        tryBindInputMethodService(userId);
                        break;
                    default:
                        Slog.wtf(TAG, "Unknown state=" + data.mState);
                        break;
                }
            }
        }

        @WorkerThread
        void onStopUser(@UserIdInt int userId) {
            if (DEBUG) {
                Slog.v(TAG, "onStopUser() userId=" + userId);
            }
            mInputMethodInfoMap.remove(userId);
            final PerUserData data = mUserDataMap.removeReturnOld(userId);
            if (data == null) {
                Slog.i(TAG, "onStopUser is called for an unknown user=" + userId);
                return;
            }
            synchronized (data.mLock) {
                switch (data.mState) {
                    case PerUserState.USER_LOCKED:
                    case PerUserState.SERVICE_RECOGNIZED:
                    case PerUserState.UNBIND_CALLED:
                        // OK, nothing to do.
                        return;
                    case PerUserState.SERVICE_CONNECTED:
                    case PerUserState.WAITING_SERVICE_CONNECTED:
                        break;
                    default:
                        Slog.wtf(TAG, "Unknown state=" + data.mState);
                        break;
                }
                data.unbindServiceLocked(mContext);
                data.mState = PerUserState.UNBIND_CALLED;
                data.mCurrentInputMethod = null;

                // When a Service is explicitly unbound with Context.unbindService(),
                // onServiceDisconnected() will not be triggered.  Hence here we explicitly call
                // onInputMethodDisconnectedLocked() as if the Service is already gone.
                data.onInputMethodDisconnectedLocked();
            }
        }

        @WorkerThread
        void onServiceConnected(PerUserData data, IMultiClientInputMethod service) {
            if (DEBUG) {
                Slog.v(TAG, "onServiceConnected() data.mUserId=" + data.mUserId);
            }
            synchronized (data.mLock) {
                switch (data.mState) {
                    case PerUserState.UNBIND_CALLED:
                        // We should ignore this callback.
                        return;
                    case PerUserState.WAITING_SERVICE_CONNECTED:
                        // OK.
                        data.mState = PerUserState.SERVICE_CONNECTED;
                        data.mCurrentInputMethod = service;
                        try {
                            data.mCurrentInputMethod.initialize(new ImeCallbacks(data));
                        } catch (RemoteException e) {
                        }
                        data.onInputMethodConnectedLocked();
                        break;
                    default:
                        Slog.wtf(TAG, "Unknown state=" + data.mState);
                        return;
                }
            }
        }

        @WorkerThread
        void onServiceDisconnected(PerUserData data) {
            if (DEBUG) {
                Slog.v(TAG, "onServiceDisconnected() data.mUserId=" + data.mUserId);
            }
            final WindowManagerInternal windowManagerInternal =
                    LocalServices.getService(WindowManagerInternal.class);
            synchronized (data.mLock) {
                // We assume the number of tokens would not be that large (up to 10 or so) hence
                // linear search should be acceptable.
                final int numTokens = data.mDisplayIdToImeWindowTokenMap.size();
                for (int i = 0; i < numTokens; ++i) {
                    final TokenInfo info = data.mDisplayIdToImeWindowTokenMap.valueAt(i);
                    windowManagerInternal.removeWindowToken(info.mToken, false, info.mDisplayId);
                }
                data.mDisplayIdToImeWindowTokenMap.clear();
                switch (data.mState) {
                    case PerUserState.UNBIND_CALLED:
                        // We should ignore this callback.
                        return;
                    case PerUserState.WAITING_SERVICE_CONNECTED:
                    case PerUserState.SERVICE_CONNECTED:
                        // onServiceDisconnected() means the biding is still alive.
                        data.mState = PerUserState.WAITING_SERVICE_CONNECTED;
                        data.mCurrentInputMethod = null;
                        data.onInputMethodDisconnectedLocked();
                        break;
                    default:
                        Slog.wtf(TAG, "Unknown state=" + data.mState);
                        return;
                }
            }
        }

        @WorkerThread
        void onBindingDied(PerUserData data) {
            if (DEBUG) {
                Slog.v(TAG, "onBindingDied() data.mUserId=" + data.mUserId);
            }
            final WindowManagerInternal windowManagerInternal =
                    LocalServices.getService(WindowManagerInternal.class);
            synchronized (data.mLock) {
                // We assume the number of tokens would not be that large (up to 10 or so) hence
                // linear search should be acceptable.
                final int numTokens = data.mDisplayIdToImeWindowTokenMap.size();
                for (int i = 0; i < numTokens; ++i) {
                    final TokenInfo info = data.mDisplayIdToImeWindowTokenMap.valueAt(i);
                    windowManagerInternal.removeWindowToken(info.mToken, false, info.mDisplayId);
                }
                data.mDisplayIdToImeWindowTokenMap.clear();
                switch (data.mState) {
                    case PerUserState.UNBIND_CALLED:
                        // We should ignore this callback.
                        return;
                    case PerUserState.WAITING_SERVICE_CONNECTED:
                    case PerUserState.SERVICE_CONNECTED: {
                        // onBindingDied() means the biding is dead.
                        data.mState = PerUserState.UNBIND_CALLED;
                        data.mCurrentInputMethod = null;
                        data.onInputMethodDisconnectedLocked();
                        // Schedule a retry
                        mHandler.sendMessageDelayed(PooledLambda.obtainMessage(
                                OnWorkerThreadCallback::tryBindInputMethodService,
                                this, data.mUserId), RECONNECT_DELAY_MSEC);
                        break;
                    }
                    default:
                        Slog.wtf(TAG, "Unknown state=" + data.mState);
                        return;
                }
            }
        }

        @WorkerThread
        void onBootPhase(int phase) {
            if (DEBUG) {
                Slog.v(TAG, "onBootPhase() phase=" + phase);
            }
            switch (phase) {
                case SystemService.PHASE_ACTIVITY_MANAGER_READY: {
                    final IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
                    filter.addDataScheme("package");
                    mContext.registerReceiver(new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            onPackageAdded(intent);
                        }
                    }, filter, null, mHandler);
                }
                break;
            }
        }

        @WorkerThread
        void onPackageAdded(Intent intent) {
            if (DEBUG) {
                Slog.v(TAG, "onPackageAdded() intent=" + intent);
            }
            final Uri uri = intent.getData();
            if (uri == null) {
                return;
            }
            if (!intent.hasExtra(Intent.EXTRA_UID)) {
                return;
            }
            final String packageName = uri.getSchemeSpecificPart();
            if (sImeComponentName == null
                    || packageName == null
                    || !TextUtils.equals(sImeComponentName.getPackageName(), packageName)) {
                return;
            }
            final int userId = UserHandle.getUserId(intent.getIntExtra(Intent.EXTRA_UID, 0));
            tryBindInputMethodService(userId);
        }
    }

    private static final class WindowInfo {
        final IBinder mWindowToken;
        final int mWindowHandle;

        WindowInfo(IBinder windowToken, int windowCookie) {
            mWindowToken = windowToken;
            mWindowHandle = windowCookie;
        }
    }

    /**
     * Describes the state of each IME client.
     */
    @Retention(SOURCE)
    @IntDef({InputMethodClientState.REGISTERED,
            InputMethodClientState.WAITING_FOR_IME_SESSION,
            InputMethodClientState.READY_TO_SEND_FIRST_BIND_RESULT,
            InputMethodClientState.ALREADY_SENT_BIND_RESULT,
            InputMethodClientState.UNREGISTERED})
    private @interface InputMethodClientState {
        /**
         * {@link IInputMethodManager#addClient(IInputMethodClient, IInputContext, int)} is called
         * and this client is now recognized by the system.  When the system lost the connection to
         * the current IME, all the clients need to be re-initialized from this state.
         */
        int REGISTERED = 1;
        /**
         * This client is notified to the current IME with {@link
         * IMultiClientInputMethod#addClient(int, int, int, int)} but the IME is not yet responded
         * with {@link IMultiClientInputMethodPrivilegedOperations#acceptClient(int,
         * IInputMethodSession, IMultiClientInputMethodSession, InputChannel)}.
         */
        int WAITING_FOR_IME_SESSION = 2;
        /**
         * This client is already accepted by the IME but a valid {@link InputBindResult} has not
         * been returned to the client yet.
         */
        int READY_TO_SEND_FIRST_BIND_RESULT = 3;
        /**
         * This client has already received a valid {@link InputBindResult} at least once. This
         * means that the client can directly call {@link IInputMethodSession} IPCs and key events
         * via {@link InputChannel}. When the current IME is unbound, these client end points also
         * need to be cleared.
         */
        int ALREADY_SENT_BIND_RESULT = 4;
        /**
         * The client process is dying.
         */
        int UNREGISTERED = 5;
    }

    private static final class InputMethodClientIdSource {
        @GuardedBy("InputMethodClientIdSource.class")
        private static int sNextValue = 0;

        private InputMethodClientIdSource() {
        }

        static synchronized int getNext() {
            final int result = sNextValue;
            sNextValue++;
            if (sNextValue < 0) {
                sNextValue = 0;
            }
            return result;
        }
    }

    private static final class WindowHandleSource {
        @GuardedBy("WindowHandleSource.class")
        private static int sNextValue = 0;

        private WindowHandleSource() {
        }

        static synchronized int getNext() {
            final int result = sNextValue;
            sNextValue++;
            if (sNextValue < 0) {
                sNextValue = 0;
            }
            return result;
        }
    }

    private static final class InputMethodClientInfo {
        final IInputMethodClient mClient;
        final int mUid;
        final int mPid;
        final int mSelfReportedDisplayId;
        final int mClientId;

        @GuardedBy("PerUserData.mLock")
        @InputMethodClientState
        int mState;
        @GuardedBy("PerUserData.mLock")
        int mBindingSequence;
        @GuardedBy("PerUserData.mLock")
        InputChannel mWriteChannel;
        @GuardedBy("PerUserData.mLock")
        IInputMethodSession mInputMethodSession;
        @GuardedBy("PerUserData.mLock")
        IMultiClientInputMethodSession mMSInputMethodSession;
        @GuardedBy("PerUserData.mLock")
        final WeakHashMap<IBinder, WindowInfo> mWindowMap = new WeakHashMap<>();

        InputMethodClientInfo(IInputMethodClient client, int uid, int pid,
                int selfReportedDisplayId) {
            mClient = client;
            mUid = uid;
            mPid = pid;
            mSelfReportedDisplayId = selfReportedDisplayId;
            mClientId = InputMethodClientIdSource.getNext();
        }
    }

    private static final class UserDataMap {
        @GuardedBy("mMap")
        private final SparseArray<PerUserData> mMap = new SparseArray<>();

        @AnyThread
        @Nullable
        PerUserData get(@UserIdInt int userId) {
            synchronized (mMap) {
                return mMap.get(userId);
            }
        }

        @AnyThread
        void put(@UserIdInt int userId, PerUserData data) {
            synchronized (mMap) {
                mMap.put(userId, data);
            }
        }

        @AnyThread
        @Nullable
        PerUserData removeReturnOld(@UserIdInt int userId) {
            synchronized (mMap) {
                return mMap.removeReturnOld(userId);
            }
        }
    }

    private static final class TokenInfo {
        final Binder mToken;
        final int mDisplayId;
        TokenInfo(Binder token, int displayId) {
            mToken = token;
            mDisplayId = displayId;
        }
    }

    @Retention(SOURCE)
    @IntDef({
            PerUserState.USER_LOCKED,
            PerUserState.SERVICE_NOT_QUERIED,
            PerUserState.SERVICE_RECOGNIZED,
            PerUserState.WAITING_SERVICE_CONNECTED,
            PerUserState.SERVICE_CONNECTED,
            PerUserState.UNBIND_CALLED})
    private @interface PerUserState {
        /**
         * The user is still locked.
         */
        int USER_LOCKED = 1;
        /**
         * The system has not queried whether there is a multi-client IME or not.
         */
        int SERVICE_NOT_QUERIED = 2;
        /**
         * A multi-client IME specified in {@link #PROP_DEBUG_MULTI_CLIENT_IME} is found in the
         * system, but not bound yet.
         */
        int SERVICE_RECOGNIZED = 3;
        /**
         * {@link Context#bindServiceAsUser(Intent, ServiceConnection, int, Handler, UserHandle)} is
         * already called for the IME but
         * {@link ServiceConnection#onServiceConnected(ComponentName, IBinder)} is not yet called
         * back. This includes once the IME is bound but temporarily disconnected as notified with
         * {@link ServiceConnection#onServiceDisconnected(ComponentName)}.
         */
        int WAITING_SERVICE_CONNECTED = 4;
        /**
         * {@link ServiceConnection#onServiceConnected(ComponentName, IBinder)} is already called
         * back. The IME is ready to be used.
         */
        int SERVICE_CONNECTED = 5;
        /**
         * The binding is gone.  Either {@link Context#unbindService(ServiceConnection)} is
         * explicitly called or the system decided to destroy the binding as notified with
         * {@link ServiceConnection#onBindingDied(ComponentName)}.
         */
        int UNBIND_CALLED = 6;
    }

    /**
     * Takes care of per-user state separation.
     */
    private static final class PerUserData {
        final Object mLock = new Object();

        /**
         * User ID (not UID) that is associated with this data.
         */
        @UserIdInt
        private final int mUserId;

        /**
         * {@link IMultiClientInputMethod} of the currently connected multi-client IME.  This
         * must be non-{@code null} only while {@link #mState} is
         * {@link PerUserState#SERVICE_CONNECTED}.
         */
        @Nullable
        @GuardedBy("mLock")
        IMultiClientInputMethod mCurrentInputMethod;

        /**
         * {@link InputMethodInfo} of the currently selected multi-client IME.  This must be
         * non-{@code null} unless {@link #mState} is {@link PerUserState#SERVICE_NOT_QUERIED}.
         */
        @GuardedBy("mLock")
        @Nullable
        InputMethodInfo mCurrentInputMethodInfo;

        /**
         * Describes the current service state.
         */
        @GuardedBy("mLock")
        @PerUserState
        int mState;

        /**
         * A {@link SparseArray} that maps display ID to IME Window token that is already issued to
         * the IME.
         */
        @GuardedBy("mLock")
        final ArraySet<TokenInfo> mDisplayIdToImeWindowTokenMap = new ArraySet<>();

        @GuardedBy("mLock")
        private final ArrayMap<IBinder, InputMethodClientInfo> mClientMap = new ArrayMap<>();

        @GuardedBy("mLock")
        private SparseArray<InputMethodClientInfo> mClientIdToClientMap = new SparseArray<>();

        private final OnWorkerThreadServiceConnection mOnWorkerThreadServiceConnection;

        /**
         * A {@link ServiceConnection} that is designed to run on a certain worker thread with
         * which {@link OnWorkerThreadCallback} is associated.
         *
         * @see Context#bindServiceAsUser(Intent, ServiceConnection, int, Handler, UserHandle).
         */
        private static final class OnWorkerThreadServiceConnection implements ServiceConnection {
            private final PerUserData mData;
            private final OnWorkerThreadCallback mCallback;

            OnWorkerThreadServiceConnection(PerUserData data, OnWorkerThreadCallback callback) {
                mData = data;
                mCallback = callback;
            }

            @WorkerThread
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                mCallback.onServiceConnected(mData,
                        IMultiClientInputMethod.Stub.asInterface(service));
            }

            @WorkerThread
            @Override
            public void onServiceDisconnected(ComponentName name) {
                mCallback.onServiceDisconnected(mData);
            }

            @WorkerThread
            @Override
            public void onBindingDied(ComponentName name) {
                mCallback.onBindingDied(mData);
            }

            Handler getHandler() {
                return mCallback.getHandler();
            }
        }

        PerUserData(@UserIdInt int userId, @Nullable InputMethodInfo inputMethodInfo,
                @PerUserState int initialState, OnWorkerThreadCallback callback) {
            mUserId = userId;
            mCurrentInputMethodInfo = inputMethodInfo;
            mState = initialState;
            mOnWorkerThreadServiceConnection =
                    new OnWorkerThreadServiceConnection(this, callback);
        }

        @GuardedBy("mLock")
        boolean bindServiceLocked(Context context, @UserIdInt int userId) {
            final Intent intent =
                    new Intent(MultiClientInputMethodServiceDelegate.SERVICE_INTERFACE)
                            .setComponent(mCurrentInputMethodInfo.getComponent())
                            .putExtra(Intent.EXTRA_CLIENT_LABEL,
                                    com.android.internal.R.string.input_method_binding_label)
                            .putExtra(Intent.EXTRA_CLIENT_INTENT, PendingIntent.getActivity(
                                    context, 0,
                                    new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS), 0));

            // Note: Instead of re-dispatching callback from the main thread to the worker thread
            // where OnWorkerThreadCallback is running, we pass the Handler object here so that
            // the callbacks will be directly dispatched to the worker thread.
            return context.bindServiceAsUser(intent, mOnWorkerThreadServiceConnection,
                    IME_CONNECTION_UNIFIED_BIND_FLAGS,
                    mOnWorkerThreadServiceConnection.getHandler(), UserHandle.of(userId));
        }

        @GuardedBy("mLock")
        void unbindServiceLocked(Context context) {
            context.unbindService(mOnWorkerThreadServiceConnection);
        }

        @GuardedBy("mLock")
        @Nullable
        InputMethodClientInfo getClientLocked(IInputMethodClient client) {
            return mClientMap.get(client.asBinder());
        }

        @GuardedBy("mLock")
        @Nullable
        InputMethodClientInfo getClientFromIdLocked(int clientId) {
            return mClientIdToClientMap.get(clientId);
        }

        @GuardedBy("mLock")
        @Nullable
        InputMethodClientInfo removeClientLocked(IInputMethodClient client) {
            final InputMethodClientInfo info = mClientMap.remove(client.asBinder());
            if (info != null) {
                mClientIdToClientMap.remove(info.mClientId);
            }
            return info;
        }

        @GuardedBy("mLock")
        void addClientLocked(int uid, int pid, IInputMethodClient client,
                int selfReportedDisplayId) {
            if (getClientLocked(client) != null) {
                Slog.wtf(TAG, "The same client is added multiple times");
                return;
            }
            final ClientDeathRecipient deathRecipient = new ClientDeathRecipient(this, client);
            try {
                client.asBinder().linkToDeath(deathRecipient, 0);
            } catch (RemoteException e) {
                throw new IllegalStateException(e);
            }
            final InputMethodClientInfo clientInfo =
                    new InputMethodClientInfo(client, uid, pid, selfReportedDisplayId);
            clientInfo.mState = InputMethodClientState.REGISTERED;
            mClientMap.put(client.asBinder(), clientInfo);
            mClientIdToClientMap.put(clientInfo.mClientId, clientInfo);
            switch (mState) {
                case PerUserState.SERVICE_CONNECTED:
                    try {
                        mCurrentInputMethod.addClient(
                                clientInfo.mClientId, clientInfo.mPid, clientInfo.mUid,
                                clientInfo.mSelfReportedDisplayId);
                        clientInfo.mState = InputMethodClientState.WAITING_FOR_IME_SESSION;
                    } catch (RemoteException e) {
                        // TODO(yukawa): Need logging and expected behavior
                    }
                    break;
            }
        }

        @GuardedBy("mLock")
        void onInputMethodConnectedLocked() {
            final int numClients = mClientMap.size();
            for (int i = 0; i < numClients; ++i) {
                final InputMethodClientInfo clientInfo = mClientMap.valueAt(i);
                switch (clientInfo.mState) {
                    case InputMethodClientState.REGISTERED:
                        // OK
                        break;
                    default:
                        Slog.e(TAG, "Unexpected state=" + clientInfo.mState);
                        return;
                }
                try {
                    mCurrentInputMethod.addClient(
                            clientInfo.mClientId, clientInfo.mUid, clientInfo.mPid,
                            clientInfo.mSelfReportedDisplayId);
                    clientInfo.mState = InputMethodClientState.WAITING_FOR_IME_SESSION;
                } catch (RemoteException e) {
                }
            }
        }

        @GuardedBy("mLock")
        void onInputMethodDisconnectedLocked() {
            final int numClients = mClientMap.size();
            for (int i = 0; i < numClients; ++i) {
                final InputMethodClientInfo clientInfo = mClientMap.valueAt(i);
                switch (clientInfo.mState) {
                    case InputMethodClientState.REGISTERED:
                        // Disconnected before onInputMethodConnectedLocked().
                        break;
                    case InputMethodClientState.WAITING_FOR_IME_SESSION:
                        // Disconnected between addClient() and acceptClient().
                        clientInfo.mState = InputMethodClientState.REGISTERED;
                        break;
                    case InputMethodClientState.READY_TO_SEND_FIRST_BIND_RESULT:
                        clientInfo.mState = InputMethodClientState.REGISTERED;
                        clientInfo.mInputMethodSession = null;
                        clientInfo.mMSInputMethodSession = null;
                        if (clientInfo.mWriteChannel != null) {
                            clientInfo.mWriteChannel.dispose();
                            clientInfo.mWriteChannel = null;
                        }
                        break;
                    case InputMethodClientState.ALREADY_SENT_BIND_RESULT:
                        try {
                            clientInfo.mClient.onUnbindMethod(clientInfo.mBindingSequence,
                                    UnbindReason.DISCONNECT_IME);
                        } catch (RemoteException e) {
                        }
                        clientInfo.mState = InputMethodClientState.REGISTERED;
                        clientInfo.mInputMethodSession = null;
                        clientInfo.mMSInputMethodSession = null;
                        if (clientInfo.mWriteChannel != null) {
                            clientInfo.mWriteChannel.dispose();
                            clientInfo.mWriteChannel = null;
                        }
                        break;
                }
            }
        }

        private static final class ClientDeathRecipient implements IBinder.DeathRecipient {
            private final PerUserData mPerUserData;
            private final IInputMethodClient mClient;

            ClientDeathRecipient(PerUserData perUserData, IInputMethodClient client) {
                mPerUserData = perUserData;
                mClient = client;
            }

            @BinderThread
            @Override
            public void binderDied() {
                synchronized (mPerUserData.mLock) {
                    mClient.asBinder().unlinkToDeath(this, 0);

                    final InputMethodClientInfo clientInfo =
                            mPerUserData.removeClientLocked(mClient);
                    if (clientInfo == null) {
                        return;
                    }

                    if (clientInfo.mWriteChannel != null) {
                        clientInfo.mWriteChannel.dispose();
                        clientInfo.mWriteChannel = null;
                    }
                    if (clientInfo.mInputMethodSession != null) {
                        try {
                            clientInfo.mInputMethodSession.finishSession();
                        } catch (RemoteException e) {
                        }
                        clientInfo.mInputMethodSession = null;
                    }
                    clientInfo.mMSInputMethodSession = null;
                    clientInfo.mState = InputMethodClientState.UNREGISTERED;
                    switch (mPerUserData.mState) {
                        case PerUserState.SERVICE_CONNECTED:
                            try {
                                mPerUserData.mCurrentInputMethod.removeClient(clientInfo.mClientId);
                            } catch (RemoteException e) {
                                // TODO(yukawa): Need logging and expected behavior
                            }
                            break;
                    }
                }
            }
        }
    }

    /**
     * Queries for multi-client IME specified with {@code componentName}.
     *
     * @param context {@link Context} to be used to query component.
     * @param userId User ID for which the multi-client IME is queried.
     * @param componentName {@link ComponentName} to be queried.
     * @return {@link InputMethodInfo} when multi-client IME is found. Otherwise {@code null}.
     */
    @Nullable
    private static InputMethodInfo queryInputMethod(Context context, @UserIdInt int userId,
            @Nullable ComponentName componentName) {
        if (componentName == null) {
            return null;
        }

        // Use for queryIntentServicesAsUser
        final PackageManager pm = context.getPackageManager();
        final List<ResolveInfo> services = pm.queryIntentServicesAsUser(
                new Intent(MultiClientInputMethodServiceDelegate.SERVICE_INTERFACE)
                        .setComponent(componentName),
                PackageManager.GET_META_DATA, userId);

        if (services.isEmpty()) {
            Slog.e(TAG, "No IME found");
            return null;
        }

        if (services.size() > 1) {
            Slog.e(TAG, "Only one IME service is supported.");
            return null;
        }

        final ResolveInfo ri = services.get(0);
        ServiceInfo si = ri.serviceInfo;
        final String imeId = InputMethodInfo.computeId(ri);
        if (!android.Manifest.permission.BIND_INPUT_METHOD.equals(si.permission)) {
            Slog.e(TAG, imeId + " must have required"
                    + android.Manifest.permission.BIND_INPUT_METHOD);
            return null;
        }

        if (!Build.IS_DEBUGGABLE && (si.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
            Slog.e(TAG, imeId + " must be pre-installed when Build.IS_DEBUGGABLE is false");
            return null;
        }

        try {
            return new InputMethodInfo(context, ri);
        } catch (Exception e) {
            Slog.wtf(TAG, "Unable to load input method " + imeId, e);
        }
        return null;
    }

    /**
     * Manages the mapping rule from user ID to {@link InputMethodInfo}.
     */
    private static final class UserToInputMethodInfoMap {
        @GuardedBy("mArray")
        private final SparseArray<InputMethodInfo> mArray = new SparseArray<>();

        @AnyThread
        void put(@UserIdInt int userId, InputMethodInfo imi) {
            synchronized (mArray) {
                mArray.put(userId, imi);
            }
        }

        @AnyThread
        void remove(@UserIdInt int userId) {
            synchronized (mArray) {
                mArray.remove(userId);
            }
        }

        @AnyThread
        @Nullable
        InputMethodInfo get(@UserIdInt int userId) {
            synchronized (mArray) {
                return mArray.get(userId);
            }
        }

        @AnyThread
        List<InputMethodInfo> getAsList(@UserIdInt int userId) {
            final InputMethodInfo info = get(userId);
            if (info == null) {
                return Collections.emptyList();
            }
            return Collections.singletonList(info);
        }
    }

    /**
     * Takes care of IPCs exposed to the multi-client IME.
     */
    private static final class ImeCallbacks
            extends IMultiClientInputMethodPrivilegedOperations.Stub {
        private final PerUserData mPerUserData;
        private final WindowManagerInternal mIWindowManagerInternal;

        ImeCallbacks(PerUserData perUserData) {
            mPerUserData = perUserData;
            mIWindowManagerInternal = LocalServices.getService(WindowManagerInternal.class);
        }

        @BinderThread
        @Override
        public IBinder createInputMethodWindowToken(int displayId) {
            synchronized (mPerUserData.mLock) {
                // We assume the number of tokens would not be that large (up to 10 or so) hence
                // linear search should be acceptable.
                final int numTokens = mPerUserData.mDisplayIdToImeWindowTokenMap.size();
                for (int i = 0; i < numTokens; ++i) {
                    final TokenInfo tokenInfo =
                            mPerUserData.mDisplayIdToImeWindowTokenMap.valueAt(i);
                    // Currently we issue up to one window token per display.
                    if (tokenInfo.mDisplayId == displayId) {
                        return tokenInfo.mToken;
                    }
                }

                final Binder token = new Binder();
                Binder.withCleanCallingIdentity(
                        PooledLambda.obtainRunnable(WindowManagerInternal::addWindowToken,
                                mIWindowManagerInternal, token, TYPE_INPUT_METHOD, displayId));
                mPerUserData.mDisplayIdToImeWindowTokenMap.add(new TokenInfo(token, displayId));
                return token;
            }
        }

        @BinderThread
        @Override
        public void deleteInputMethodWindowToken(IBinder token) {
            synchronized (mPerUserData.mLock) {
                // We assume the number of tokens would not be that large (up to 10 or so) hence
                // linear search should be acceptable.
                final int numTokens = mPerUserData.mDisplayIdToImeWindowTokenMap.size();
                for (int i = 0; i < numTokens; ++i) {
                    final TokenInfo tokenInfo =
                            mPerUserData.mDisplayIdToImeWindowTokenMap.valueAt(i);
                    if (tokenInfo.mToken == token) {
                        mPerUserData.mDisplayIdToImeWindowTokenMap.remove(tokenInfo);
                        break;
                    }
                }
            }
        }

        @BinderThread
        @Override
        public void acceptClient(int clientId, IInputMethodSession inputMethodSession,
                IMultiClientInputMethodSession multiSessionInputMethodSession,
                InputChannel writeChannel) {
            synchronized (mPerUserData.mLock) {
                final InputMethodClientInfo clientInfo =
                        mPerUserData.getClientFromIdLocked(clientId);
                if (clientInfo == null) {
                    Slog.e(TAG, "Unknown clientId=" + clientId);
                    return;
                }
                switch (clientInfo.mState) {
                    case InputMethodClientState.WAITING_FOR_IME_SESSION:
                        try {
                            clientInfo.mClient.setActive(true, false);
                        } catch (RemoteException e) {
                            // TODO(yukawa): Remove this client.
                            return;
                        }
                        clientInfo.mState = InputMethodClientState.READY_TO_SEND_FIRST_BIND_RESULT;
                        clientInfo.mWriteChannel = writeChannel;
                        clientInfo.mInputMethodSession = inputMethodSession;
                        clientInfo.mMSInputMethodSession = multiSessionInputMethodSession;
                        break;
                    default:
                        Slog.e(TAG, "Unexpected state=" + clientInfo.mState);
                        break;
                }
            }
        }

        @BinderThread
        @Override
        public void reportImeWindowTarget(int clientId, int targetWindowHandle,
                IBinder imeWindowToken) {
            synchronized (mPerUserData.mLock) {
                final InputMethodClientInfo clientInfo =
                        mPerUserData.getClientFromIdLocked(clientId);
                if (clientInfo == null) {
                    Slog.e(TAG, "Unknown clientId=" + clientId);
                    return;
                }
                for (WindowInfo windowInfo : clientInfo.mWindowMap.values()) {
                    if (windowInfo.mWindowHandle == targetWindowHandle) {
                        final IBinder targetWindowToken = windowInfo.mWindowToken;
                        // TODO(yukawa): Report targetWindowToken and targetWindowToken to WMS.
                        if (DEBUG) {
                            Slog.v(TAG, "reportImeWindowTarget"
                                    + " clientId=" + clientId
                                    + " imeWindowToken=" + imeWindowToken
                                    + " targetWindowToken=" + targetWindowToken);
                        }
                    }
                }
                // not found.
            }
        }

        @BinderThread
        @Override
        public boolean isUidAllowedOnDisplay(int displayId, int uid) {
            return mIWindowManagerInternal.isUidAllowedOnDisplay(displayId, uid);
        }
    }

    /**
     * Takes care of IPCs exposed to the IME client.
     */
    private static final class ApiCallbacks extends IInputMethodManager.Stub {
        private final UserDataMap mUserDataMap;
        private final UserToInputMethodInfoMap mInputMethodInfoMap;
        private final AppOpsManager mAppOpsManager;
        private final WindowManagerInternal mWindowManagerInternal;

        ApiCallbacks(Context context, UserDataMap userDataMap,
                UserToInputMethodInfoMap inputMethodInfoMap) {
            mUserDataMap = userDataMap;
            mInputMethodInfoMap = inputMethodInfoMap;
            mAppOpsManager = context.getSystemService(AppOpsManager.class);
            mWindowManagerInternal = LocalServices.getService(WindowManagerInternal.class);
        }

        @AnyThread
        private boolean checkFocus(int uid, int pid, int displayId) {
            return mWindowManagerInternal.isInputMethodClientFocus(uid, pid, displayId);
        }

        @BinderThread
        @Override
        public void addClient(IInputMethodClient client, IInputContext inputContext,
                int selfReportedDisplayId) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int userId = UserHandle.getUserId(callingUid);
            final PerUserData data = mUserDataMap.get(userId);
            if (data == null) {
                Slog.e(TAG, "addClient() from unknown userId=" + userId
                        + " uid=" + callingUid + " pid=" + callingPid);
                return;
            }
            synchronized (data.mLock) {
                data.addClientLocked(callingUid, callingPid, client, selfReportedDisplayId);
            }
        }

        @BinderThread
        @Override
        public List<InputMethodInfo> getInputMethodList() {
            return mInputMethodInfoMap.getAsList(UserHandle.getUserId(Binder.getCallingUid()));
        }

        @BinderThread
        @Override
        public List<InputMethodInfo> getVrInputMethodList() {
            reportNotSupported();
            return Collections.emptyList();
        }

        @BinderThread
        @Override
        public List<InputMethodInfo> getEnabledInputMethodList() {
            return mInputMethodInfoMap.getAsList(UserHandle.getUserId(Binder.getCallingUid()));
        }

        @BinderThread
        @Override
        public List<InputMethodSubtype> getEnabledInputMethodSubtypeList(String imiId,
                boolean allowsImplicitlySelectedSubtypes) {
            reportNotSupported();
            return Collections.emptyList();
        }

        @BinderThread
        @Override
        public InputMethodSubtype getLastInputMethodSubtype() {
            reportNotSupported();
            return null;
        }

        @BinderThread
        @Override
        public boolean showSoftInput(
                IInputMethodClient client, int flags, ResultReceiver resultReceiver) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int userId = UserHandle.getUserId(callingUid);
            final PerUserData data = mUserDataMap.get(userId);
            if (data == null) {
                Slog.e(TAG, "showSoftInput() from unknown userId=" + userId
                        + " uid=" + callingUid + " pid=" + callingPid);
                return false;
            }
            synchronized (data.mLock) {
                final InputMethodClientInfo clientInfo = data.getClientLocked(client);
                if (clientInfo == null) {
                    Slog.e(TAG, "showSoftInput. client not found. ignoring.");
                    return false;
                }
                if (clientInfo.mUid != callingUid) {
                    Slog.e(TAG, "Expected calling UID=" + clientInfo.mUid
                            + " actual=" + callingUid);
                    return false;
                }
                switch (clientInfo.mState) {
                    case InputMethodClientState.READY_TO_SEND_FIRST_BIND_RESULT:
                    case InputMethodClientState.ALREADY_SENT_BIND_RESULT:
                        try {
                            clientInfo.mMSInputMethodSession.showSoftInput(flags, resultReceiver);
                        } catch (RemoteException e) {
                        }
                        break;
                    default:
                        if (DEBUG) {
                            Slog.e(TAG, "Ignoring showSoftInput(). clientState="
                                    + clientInfo.mState);
                        }
                        break;
                }
                return true;
            }
        }

        @BinderThread
        @Override
        public boolean hideSoftInput(
                IInputMethodClient client, int flags, ResultReceiver resultReceiver) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int userId = UserHandle.getUserId(callingUid);
            final PerUserData data = mUserDataMap.get(userId);
            if (data == null) {
                Slog.e(TAG, "hideSoftInput() from unknown userId=" + userId
                        + " uid=" + callingUid + " pid=" + callingPid);
                return false;
            }
            synchronized (data.mLock) {
                final InputMethodClientInfo clientInfo = data.getClientLocked(client);
                if (clientInfo == null) {
                    return false;
                }
                if (clientInfo.mUid != callingUid) {
                    Slog.e(TAG, "Expected calling UID=" + clientInfo.mUid
                            + " actual=" + callingUid);
                    return false;
                }
                switch (clientInfo.mState) {
                    case InputMethodClientState.READY_TO_SEND_FIRST_BIND_RESULT:
                    case InputMethodClientState.ALREADY_SENT_BIND_RESULT:
                        try {
                            clientInfo.mMSInputMethodSession.hideSoftInput(flags, resultReceiver);
                        } catch (RemoteException e) {
                        }
                        break;
                    default:
                        if (DEBUG) {
                            Slog.e(TAG, "Ignoring hideSoftInput(). clientState="
                                    + clientInfo.mState);
                        }
                        break;
                }
                return true;
            }
        }

        @BinderThread
        @Override
        public InputBindResult startInputOrWindowGainedFocus(
                @StartInputReason int startInputReason,
                @Nullable IInputMethodClient client,
                @Nullable IBinder windowToken,
                @StartInputFlags int startInputFlags,
                @SoftInputModeFlags int softInputMode,
                int windowFlags,
                @Nullable EditorInfo editorInfo,
                @Nullable IInputContext inputContext,
                @MissingMethodFlags int missingMethods,
                int unverifiedTargetSdkVersion) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int userId = UserHandle.getUserId(callingUid);

            if (client == null) {
                return InputBindResult.INVALID_CLIENT;
            }

            final boolean packageNameVerified =
                    editorInfo != null && InputMethodUtils.checkIfPackageBelongsToUid(
                            mAppOpsManager, callingUid, editorInfo.packageName);
            if (editorInfo != null && !packageNameVerified) {
                Slog.e(TAG, "Rejecting this client as it reported an invalid package name."
                        + " uid=" + callingUid + " package=" + editorInfo.packageName);
                return InputBindResult.INVALID_PACKAGE_NAME;
            }

            final PerUserData data = mUserDataMap.get(userId);
            if (data == null) {
                Slog.e(TAG, "startInputOrWindowGainedFocus() from unknown userId=" + userId
                        + " uid=" + callingUid + " pid=" + callingPid);
                return InputBindResult.INVALID_USER;
            }

            synchronized (data.mLock) {
                final InputMethodClientInfo clientInfo = data.getClientLocked(client);
                if (clientInfo == null) {
                    return InputBindResult.INVALID_CLIENT;
                }
                if (clientInfo.mUid != callingUid) {
                    Slog.e(TAG, "Expected calling UID=" + clientInfo.mUid
                            + " actual=" + callingUid);
                    return InputBindResult.INVALID_CLIENT;
                }

                switch (data.mState) {
                    case PerUserState.USER_LOCKED:
                    case PerUserState.SERVICE_NOT_QUERIED:
                    case PerUserState.SERVICE_RECOGNIZED:
                    case PerUserState.WAITING_SERVICE_CONNECTED:
                    case PerUserState.UNBIND_CALLED:
                        return InputBindResult.IME_NOT_CONNECTED;
                    case PerUserState.SERVICE_CONNECTED:
                        // OK
                        break;
                    default:
                        Slog.wtf(TAG, "Unexpected state=" + data.mState);
                        return InputBindResult.IME_NOT_CONNECTED;
                }

                WindowInfo windowInfo = null;
                if (windowToken != null) {
                    windowInfo = clientInfo.mWindowMap.get(windowToken);
                    if (windowInfo == null) {
                        windowInfo = new WindowInfo(windowToken, WindowHandleSource.getNext());
                        clientInfo.mWindowMap.put(windowToken, windowInfo);
                    }
                }

                if (!checkFocus(clientInfo.mUid, clientInfo.mPid,
                        clientInfo.mSelfReportedDisplayId)) {
                    return InputBindResult.NOT_IME_TARGET_WINDOW;
                }

                if (editorInfo == null) {
                    // So-called dummy InputConnection scenario.  For app compatibility, we still
                    // notify this to the IME.
                    switch (clientInfo.mState) {
                        case InputMethodClientState.READY_TO_SEND_FIRST_BIND_RESULT:
                        case InputMethodClientState.ALREADY_SENT_BIND_RESULT:
                            final int windowHandle = windowInfo != null
                                    ? windowInfo.mWindowHandle
                                    : MultiClientInputMethodServiceDelegate.INVALID_WINDOW_HANDLE;
                            try {
                                clientInfo.mMSInputMethodSession.startInputOrWindowGainedFocus(
                                        inputContext, missingMethods, editorInfo, startInputFlags,
                                        softInputMode, windowHandle);
                            } catch (RemoteException e) {
                            }
                            break;
                    }
                    return InputBindResult.NULL_EDITOR_INFO;
                }

                switch (clientInfo.mState) {
                    case InputMethodClientState.REGISTERED:
                    case InputMethodClientState.WAITING_FOR_IME_SESSION:
                        clientInfo.mBindingSequence++;
                        if (clientInfo.mBindingSequence < 0) {
                            clientInfo.mBindingSequence = 0;
                        }
                        return new InputBindResult(
                                InputBindResult.ResultCode.SUCCESS_WAITING_IME_SESSION,
                                null, null, data.mCurrentInputMethodInfo.getId(),
                                clientInfo.mBindingSequence);
                    case InputMethodClientState.READY_TO_SEND_FIRST_BIND_RESULT:
                    case InputMethodClientState.ALREADY_SENT_BIND_RESULT:
                        clientInfo.mBindingSequence++;
                        if (clientInfo.mBindingSequence < 0) {
                            clientInfo.mBindingSequence = 0;
                        }
                        // Successful start input.
                        final int windowHandle = windowInfo != null
                                ? windowInfo.mWindowHandle
                                : MultiClientInputMethodServiceDelegate.INVALID_WINDOW_HANDLE;
                        try {
                            clientInfo.mMSInputMethodSession.startInputOrWindowGainedFocus(
                                    inputContext, missingMethods, editorInfo, startInputFlags,
                                    softInputMode, windowHandle);
                        } catch (RemoteException e) {
                        }
                        clientInfo.mState = InputMethodClientState.ALREADY_SENT_BIND_RESULT;
                        return new InputBindResult(
                                InputBindResult.ResultCode.SUCCESS_WITH_IME_SESSION,
                                clientInfo.mInputMethodSession,
                                clientInfo.mWriteChannel.dup(),
                                data.mCurrentInputMethodInfo.getId(),
                                clientInfo.mBindingSequence);
                    case InputMethodClientState.UNREGISTERED:
                        Slog.e(TAG, "The client is already unregistered.");
                        return InputBindResult.INVALID_CLIENT;
                }
            }
            return null;
        }

        @BinderThread
        @Override
        public void showInputMethodPickerFromClient(
                IInputMethodClient client, int auxiliarySubtypeMode) {
            reportNotSupported();
        }

        @BinderThread
        @Override
        public void showInputMethodPickerFromSystem(
                IInputMethodClient client, int auxiliarySubtypeMode, int displayId) {
            reportNotSupported();
        }

        @BinderThread
        @Override
        public void showInputMethodAndSubtypeEnablerFromClient(
                IInputMethodClient client, String inputMethodId) {
            reportNotSupported();
        }

        @BinderThread
        @Override
        public boolean isInputMethodPickerShownForTest() {
            reportNotSupported();
            return false;
        }

        @BinderThread
        @Override
        public InputMethodSubtype getCurrentInputMethodSubtype() {
            reportNotSupported();
            return null;
        }

        @BinderThread
        @Override
        public boolean setCurrentInputMethodSubtype(InputMethodSubtype subtype) {
            reportNotSupported();
            return false;
        }

        @BinderThread
        @Override
        public void setAdditionalInputMethodSubtypes(String imiId, InputMethodSubtype[] subtypes) {
            reportNotSupported();
        }

        @BinderThread
        @Override
        public int getInputMethodWindowVisibleHeight() {
            reportNotSupported();
            return 0;
        }

        @BinderThread
        @Override
        public void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
                @Nullable FileDescriptor err, String[] args, @Nullable ShellCallback callback,
                ResultReceiver resultReceiver) {
        }
    }
}
