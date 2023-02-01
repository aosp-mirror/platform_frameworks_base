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

package com.android.server.pm.resolution;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.Computer;
import com.android.server.pm.DumpState;
import com.android.server.pm.pkg.component.ParsedActivity;
import com.android.server.pm.pkg.component.ParsedProvider;
import com.android.server.pm.pkg.component.ParsedService;

import java.io.PrintWriter;
import java.util.List;

public interface ComponentResolverApi {

    boolean isActivityDefined(@NonNull ComponentName component);

    @Nullable
    ParsedActivity getActivity(@NonNull ComponentName component);

    @Nullable
    ParsedProvider getProvider(@NonNull ComponentName component);

    @Nullable
    ParsedActivity getReceiver(@NonNull ComponentName component);

    @Nullable
    ParsedService getService(@NonNull ComponentName component);

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    boolean componentExists(@NonNull ComponentName componentName);

    @Nullable
    List<ResolveInfo> queryActivities(@NonNull Computer computer, @NonNull Intent intent,
            @Nullable String resolvedType, long flags, @UserIdInt int userId);

    @Nullable
    List<ResolveInfo> queryActivities(@NonNull Computer computer, @NonNull Intent intent,
            @Nullable String resolvedType, long flags, @NonNull List<ParsedActivity> activities,
            @UserIdInt int userId);

    @Nullable
    ProviderInfo queryProvider(@NonNull Computer computer, @NonNull String authority, long flags,
            @UserIdInt int userId);

    @Nullable
    List<ResolveInfo> queryProviders(@NonNull Computer computer, @NonNull Intent intent,
            @Nullable String resolvedType, long flags, @UserIdInt int userId);

    @Nullable
    List<ResolveInfo> queryProviders(@NonNull Computer computer, @NonNull Intent intent,
            @Nullable String resolvedType, long flags, @NonNull List<ParsedProvider> providers,
            @UserIdInt int userId);

    @Nullable
    List<ProviderInfo> queryProviders(@NonNull Computer computer, @Nullable String processName,
            @Nullable String metaDataKey, int uid, long flags, @UserIdInt int userId);

    @Nullable
    List<ResolveInfo> queryReceivers(@NonNull Computer computer, @NonNull Intent intent,
            @Nullable String resolvedType, long flags, @UserIdInt int userId);

    @Nullable
    List<ResolveInfo> queryReceivers(@NonNull Computer computer, @NonNull Intent intent,
            @Nullable String resolvedType, long flags, @NonNull List<ParsedActivity> receivers,
            @UserIdInt int userId);

    @Nullable
    List<ResolveInfo> queryServices(@NonNull Computer computer, @NonNull Intent intent,
            @Nullable String resolvedType, long flags, @UserIdInt int userId);

    @Nullable
    List<ResolveInfo> queryServices(@NonNull Computer computer, @NonNull Intent intent,
            @Nullable String resolvedType, long flags, @NonNull List<ParsedService> services,
            @UserIdInt int userId);

    void querySyncProviders(@NonNull Computer computer, @NonNull List<String> outNames,
            @NonNull List<ProviderInfo> outInfo, boolean safeMode, @UserIdInt int userId);

    void dumpActivityResolvers(@NonNull PrintWriter pw, @NonNull DumpState dumpState,
            @NonNull String packageName);

    void dumpProviderResolvers(@NonNull PrintWriter pw, @NonNull DumpState dumpState,
            @NonNull String packageName);

    void dumpReceiverResolvers(@NonNull PrintWriter pw, @NonNull DumpState dumpState,
            @NonNull String packageName);

    void dumpServiceResolvers(@NonNull PrintWriter pw, @NonNull DumpState dumpState,
            @NonNull String packageName);

    void dumpContentProviders(@NonNull Computer computer, @NonNull PrintWriter pw,
            @NonNull DumpState dumpState, @NonNull String packageName);

    void dumpServicePermissions(@NonNull PrintWriter pw, @NonNull DumpState dumpState);
}
