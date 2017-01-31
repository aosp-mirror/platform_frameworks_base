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

package android.permissionpresenterservice;

import android.annotation.SystemApi;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.permission.IRuntimePermissionPresenter;
import android.content.pm.permission.RuntimePermissionPresentationInfo;
import android.content.pm.permission.RuntimePermissionPresenter;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallback;
import com.android.internal.os.SomeArgs;

import java.util.List;

/**
 * This service presents information regarding runtime permissions that is
 * used for presenting them in the UI. Runtime permissions are presented as
 * a single permission in the UI but may be composed of several individual
 * permissions.
 *
 * @see RuntimePermissionPresenter
 * @see RuntimePermissionPresentationInfo
 *
 * @hide
 */
@SystemApi
public abstract class RuntimePermissionPresenterService extends Service {

    /**
     * The {@link Intent} action that must be declared as handled by a service
     * in its manifest for the system to recognize it as a runtime permission
     * presenter service.
     */
    public static final String SERVICE_INTERFACE =
            "android.permissionpresenterservice.RuntimePermissionPresenterService";

    // No need for locking - always set first and never modified
    private Handler mHandler;

    @Override
    public final void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        mHandler = new MyHandler(base.getMainLooper());
    }

    /**
     * Gets the runtime permissions for an app.
     *
     * @param packageName The package for which to query.
     */
    public abstract List<RuntimePermissionPresentationInfo> onGetAppPermissions(String packageName);

    /**
     * Gets the apps that use runtime permissions.
     *
     * @param system Whether to return only the system apps or only the non-system ones.
     * @return The app list.
     */
    public abstract List<ApplicationInfo> onGetAppsUsingPermissions(boolean system);

    @Override
    public final IBinder onBind(Intent intent) {
        return new IRuntimePermissionPresenter.Stub() {
            @Override
            public void getAppPermissions(String packageName, RemoteCallback callback) {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = packageName;
                args.arg2 = callback;
                mHandler.obtainMessage(MyHandler.MSG_GET_APP_PERMISSIONS,
                        args).sendToTarget();
            }

            @Override
            public void getAppsUsingPermissions(boolean system, RemoteCallback callback) {
                mHandler.obtainMessage(MyHandler.MSG_GET_APPS_USING_PERMISSIONS,
                        system ? 1 : 0, 0, callback).sendToTarget();
            }
        };
    }

    private final class MyHandler extends Handler {
        public static final int MSG_GET_APP_PERMISSIONS = 1;
        public static final int MSG_GET_APPS_USING_PERMISSIONS = 2;

        public MyHandler(Looper looper) {
            super(looper, null, false);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_GET_APP_PERMISSIONS: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    String packageName = (String) args.arg1;
                    RemoteCallback callback = (RemoteCallback) args.arg2;
                    args.recycle();
                    List<RuntimePermissionPresentationInfo> permissions =
                            onGetAppPermissions(packageName);
                    if (permissions != null && !permissions.isEmpty()) {
                        Bundle result = new Bundle();
                        result.putParcelableList(RuntimePermissionPresenter.KEY_RESULT,
                                permissions);
                        callback.sendResult(result);
                    } else {
                        callback.sendResult(null);
                    }
                } break;

                case MSG_GET_APPS_USING_PERMISSIONS: {
                    RemoteCallback callback = (RemoteCallback) msg.obj;
                    final boolean system = msg.arg1 == 1;
                    List<ApplicationInfo> apps = onGetAppsUsingPermissions(system);
                    if (apps != null && !apps.isEmpty()) {
                        Bundle result = new Bundle();
                        result.putParcelableList(RuntimePermissionPresenter.KEY_RESULT, apps);
                        callback.sendResult(result);
                    } else {
                        callback.sendResult(null);
                    }
                } break;
            }
        }
    }
}
