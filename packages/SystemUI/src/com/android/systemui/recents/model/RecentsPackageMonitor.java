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

import android.content.Context;
import android.os.UserHandle;

import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.PackagesChangedEvent;

/**
 * The package monitor listens for changes from PackageManager to update the contents of the
 * Recents list.
 */
public class RecentsPackageMonitor extends PackageMonitor {

    /** Registers the broadcast receivers with the specified callbacks. */
    public void register(Context context) {
        try {
            // We register for events from all users, but will cross-reference them with
            // packages for the current user and any profiles they have.  Ensure that events are
            // handled in a background thread.
            register(context, BackgroundThread.get().getLooper(), UserHandle.ALL, true);
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
        // Notify callbacks on the main thread that a package has changed
        final int eventUserId = getChangingUserId();
        EventBus.getDefault().post(new PackagesChangedEvent(this, packageName, eventUserId));
    }

    @Override
    public boolean onPackageChanged(String packageName, int uid, String[] components) {
        onPackageModified(packageName);
        return true;
    }

    @Override
    public void onPackageModified(String packageName) {
        // Notify callbacks on the main thread that a package has changed
        final int eventUserId = getChangingUserId();
        EventBus.getDefault().post(new PackagesChangedEvent(this, packageName, eventUserId));
    }
}
