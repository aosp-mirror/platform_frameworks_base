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

package com.android.settingslib.bluetooth;

import android.content.Context;
import android.content.SharedPreferences;
import android.icu.text.SimpleDateFormat;
import android.icu.util.TimeZone;
import android.util.Log;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Utils class to report hearing aid metrics to statsd */
public final class HearingAidStatsLogUtils {

    private static final String TAG = "HearingAidStatsLogUtils";
    private static final boolean DEBUG = true;
    private static final String ACCESSIBILITY_PREFERENCE = "accessibility_prefs";
    private static final String BT_HEARING_AIDS_PAIRED_HISTORY = "bt_hearing_aids_paired_history";
    private static final String BT_HEARING_AIDS_CONNECTED_HISTORY =
            "bt_hearing_aids_connected_history";
    private static final String BT_HEARING_DEVICES_PAIRED_HISTORY =
            "bt_hearing_devices_paired_history";
    private static final String BT_HEARING_DEVICES_CONNECTED_HISTORY =
            "bt_hearing_devices_connected_history";
    private static final String HISTORY_RECORD_DELIMITER = ",";

    /**
     * Type of different Bluetooth device events history related to hearing.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            HistoryType.TYPE_UNKNOWN,
            HistoryType.TYPE_HEARING_AIDS_PAIRED,
            HistoryType.TYPE_HEARING_AIDS_CONNECTED,
            HistoryType.TYPE_HEARING_DEVICES_PAIRED,
            HistoryType.TYPE_HEARING_DEVICES_CONNECTED})
    public @interface HistoryType {
        int TYPE_UNKNOWN = -1;
        int TYPE_HEARING_AIDS_PAIRED = 0;
        int TYPE_HEARING_AIDS_CONNECTED = 1;
        int TYPE_HEARING_DEVICES_PAIRED = 2;
        int TYPE_HEARING_DEVICES_CONNECTED = 3;
    }

    private static final HashMap<String, Integer> sDeviceAddressToBondEntryMap = new HashMap<>();
    private static final Set<String> sJustBondedDeviceAddressSet = new HashSet<>();

    /**
     * Sets the mapping from hearing aid device to the bond entry where this device starts it's
     * bonding(connecting) process.
     *
     * @param bondEntry The entry page id where the bonding process starts
     * @param device The bonding(connecting) hearing aid device
     */
    public static void setBondEntryForDevice(int bondEntry, CachedBluetoothDevice device) {
        sDeviceAddressToBondEntryMap.put(device.getAddress(), bondEntry);
    }

    /**
     * Logs hearing aid device information to statsd, including device mode, device side, and entry
     * page id where the binding(connecting) process starts.
     *
     * Only logs the info once after hearing aid is bonded(connected). Clears the map entry of this
     * device when logging is completed.
     *
     * @param device The bonded(connected) hearing aid device
     */
    public static void logHearingAidInfo(CachedBluetoothDevice device) {
        final String deviceAddress = device.getAddress();
        if (sDeviceAddressToBondEntryMap.containsKey(deviceAddress)) {
            final int bondEntry = sDeviceAddressToBondEntryMap.getOrDefault(deviceAddress, -1);
            final int deviceMode = device.getDeviceMode();
            final int deviceSide = device.getDeviceSide();
            FrameworkStatsLog.write(FrameworkStatsLog.HEARING_AID_INFO_REPORTED, deviceMode,
                    deviceSide, bondEntry);

            sDeviceAddressToBondEntryMap.remove(deviceAddress);
        } else {
            Log.w(TAG, "The device address was not found. Hearing aid device info is not logged.");
        }
    }

    @VisibleForTesting
    static HashMap<String, Integer> getDeviceAddressToBondEntryMap() {
        return sDeviceAddressToBondEntryMap;
    }

    /**
     * Maintains a temporarily list of just bonded device address. After the device profiles are
     * connected, {@link HearingAidStatsLogUtils#removeFromJustBonded} will be called to remove the
     * address.
     * @param address the device address
     */
    public static void addToJustBonded(String address) {
        sJustBondedDeviceAddressSet.add(address);
    }

    /**
     * Removes the device address from the just bonded list.
     * @param address the device address
     */
    public static void removeFromJustBonded(String address) {
        sJustBondedDeviceAddressSet.remove(address);
    }

    /**
     * Checks whether the device address is in the just bonded list.
     * @param address the device address
     * @return true if the device address is in the just bonded list
     */
    public static boolean isJustBonded(String address) {
        return sJustBondedDeviceAddressSet.contains(address);
    }

    /**
     * Clears all BT hearing devices related history stored in shared preference.
     * @param context the request context
     */
    public static synchronized void clearHistory(Context context) {
        SharedPreferences.Editor editor = getSharedPreferences(context).edit();
        editor.remove(BT_HEARING_AIDS_PAIRED_HISTORY)
                .remove(BT_HEARING_AIDS_CONNECTED_HISTORY)
                .remove(BT_HEARING_DEVICES_PAIRED_HISTORY)
                .remove(BT_HEARING_DEVICES_CONNECTED_HISTORY)
                .apply();
    }

