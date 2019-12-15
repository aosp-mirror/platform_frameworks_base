/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net.wifi;

import android.annotation.IntDef;
import android.annotation.StringDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Wifi annotations meant to be statically linked into client modules, since they cannot be
 * exposed as @SystemApi.
 *
 * e.g. {@link IntDef}, {@link StringDef}
 *
 * @hide
 */
public final class WifiAnnotations {
    private WifiAnnotations() {}

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"SCAN_TYPE_"}, value = {
            WifiScanner.SCAN_TYPE_LOW_LATENCY,
            WifiScanner.SCAN_TYPE_LOW_POWER,
            WifiScanner.SCAN_TYPE_HIGH_ACCURACY})
    public @interface ScanType {}

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"WIFI_BAND_"}, value = {
            WifiScanner.WIFI_BAND_UNSPECIFIED,
            WifiScanner.WIFI_BAND_24_GHZ,
            WifiScanner.WIFI_BAND_5_GHZ,
            WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY,
            WifiScanner.WIFI_BAND_6_GHZ})
    public @interface WifiBandBasic {}
}
