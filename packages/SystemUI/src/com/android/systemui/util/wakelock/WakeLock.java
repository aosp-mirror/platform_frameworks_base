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

import javax.inject.Inject;

/** WakeLock wrapper for testability */
public interface WakeLock {

    static final String TAG = "WakeLock";
    static final String REASON_WRAP = "wrap";

    /**
     * Default wake-lock timeout in milliseconds, to avoid battery regressions.
     */
    long DEFAULT_MAX_TIMEOUT = 20000;

    /**
     * Default wake-lock levels and flags.
     */
    int DEFAULT_LEVELS_AND_FLAGS = PowerManager.PARTIAL_WAKE_LOCK;

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
        return createPartial(context, tag, DEFAULT_MAX_TIMEOUT);
    }

    /**
     * Creates a {@link WakeLock} that has a default release timeout.
     * @see android.os.PowerManager.WakeLock#acquire(long) */
    static WakeLock createPartial(Context context, String tag, long maxTimeout) {
        return wrap(createWakeLockInner(context, tag, DEFAULT_LEVELS_AND_FLAGS), maxTimeout);
    }

    /**
     * Creates a {@link WakeLock} that has a default release timeout and flags.
     */
    static WakeLock createWakeLock(Context context, String tag, int flags, long maxTimeout) {
        return wrap(createWakeLockInner(context, tag, flags), maxTimeout);
    }

    @VisibleForTesting
    static PowerManager.WakeLock createWakeLockInner(
            Context context, String tag, int levelsAndFlags) {
        return context.getSystemService(PowerManager.class)
                    .newWakeLock(levelsAndFlags, tag);
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

    /**
     * Create a {@link WakeLock} containing a {@link PowerManager.WakeLock}.
     * @param inner To be wrapped.
     * @param maxTimeout When to expire.
     * @return The new wake lock.
     */
    @VisibleForTesting
    static WakeLock wrap(final PowerManager.WakeLock inner, long maxTimeout) {
        return new WakeLock() {
            private final HashMap<String, Integer> mActiveClients = new HashMap<>();

            /** @see PowerManager.WakeLock#acquire() */
            public void acquire(String why) {
                mActiveClients.putIfAbsent(why, 0);
                mActiveClients.put(why, mActiveClients.get(why) + 1);
                inner.acquire(maxTimeout);
            }

            /** @see PowerManager.WakeLock#release() */
            public void release(String why) {
                Integer count = mActiveClients.get(why);
                if (count == null) {
                    Log.wtf(TAG, "Releasing WakeLock with invalid reason: " + why,
                            new Throwable());
                    return;
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

    /**
     * An injectable Builder that wraps {@link #createPartial(Context, String, long)}.
     */
    class Builder {
        private final Context mContext;
        private String mTag;
        private int mLevelsAndFlags = DEFAULT_LEVELS_AND_FLAGS;
        private long mMaxTimeout = DEFAULT_MAX_TIMEOUT;

        @Inject
        public Builder(Context context) {
            mContext = context;
        }

        public Builder setTag(String tag) {
            this.mTag = tag;
            return this;
        }

        public Builder setLevelsAndFlags(int levelsAndFlags) {
            this.mLevelsAndFlags = levelsAndFlags;
            return this;
        }

        public Builder setMaxTimeout(long maxTimeout) {
            this.mMaxTimeout = maxTimeout;
            return this;
        }

        public WakeLock build() {
            return WakeLock.createWakeLock(mContext, mTag, mLevelsAndFlags, mMaxTimeout);
        }
    }
}
