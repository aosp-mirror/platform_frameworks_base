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

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.UserHandle;
import android.testing.LeakCheck;
import android.testing.TestableContext;
import android.util.ArraySet;
import android.util.Log;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class SysuiTestableContext extends TestableContext {

    @GuardedBy("mRegisteredReceivers")
    private final Set<BroadcastReceiver> mRegisteredReceivers = new ArraySet<>();
    private final Map<UserHandle, Context> mContextForUser = new HashMap<>();

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

    public SysuiTestableContext createDefaultDisplayContext() {
        Display display = getBaseContext().getSystemService(DisplayManager.class).getDisplays()[0];
        return (SysuiTestableContext) createDisplayContext(display);
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
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) {
        if (receiver != null) {
            synchronized (mRegisteredReceivers) {
                mRegisteredReceivers.add(receiver);
            }
        }
        return super.registerReceiver(receiver, filter, flags);
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
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, Handler scheduler, int flags) {
        if (receiver != null) {
            synchronized (mRegisteredReceivers) {
                mRegisteredReceivers.add(receiver);
            }
        }
        return super.registerReceiver(receiver, filter, broadcastPermission, scheduler, flags);
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
    public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
            IntentFilter filter, String broadcastPermission, Handler scheduler, int flags) {
        if (receiver != null) {
            synchronized (mRegisteredReceivers) {
                mRegisteredReceivers.add(receiver);
            }
        }
        return super.registerReceiverAsUser(receiver, user, filter, broadcastPermission, scheduler,
                flags);
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

    /**
     * Sets a Context object that will be returned as the result of {@link #createContextAsUser}
     * for a specific {@code user}.
     */
    public void prepareCreateContextAsUser(UserHandle user, Context context) {
        mContextForUser.put(user, context);
    }

    @Override
    @NonNull
    public Context createContextAsUser(UserHandle user, int flags) {
        Context userContext = mContextForUser.get(user);
        if (userContext != null) {
            return userContext;
        }
        return super.createContextAsUser(user, flags);
    }
}
