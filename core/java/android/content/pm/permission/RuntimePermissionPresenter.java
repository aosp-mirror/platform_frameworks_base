/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.content.pm.permission;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.permissionpresenterservice.RuntimePermissionPresenterService;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.SomeArgs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * This class provides information about runtime permissions for a specific
 * app or all apps. This information is dedicated for presentation purposes
 * and does not necessarily reflect the individual permissions requested/
 * granted to an app as the platform may be grouping permissions to improve
 * presentation and help the user make an informed choice. For example, all
 * runtime permissions in the same permission group may be presented as a
 * single permission in the UI.
 *
 * @hide
 */
public final class RuntimePermissionPresenter {
    private static final String TAG = "RuntimePermPresenter";

    /**
     * The key for retrieving the result from the returned bundle.
     *
     * @hide
     */
    public static final String KEY_RESULT =
            "android.content.pm.permission.RuntimePermissionPresenter.key.result";

    /**
     * Listener for delivering a result.
     */
    public static abstract class OnResultCallback {
        /**
         * The result for {@link #getAppPermissions(String, OnResultCallback, Handler)}.
         * @param permissions The permissions list.
         */
        public void onGetAppPermissions(@NonNull
                List<RuntimePermissionPresentationInfo> permissions) {
            /* do nothing - stub */
        }
    }

    private static final Object sLock = new Object();

    @GuardedBy("sLock")
    private static RuntimePermissionPresenter sInstance;

    private final RemoteService mRemoteService;

    /**
     * Gets the singleton runtime permission presenter.
     *
     * @param context Context for accessing resources.
     * @return The singleton instance.
     */
    public static RuntimePermissionPresenter getInstance(@NonNull Context context) {
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new RuntimePermissionPresenter(context.getApplicationContext());
            }
            return sInstance;
        }
    }

    private RuntimePermissionPresenter(Context context) {
        mRemoteService = new RemoteService(context);
    }

    /**
     * Gets the runtime permissions for an app.
     *
     * @param packageName The package for which to query.
     * @param callback Callback to receive the result.
     * @param handler Handler on which to invoke the callback.
     */
    public void getAppPermissions(@NonNull String packageName,
            @NonNull OnResultCallback callback, @Nullable Handler handler) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = packageName;
        args.arg2 = callback;
        args.arg3 = handler;
        Message message = mRemoteService.obtainMessage(
                RemoteService.MSG_GET_APP_PERMISSIONS, args);
        mRemoteService.processMessage(message);
    }

    /**
     * Revoke the permission {@code permissionName} for app {@code packageName}
     *
     * @param packageName The package for which to revoke
     * @param permissionName The permission to revoke
     */
    public void revokeRuntimePermission(String packageName, String permissionName) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = packageName;
        args.arg2 = permissionName;

        Message message = mRemoteService.obtainMessage(
                RemoteService.MSG_REVOKE_APP_PERMISSIONS, args);
        mRemoteService.processMessage(message);
    }

    private static final class RemoteService
            extends Handler implements ServiceConnection {
        private static final long UNBIND_TIMEOUT_MILLIS = 10000;

        public static final int MSG_GET_APP_PERMISSIONS = 1;
        public static final int MSG_GET_APPS_USING_PERMISSIONS = 2;
        public static final int MSG_UNBIND = 3;
        public static final int MSG_REVOKE_APP_PERMISSIONS = 4;

        private final Object mLock = new Object();

        private final Context mContext;

        @GuardedBy("mLock")
        private final List<Message> mPendingWork = new ArrayList<>();

        @GuardedBy("mLock")
        private IRuntimePermissionPresenter mRemoteInstance;

        @GuardedBy("mLock")
        private boolean mBound;

        public RemoteService(Context context) {
            super(context.getMainLooper(), null, false);
            mContext = context;
        }

        public void processMessage(Message message) {
            synchronized (mLock) {
                if (!mBound) {
                    Intent intent = new Intent(
                            RuntimePermissionPresenterService.SERVICE_INTERFACE);
                    intent.setPackage(mContext.getPackageManager()
                            .getPermissionControllerPackageName());
                    mBound = mContext.bindService(intent, this,
                            Context.BIND_AUTO_CREATE);
                }
                mPendingWork.add(message);
                scheduleNextMessageIfNeededLocked();
            }
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                mRemoteInstance = IRuntimePermissionPresenter.Stub.asInterface(service);
                scheduleNextMessageIfNeededLocked();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                mRemoteInstance = null;
            }
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_GET_APP_PERMISSIONS: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    final String packageName = (String) args.arg1;
                    final OnResultCallback callback = (OnResultCallback) args.arg2;
                    final Handler handler = (Handler) args.arg3;
                    args.recycle();
                    final IRuntimePermissionPresenter remoteInstance;
                    synchronized (mLock) {
                        remoteInstance = mRemoteInstance;
                    }
                    if (remoteInstance == null) {
                        return;
                    }
                    try {
                        remoteInstance.getAppPermissions(packageName,
                                new RemoteCallback(new RemoteCallback.OnResultListener() {
                            @Override
                            public void onResult(Bundle result) {
                                final List<RuntimePermissionPresentationInfo> reportedPermissions;
                                List<RuntimePermissionPresentationInfo> permissions = null;
                                if (result != null) {
                                    permissions = result.getParcelableArrayList(KEY_RESULT);
                                }
                                if (permissions == null) {
                                    permissions = Collections.emptyList();
                                }
                                reportedPermissions = permissions;
                                if (handler != null) {
                                    handler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            callback.onGetAppPermissions(reportedPermissions);
                                        }
                                    });
                                } else {
                                    callback.onGetAppPermissions(reportedPermissions);
                                }
                            }
                        }, this));
                    } catch (RemoteException re) {
                        Log.e(TAG, "Error getting app permissions", re);
                    }
                    scheduleUnbind();
                } break;

                case MSG_UNBIND: {
                    synchronized (mLock) {
                        if (mBound) {
                            mContext.unbindService(this);
                            mBound = false;
                        }
                        mRemoteInstance = null;
                    }
                } break;

                case MSG_REVOKE_APP_PERMISSIONS: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    final String packageName = (String) args.arg1;
                    final String permissionName = (String) args.arg2;
                    args.recycle();
                    final IRuntimePermissionPresenter remoteInstance;
                    synchronized (mLock) {
                        remoteInstance = mRemoteInstance;
                    }
                    if (remoteInstance == null) {
                        return;
                    }
                    try {
                        remoteInstance.revokeRuntimePermission(packageName, permissionName);
                    } catch (RemoteException re) {
                        Log.e(TAG, "Error getting app permissions", re);
                    }
                } break;
            }

            synchronized (mLock) {
                scheduleNextMessageIfNeededLocked();
            }
        }

        @GuardedBy("mLock")
        private void scheduleNextMessageIfNeededLocked() {
            if (mBound && mRemoteInstance != null && !mPendingWork.isEmpty()) {
                Message nextMessage = mPendingWork.remove(0);
                sendMessage(nextMessage);
            }
        }

        private void scheduleUnbind() {
            removeMessages(MSG_UNBIND);
            sendEmptyMessageDelayed(MSG_UNBIND, UNBIND_TIMEOUT_MILLIS);
        }
    }
}
