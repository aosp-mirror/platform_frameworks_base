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

import android.net.SntpClient;
import android.os.SystemClock;

/**
 * {@link TrustedTime} that connects with a remote NTP server as its remote
 * trusted time source.
 *
 * @hide
 */
public class NtpTrustedTime implements TrustedTime {
    private String mNtpServer;
    private long mNtpTimeout;

    private boolean mHasCache;
    private long mCachedNtpTime;
    private long mCachedNtpElapsedRealtime;
    private long mCachedNtpCertainty;

    public NtpTrustedTime() {
    }

    public void setNtpServer(String server, long timeout) {
        mNtpServer = server;
        mNtpTimeout = timeout;
    }

    /** {@inheritDoc} */
    public boolean forceRefresh() {
        if (mNtpServer == null) {
            throw new IllegalStateException("Missing NTP server");
        }

        final SntpClient client = new SntpClient();
        if (client.requestTime(mNtpServer, (int) mNtpTimeout)) {
            mHasCache = true;
            mCachedNtpTime = client.getNtpTime();
            mCachedNtpElapsedRealtime = client.getNtpTimeReference();
            mCachedNtpCertainty = client.getRoundTripTime() / 2;
            return true;
        } else {
            return false;
        }
    }

    /** {@inheritDoc} */
    public boolean hasCache() {
        return mHasCache;
    }

    /** {@inheritDoc} */
    public long getCacheAge() {
        if (mHasCache) {
            return SystemClock.elapsedRealtime() - mCachedNtpElapsedRealtime;
        } else {
            return Long.MAX_VALUE;
        }
    }

    /** {@inheritDoc} */
    public long getCacheCertainty() {
        if (mHasCache) {
            return mCachedNtpCertainty;
        } else {
            return Long.MAX_VALUE;
        }
    }

    /** {@inheritDoc} */
    public long currentTimeMillis() {
        if (!mHasCache) {
            throw new IllegalStateException("Missing authoritative time source");
        }

        // current time is age after the last ntp cache; callers who
        // want fresh values will hit makeAuthoritative() first.
        return mCachedNtpTime + getCacheAge();
    }
}
