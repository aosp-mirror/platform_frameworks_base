/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.settingslib.emergencynumber;

import static android.telephony.emergency.EmergencyNumber.EMERGENCY_NUMBER_SOURCE_DATABASE;
import static android.telephony.emergency.EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_POLICE;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.emergency.EmergencyNumber;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Util class to help manage emergency numbers
 */
public class EmergencyNumberUtils {
    private static final String TAG = "EmergencyNumberUtils";

    public static final Uri EMERGENCY_NUMBER_OVERRIDE_AUTHORITY = new Uri.Builder().scheme(
            ContentResolver.SCHEME_CONTENT)
            .authority("com.android.emergency.gesture")
            .build();
    public static final String METHOD_NAME_GET_EMERGENCY_NUMBER_OVERRIDE =
            "GET_EMERGENCY_NUMBER_OVERRIDE";
    public static final String METHOD_NAME_SET_EMERGENCY_NUMBER_OVERRIDE =
            "SET_EMERGENCY_NUMBER_OVERRIDE";
    public static final String METHOD_NAME_SET_EMERGENCY_GESTURE = "SET_EMERGENCY_GESTURE";
    public static final String METHOD_NAME_SET_EMERGENCY_SOUND = "SET_EMERGENCY_SOUND";
    public static final String METHOD_NAME_GET_EMERGENCY_GESTURE_ENABLED = "GET_EMERGENCY_GESTURE";
    public static final String METHOD_NAME_GET_EMERGENCY_GESTURE_SOUND_ENABLED =
            "GET_EMERGENCY_SOUND";
    public static final String EMERGENCY_GESTURE_CALL_NUMBER = "emergency_gesture_call_number";
    public static final String EMERGENCY_SETTING_VALUE = "emergency_setting_value";
    public static final int EMERGENCY_SETTING_ON = 1;
    public static final int EMERGENCY_SETTING_OFF = 0;

    @VisibleForTesting
    static final String FALL_BACK_NUMBER = "112";

    private final Context mContext;
    private final TelephonyManager mTelephonyManager;
    private final CarrierConfigManager mCarrierConfigManager;

