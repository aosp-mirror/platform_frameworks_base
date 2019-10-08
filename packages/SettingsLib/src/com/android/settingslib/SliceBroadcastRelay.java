/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settingslib;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Process;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Log;

import java.util.Set;

/**
 * Utility class that allows Settings to use SystemUI to relay broadcasts related to pinned slices.
 */
public class SliceBroadcastRelay {

    public static final String ACTION_REGISTER
            = "com.android.settingslib.action.REGISTER_SLICE_RECEIVER";
    public static final String ACTION_UNREGISTER
            = "com.android.settingslib.action.UNREGISTER_SLICE_RECEIVER";
    public static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_RECEIVER = "receiver";
    public static final String EXTRA_FILTER = "filter";
    private static final String TAG = "SliceBroadcastRelay";

    private static final Set<Uri> sRegisteredUris = new ArraySet<>();

    /**
     * Associate intent filter/sliceUri with corresponding receiver.
     */
    public static void registerReceiver(Context context, Uri sliceUri,
            Class<? extends BroadcastReceiver> receiver, IntentFilter filter) {

        Log.d(TAG, "Registering Uri for broadcast relay: " + sliceUri);
        sRegisteredUris.add(sliceUri);

        Intent registerBroadcast = new Intent(ACTION_REGISTER);
        registerBroadcast.setPackage(SYSTEMUI_PACKAGE);
        registerBroadcast.putExtra(EXTRA_URI, ContentProvider.maybeAddUserId(sliceUri,
                Process.myUserHandle().getIdentifier()));
        registerBroadcast.putExtra(EXTRA_RECEIVER,
                new ComponentName(context.getPackageName(), receiver.getName()));
        registerBroadcast.putExtra(EXTRA_FILTER, filter);

        context.sendBroadcastAsUser(registerBroadcast, UserHandle.SYSTEM);
    }

    /**
     * Unregisters all receivers for a given slice uri.
     */

    public static void unregisterReceivers(Context context, Uri sliceUri) {
        if (!sRegisteredUris.contains(sliceUri)) {
            return;
        }
        Log.d(TAG, "Unregistering uri broadcast relay: " + sliceUri);
        final Intent registerBroadcast = new Intent(ACTION_UNREGISTER);
        registerBroadcast.setPackage(SYSTEMUI_PACKAGE);
        registerBroadcast.putExtra(EXTRA_URI, ContentProvider.maybeAddUserId(sliceUri,
                Process.myUserHandle().getIdentifier()));

        context.sendBroadcastAsUser(registerBroadcast, UserHandle.SYSTEM);
        sRegisteredUris.remove(sliceUri);
    }
}
