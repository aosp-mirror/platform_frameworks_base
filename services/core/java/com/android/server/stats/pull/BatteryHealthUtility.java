/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.stats.pull;

import android.util.StatsEvent;

import com.android.internal.util.FrameworkStatsLog;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Utility class to redact Battery Health data from HealthServiceWrapper
 *
 * @hide
 */
public abstract class BatteryHealthUtility {
    /**
     * Create a StatsEvent corresponding to the Battery Health data, the fields
     * of which are redacted to preserve users' privacy.
     * The redaction consists in truncating the timestamps to the Monday of the
     * corresponding week, and reducing the battery serial into the last byte
     * of its MD5.
     */
    public static StatsEvent buildStatsEvent(int atomTag,
            android.hardware.health.BatteryHealthData data, int chargeStatus, int chargePolicy)
            throws NoSuchAlgorithmException {
        int manufacturingDate = secondsToWeekYYYYMMDD(data.batteryManufacturingDateSeconds);
        int firstUsageDate = secondsToWeekYYYYMMDD(data.batteryFirstUsageSeconds);
        long stateOfHealth = data.batteryStateOfHealth;
        int partStatus = data.batteryPartStatus;
        int serialHashTruncated = stringToIntHash(data.batterySerialNumber) & 0xFF; // Last byte

        return FrameworkStatsLog.buildStatsEvent(atomTag, manufacturingDate, firstUsageDate,
                (int) stateOfHealth, serialHashTruncated, partStatus, chargeStatus, chargePolicy);
    }

    private static int secondsToWeekYYYYMMDD(long seconds) {
        Calendar calendar = Calendar.getInstance();
        long millis = seconds * 1000L;

        calendar.setTimeInMillis(millis);

        // Truncate all date information, up to week, which is rounded to
        // MONDAY
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", Locale.US);

        String formattedDate = sdf.format(calendar.getTime());

        return Integer.parseInt(formattedDate);
    }

    private static int stringToIntHash(String data) throws NoSuchAlgorithmException {
        if (data == null || data.isEmpty()) {
            return 0;
        }

        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] hashBytes = digest.digest(data.getBytes());

        // Convert to integer (simplest way, but potential for loss of information)
        BigInteger bigInt = new BigInteger(1, hashBytes);
        return bigInt.intValue();
    }
}
