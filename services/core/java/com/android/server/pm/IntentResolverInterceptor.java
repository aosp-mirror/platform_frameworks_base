/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.pm;

import static com.android.server.wm.ActivityInterceptorCallback.INTENT_RESOLVER_ORDERED_ID;

import android.Manifest;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.provider.DeviceConfig;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.app.ChooserActivity;
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.server.LocalServices;
import com.android.server.wm.ActivityInterceptorCallback;
import com.android.server.wm.ActivityInterceptorCallback.ActivityInterceptorInfo;
import com.android.server.wm.ActivityTaskManagerInternal;

/**
 * Redirects Activity starts for the system bundled {@link ChooserActivity} to an external
 * Sharesheet implementation by modifying the target component when appropriate.
 * <p>
 * Note: config_chooserActivity (Used also by ActivityTaskSupervisor) is already updated to point
 * to the new instance. This value is read and used for the new target component.
 */
public final class IntentResolverInterceptor {
    private static final String TAG = "IntentResolverIntercept";
    private final Context mContext;
    private final ComponentName mFrameworkChooserComponent;
    private final ComponentName mUnbundledChooserComponent;
    private boolean mUseUnbundledSharesheet;

    private final ActivityInterceptorCallback mActivityInterceptorCallback =
            new ActivityInterceptorCallback() {
                @Nullable
                @Override
                public ActivityInterceptResult intercept(ActivityInterceptorInfo info) {
                    if (mUseUnbundledSharesheet && isSystemChooserActivity(info)) {
                        Slog.d(TAG, "Redirecting to UNBUNDLED Sharesheet");
                        info.intent.setComponent(mUnbundledChooserComponent);
                        return new ActivityInterceptResult(info.intent, info.checkedOptions);
                    }
                    return null;
                }
            };

    public IntentResolverInterceptor(Context context) {
        mContext = context;
        mFrameworkChooserComponent = new ComponentName(mContext, ChooserActivity.class);
        mUnbundledChooserComponent =  ComponentName.unflattenFromString(
                Resources.getSystem().getString(R.string.config_chooserActivity));
    }

    /**
     * Start listening for intents and USE_UNBUNDLED_SHARESHEET property changes.
     */
    @RequiresPermission(Manifest.permission.READ_DEVICE_CONFIG)
    public void registerListeners() {
        LocalServices.getService(ActivityTaskManagerInternal.class)
                .registerActivityStartInterceptor(INTENT_RESOLVER_ORDERED_ID,
                        mActivityInterceptorCallback);

        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_SYSTEMUI,
                mContext.getMainExecutor(), properties -> updateUseUnbundledSharesheet());
        updateUseUnbundledSharesheet();
    }

    @RequiresPermission(Manifest.permission.READ_DEVICE_CONFIG)
    private void updateUseUnbundledSharesheet() {
        mUseUnbundledSharesheet = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.USE_UNBUNDLED_SHARESHEET,
                false);
        if (mUseUnbundledSharesheet) {
            Slog.d(TAG, "using UNBUNDLED Sharesheet");
        } else {
            Slog.d(TAG, "using FRAMEWORK Sharesheet");
        }
    }

    private boolean isSystemChooserActivity(ActivityInterceptorInfo info) {
        return mFrameworkChooserComponent.getPackageName().equals(info.aInfo.packageName)
                && mFrameworkChooserComponent.getClassName().equals(info.aInfo.name);
    }
}
