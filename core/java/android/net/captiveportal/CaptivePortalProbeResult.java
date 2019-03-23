/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.net.captiveportal;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;

/**
 * Result of calling isCaptivePortal().
 * @hide
 */
@SystemApi
@TestApi
public final class CaptivePortalProbeResult {
    public static final int SUCCESS_CODE = 204;
    public static final int FAILED_CODE = 599;
    public static final int PORTAL_CODE = 302;
    // Set partial connectivity http response code to -1 to prevent conflict with the other http
    // response codes. Besides the default http response code of probe result is set as 599 in
    // NetworkMonitor#sendParallelHttpProbes(), so response code will be set as -1 only when
    // NetworkMonitor detects partial connectivity.
    /**
     * @hide
     */
    public static final int PARTIAL_CODE = -1;

    @NonNull
    public static final CaptivePortalProbeResult FAILED = new CaptivePortalProbeResult(FAILED_CODE);
    @NonNull
    public static final CaptivePortalProbeResult SUCCESS =
            new CaptivePortalProbeResult(SUCCESS_CODE);
    public static final CaptivePortalProbeResult PARTIAL =
            new CaptivePortalProbeResult(PARTIAL_CODE);

    private final int mHttpResponseCode;  // HTTP response code returned from Internet probe.
    @Nullable
    public final String redirectUrl;      // Redirect destination returned from Internet probe.
    @Nullable
    public final String detectUrl;        // URL where a 204 response code indicates
                                          // captive portal has been appeased.
    @Nullable
    public final CaptivePortalProbeSpec probeSpec;

    public CaptivePortalProbeResult(int httpResponseCode) {
        this(httpResponseCode, null, null);
    }

    public CaptivePortalProbeResult(int httpResponseCode, @Nullable String redirectUrl,
            @Nullable String detectUrl) {
        this(httpResponseCode, redirectUrl, detectUrl, null);
    }

    public CaptivePortalProbeResult(int httpResponseCode, @Nullable String redirectUrl,
            @Nullable String detectUrl, @Nullable CaptivePortalProbeSpec probeSpec) {
        mHttpResponseCode = httpResponseCode;
        this.redirectUrl = redirectUrl;
        this.detectUrl = detectUrl;
        this.probeSpec = probeSpec;
    }

    public boolean isSuccessful() {
        return mHttpResponseCode == SUCCESS_CODE;
    }

    public boolean isPortal() {
        return !isSuccessful() && (mHttpResponseCode >= 200) && (mHttpResponseCode <= 399);
    }

    public boolean isFailed() {
        return !isSuccessful() && !isPortal();
    }

    public boolean isPartialConnectivity() {
        return mHttpResponseCode == PARTIAL_CODE;
    }
}
