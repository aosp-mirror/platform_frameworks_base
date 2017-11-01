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

package com.android.server.connectivity.tethering;

import static com.android.internal.telephony.IccCardConstants.INTENT_VALUE_ICC_LOADED;
import static com.android.internal.telephony.IccCardConstants.INTENT_KEY_ICC_STATE;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.util.VersionedBroadcastListener;
import android.net.util.VersionedBroadcastListener.IntentCallback;
import android.os.Handler;
import android.util.Log;

import com.android.internal.telephony.TelephonyIntents;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;


/**
 * A utility class that runs the provided callback on the provided handler when
 * observing a new SIM card having been loaded.
 *
 * @hide
 */
public class SimChangeListener extends VersionedBroadcastListener {
    private static final String TAG = SimChangeListener.class.getSimpleName();
    private static final boolean DBG = false;

    public SimChangeListener(Context ctx, Handler handler, Runnable onSimCardLoadedCallback) {
        super(TAG, ctx, handler, makeIntentFilter(), makeCallback(onSimCardLoadedCallback));
    }

    private static IntentFilter makeIntentFilter() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        return filter;
    }

    private static Consumer<Intent> makeCallback(Runnable onSimCardLoadedCallback) {
        return new Consumer<Intent>() {
            private boolean mSimNotLoadedSeen = false;

            @Override
            public void accept(Intent intent) {
                final String state = intent.getStringExtra(INTENT_KEY_ICC_STATE);
                Log.d(TAG, "got Sim changed to state " + state + ", mSimNotLoadedSeen=" +
                        mSimNotLoadedSeen);

                if (!INTENT_VALUE_ICC_LOADED.equals(state)) {
                    mSimNotLoadedSeen = true;
                    return;
                }

                if (mSimNotLoadedSeen) {
                    mSimNotLoadedSeen = false;
                    onSimCardLoadedCallback.run();
                }
            }
        };
    }
}
