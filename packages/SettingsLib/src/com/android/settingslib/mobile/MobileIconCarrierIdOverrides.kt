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

package com.android.settingslib.mobile

import android.annotation.DrawableRes
import android.content.res.Resources
import android.content.res.TypedArray
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.settingslib.R
import com.android.settingslib.SignalIcon.MobileIconGroup

/**
 * This class defines a network type (3G, 4G, etc.) override mechanism on a per-carrierId basis.
 *
 * Traditionally, carrier-customized network type iconography was achieved using the `MCC/MNC`
 * resource qualifiers, and swapping out the drawable resource by name. It would look like this:
 *
 *     res/
 *       drawable/
 *         3g_mobiledata_icon.xml
 *       drawable-MCC-MNC/
 *         3g_mobiledata_icon.xml
 *
 * This would mean that, provided a context created with this MCC/MNC configuration set, loading
 * the network type icon through [MobileIconGroup] would provide a carrier-defined network type
 * icon rather than the AOSP-defined default.
 *
 * The MCC/MNC mechanism no longer can fully define carrier-specific network type icons, because
 * there is no longer a 1:1 mapping between MCC/MNC and carrier. With the advent of MVNOs, multiple
 * carriers can have the same MCC/MNC value, but wish to differentiate based on their carrier ID.
 * CarrierId is a newer concept than MCC/MNC, and provides more granularity when it comes to
 * determining the carrier (e.g. MVNOs can share MCC/MNC values with the network owner), therefore
 * it can fit all of the same use cases currently handled by `MCC/MNC`, without the need to apply a
 * configuration context in order to get the proper UI for a given SIM icon.
 *
 * NOTE: CarrierId icon overrides will always take precedence over those defined using `MCC/MNC`
 * resource qualifiers.
 *
 * [MAPPING] encodes the relationship between CarrierId and the corresponding override array
 * that exists in the config.xml. An alternative approach could be to generate the resource name
 * by string concatenation at run-time:
 *
 *    val resName = "carrierId_$carrierId_iconOverrides"
 *    val override = resources.getResourceIdentifier(resName)
 *
 * However, that's going to be far less efficient until MAPPING grows to a sufficient size. For now,
 * given a relatively small number of entries, we should just maintain the mapping here.
 */
interface MobileIconCarrierIdOverrides {
    @DrawableRes
    fun getOverrideFor(carrierId: Int, networkType: String, resources: Resources): Int
    fun carrierIdEntryExists(carrierId: Int): Boolean
}

class MobileIconCarrierIdOverridesImpl : MobileIconCarrierIdOverrides {
    @DrawableRes
    override fun getOverrideFor(carrierId: Int, networkType: String, resources: Resources): Int {
        val resId = MAPPING[carrierId] ?: return 0
        val ta = resources.obtainTypedArray(resId)
        val map = parseNetworkIconOverrideTypedArray(ta)
        ta.recycle()
        return map[networkType] ?: 0
    }

    override fun carrierIdEntryExists(carrierId: Int) =
        overrideExists(carrierId, MAPPING)

    companion object {
        private const val TAG = "MobileIconOverrides"
        /**
         * This map maintains the lookup from the canonical carrier ID (see below link) to the
         * corresponding overlay resource. New overrides should add an entry below in order to
         * change the network type icon resources based on carrier ID
         *
         * Refer to the link below for the canonical mapping maintained in AOSP:
         * https://android.googlesource.com/platform/packages/providers/TelephonyProvider/+/master/assets/latest_carrier_id/carrier_list.textpb
         */
        private val MAPPING = mapOf(
            // 2032 == Xfinity Mobile
            2032 to R.array.carrierId_2032_iconOverrides,
        )

        /**
         * Parse `carrierId_XXXX_iconOverrides` for a particular network type. The resource file
         * "carrierid_icon_overrides.xml" defines a TypedArray format for overriding specific
         * network type icons (a.k.a. RAT icons) for a particular carrier ID. The format is defined
         * as an array of (network type name, drawable) pairs:
         *    <array name="carrierId_XXXX_iconOverrides>
         *        <item>NET_TYPE_1</item>
         *        <item>@drawable/net_type_1_override</item>
         *        <item>NET_TYPE_2</item>
         *        <item>@drawable/net_type_2_override</item>
         *    </array>
         *
         * @param ta the [TypedArray] defined in carrierid_icon_overrides.xml
         * @return the overridden drawable resource ID if it exists, or 0 if it does not
         */
        @VisibleForTesting
        @JvmStatic
        fun parseNetworkIconOverrideTypedArray(ta: TypedArray): Map<String, Int> {
            if (ta.length() % 2 != 0) {
                Log.w(TAG,
                    "override must contain an even number of (key, value) entries. skipping")

                return mapOf()
            }

            val result = mutableMapOf<String, Int>()
            // The array is defined as Pair(String, resourceId), so walk by 2
            for (i in 0 until ta.length() step 2) {
                val key = ta.getString(i)
                val override = ta.getResourceId(i + 1, 0)
                if (key == null || override == 0) {
                    Log.w(TAG, "Invalid override found. Skipping")
                    continue
                }
                result[key] = override
            }

            return result
        }

        @JvmStatic
        private fun overrideExists(carrierId: Int, mapping: Map<Int, Int>): Boolean =
            mapping.containsKey(carrierId)
    }
}
