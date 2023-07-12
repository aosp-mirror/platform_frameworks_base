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

import java.io.FileOutputStream;
import java.util.List;

class FeatureFlagsBinder extends IFeatureFlags.Stub {
    private final FlagOverrideStore mFlagStore;
    private final FlagsShellCommand mShellCommand;
    private final FlagCache<String> mFlagCache = new FlagCache<>();
    private final DynamicFlagBinderDelegate mDynamicFlagDelegate;

    FeatureFlagsBinder(FlagOverrideStore flagStore, FlagsShellCommand shellCommand) {
        mFlagStore = flagStore;
        mShellCommand = shellCommand;
        mDynamicFlagDelegate = new DynamicFlagBinderDelegate(flagStore);
    }

    @Override
    public void registerCallback(IFeatureFlagsCallback callback) {
        mDynamicFlagDelegate.registerCallback(getCallingPid(), callback);
    }

    @Override
    public void unregisterCallback(IFeatureFlagsCallback callback) {
        mDynamicFlagDelegate.unregisterCallback(getCallingPid(), callback);
    }

    @Override
    public List<SyncableFlag> syncFlags(List<SyncableFlag> incomingFlags) {
        int pid = getCallingPid();
        for (SyncableFlag sf : incomingFlags) {
            String ns = sf.getNamespace();
            String name = sf.getName();
            if (sf.isDynamic()) {
                mDynamicFlagDelegate.syncDynamicFlag(pid, sf);
            } else {
                synchronized (mFlagCache) {
                    String value = mFlagCache.getOrNull(ns, name);
                    if (value == null) {
                        String overrideValue = Build.IS_USER ? null : mFlagStore.get(ns, name);
                        value = overrideValue != null ? overrideValue : sf.getValue();
                        mFlagCache.setIfChanged(ns, name, value);
                    }
                    sf.setValue(value);
                }
            }
        }
        return incomingFlags;
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