    public EmergencyNumberUtils(Context context) {
        mContext = context;
        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            mTelephonyManager = context.getSystemService(TelephonyManager.class);
            mCarrierConfigManager = context.getSystemService(CarrierConfigManager.class);
        } else {
            mTelephonyManager = null;
            mCarrierConfigManager = null;
        }
    }

    /**
     * Returns the most appropriate number for police.
     */
    public String getDefaultPoliceNumber() {
        if (mTelephonyManager == null) {
            return FALL_BACK_NUMBER;
        }
        final List<String> promotedPoliceNumber = getPromotedEmergencyNumbers(
                EMERGENCY_SERVICE_CATEGORY_POLICE);
        if (promotedPoliceNumber == null || promotedPoliceNumber.isEmpty()) {
            return FALL_BACK_NUMBER;
        }
        return promotedPoliceNumber.get(0);
    }

    /**
     * Returns the number chosen by user. If user has not provided any number, use default ({@link
     * #getDefaultPoliceNumber()}).
     */
    public String getPoliceNumber() {
        final String userProvidedNumber = getEmergencyNumberOverride();
        return TextUtils.isEmpty(userProvidedNumber)
                ? getDefaultPoliceNumber() : userProvidedNumber;
    }

    /**
     * Sets device-local emergency number override
     */
    public void setEmergencyNumberOverride(String number) {
        final Bundle bundle = new Bundle();
        bundle.putString(EMERGENCY_GESTURE_CALL_NUMBER, number);
        mContext.getContentResolver().call(EMERGENCY_NUMBER_OVERRIDE_AUTHORITY,
                METHOD_NAME_SET_EMERGENCY_NUMBER_OVERRIDE, null /* args */, bundle);
    }

    /**
     * Enable/disable the emergency gesture setting
     */
    public void setEmergencyGestureEnabled(boolean enabled) {
        final Bundle bundle = new Bundle();
        bundle.putInt(EMERGENCY_SETTING_VALUE,
                enabled ? EMERGENCY_SETTING_ON : EMERGENCY_SETTING_OFF);
        mContext.getContentResolver().call(EMERGENCY_NUMBER_OVERRIDE_AUTHORITY,
                METHOD_NAME_SET_EMERGENCY_GESTURE, null /* args */, bundle);
    }

    /**
     * Enable/disable the emergency gesture sound setting
     */
    public void setEmergencySoundEnabled(boolean enabled) {
        final Bundle bundle = new Bundle();
        bundle.putInt(EMERGENCY_SETTING_VALUE,
                enabled ? EMERGENCY_SETTING_ON : EMERGENCY_SETTING_OFF);
        mContext.getContentResolver().call(EMERGENCY_NUMBER_OVERRIDE_AUTHORITY,
                METHOD_NAME_SET_EMERGENCY_SOUND, null /* args */, bundle);
    }

    /**
     * Whether or not emergency gesture is enabled.
     */
    public boolean getEmergencyGestureEnabled() {
        final Bundle bundle = mContext.getContentResolver().call(
                EMERGENCY_NUMBER_OVERRIDE_AUTHORITY,
                METHOD_NAME_GET_EMERGENCY_GESTURE_ENABLED, null /* args */, null /* bundle */);
        return bundle == null ? true : bundle.getInt(EMERGENCY_SETTING_VALUE, EMERGENCY_SETTING_ON)
                == EMERGENCY_SETTING_ON;
    }

    /**
     * Whether or not emergency gesture sound is enabled.
     */
    public boolean getEmergencyGestureSoundEnabled() {
        final Bundle bundle = mContext.getContentResolver().call(
                EMERGENCY_NUMBER_OVERRIDE_AUTHORITY,
                METHOD_NAME_GET_EMERGENCY_GESTURE_SOUND_ENABLED, null /* args */,
                null /* bundle */);
        return bundle == null ? true : bundle.getInt(EMERGENCY_SETTING_VALUE, EMERGENCY_SETTING_OFF)
                == EMERGENCY_SETTING_ON;
    }

    private String getEmergencyNumberOverride() {
        final Bundle bundle = mContext.getContentResolver().call(
                EMERGENCY_NUMBER_OVERRIDE_AUTHORITY,
                METHOD_NAME_GET_EMERGENCY_NUMBER_OVERRIDE, null /* args */, null /* bundle */);
        return bundle == null ? null : bundle.getString(EMERGENCY_GESTURE_CALL_NUMBER);
    }

    private List<String> getPromotedEmergencyNumbers(int categories) {
        Map<Integer, List<EmergencyNumber>> allLists = mTelephonyManager.getEmergencyNumberList(
                categories);
        if (allLists == null || allLists.isEmpty()) {
            Log.w(TAG, "Unable to retrieve emergency number lists!");
            return new ArrayList<>();
        }
        Map<Integer, List<String>> promotedEmergencyNumberLists = new ArrayMap<>();
        for (Map.Entry<Integer, List<EmergencyNumber>> entry : allLists.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            int subId = entry.getKey();
            List<EmergencyNumber> emergencyNumberList = entry.getValue();
            Log.d(TAG, "Emergency numbers for subscription id " + entry.getKey());

            // The list of promoted emergency numbers which will be visible on shortcut view.
            List<EmergencyNumber> promotedList = new ArrayList<>();
            // A temporary list for non-prioritized emergency numbers.
            List<EmergencyNumber> tempList = new ArrayList<>();

            for (EmergencyNumber emergencyNumber : emergencyNumberList) {
                // Emergency numbers in DATABASE are prioritized since they were well-categorized.
                boolean isFromPrioritizedSource =
                        emergencyNumber.getEmergencyNumberSources().contains(
                                EMERGENCY_NUMBER_SOURCE_DATABASE);

                Log.d(TAG, String.format("Number %s, isFromPrioritizedSource %b",
                        emergencyNumber, isFromPrioritizedSource));
                if (isFromPrioritizedSource) {
                    promotedList.add(emergencyNumber);
                } else {
                    tempList.add(emergencyNumber);
                }
            }
            // Puts numbers in temp list after prioritized numbers.
            promotedList.addAll(tempList);

            if (!promotedList.isEmpty()) {
                List<String> sanitizedNumbers = sanitizeEmergencyNumbers(promotedList, subId);
                promotedEmergencyNumberLists.put(subId, sanitizedNumbers);
            }
        }

        if (promotedEmergencyNumberLists.isEmpty()) {
            Log.w(TAG, "No promoted emergency number found!");
        }
        return promotedEmergencyNumberLists.get(SubscriptionManager.getDefaultSubscriptionId());
    }

    private List<String> sanitizeEmergencyNumbers(
            List<EmergencyNumber> input, int subscriptionId) {
        // Make a copy of data so we can mutate.
        List<EmergencyNumber> data = new ArrayList<>(input);
        String[] carrierPrefixes =
                getCarrierEmergencyNumberPrefixes(mCarrierConfigManager, subscriptionId);
        return data.stream()
                .map(d -> removePrefix(d, carrierPrefixes))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private String removePrefix(EmergencyNumber emergencyNumber, String[] prefixes) {
        String number = emergencyNumber.getNumber();
        if (prefixes == null || prefixes.length == 0) {
            return number;
        }
        for (String prefix : prefixes) {
            int prefixStartIndex = number.indexOf(prefix);
            if (prefixStartIndex != 0) {
                continue;
            }
            Log.d(TAG, "Removing prefix " + prefix + " from " + number);
            return number.substring(prefix.length());
        }
        return number;
    }

    private static String[] getCarrierEmergencyNumberPrefixes(
            CarrierConfigManager carrierConfigManager, int subId) {
        PersistableBundle b = carrierConfigManager.getConfigForSubId(subId);
        return b == null
                ? null
                : b.getStringArray(CarrierConfigManager.KEY_EMERGENCY_NUMBER_PREFIX_STRING_ARRAY);
    }
}
