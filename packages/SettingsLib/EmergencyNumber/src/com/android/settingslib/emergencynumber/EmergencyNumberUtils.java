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

import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
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

/**
 * Util class to help manage emergency numbers
 */
public class EmergencyNumberUtils {
    private static final String TAG = "EmergencyNumberUtils";
    private static final String EMERGENCY_GESTURE_CALL_NUMBER = "emergency_gesture_call_number";
    @VisibleForTesting
    static final String FALL_BACK_NUMBER = "112";

    private final Context mContext;
    private final TelephonyManager mTelephonyManager;


    public EmergencyNumberUtils(Context context) {
        mContext = context;
        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            mTelephonyManager = context.getSystemService(TelephonyManager.class);
        } else {
            mTelephonyManager = null;
        }
    }

    /**
     * Returns the most appropriate number for police.
     */
    public String getDefaultPoliceNumber() {
        if (mTelephonyManager == null) {
            return FALL_BACK_NUMBER;
        }
        final List<EmergencyNumber> promotedPoliceNumber = getPromotedEmergencyNumbers(
                EMERGENCY_SERVICE_CATEGORY_POLICE);
        if (promotedPoliceNumber == null || promotedPoliceNumber.isEmpty()) {
            return FALL_BACK_NUMBER;
        }
        return promotedPoliceNumber.get(0).getNumber();
    }

    /**
     * Returns the number chosen by user. If user has not provided any number, use default ({@link
     * #getDefaultPoliceNumber()}).
     */
    public String getPoliceNumber() {
        final String userProvidedNumber = Settings.Secure.getString(mContext.getContentResolver(),
                EMERGENCY_GESTURE_CALL_NUMBER);
        return TextUtils.isEmpty(userProvidedNumber)
                ? getDefaultPoliceNumber() : userProvidedNumber;
    }

    private List<EmergencyNumber> getPromotedEmergencyNumbers(int categories) {
        // TODO(b/171542607): Use platform API when its bug is fixed.
        Map<Integer, List<EmergencyNumber>> allLists = filterEmergencyNumbersByCategories(
                mTelephonyManager.getEmergencyNumberList(), categories);
        if (allLists == null || allLists.isEmpty()) {
            Log.w(TAG, "Unable to retrieve emergency number lists!");
            return new ArrayList<>();
        }
        Map<Integer, List<EmergencyNumber>> promotedEmergencyNumberLists = new ArrayMap<>();
        for (Map.Entry<Integer, List<EmergencyNumber>> entry : allLists.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
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
                promotedEmergencyNumberLists.put(entry.getKey(), promotedList);
            }
        }

        if (promotedEmergencyNumberLists.isEmpty()) {
            Log.w(TAG, "No promoted emergency number found!");
        }
        return promotedEmergencyNumberLists.get(SubscriptionManager.getDefaultSubscriptionId());
    }

    /**
     * Filter emergency numbers with categories.
     */
    private Map<Integer, List<EmergencyNumber>> filterEmergencyNumbersByCategories(
            Map<Integer, List<EmergencyNumber>> emergencyNumberList, int categories) {
        Map<Integer, List<EmergencyNumber>> filteredMap = new ArrayMap<>();
        if (emergencyNumberList == null) {
            return filteredMap;
        }
        for (Integer subscriptionId : emergencyNumberList.keySet()) {
            List<EmergencyNumber> allNumbersForSub = emergencyNumberList.get(
                    subscriptionId);
            List<EmergencyNumber> numbersForCategoriesPerSub = new ArrayList<>();
            for (EmergencyNumber number : allNumbersForSub) {
                if (number.isInEmergencyServiceCategories(categories)) {
                    numbersForCategoriesPerSub.add(number);
                }
            }
            filteredMap.put(
                    subscriptionId, numbersForCategoriesPerSub);
        }
        return filteredMap;
    }
}
