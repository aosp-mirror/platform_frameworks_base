/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.util;

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;

/**
 * Interface that provides trusted time information, possibly coming from an NTP
 * server.
 *
 * @hide
 * @deprecated Only kept for UnsupportedAppUsage. Do not use. See {@link NtpTrustedTime}
 */
public interface TrustedTime {
    /**
     * Force update with an external trusted time source, returning {@code true}
     * when successful.
     *
     * @deprecated Only kept for UnsupportedAppUsage. Do not use. See {@link NtpTrustedTime}
     */
    @Deprecated
    @UnsupportedAppUsage
    public boolean forceRefresh();

    /**
     * Check if this instance has cached a response from a trusted time source.
     *
     * @deprecated Only kept for UnsupportedAppUsage. Do not use. See {@link NtpTrustedTime}
     */
    @Deprecated
    @UnsupportedAppUsage
    boolean hasCache();

    /**
     * Return time since last trusted time source contact, or
     * {@link Long#MAX_VALUE} if never contacted.
     *
     * @deprecated Only kept for UnsupportedAppUsage. Do not use. See {@link NtpTrustedTime}
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public long getCacheAge();

    /**
     * Return current time similar to {@link System#currentTimeMillis()},
     * possibly using a cached authoritative time source.
     *
     * @deprecated Only kept for UnsupportedAppUsage. Do not use. See {@link NtpTrustedTime}
     */
    @Deprecated
    @UnsupportedAppUsage
    long currentTimeMillis();
}
