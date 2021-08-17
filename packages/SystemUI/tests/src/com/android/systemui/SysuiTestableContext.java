/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.UserHandle;
import android.testing.LeakCheck;
import android.testing.TestableContext;
import android.util.ArraySet;
import android.util.Log;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;

import java.util.Set;

public class SysuiTestableContext extends TestableContext {

    @GuardedBy("mRegisteredReceivers")
    private final Set<BroadcastReceiver> mRegisteredReceivers = new ArraySet<>();

    public SysuiTestableContext(Context base) {
        super(base);
        setTheme(R.style.Theme_SystemUI);
    }

    public SysuiTestableContext(Context base, LeakCheck check) {
        super(base, check);
        setTheme(R.style.Theme_SystemUI);
    }

    @Override
    public Context createDisplayContext(Display display) {
        if (display == null) {
            throw new IllegalArgumentException("display must not be null");
        }

        SysuiTestableContext context =
                new SysuiTestableContext(getBaseContext().createDisplayContext(display));
        return context;
    }

    public void cleanUpReceivers(String testName) {
        Set<BroadcastReceiver> copy;
        synchronized (mRegisteredReceivers) {
            copy = new ArraySet<>(mRegisteredReceivers);
            mRegisteredReceivers.clear();
        }
        for (BroadcastReceiver r : copy) {
            try {
                unregisterReceiver(r);
                Log.w(testName, "Receiver not unregistered from Context: " + r);
            } catch (IllegalArgumentException e) {
                // Nothing to do here. Somehow it got unregistered.
            }
        }
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        if (receiver != null) {
            synchronized (mRegisteredReceivers) {
                mRegisteredReceivers.add(receiver);
            }
        }
        return super.registerReceiver(receiver, filter);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, Handler scheduler) {
        if (receiver != null) {
            synchronized (mRegisteredReceivers) {
                mRegisteredReceivers.add(receiver);
            }
        }
        return super.registerReceiver(receiver, filter, broadcastPermission, scheduler);
    }

    @Override
    public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
            IntentFilter filter, String broadcastPermission, Handler scheduler) {
        if (receiver != null) {
            synchronized (mRegisteredReceivers) {
                mRegisteredReceivers.add(receiver);
            }
        }
        return super.registerReceiverAsUser(receiver, user, filter, broadcastPermission, scheduler);
    }

    @Override
    public void unregisterReceiver(BroadcastReceiver receiver) {
        if (receiver != null) {
            synchronized (mRegisteredReceivers) {
                mRegisteredReceivers.remove(receiver);
            }
        }
        super.unregisterReceiver(receiver);
    }
}
