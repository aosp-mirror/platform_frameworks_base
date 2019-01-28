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

    public static final CaptivePortalProbeResult FAILED = new CaptivePortalProbeResult(FAILED_CODE);
    public static final CaptivePortalProbeResult SUCCESS =
            new CaptivePortalProbeResult(SUCCESS_CODE);

    private final int mHttpResponseCode;  // HTTP response code returned from Internet probe.
    public final String redirectUrl;      // Redirect destination returned from Internet probe.
    public final String detectUrl;        // URL where a 204 response code indicates
                                          // captive portal has been appeased.
    @Nullable
    public final CaptivePortalProbeSpec probeSpec;

    public CaptivePortalProbeResult(int httpResponseCode) {
        this(httpResponseCode, null, null);
    }

    public CaptivePortalProbeResult(int httpResponseCode, String redirectUrl, String detectUrl) {
        this(httpResponseCode, redirectUrl, detectUrl, null);
    }

    public CaptivePortalProbeResult(int httpResponseCode, String redirectUrl, String detectUrl,
            CaptivePortalProbeSpec probeSpec) {
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
}
