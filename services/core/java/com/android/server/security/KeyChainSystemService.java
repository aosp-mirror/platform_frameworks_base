/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.security;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.UserHandle;
import android.security.IKeyChainService;
import android.util.Slog;

import com.android.server.DeviceIdleController;
import com.android.server.LocalServices;
import com.android.server.SystemService;

/**
 * Service related to {@link android.security.KeyChain}.
 * <p>
 * Most of the implementation of KeyChain is provided by the com.android.keychain app. Until O,
 * this was OK because a system app has roughly the same privileges as the system process.
 * <p>
 * With the introduction of background check, PACKAGE_* broadcasts (_ADDED, _REMOVED, _REPLACED)
 * aren't received when the KeyChain app is in the background, which is bad as it uses those to
 * drive internal cleanup.
 * <p>
 * TODO (b/35968281): take a more sophisticated look at what bits of KeyChain should be inside the
 *                    system server and which make sense inside a system app.
 */
public class KeyChainSystemService extends SystemService {

    private static final String TAG = "KeyChainSystemService";

    /**
     * Maximum time limit for the KeyChain app to deal with packages being removed.
     */
    private static final int KEYCHAIN_IDLE_WHITELIST_DURATION_MS = 30 * 1000;

    public KeyChainSystemService(final Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        IntentFilter packageFilter = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addDataScheme("package");
        try {
            getContext().registerReceiverAsUser(mPackageReceiver, UserHandle.ALL,
                    packageFilter, null /*broadcastPermission*/, null /*handler*/);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Unable to register for package removed broadcast", e);
        }
    }

    private final BroadcastReceiver mPackageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent broadcastIntent) {
            if (broadcastIntent.getPackage() != null) {
                return;
            }

            try {
                final Intent intent = new Intent(IKeyChainService.class.getName());
                ComponentName service =
                        intent.resolveSystemService(getContext().getPackageManager(), 0 /*flags*/);
                if (service == null) {
                    return;
                }
                intent.setComponent(service);
                intent.setAction(broadcastIntent.getAction());
                startServiceInBackgroundAsUser(intent, UserHandle.of(getSendingUserId()));
            } catch (RuntimeException e) {
                Slog.e(TAG, "Unable to forward package removed broadcast to KeyChain", e);
            }
        }
    };


    private void startServiceInBackgroundAsUser(final Intent intent, final UserHandle user) {
        if (intent.getComponent() == null) {
            return;
        }

        final String packageName = intent.getComponent().getPackageName();
        final DeviceIdleController.LocalService idleController =
                LocalServices.getService(DeviceIdleController.LocalService.class);
        idleController.addPowerSaveTempWhitelistApp(Process.myUid(), packageName,
                KEYCHAIN_IDLE_WHITELIST_DURATION_MS, user.getIdentifier(), false, "keychain");

        getContext().startServiceAsUser(intent, user);
    }
}
