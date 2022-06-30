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

package android.location;

/**
 * DeviceConfig keys within the location namespace.
 *
 * @hide
 */
public final class LocationDeviceConfig {

    /**
     * Package/tag combinations that are allowlisted for ignoring location settings (may retrieve
     * location even when user location settings are off), for advanced driver-assistance systems
     * only.
     *
     * <p>Package/tag combinations are separated by commas (","), and with in each combination is a
     * package name followed by 0 or more attribution tags, separated by semicolons (";"). If a
     * package is followed by 0 attribution tags, this is interpreted the same as the wildcard
     * value. There are two special interpreted values for attribution tags, the wildcard value
     * ("*") which represents all attribution tags, and the null value ("null"), which is converted
     * to the null string (since attribution tags may be null). This format implies that attribution
     * tags which should be on this list may not contain semicolons.
     *
     * <p>Examples of valid entries:
     *
     * <ul>
     *   <li>android
     *   <li>android;*
     *   <li>android;*,com.example.app;null;my_attr
     *   <li>android;*,com.example.app;null;my_attr,com.example.otherapp;my_attr
     * </ul>
     */
    public static final String ADAS_SETTINGS_ALLOWLIST = "adas_settings_allowlist";

    /**
     * Package/tag combinations that are allowedlisted for ignoring location settings (may retrieve
     * location even when user location settings are off, and may ignore throttling, etc), for
     * emergency purposes only.
     *
     * <p>Package/tag combinations are separated by commas (","), and with in each combination is a
     * package name followed by 0 or more attribution tags, separated by semicolons (";"). If a
     * package is followed by 0 attribution tags, this is interpreted the same as the wildcard
     * value. There are two special interpreted values for attribution tags, the wildcard value
     * ("*") which represents all attribution tags, and the null value ("null"), which is converted
     * to the null string (since attribution tags may be null). This format implies that attribution
     * tags which should be on this list may not contain semicolons.
     *
     * <p>Examples of valid entries:
     *
     * <ul>
     *   <li>android
     *   <li>android;*
     *   <li>android;*,com.example.app;null;my_attr
     *   <li>android;*,com.example.app;null;my_attr,com.example.otherapp;my_attr
     * </ul>
     */
    public static final String IGNORE_SETTINGS_ALLOWLIST = "ignore_settings_allowlist";

    private LocationDeviceConfig() {}
}
