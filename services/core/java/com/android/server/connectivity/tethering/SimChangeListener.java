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
import android.os.Handler;
import android.util.Log;

import com.android.internal.telephony.TelephonyIntents;

import java.util.concurrent.atomic.AtomicInteger;


/**
 * A utility class that runs the provided callback on the provided handler when
 * observing a new SIM card having been loaded.
 *
 * @hide
 */
public class SimChangeListener {
    private static final String TAG = SimChangeListener.class.getSimpleName();
    private static final boolean DBG = false;

    private final Context mContext;
    private final Handler mTarget;
    private final AtomicInteger mSimBcastGenerationNumber;
    private final Runnable mCallback;
    private BroadcastReceiver mBroadcastReceiver;

    public SimChangeListener(Context ctx, Handler handler, Runnable onSimCardLoadedCallback) {
        mContext = ctx;
        mTarget = handler;
        mCallback = onSimCardLoadedCallback;
        mSimBcastGenerationNumber = new AtomicInteger(0);
    }

    public int generationNumber() {
        return mSimBcastGenerationNumber.get();
    }

    public void startListening() {
        if (DBG) Log.d(TAG, "startListening for SIM changes");

        if (mBroadcastReceiver != null) return;

        mBroadcastReceiver = new SimChangeBroadcastReceiver(
                mSimBcastGenerationNumber.incrementAndGet());
        final IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);

        mContext.registerReceiver(mBroadcastReceiver, filter, null, mTarget);
    }

    public void stopListening() {
        if (DBG) Log.d(TAG, "stopListening for SIM changes");

        if (mBroadcastReceiver == null) return;

        mSimBcastGenerationNumber.incrementAndGet();
        mContext.unregisterReceiver(mBroadcastReceiver);
        mBroadcastReceiver = null;
    }

    private boolean isSimCardLoaded(String state) {
        return INTENT_VALUE_ICC_LOADED.equals(state);
    }

    private class SimChangeBroadcastReceiver extends BroadcastReceiver {
        // used to verify this receiver is still current
        final private int mGenerationNumber;

        // used to check the sim state transition from non-loaded to loaded
        private boolean mSimNotLoadedSeen = false;

        public SimChangeBroadcastReceiver(int generationNumber) {
            mGenerationNumber = generationNumber;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final int currentGenerationNumber = mSimBcastGenerationNumber.get();

            if (DBG) {
                Log.d(TAG, "simchange mGenerationNumber=" + mGenerationNumber +
                        ", current generationNumber=" + currentGenerationNumber);
            }
            if (mGenerationNumber != currentGenerationNumber) return;

            final String state = intent.getStringExtra(INTENT_KEY_ICC_STATE);
            Log.d(TAG, "got Sim changed to state " + state + ", mSimNotLoadedSeen=" +
                    mSimNotLoadedSeen);

            if (!isSimCardLoaded(state)) {
                mSimNotLoadedSeen = true;
                return;
            }

            if (mSimNotLoadedSeen) {
                mSimNotLoadedSeen = false;
                mCallback.run();
            }
        }
    }
}
