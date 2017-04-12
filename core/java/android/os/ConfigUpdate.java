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
 * limitations under the License.
 */

package android.os;

import android.annotation.SystemApi;

/**
 * Intents used to provide unbundled updates of system data.
 * All require the UPDATE_CONFIG permission.
 *
 * @see com.android.server.updates
 * @hide
 */
@SystemApi
public final class ConfigUpdate {

    /**
     * Update system wide certificate pins for TLS connections.
     * @hide
     */
    @SystemApi
    public static final String ACTION_UPDATE_PINS = "android.intent.action.UPDATE_PINS";

    /**
     * Update system wide Intent firewall.
     * @hide
     */
    @SystemApi
    public static final String ACTION_UPDATE_INTENT_FIREWALL
            = "android.intent.action.UPDATE_INTENT_FIREWALL";

    /**
     * Update list of permium SMS short codes.
     * @hide
     */
    @SystemApi
    public static final String ACTION_UPDATE_SMS_SHORT_CODES
            = "android.intent.action.UPDATE_SMS_SHORT_CODES";

    /**
     * Update list of carrier provisioning URLs.
     * @hide
     */
    @SystemApi
    public static final String ACTION_UPDATE_CARRIER_PROVISIONING_URLS
            = "android.intent.action.UPDATE_CARRIER_PROVISIONING_URLS";

    /**
     * Update set of trusted logs used for Certificate Transparency support for TLS connections.
     * @hide
     */
    @SystemApi
    public static final String ACTION_UPDATE_CT_LOGS
            = "android.intent.action.UPDATE_CT_LOGS";

    /**
     * Update system wide timezone data.
     * @hide
     */
    @SystemApi
    public static final String ACTION_UPDATE_TZDATA = "android.intent.action.UPDATE_TZDATA";

    /**
     * Update language detection model file.
     * @hide
     */
    @SystemApi
    public static final String ACTION_UPDATE_LANG_ID = "android.intent.action.UPDATE_LANG_ID";

    /**
     * Update smart selection model file.
     * @hide
     */
    @SystemApi
    public static final String ACTION_UPDATE_SMART_SELECTION
            = "android.intent.action.UPDATE_SMART_SELECTION";

    private ConfigUpdate() {
    }
}
