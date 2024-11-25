/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server;

import static android.os.SecurityStateManager.KEY_KERNEL_VERSION;
import static android.os.SecurityStateManager.KEY_SYSTEM_SPL;
import static android.os.SecurityStateManager.KEY_VENDOR_SPL;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.ISecurityStateManager;
import android.os.SystemProperties;
import android.os.VintfRuntimeInfo;
import android.text.TextUtils;
import android.util.Slog;
import android.webkit.WebViewProviderInfo;
import android.webkit.WebViewUpdateService;

import com.android.internal.R;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SecurityStateManagerService extends ISecurityStateManager.Stub {

    private static final String TAG = "SecurityStateManagerService";

    static final String VENDOR_SECURITY_PATCH_PROPERTY_KEY = "ro.vendor.build"
            + ".security_patch";
    static final Pattern KERNEL_RELEASE_PATTERN = Pattern.compile("(\\d+\\.\\d+\\.\\d+)("
            + ".*)");

    private final Context mContext;
    private final PackageManager mPackageManager;

    public SecurityStateManagerService(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
    }

    @Override
    public Bundle getGlobalSecurityState() {
        final long token = Binder.clearCallingIdentity();
        try {
            return getGlobalSecurityStateInternal();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private Bundle getGlobalSecurityStateInternal() {
        Bundle globalSecurityState = new Bundle();
        globalSecurityState.putString(KEY_SYSTEM_SPL, Build.VERSION.SECURITY_PATCH);
        globalSecurityState.putString(KEY_VENDOR_SPL,
                SystemProperties.get(VENDOR_SECURITY_PATCH_PROPERTY_KEY, ""));
        String moduleMetadataProviderPackageName =
                mContext.getString(R.string.config_defaultModuleMetadataProvider);
        if (!moduleMetadataProviderPackageName.isEmpty()) {
            globalSecurityState.putString(moduleMetadataProviderPackageName,
                    getSpl(moduleMetadataProviderPackageName));
        }
        globalSecurityState.putString(KEY_KERNEL_VERSION, getKernelVersion());
        addWebViewPackages(globalSecurityState);
        addSecurityStatePackages(globalSecurityState);
        return globalSecurityState;
    }

    private String getSpl(String packageName) {
        if (!TextUtils.isEmpty(packageName)) {
            try {
                return mPackageManager.getPackageInfo(packageName, 0 /* flags */).versionName;
            } catch (PackageManager.NameNotFoundException e) {
                Slog.e(TAG, TextUtils.formatSimple("Failed to get SPL for package %s.",
                        packageName), e);
            }
        }
        return "";
    }

    private String getKernelVersion() {
        Matcher matcher = KERNEL_RELEASE_PATTERN.matcher(VintfRuntimeInfo.getKernelVersion());
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return "";
    }

    private void addWebViewPackages(Bundle bundle) {
        for (WebViewProviderInfo info : WebViewUpdateService.getAllWebViewPackages()) {
            String packageName = info.packageName;
            bundle.putString(packageName, getSpl(packageName));
        }
    }

    private void addSecurityStatePackages(Bundle bundle) {
        String[] packageNames;
        packageNames = mContext.getResources().getStringArray(R.array.config_securityStatePackages);
        for (String packageName : packageNames) {
            bundle.putString(packageName, getSpl(packageName));
        }
    }
}
