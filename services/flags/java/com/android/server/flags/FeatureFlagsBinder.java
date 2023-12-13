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

package com.android.server.flags;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.flags.IFeatureFlags;
import android.flags.IFeatureFlagsCallback;
import android.flags.SyncableFlag;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import com.android.internal.flags.CoreFlags;
import com.android.server.flags.FeatureFlagsService.PermissionsChecker;

import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

class FeatureFlagsBinder extends IFeatureFlags.Stub {
    private final FlagOverrideStore mFlagStore;
    private final FlagsShellCommand mShellCommand;
    private final FlagCache<String> mFlagCache = new FlagCache<>();
    private final DynamicFlagBinderDelegate mDynamicFlagDelegate;
    private final PermissionsChecker mPermissionsChecker;

    FeatureFlagsBinder(
            FlagOverrideStore flagStore,
            FlagsShellCommand shellCommand,
            PermissionsChecker permissionsChecker) {
        mFlagStore = flagStore;
        mShellCommand = shellCommand;
        mDynamicFlagDelegate = new DynamicFlagBinderDelegate(flagStore);
        mPermissionsChecker = permissionsChecker;
    }

    @Override
    public void registerCallback(IFeatureFlagsCallback callback) {
        mDynamicFlagDelegate.registerCallback(getCallingPid(), callback);
    }

    @Override
    public void unregisterCallback(IFeatureFlagsCallback callback) {
        mDynamicFlagDelegate.unregisterCallback(getCallingPid(), callback);
    }

    // Note: The internals of this method should be kept in sync with queryFlags
    // as they both should return identical results. The difference is that this method
    // caches any values it receives and/or reads, whereas queryFlags does not.

    @Override
    public List<SyncableFlag> syncFlags(List<SyncableFlag> incomingFlags) {
        int pid = getCallingPid();
        List<SyncableFlag> outputFlags = new ArrayList<>();

        boolean hasFullSyncPrivileges = false;
        SecurityException permissionFailureException = null;
        try {
            assertSyncPermission();
            hasFullSyncPrivileges = true;
        } catch (SecurityException e) {
            permissionFailureException = e;
        }

        for (SyncableFlag sf : incomingFlags) {
            if (!hasFullSyncPrivileges && !CoreFlags.isCoreFlag(sf)) {
                throw permissionFailureException;
            }

            String ns = sf.getNamespace();
            String name = sf.getName();
            SyncableFlag outFlag;
            if (sf.isDynamic()) {
                outFlag = mDynamicFlagDelegate.syncDynamicFlag(pid, sf);
            } else {
                synchronized (mFlagCache) {
                    String value = mFlagCache.getOrNull(ns, name);
                    if (value == null) {
                        String overrideValue = Build.IS_USER ? null : mFlagStore.get(ns, name);
                        value = overrideValue != null ? overrideValue : sf.getValue();
                        mFlagCache.setIfChanged(ns, name, value);
                    }
                    outFlag = new SyncableFlag(sf.getNamespace(), sf.getName(), value, false);
                }
            }
            outputFlags.add(outFlag);
        }
        return outputFlags;
    }

    @Override
    public void overrideFlag(SyncableFlag flag) {
        assertWritePermission();
        mFlagStore.set(flag.getNamespace(), flag.getName(), flag.getValue());
    }

    @Override
    public void resetFlag(SyncableFlag flag) {
        assertWritePermission();
        mFlagStore.erase(flag.getNamespace(), flag.getName());
    }

    @Override
    public List<SyncableFlag> queryFlags(List<SyncableFlag> incomingFlags) {
        assertSyncPermission();
        List<SyncableFlag> outputFlags = new ArrayList<>();
        for (SyncableFlag sf : incomingFlags) {
            String ns = sf.getNamespace();
            String name = sf.getName();
            String value;
            String storeValue = mFlagStore.get(ns, name);
            boolean overridden  = storeValue != null;

            if (sf.isDynamic()) {
                value = mDynamicFlagDelegate.getFlagValue(ns, name, sf.getValue());
            } else {
                value = mFlagCache.getOrNull(ns, name);
                if (value == null) {
                    value = Build.IS_USER ? null : storeValue;
                    if (value == null) {
                        value = sf.getValue();
                    }
                }
            }
            outputFlags.add(new SyncableFlag(
                    sf.getNamespace(), sf.getName(), value, sf.isDynamic(), overridden));
        }

        return outputFlags;
    }

    private void assertSyncPermission() {
        mPermissionsChecker.assertSyncPermission();
        clearCallingIdentity();
    }

    private void assertWritePermission() {
        mPermissionsChecker.assertWritePermission();
        clearCallingIdentity();
    }


    @SystemApi
    public int handleShellCommand(
            @NonNull ParcelFileDescriptor in,
            @NonNull ParcelFileDescriptor out,
            @NonNull ParcelFileDescriptor err,
            @NonNull String[] args) {
        FileOutputStream fout = new FileOutputStream(out.getFileDescriptor());
        FileOutputStream ferr = new FileOutputStream(err.getFileDescriptor());

        return mShellCommand.process(args, fout, ferr);
    }
}
