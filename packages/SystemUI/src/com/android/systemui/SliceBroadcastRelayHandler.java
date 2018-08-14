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

package com.android.systemui;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.SliceBroadcastRelay;

/**
 * Allows settings to register certain broadcasts to launch the settings app for pinned slices.
 * @see SliceBroadcastRelay
 */
public class SliceBroadcastRelayHandler extends SystemUI {
    private static final String TAG = "SliceBroadcastRelay";
    private static final boolean DEBUG = false;

    private final ArrayMap<Uri, BroadcastRelay> mRelays = new ArrayMap<>();

    @Override
    public void start() {
        if (DEBUG) Log.d(TAG, "Start");
        IntentFilter filter = new IntentFilter(SliceBroadcastRelay.ACTION_REGISTER);
        filter.addAction(SliceBroadcastRelay.ACTION_UNREGISTER);
        mContext.registerReceiver(mReceiver, filter);
    }

    @VisibleForTesting
    void handleIntent(Intent intent) {
        if (SliceBroadcastRelay.ACTION_REGISTER.equals(intent.getAction())) {
            Uri uri = intent.getParcelableExtra(SliceBroadcastRelay.EXTRA_URI);
            ComponentName receiverClass =
                    intent.getParcelableExtra(SliceBroadcastRelay.EXTRA_RECEIVER);
            IntentFilter filter = intent.getParcelableExtra(SliceBroadcastRelay.EXTRA_FILTER);
            if (DEBUG) Log.d(TAG, "Register " + uri + " " + receiverClass + " " + filter);
            getOrCreateRelay(uri).register(mContext, receiverClass, filter);
        } else if (SliceBroadcastRelay.ACTION_UNREGISTER.equals(intent.getAction())) {
            Uri uri = intent.getParcelableExtra(SliceBroadcastRelay.EXTRA_URI);
            if (DEBUG) Log.d(TAG, "Unregister " + uri);
            getAndRemoveRelay(uri).unregister(mContext);
        }
    }

    private BroadcastRelay getOrCreateRelay(Uri uri) {
        BroadcastRelay ret = mRelays.get(uri);
        if (ret == null) {
            ret = new BroadcastRelay(uri);
            mRelays.put(uri, ret);
        }
        return ret;
    }

    private BroadcastRelay getAndRemoveRelay(Uri uri) {
        return mRelays.remove(uri);
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleIntent(intent);
        }
    };

    private static class BroadcastRelay extends BroadcastReceiver {

        private final ArraySet<ComponentName> mReceivers = new ArraySet<>();
        private final UserHandle mUserId;
        private final Uri mUri;

        public BroadcastRelay(Uri uri) {
            mUserId = new UserHandle(ContentProvider.getUserIdFromUri(uri));
            mUri = uri;
        }

        public void register(Context context, ComponentName receiver, IntentFilter filter) {
            mReceivers.add(receiver);
            context.registerReceiver(this, filter);
        }

        public void unregister(Context context) {
            context.unregisterReceiver(this);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            for (ComponentName receiver : mReceivers) {
                intent.setComponent(receiver);
                intent.putExtra(SliceBroadcastRelay.EXTRA_URI, mUri.toString());
                if (DEBUG) Log.d(TAG, "Forwarding " + receiver + " " + intent + " " + mUserId);
                context.sendBroadcastAsUser(intent, mUserId);
            }
        }
    }
}
