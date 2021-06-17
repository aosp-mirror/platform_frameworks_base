/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm;

import android.annotation.NonNull;;
import android.text.TextUtils;

import com.android.internal.util.HexDump;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class PerPackageReadTimeouts {
    static long tryParseLong(String str, long defaultValue) {
        try {
            return Long.parseLong(str);
        } catch (NumberFormatException nfe) {
            return defaultValue;
        }
    }

    static byte[] tryParseSha256(String str) {
        if (TextUtils.isEmpty(str)) {
            return null;
        }
        try {
            return HexDump.hexStringToByteArray(str);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static class Timeouts {
        public final long minTimeUs;
        public final long minPendingTimeUs;
        public final long maxPendingTimeUs;

        // 3600000000us == 1hr
        public static final Timeouts DEFAULT = new Timeouts(3600000000L, 3600000000L, 3600000000L);

        private Timeouts(long minTimeUs, long minPendingTimeUs, long maxPendingTimeUs) {
            this.minTimeUs = minTimeUs;
            this.minPendingTimeUs = minPendingTimeUs;
            this.maxPendingTimeUs = maxPendingTimeUs;
        }

        static Timeouts parse(String timeouts) {
            String[] splits = timeouts.split(":", 3);
            if (splits.length != 3) {
                return DEFAULT;
            }
            final long minTimeUs = tryParseLong(splits[0], DEFAULT.minTimeUs);
            final long minPendingTimeUs = tryParseLong(splits[1], DEFAULT.minPendingTimeUs);
            final long maxPendingTimeUs = tryParseLong(splits[2], DEFAULT.maxPendingTimeUs);
            if (0 <= minTimeUs && minTimeUs <= minPendingTimeUs
                    && minPendingTimeUs <= maxPendingTimeUs) {
                // validity check
                return new Timeouts(minTimeUs, minPendingTimeUs, maxPendingTimeUs);
            }
            return DEFAULT;
        }
    }

    static class VersionCodes {
        public final long minVersionCode;
        public final long maxVersionCode;

        public static final VersionCodes ALL_VERSION_CODES = new VersionCodes(Long.MIN_VALUE,
                Long.MAX_VALUE);

        private VersionCodes(long minVersionCode, long maxVersionCode) {
            this.minVersionCode = minVersionCode;
            this.maxVersionCode = maxVersionCode;
        }

        static VersionCodes parse(String codes) {
            if (TextUtils.isEmpty(codes)) {
                return ALL_VERSION_CODES;
            }
            String[] splits = codes.split("-", 2);
            switch (splits.length) {
                case 1: {
                    // single version code
                    try {
                        final long versionCode = Long.parseLong(splits[0]);
                        return new VersionCodes(versionCode, versionCode);
                    } catch (NumberFormatException nfe) {
                        return ALL_VERSION_CODES;
                    }
                }
                case 2: {
                    final long minVersionCode = tryParseLong(splits[0],
                            ALL_VERSION_CODES.minVersionCode);
                    final long maxVersionCode = tryParseLong(splits[1],
                            ALL_VERSION_CODES.maxVersionCode);
                    if (minVersionCode <= maxVersionCode) {
                        return new VersionCodes(minVersionCode, maxVersionCode);
                    }
                    break;
                }
            }
            return ALL_VERSION_CODES;
        }
    }

    public final String packageName;
    public final byte[] sha256certificate;
    public final VersionCodes versionCodes;
    public final Timeouts timeouts;

    private PerPackageReadTimeouts(String packageName, byte[] sha256certificate,
            VersionCodes versionCodes, Timeouts timeouts) {
        this.packageName = packageName;
        this.sha256certificate = sha256certificate;
        this.versionCodes = versionCodes;
        this.timeouts = timeouts;
    }

    @SuppressWarnings("fallthrough")
    static PerPackageReadTimeouts parse(String timeoutsStr, VersionCodes defaultVersionCodes,
            Timeouts defaultTimeouts) {
        String packageName = null;
        byte[] sha256certificate = null;
        VersionCodes versionCodes = defaultVersionCodes;
        Timeouts timeouts = defaultTimeouts;

        final String[] splits = timeoutsStr.split(":", 4);
        switch (splits.length) {
            case 4:
                timeouts = Timeouts.parse(splits[3]);
                // fall through
            case 3:
                versionCodes = VersionCodes.parse(splits[2]);
                // fall through
            case 2:
                sha256certificate = tryParseSha256(splits[1]);
                // fall through
            case 1:
                packageName = splits[0];
                break;
            default:
                return null;
        }
        if (TextUtils.isEmpty(packageName)) {
            return null;
        }

        return new PerPackageReadTimeouts(packageName, sha256certificate, versionCodes,
                timeouts);
    }

    static @NonNull List<PerPackageReadTimeouts> parseDigestersList(String defaultTimeoutsStr,
            String knownDigestersList) {
        if (TextUtils.isEmpty(knownDigestersList)) {
            return Collections.emptyList();
        }

        final VersionCodes defaultVersionCodes = VersionCodes.ALL_VERSION_CODES;
        final Timeouts defaultTimeouts = Timeouts.parse(defaultTimeoutsStr);

        String[] packages = knownDigestersList.split(",");
        List<PerPackageReadTimeouts> result = new ArrayList<>(packages.length);
        for (int i = 0, size = packages.length; i < size; ++i) {
            PerPackageReadTimeouts timeouts = PerPackageReadTimeouts.parse(packages[i],
                    defaultVersionCodes, defaultTimeouts);
            if (timeouts != null) {
                result.add(timeouts);
            }
        }
        return result;
    }
}
