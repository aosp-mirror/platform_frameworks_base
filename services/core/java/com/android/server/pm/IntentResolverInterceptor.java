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
import android.content.Intent;
import android.content.res.Resources;
import android.provider.DeviceConfig;

import com.android.internal.R;
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.server.LocalServices;
import com.android.server.wm.ActivityInterceptorCallback;
import com.android.server.wm.ActivityInterceptorCallback.ActivityInterceptorInfo;
import com.android.server.wm.ActivityTaskManagerInternal;

/**
 * Service to register an {@code ActivityInterceptorCallback} that modifies any {@code Intent}
 * that's being used to launch a user-space {@code ChooserActivity} by setting the destination
 * component to the delegated component when appropriate.
 */
public final class IntentResolverInterceptor {
    private static final String TAG = "IntentResolverIntercept";

    private final Context mContext;
    private boolean mUseDelegateChooser;

    private final ActivityInterceptorCallback mActivityInterceptorCallback =
            new ActivityInterceptorCallback() {
                @Nullable
                @Override
                public ActivityInterceptResult intercept(ActivityInterceptorInfo info) {
                    if (mUseDelegateChooser && isChooserActivity(info)) {
                        return new ActivityInterceptResult(
                                modifyChooserIntent(info.intent),
                                info.checkedOptions);
                    }
                    return null;
                }
            };

    public IntentResolverInterceptor(Context context) {
        mContext = context;
    }

    /**
     * Start listening for intents and USE_DELEGATE_CHOOSER property changes.
     */
    @RequiresPermission(Manifest.permission.READ_DEVICE_CONFIG)
    public void registerListeners() {
        LocalServices.getService(ActivityTaskManagerInternal.class)
                .registerActivityStartInterceptor(INTENT_RESOLVER_ORDERED_ID,
                        mActivityInterceptorCallback);

        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_SYSTEMUI,
                mContext.getMainExecutor(), properties -> updateUseDelegateChooser());
        updateUseDelegateChooser();
    }

    @RequiresPermission(Manifest.permission.READ_DEVICE_CONFIG)
    private void updateUseDelegateChooser() {
        mUseDelegateChooser = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.USE_DELEGATE_CHOOSER,
                false);
    }

    private Intent modifyChooserIntent(Intent intent) {
        intent.setComponent(getUnbundledChooserComponentName());
        return intent;
    }

    private static boolean isChooserActivity(ActivityInterceptorInfo info) {
        ComponentName targetComponent = new ComponentName(info.aInfo.packageName, info.aInfo.name);

        return targetComponent.equals(getSystemChooserComponentName())
                || targetComponent.equals(getUnbundledChooserComponentName());
    }

    private static ComponentName getSystemChooserComponentName() {
        return new ComponentName("android", "com.android.internal.app.ChooserActivity");
    }

    private static ComponentName getUnbundledChooserComponentName() {
        return ComponentName.unflattenFromString(
                Resources.getSystem().getString(R.string.config_chooserActivity));
    }
}
