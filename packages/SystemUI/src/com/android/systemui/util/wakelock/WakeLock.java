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
import android.support.annotation.VisibleForTesting;

import com.android.systemui.plugins.doze.DozeProvider;

/** WakeLock wrapper for testability */
public interface WakeLock extends DozeProvider.WakeLock {

    static WakeLock createPartial(Context context, String tag) {
        return wrap(createPartialInner(context, tag));
    }

    @VisibleForTesting
    static PowerManager.WakeLock createPartialInner(Context context, String tag) {
        return context.getSystemService(PowerManager.class)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag);
    }

    static WakeLock wrap(final PowerManager.WakeLock inner) {
        return new WakeLock() {
            /** @see PowerManager.WakeLock#acquire() */
            public void acquire() {
                inner.acquire();
            }

            /** @see PowerManager.WakeLock#release() */
            public void release() {
                inner.release();
            }

            /** @see PowerManager.WakeLock#wrap(Runnable) */
            public Runnable wrap(Runnable runnable) {
                return inner.wrap(runnable);
            }
        };
    }
}