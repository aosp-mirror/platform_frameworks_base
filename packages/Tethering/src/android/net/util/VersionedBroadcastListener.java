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

package android.net.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.util.Log;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;


/**
 * A utility class that runs the provided callback on the provided handler when
 * intents matching the provided filter arrive. Intents received by a stale
 * receiver are safely ignored.
 *
 * Calls to startListening() and stopListening() must happen on the same thread.
 *
 * @hide
 */
public class VersionedBroadcastListener {
    private static final boolean DBG = false;

    private final String mTag;
    private final Context mContext;
    private final Handler mHandler;
    private final IntentFilter mFilter;
    private final Consumer<Intent> mCallback;
    private final AtomicInteger mGenerationNumber;
    private BroadcastReceiver mReceiver;

    public VersionedBroadcastListener(String tag, Context ctx, Handler handler,
            IntentFilter filter, Consumer<Intent> callback) {
        mTag = tag;
        mContext = ctx;
        mHandler = handler;
        mFilter = filter;
        mCallback = callback;
        mGenerationNumber = new AtomicInteger(0);
    }

    /** Start listening to intent broadcast. */
    public void startListening() {
        if (DBG) Log.d(mTag, "startListening");
        if (mReceiver != null) return;

        mReceiver = new Receiver(mTag, mGenerationNumber, mCallback);
        mContext.registerReceiver(mReceiver, mFilter, null, mHandler);
    }

    /** Stop listening to intent broadcast. */
    public void stopListening() {
        if (DBG) Log.d(mTag, "stopListening");
        if (mReceiver == null) return;

        mGenerationNumber.incrementAndGet();
        mContext.unregisterReceiver(mReceiver);
        mReceiver = null;
    }

    private static class Receiver extends BroadcastReceiver {
        public final String tag;
        public final AtomicInteger atomicGenerationNumber;
        public final Consumer<Intent> callback;
        // Used to verify this receiver is still current.
        public final int generationNumber;

        Receiver(String tag, AtomicInteger atomicGenerationNumber, Consumer<Intent> callback) {
            this.tag = tag;
            this.atomicGenerationNumber = atomicGenerationNumber;
            this.callback = callback;
            generationNumber = atomicGenerationNumber.incrementAndGet();
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final int currentGenerationNumber = atomicGenerationNumber.get();

            if (DBG) {
                Log.d(tag, "receiver generationNumber=" + generationNumber
                        + ", current generationNumber=" + currentGenerationNumber);
            }
            if (generationNumber != currentGenerationNumber) return;

            callback.accept(intent);
        }
    }
}
