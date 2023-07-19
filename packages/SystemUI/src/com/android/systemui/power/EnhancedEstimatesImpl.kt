/*
 * Copyright (C) 2021 Benzo Rom
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/
package com.android.systemui.power

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.Settings
import android.util.KeyValueListParser
import android.util.Log
import com.android.settingslib.fuelgauge.Estimate
import com.android.settingslib.utils.PowerUtil
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.power.EnhancedEstimates
import java.time.Duration
import javax.inject.Inject

@SysUISingleton
class EnhancedEstimatesImpl @Inject constructor(private val mContext: Context) :
    EnhancedEstimates {
    private val mParser: KeyValueListParser = KeyValueListParser(',')

    override fun isHybridNotificationEnabled(): Boolean {
        val isHybridEnabled: Boolean
        isHybridEnabled = try {
            if (!mContext.packageManager.getPackageInfo(
                    TURBO_PACKAGE_NAME,
                    PackageManager.MATCH_DISABLED_COMPONENTS
                ).applicationInfo.enabled
            ) {
                false
            } else {
                updateFlags()
                mParser.getBoolean("hybrid_enabled", true)
            }
        } catch (ex: PackageManager.NameNotFoundException) {
            false
        }
        return isHybridEnabled
    }

    override fun getEstimate(): Estimate {
        var query: Cursor? = null
        try {
            query = mContext.contentResolver.query(
                Uri.Builder()
                    .scheme("content")
                    .authority("com.google.android.apps.turbo.estimated_time_remaining")
                    .appendPath("time_remaining")
                    .build(), null, null, null, null
            )
        } catch (ex: Exception) {
            Log.d(
                TAG,
                "Something went wrong when getting an estimate from Turbo",
                ex
            )
        }
        if (query == null || !query.moveToFirst()) {
            query?.close()
            return Estimate(-1, false, -1)
        }
        var isBasedOnUsage = true
        if (query.getColumnIndex("is_based_on_usage") != -1 && query.getInt(
                query.getColumnIndex(
                    "is_based_on_usage"
                )
            ) == 0
        ) isBasedOnUsage = false
        var averageDischargeTime: Long = -1
        val columnIndex = query.getColumnIndex("average_battery_life")
        if (columnIndex != -1) {
            val averageBatteryLife = query.getLong(columnIndex)
            if (averageBatteryLife != -1L) {
                var threshold = Duration.ofMinutes(15).toMillis()
                if (Duration.ofMillis(averageBatteryLife)
                        .compareTo(Duration.ofDays(1)) >= 0
                ) threshold = Duration.ofHours(1).toMillis()
                averageDischargeTime =
                    PowerUtil.roundTimeToNearestThreshold(averageBatteryLife, threshold)
            }
        }
        val estimate = Estimate(
            query.getLong(
                query.getColumnIndex(
                    "battery_estimate"
                )
            ), isBasedOnUsage, averageDischargeTime
        )
        query.close()
        return estimate
    }

    override fun getLowWarningThreshold(): Long {
        updateFlags()
        return mParser.getLong("low_threshold", Duration.ofHours(3).toMillis())
    }

    override fun getSevereWarningThreshold(): Long {
        updateFlags()
        return mParser.getLong("severe_threshold", Duration.ofHours(1).toMillis())
    }

    override fun getLowWarningEnabled(): Boolean {
        updateFlags()
        return mParser.getBoolean("low_warning_enabled", false)
    }

    protected fun updateFlags() {
        try {
            mParser.setString(
                Settings.Global.getString(
                    mContext.contentResolver,
                    "hybrid_sysui_battery_warning_flags"
                )
            )
        } catch (ex: IllegalArgumentException) {
            Log.e(TAG, "Bad hybrid sysui warning flags")
        }
    }

    companion object {
        const val TAG = "EnhancedEstimates"
        private const val TURBO_PACKAGE_NAME = "com.google.android.apps.turbo"
    }
}
