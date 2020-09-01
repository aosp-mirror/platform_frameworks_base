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

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
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

    /**
     * Update conversation actions model file.
     * @hide
     */
    @SystemApi
    public static final String ACTION_UPDATE_CONVERSATION_ACTIONS
            = "android.intent.action.UPDATE_CONVERSATION_ACTIONS";

    /**
     * Update network watchlist config file.
     * @hide
     */
    @SystemApi
    public static final String ACTION_UPDATE_NETWORK_WATCHLIST
            = "android.intent.action.UPDATE_NETWORK_WATCHLIST";

    /**
     * Broadcast intent action indicating that the updated carrier id config is available.
     * <p>Extra: "VERSION" the numeric version of the new data. Devices should only install if the
     * update version is newer than the current one.
     * <p>Extra: "REQUIRED_HASH" the hash of the current update data.
     * <p>Input: {@link android.content.Intent#getData} is URI of downloaded carrier id file.
     * Devices should pick up the downloaded file and persist to the database
     * {@link com.android.providers.telephony.CarrierIdProvider}.
     *
     * @hide
     */
    @SystemApi
    public static final String ACTION_UPDATE_CARRIER_ID_DB
            = "android.os.action.UPDATE_CARRIER_ID_DB";

    /**
    * Update the emergency number database into the devices.
    * <p>Extra: {@link #EXTRA_VERSION} the numeric version of the database.
    * <p>Extra: {@link #EXTRA_REQUIRED_HASH} hash of the database, which is encoded by base-16
     * SHA512.
    * <p>Input: {@link android.content.Intent#getData} the URI to download emergency number
    * database.
    *
    * @hide
    */
    @SystemApi
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_UPDATE_EMERGENCY_NUMBER_DB =
            "android.os.action.UPDATE_EMERGENCY_NUMBER_DB";

    /**
     * An integer to indicate the numeric version of the new data. Devices should only install
     * if the update version is newer than the current one.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_VERSION = "android.os.extra.VERSION";

    /**
     * Hash of the database, which is encoded by base-16 SHA512.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_REQUIRED_HASH = "android.os.extra.REQUIRED_HASH";

    private ConfigUpdate() {
    }
}
