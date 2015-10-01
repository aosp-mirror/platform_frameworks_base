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

package com.android.systemui.recents.model;

import android.content.ComponentName;
import android.content.Context;
import android.os.Looper;
import android.os.UserHandle;
import com.android.internal.content.PackageMonitor;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.PackagesChangedEvent;
import com.android.systemui.recents.misc.SystemServicesProxy;

import java.util.HashSet;
import java.util.List;

/**
 * The package monitor listens for changes from PackageManager to update the contents of the
 * Recents list.
 */
public class RecentsPackageMonitor extends PackageMonitor {

    /** Registers the broadcast receivers with the specified callbacks. */
    public void register(Context context) {
        try {
            // We register for events from all users, but will cross-reference them with
            // packages for the current user and any profiles they have
            register(context, Looper.getMainLooper(), UserHandle.ALL, true);
        } catch (IllegalStateException e) {
            e.printStackTrace();
        }
    }

    /** Unregisters the broadcast receivers. */
    @Override
    public void unregister() {
        try {
            super.unregister();
        } catch (IllegalStateException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onPackageRemoved(String packageName, int uid) {
        // Notify callbacks that a package has changed
        final int eventUserId = getChangingUserId();
        EventBus.getDefault().send(new PackagesChangedEvent(this, packageName, eventUserId));
    }

    @Override
    public boolean onPackageChanged(String packageName, int uid, String[] components) {
        onPackageModified(packageName);
        return true;
    }

    @Override
    public void onPackageModified(String packageName) {
        // Notify callbacks that a package has changed
        final int eventUserId = getChangingUserId();
        EventBus.getDefault().send(new PackagesChangedEvent(this, packageName, eventUserId));
    }

    /**
     * Computes the components that have been removed as a result of a change in the specified
     * package.
     */
    public HashSet<ComponentName> computeComponentsRemoved(List<Task.TaskKey> taskKeys,
            String packageName, int userId) {
        // Identify all the tasks that should be removed as a result of the package being removed.
        // Using a set to ensure that we callback once per unique component.
        HashSet<ComponentName> existingComponents = new HashSet<ComponentName>();
        HashSet<ComponentName> removedComponents = new HashSet<ComponentName>();
        for (Task.TaskKey t : taskKeys) {
            // Skip if this doesn't apply to the current user
            if (t.userId != userId) continue;

            ComponentName cn = t.baseIntent.getComponent();
            if (cn.getPackageName().equals(packageName)) {
                if (existingComponents.contains(cn)) {
                    // If we know that the component still exists in the package, then skip
                    continue;
                }
                SystemServicesProxy ssp = RecentsTaskLoader.getInstance().getSystemServicesProxy();
                if (ssp.getActivityInfo(cn, userId) != null) {
                    existingComponents.add(cn);
                } else {
                    removedComponents.add(cn);
                }
            }
        }
        return removedComponents;
    }
}
