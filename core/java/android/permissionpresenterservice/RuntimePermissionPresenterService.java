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

import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.permission.IRuntimePermissionPresenter;
import android.content.pm.permission.RuntimePermissionPresentationInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteCallback;
import android.permission.PermissionControllerService;

import java.util.List;

/**
 * This service presents information regarding runtime permissions that is
 * used for presenting them in the UI. Runtime permissions are presented as
 * a single permission in the UI but may be composed of several individual
 * permissions.
 *
 * @see RuntimePermissionPresentationInfo
 *
 * @hide
 *
 * @deprecated use {@link PermissionControllerService} instead
 */
@Deprecated
@SystemApi
public abstract class RuntimePermissionPresenterService extends Service {

    /**
     * The {@link Intent} action that must be declared as handled by a service
     * in its manifest for the system to recognize it as a runtime permission
     * presenter service.
     */
    public static final String SERVICE_INTERFACE =
            "android.permissionpresenterservice.RuntimePermissionPresenterService";

    private static final String KEY_RESULT =
            "android.content.pm.permission.RuntimePermissionPresenter.key.result";

    // No need for locking - always set first and never modified
    private Handler mHandler;

    @Override
    public final void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        mHandler = new Handler(base.getMainLooper());
    }

    /**
     * Gets the runtime permissions for an app.
     *
     * @param packageName The package for which to query.
     */
    public abstract List<RuntimePermissionPresentationInfo> onGetAppPermissions(
            @NonNull String packageName);

    @Override
    public final IBinder onBind(Intent intent) {
        return new IRuntimePermissionPresenter.Stub() {
            @Override
            public void getAppPermissions(String packageName, RemoteCallback callback) {
                checkNotNull(packageName, "packageName");
                checkNotNull(callback, "callback");

                mHandler.sendMessage(
                        obtainMessage(RuntimePermissionPresenterService::getAppPermissions,
                                RuntimePermissionPresenterService.this, packageName, callback));
            }
        };
    }

    private void getAppPermissions(@NonNull String packageName, @NonNull RemoteCallback callback) {
        List<RuntimePermissionPresentationInfo> permissions = onGetAppPermissions(packageName);
        if (permissions != null && !permissions.isEmpty()) {
            Bundle result = new Bundle();
            result.putParcelableList(KEY_RESULT, permissions);
            callback.sendResult(result);
        } else {
            callback.sendResult(null);
        }
    }
}
