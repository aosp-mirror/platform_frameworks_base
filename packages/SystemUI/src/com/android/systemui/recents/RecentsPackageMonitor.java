/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Looper;
import com.android.internal.content.PackageMonitor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The package monitor listens for changes from PackageManager to update the contents of the Recents
 * list.
 */
public class RecentsPackageMonitor extends PackageMonitor {
    public interface PackageCallbacks {
        public void onComponentRemoved(Set<ComponentName> cns);
    }

    PackageCallbacks mCb;
    List<ActivityManager.RecentTaskInfo> mTasks;
    SystemServicesProxy mSsp;
    boolean mRegistered;

    public RecentsPackageMonitor(Context context) {
        mSsp = new SystemServicesProxy(context);
    }

    /** Registers the broadcast receivers with the specified callbacks. */
    public void register(Context context, PackageCallbacks cb) {
        mCb = cb;
        if (!mRegistered) {
            register(context, Looper.getMainLooper(), false);
            mRegistered = true;
        }
    }

    /** Unregisters the broadcast receivers. */
    @Override
    public void unregister() {
        if (mRegistered) {
            super.unregister();
            mRegistered = false;
        }
        mTasks.clear();
    }

    /** Sets the list of tasks to match against package broadcast changes. */
    void setTasks(List<ActivityManager.RecentTaskInfo> tasks) {
        mTasks = tasks;
    }

    @Override
    public void onPackageRemoved(String packageName, int uid) {
        if (mCb == null) return;

        // Identify all the tasks that should be removed as a result of the package being removed.
        // Using a set to ensure that we callback once per unique component.
        HashSet<ComponentName> componentsToRemove = new HashSet<ComponentName>();
        for (ActivityManager.RecentTaskInfo t : mTasks) {
            ComponentName cn = t.baseIntent.getComponent();
            if (cn.getPackageName().equals(packageName)) {
                componentsToRemove.add(cn);
            }
        }
        // Notify our callbacks that the components no longer exist
        mCb.onComponentRemoved(componentsToRemove);
    }

    @Override
    public boolean onPackageChanged(String packageName, int uid, String[] components) {
        onPackageModified(packageName);
        return true;
    }

    @Override
    public void onPackageModified(String packageName) {
        if (mCb == null) return;

        // Identify all the tasks that should be removed as a result of the package being removed.
        // Using a set to ensure that we callback once per unique component.
        HashSet<ComponentName> componentsKnownToExist = new HashSet<ComponentName>();
        HashSet<ComponentName> componentsToRemove = new HashSet<ComponentName>();
        for (ActivityManager.RecentTaskInfo t : mTasks) {
            ComponentName cn = t.baseIntent.getComponent();
            if (cn.getPackageName().equals(packageName)) {
                if (componentsKnownToExist.contains(cn)) {
                    // If we know that the component still exists in the package, then skip
                    continue;
                }
                if (mSsp.getActivityInfo(cn) != null) {
                    componentsKnownToExist.add(cn);
                } else {
                    componentsToRemove.add(cn);
                }
            }
        }
        // Notify our callbacks that the components no longer exist
        mCb.onComponentRemoved(componentsToRemove);
    }
}
