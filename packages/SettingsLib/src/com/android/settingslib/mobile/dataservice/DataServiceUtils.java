/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.mobile.dataservice;

import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.UiccPortInfo;
import android.telephony.UiccSlotMapping;

public class DataServiceUtils {

    /**
     * Represents columns of the MobileNetworkInfoData table, define these columns from
     * {@see MobileNetworkUtils} or relevant common APIs.
     */
    public static final class MobileNetworkInfoData {

        /** The name of the MobileNetworkInfoData table. */
        public static final String TABLE_NAME = "MobileNetworkInfo";

        /**
         * The name of the ID column, set the {@link SubscriptionInfo#getSubscriptionId()}
         * as the primary key.
         */
        public static final String COLUMN_ID = "subId";

        /**
         * The name of the mobile network data state column,
         * {@see MobileNetworkUtils#isMobileDataEnabled(Context)}.
         */
        public static final String COLUMN_IS_MOBILE_DATA_ENABLED = "isMobileDataEnabled";

        /**
         * The name of the show toggle for physicalSim state column,
         * {@see SubscriptionUtil#showToggleForPhysicalSim(SubscriptionManager)}.
         */
        public static final String COLUMN_SHOW_TOGGLE_FOR_PHYSICAL_SIM = "showToggleForPhysicalSim";
    }

    /**
     * Represents columns of the UiccInfoData table, define these columns from
     * {@link android.telephony.UiccSlotInfo}, {@link android.telephony.UiccCardInfo},
     * {@link UiccSlotMapping} and {@link android.telephony.UiccPortInfo}.If columns of these 4
     * classes are changed, we should also update the table except PII data.
     */
    public static final class UiccInfoData {

        /** The name of the UiccInfoData table. */
        public static final String TABLE_NAME = "uiccInfo";

        /**
         * The name of the ID column, set the {@link SubscriptionInfo#getSubscriptionId()}
         * as the primary key.
         */
        public static final String COLUMN_ID = "sudId";

        /**
         * The name of the active state column, see {@link UiccPortInfo#isActive()}.
         */
        public static final String COLUMN_IS_ACTIVE = "isActive";
    }

    /**
     * Represents columns of the SubscriptionInfoData table, define these columns from
     * {@link SubscriptionInfo}, {@see SubscriptionUtil} and
     * {@link SubscriptionManager} or relevant common APIs. If columns of the
     * {@link SubscriptionInfo} are changed, we should also update the table except PII data.
     */
    public static final class SubscriptionInfoData {

        /** The name of the SubscriptionInfoData table. */
        public static final String TABLE_NAME = "subscriptionInfo";

        /**
         * The name of the ID column, set the {@link SubscriptionInfo#getSubscriptionId()}
         * as the primary key.
         */
        public static final String COLUMN_ID = "sudId";

        /**
         * The name of the sim slot index column, see
         * {@link SubscriptionInfo#getSimSlotIndex()}.
         */
        public static final String COLUMN_SIM_SLOT_INDEX = "simSlotIndex";

        /**
         * The name of the embedded state column, see {@link SubscriptionInfo#isEmbedded()}.
         */
        public static final String COLUMN_IS_EMBEDDED = "isEmbedded";

        /**
         * The name of the opportunistic state column, see
         * {@link SubscriptionInfo#isOpportunistic()}.
         */
        public static final String COLUMN_IS_OPPORTUNISTIC = "isOpportunistic";

        /**
         * The name of the uniqueName column,
         * {@see SubscriptionUtil#getUniqueSubscriptionDisplayName(SubscriptionInfo, Context)}.
         */
        public static final String COLUMN_UNIQUE_NAME = "uniqueName";

        /**
         * The name of the subscription visible state column,
         * {@see SubscriptionUtil#isSubscriptionVisible(SubscriptionManager, Context,
         * SubscriptionInfo)}.
         */
        public static final String COLUMN_IS_SUBSCRIPTION_VISIBLE = "isSubscriptionVisible";

        /**
         * The name of the default subscription selection column,
         * {@see SubscriptionUtil#getSubscriptionOrDefault(Context, int)}.
         */
        public static final String COLUMN_IS_DEFAULT_SUBSCRIPTION_SELECTION =
                "isDefaultSubscriptionSelection";

        /**
         * The name of the valid subscription column,
         * {@link SubscriptionManager#isValidSubscriptionId(int)}.
         */
        public static final String COLUMN_IS_VALID_SUBSCRIPTION = "isValidSubscription";

        /**
         * The name of the active subscription column,
         * {@link SubscriptionManager#isActiveSubscriptionId(int)}.
         */
        public static final String COLUMN_IS_ACTIVE_SUBSCRIPTION_ID = "isActiveSubscription";

        /**
         * The name of the active data subscription state column, see
         * {@link SubscriptionManager#getActiveDataSubscriptionId()}.
         */
        public static final String COLUMN_IS_ACTIVE_DATA_SUBSCRIPTION =
                "isActiveDataSubscriptionId";
    }
}
