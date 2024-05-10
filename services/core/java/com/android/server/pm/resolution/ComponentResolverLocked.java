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

import com.android.internal.pm.pkg.component.ParsedActivity;
import com.android.internal.pm.pkg.component.ParsedProvider;
import com.android.internal.pm.pkg.component.ParsedService;
import com.android.server.pm.Computer;
import com.android.server.pm.DumpState;
import com.android.server.pm.PackageManagerTracedLock;
import com.android.server.pm.UserManagerService;

import java.io.PrintWriter;
import java.util.List;

public abstract class ComponentResolverLocked extends ComponentResolverBase {

    protected final PackageManagerTracedLock mLock = new PackageManagerTracedLock();

    protected ComponentResolverLocked(@NonNull UserManagerService userManager) {
        super(userManager);
    }

    @Override
    public boolean componentExists(@NonNull ComponentName componentName) {
        synchronized (mLock) {
            return super.componentExists(componentName);
        }
    }

    @Nullable
    @Override
    public ParsedActivity getActivity(@NonNull ComponentName component) {
        synchronized (mLock) {
            return super.getActivity(component);
        }
    }

    @Nullable
    @Override
    public ParsedProvider getProvider(@NonNull ComponentName component) {
        synchronized (mLock) {
            return super.getProvider(component);
        }
    }

    @Nullable
    @Override
    public ParsedActivity getReceiver(@NonNull ComponentName component) {
        synchronized (mLock) {
            return super.getReceiver(component);
        }
    }

    @Nullable
    @Override
    public ParsedService getService(@NonNull ComponentName component) {
        synchronized (mLock) {
            return super.getService(component);
        }
    }

    @Override
    public boolean isActivityDefined(@NonNull ComponentName component) {
        synchronized (mLock) {
            return super.isActivityDefined(component);
        }
    }

    @Nullable
    @Override
    public List<ResolveInfo> queryActivities(@NonNull Computer computer, @NonNull Intent intent,
            @Nullable String resolvedType, long flags, @UserIdInt int userId) {
        synchronized (mLock) {
            return super.queryActivities(computer, intent, resolvedType, flags, userId);
        }
    }

    @Nullable
    @Override
    public List<ResolveInfo> queryActivities(@NonNull Computer computer, @NonNull Intent intent,
            @Nullable String resolvedType, long flags, @NonNull List<ParsedActivity> activities,
            @UserIdInt int userId) {
        synchronized (mLock) {
            return super.queryActivities(computer, intent, resolvedType, flags, activities, userId);
        }
    }

    @Nullable
    @Override
    public ProviderInfo queryProvider(@NonNull Computer computer, @NonNull String authority,
            long flags, @UserIdInt int userId) {
        synchronized (mLock) {
            return super.queryProvider(computer, authority, flags, userId);
        }
    }

    @Nullable
    @Override
    public List<ResolveInfo> queryProviders(@NonNull Computer computer, @NonNull Intent intent,
            @Nullable String resolvedType, long flags, @UserIdInt int userId) {
        synchronized (mLock) {
            return super.queryProviders(computer, intent, resolvedType, flags, userId);
        }
    }

    @Nullable
    @Override
    public List<ResolveInfo> queryProviders(@NonNull Computer computer, @NonNull Intent intent,
            @Nullable String resolvedType, long flags, @NonNull List<ParsedProvider> providers,
            @UserIdInt int userId) {
        synchronized (mLock) {
            return super.queryProviders(computer, intent, resolvedType, flags, providers, userId);
        }
    }

    @Nullable
    @Override
    public List<ProviderInfo> queryProviders(@NonNull Computer computer,
            @Nullable String processName, @Nullable String metaDataKey, int uid, long flags,
            @UserIdInt int userId) {
        synchronized (mLock) {
            return super.queryProviders(computer, processName, metaDataKey, uid, flags, userId);
        }
    }

    @Nullable
    @Override
    public List<ResolveInfo> queryReceivers(@NonNull Computer computer, @NonNull Intent intent,
            @Nullable String resolvedType, long flags, @UserIdInt int userId) {
        synchronized (mLock) {
            return super.queryReceivers(computer, intent, resolvedType, flags, userId);
        }
    }

    @Nullable
    @Override
    public List<ResolveInfo> queryReceivers(@NonNull Computer computer, @NonNull Intent intent,
            @Nullable String resolvedType, long flags, @NonNull List<ParsedActivity> receivers,
            @UserIdInt int userId) {
        synchronized (mLock) {
            return super.queryReceivers(computer, intent, resolvedType, flags, receivers, userId);
        }
    }

    @Nullable
    @Override
    public List<ResolveInfo> queryServices(@NonNull Computer computer, @NonNull Intent intent,
            @Nullable String resolvedType, long flags, @UserIdInt int userId) {
        synchronized (mLock) {
            return super.queryServices(computer, intent, resolvedType, flags, userId);
        }
    }

    @Nullable
    @Override
    public List<ResolveInfo> queryServices(@NonNull Computer computer, @NonNull Intent intent,
            @Nullable String resolvedType, long flags, @NonNull List<ParsedService> services,
            @UserIdInt int userId) {
        synchronized (mLock) {
            return super.queryServices(computer, intent, resolvedType, flags, services, userId);
        }
    }

    @Override
    public void querySyncProviders(@NonNull Computer computer, @NonNull List<String> outNames,
            @NonNull List<ProviderInfo> outInfo, boolean safeMode, @UserIdInt int userId) {
        synchronized (mLock) {
            super.querySyncProviders(computer, outNames, outInfo, safeMode, userId);
        }
    }

    @Override
    public void dumpActivityResolvers(@NonNull PrintWriter pw, @NonNull DumpState dumpState,
            @NonNull String packageName) {
        synchronized (mLock) {
            super.dumpActivityResolvers(pw, dumpState, packageName);
        }
    }

    @Override
    public void dumpProviderResolvers(@NonNull PrintWriter pw, @NonNull DumpState dumpState,
            @NonNull String packageName) {
        synchronized (mLock) {
            super.dumpProviderResolvers(pw, dumpState, packageName);
        }
    }

    @Override
    public void dumpReceiverResolvers(@NonNull PrintWriter pw, @NonNull DumpState dumpState,
            @NonNull String packageName) {
        synchronized (mLock) {
            super.dumpReceiverResolvers(pw, dumpState, packageName);
        }
    }

    @Override
    public void dumpServiceResolvers(@NonNull PrintWriter pw, @NonNull DumpState dumpState,
            @NonNull String packageName) {
        synchronized (mLock) {
            super.dumpServiceResolvers(pw, dumpState, packageName);
        }
    }

    @Override
    public void dumpContentProviders(@NonNull Computer computer, @NonNull PrintWriter pw,
            @NonNull DumpState dumpState, @NonNull String packageName) {
        synchronized (mLock) {
            super.dumpContentProviders(computer, pw, dumpState, packageName);
        }
    }

    @Override
    public void dumpServicePermissions(@NonNull PrintWriter pw, @NonNull DumpState dumpState) {
        synchronized (mLock) {
            super.dumpServicePermissions(pw, dumpState);
        }
    }
}
