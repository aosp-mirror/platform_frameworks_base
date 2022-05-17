/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.util.service;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PatternMatcher;
import android.util.Log;

import com.google.android.collect.Lists;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;

import javax.inject.Inject;

/**
 * {@link PackageObserver} allows for monitoring the system for changes relating to a particular
 * package. This can be used by clients to detect when a related package has changed and reloading
 * is necessary.
 */
public class PackageObserver implements Observer {
    private static final String TAG = "PackageObserver";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final ArrayList<WeakReference<Callback>> mCallbacks = Lists.newArrayList();

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) {
                Log.d(TAG, "package added receiver - onReceive");
            }

            final Iterator<WeakReference<Callback>> iter = mCallbacks.iterator();
            while (iter.hasNext()) {
                final Callback callback = iter.next().get();
                if (callback != null) {
                    callback.onSourceChanged();
                } else {
                    iter.remove();
                }
            }
        }
    };

    private final String mPackageName;
    private final Context mContext;

    @Inject
    public PackageObserver(Context context, ComponentName component) {
        mContext = context;
        mPackageName = component.getPackageName();
    }

    @Override
    public void addCallback(Callback callback) {
        if (DEBUG) {
            Log.d(TAG, "addCallback:" + callback);
        }
        mCallbacks.add(new WeakReference<>(callback));

        // Only register for listening to package additions on first callback.
        if (mCallbacks.size() > 1) {
            return;
        }

        final IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addDataScheme("package");
        filter.addDataSchemeSpecificPart(mPackageName, PatternMatcher.PATTERN_LITERAL);
        // Note that we directly register the receiver here as data schemes are not supported by
        // BroadcastDispatcher.
        mContext.registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED);
    }

    @Override
    public void removeCallback(Callback callback) {
        if (DEBUG) {
            Log.d(TAG, "removeCallback:" + callback);
        }
        final boolean removed = mCallbacks.removeIf(el -> el.get() == callback);

        if (removed && mCallbacks.isEmpty()) {
            mContext.unregisterReceiver(mReceiver);
        }
    }
}