    /**
     * Adds current timestamp into BT hearing devices related history.
     * @param context the request context
     * @param type the type of history to store the data. See {@link HistoryType}.
     */
    public static void addCurrentTimeToHistory(Context context, @HistoryType int type) {
        addToHistory(context, type, System.currentTimeMillis());
    }

    static synchronized void addToHistory(Context context, @HistoryType int type,
            long timestamp) {

        LinkedList<Long> history = getHistory(context, type);
        if (history == null) {
            if (DEBUG) {
                Log.w(TAG, "Couldn't find shared preference name matched type=" + type);
            }
            return;
        }
        if (history.peekLast() != null && isSameDay(history.peekLast(), timestamp)) {
            if (DEBUG) {
                Log.w(TAG, "Skip this record, it's same day record");
            }
            return;
        }
        history.add(timestamp);
        SharedPreferences.Editor editor = getSharedPreferences(context).edit();
        editor.putString(HISTORY_TYPE_TO_SP_NAME_MAPPING.get(type),
                convertToHistoryString(history)).apply();
    }

    @Nullable
    static synchronized LinkedList<Long> getHistory(Context context, @HistoryType int type) {
        String spName = HISTORY_TYPE_TO_SP_NAME_MAPPING.get(type);
        if (BT_HEARING_AIDS_PAIRED_HISTORY.equals(spName)
                || BT_HEARING_DEVICES_PAIRED_HISTORY.equals(spName)) {
            LinkedList<Long> history = convertToHistoryList(
                    getSharedPreferences(context).getString(spName, ""));
            removeRecordsBeforeDays(history, 30);
            return history;
        } else if (BT_HEARING_AIDS_CONNECTED_HISTORY.equals(spName)
                || BT_HEARING_DEVICES_CONNECTED_HISTORY.equals(spName)) {
            LinkedList<Long> history = convertToHistoryList(
                    getSharedPreferences(context).getString(spName, ""));
            removeRecordsBeforeDays(history, 7);
            return history;
        }
        return null;
    }

    private static void removeRecordsBeforeDays(LinkedList<Long> history, int days) {
        if (history == null) {
            return;
        }
        Long currentTime = System.currentTimeMillis();
        while (history.peekFirst() != null
                && currentTime - history.peekFirst() > TimeUnit.DAYS.toMillis(days)) {
            history.poll();
        }
    }

    private static String convertToHistoryString(LinkedList<Long> history) {
        return history.stream().map(Object::toString).collect(
                Collectors.joining(HISTORY_RECORD_DELIMITER));
    }
    private static LinkedList<Long> convertToHistoryList(String string) {
        if (string == null || string.isEmpty()) {
            return new LinkedList<>();
        }
        LinkedList<Long> ll = new LinkedList<>();
        String[] elements = string.split(HISTORY_RECORD_DELIMITER);
        for (String e: elements) {
            if (e.isEmpty()) continue;
            ll.offer(Long.parseLong(e));
        }
        return ll;
    }

    /**
     * Check if two timestamps are in the same date according to current timezone. This function
     * doesn't consider the original timezone when the timestamp is saved.
     *
     * @param t1 the first epoch timestamp
     * @param t2 the second epoch timestamp
     * @return {@code true} if two timestamps are on the same day
     */
    private static boolean isSameDay(long t1, long t2) {
        final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
        sdf.setTimeZone(TimeZone.getDefault());
        String dateString1 = sdf.format(t1);
        String dateString2 = sdf.format(t2);
        return dateString1.equals(dateString2);
    }

    private static SharedPreferences getSharedPreferences(Context context) {
        return context.getSharedPreferences(ACCESSIBILITY_PREFERENCE, Context.MODE_PRIVATE);
    }

    private static final HashMap<Integer, String> HISTORY_TYPE_TO_SP_NAME_MAPPING;
    static {
        HISTORY_TYPE_TO_SP_NAME_MAPPING = new HashMap<>();
        HISTORY_TYPE_TO_SP_NAME_MAPPING.put(
                HistoryType.TYPE_HEARING_AIDS_PAIRED, BT_HEARING_AIDS_PAIRED_HISTORY);
        HISTORY_TYPE_TO_SP_NAME_MAPPING.put(
                HistoryType.TYPE_HEARING_AIDS_CONNECTED, BT_HEARING_AIDS_CONNECTED_HISTORY);
        HISTORY_TYPE_TO_SP_NAME_MAPPING.put(
                HistoryType.TYPE_HEARING_DEVICES_PAIRED, BT_HEARING_DEVICES_PAIRED_HISTORY);
        HISTORY_TYPE_TO_SP_NAME_MAPPING.put(
                HistoryType.TYPE_HEARING_DEVICES_CONNECTED, BT_HEARING_DEVICES_CONNECTED_HISTORY);
    }
    private HearingAidStatsLogUtils() {}
}
