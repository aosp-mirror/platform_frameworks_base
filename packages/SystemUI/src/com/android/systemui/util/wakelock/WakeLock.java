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
 * limitations under the License
 */

package com.android.systemui.util.wakelock;

import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import java.util.HashMap;

/** WakeLock wrapper for testability */
public interface WakeLock {

    static final String TAG = "WakeLock";
    static final String REASON_WRAP = "wrap";

    /**
     * @param why A tag that will be saved for sysui dumps.
     * @see android.os.PowerManager.WakeLock#acquire()
     **/
    void acquire(String why);

    /**
     * @param why Same tag used in {@link #acquire(String)}
     * @see android.os.PowerManager.WakeLock#release()
     **/
    void release(String why);

    /** @see android.os.PowerManager.WakeLock#wrap(Runnable) */
    Runnable wrap(Runnable r);

    static WakeLock createPartial(Context context, String tag) {
        return wrap(createPartialInner(context, tag));
    }

    @VisibleForTesting
    static PowerManager.WakeLock createPartialInner(Context context, String tag) {
        return context.getSystemService(PowerManager.class)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag);
    }

    static Runnable wrapImpl(WakeLock w, Runnable r) {
        w.acquire(REASON_WRAP);
        return () -> {
            try {
                r.run();
            } finally {
                w.release(REASON_WRAP);
            }
        };
    }

    static WakeLock wrap(final PowerManager.WakeLock inner) {
        return new WakeLock() {
            private final HashMap<String, Integer> mActiveClients = new HashMap<>();

            /** @see PowerManager.WakeLock#acquire() */
            public void acquire(String why) {
                mActiveClients.putIfAbsent(why, 0);
                mActiveClients.put(why, mActiveClients.get(why) + 1);
                inner.acquire();
            }

            /** @see PowerManager.WakeLock#release() */
            public void release(String why) {
                Integer count = mActiveClients.get(why);
                if (count == null) {
                    Log.wtf(TAG, "Releasing WakeLock with invalid reason: " + why,
                            new Throwable());
                } else if (count == 1) {
                    mActiveClients.remove(why);
                } else {
                    mActiveClients.put(why, count - 1);
                }
                inner.release();
            }

            /** @see PowerManager.WakeLock#wrap(Runnable) */
            public Runnable wrap(Runnable runnable) {
                return wrapImpl(this, runnable);
            }

            @Override
            public String toString() {
                return "active clients= " + mActiveClients.toString();
            }
        };
    }
}
