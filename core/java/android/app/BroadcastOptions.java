/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.app;

import android.annotation.SystemApi;
import android.os.Bundle;

/**
 * Helper class for building an options Bundle that can be used with
 * {@link android.content.Context#sendBroadcast(android.content.Intent)
 * Context.sendBroadcast(Intent)} and related methods.
 * {@hide}
 */
@SystemApi
public class BroadcastOptions {
    private long mTemporaryAppWhitelistDuration;

    /**
     * How long to temporarily put an app on the power whitelist when executing this broadcast
     * to it.
     * @hide
     */
    public static final String KEY_TEMPORARY_APP_WHITELIST_DURATION
            = "android:broadcast.temporaryAppWhitelistDuration";

    public static BroadcastOptions makeBasic() {
        BroadcastOptions opts = new BroadcastOptions();
        return opts;
    }

    private BroadcastOptions() {
    }

    /** @hide */
    public BroadcastOptions(Bundle opts) {
        mTemporaryAppWhitelistDuration = opts.getLong(KEY_TEMPORARY_APP_WHITELIST_DURATION);
    }

    /**
     * Set a duration for which the system should temporary place an application on the
     * power whitelist when this broadcast is being delivered to it.
     * @param duration The duration in milliseconds; 0 means to not place on whitelist.
     */
    public void setTemporaryAppWhitelistDuration(long duration) {
        mTemporaryAppWhitelistDuration = duration;
    }

    /**
     * Return {@link #setTemporaryAppWhitelistDuration}.
     * @hide
     */
    public long getTemporaryAppWhitelistDuration() {
        return mTemporaryAppWhitelistDuration;
    }

    /**
     * Returns the created options as a Bundle, which can be passed to
     * {@link android.content.Context#sendBroadcast(android.content.Intent)
     * Context.sendBroadcast(Intent)} and related methods.
     * Note that the returned Bundle is still owned by the ActivityOptions
     * object; you must not modify it, but can supply it to the sendBroadcast
     * methods that take an options Bundle.
     */
    public Bundle toBundle() {
        Bundle b = new Bundle();
        if (mTemporaryAppWhitelistDuration > 0) {
            b.putLong(KEY_TEMPORARY_APP_WHITELIST_DURATION, mTemporaryAppWhitelistDuration);
        }
        return b.isEmpty() ? null : b;
    }
}
