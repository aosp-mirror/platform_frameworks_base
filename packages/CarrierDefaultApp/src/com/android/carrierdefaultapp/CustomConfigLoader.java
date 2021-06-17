/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.carrierdefaultapp;

import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Default carrier app allows carrier customization. OEMs could configure a list
 * of carrier actions defined in {@link com.android.carrierdefaultapp.CarrierActionUtils
 * CarrierActionUtils} to act upon certain signal or even different args of the same signal.
 * This allows different interpretations of the signal between carriers and could easily alter the
 * app's behavior in a configurable way. This helper class loads and parses the carrier configs
 * and return a list of predefined carrier actions for the given input signal.
 */
public class CustomConfigLoader {
    // delimiters for parsing carrier configs of the form "arg1, arg2 : action1, action2"
    private static final String INTRA_GROUP_DELIMITER = "\\s*,\\s*";
    private static final String INTER_GROUP_DELIMITER = "\\s*:\\s*";

    private static final String TAG = CustomConfigLoader.class.getSimpleName();
    private static final boolean VDBG = Log.isLoggable(TAG, Log.VERBOSE);

    /**
     * loads and parses the carrier config, return a list of carrier action for the given signal
     * @param context
     * @param intent passing signal for config match
     * @return a list of carrier action for the given signal based on the carrier config.
     *
     *  Example: input intent TelephonyManager.ACTION_CARRIER_SIGNAL_REQUEST_NETWORK_FAILED
     *  This intent allows fined-grained matching based on both intent type & extra values:
     *  apnType and errorCode.
     *  apnType read from passing intent is "default" and errorCode is 0x26 for example and
     *  returned carrier config from carrier_default_actions_on_redirection_string_array is
     *  {
     *      "default, 0x26:1,4", // 0x26(NETWORK_FAILURE)
     *      "default, 0x70:2,3" // 0x70(APN_TYPE_CONFLICT)
     *  }
     *  [1, 4] // 1(CARRIER_ACTION_DISABLE_METERED_APNS), 4(CARRIER_ACTION_SHOW_PORTAL_NOTIFICATION)
     *  returns as the action index list based on the matching rule.
     */
    public static List<Integer> loadCarrierActionList(Context context, Intent intent) {
        CarrierConfigManager carrierConfigManager = (CarrierConfigManager) context.getSystemService(
                Context.CARRIER_CONFIG_SERVICE);
        // return an empty list if no match found
        List<Integer> actionList = new ArrayList<>();
        if (carrierConfigManager == null) {
            Log.e(TAG, "load carrier config failure with carrier config manager uninitialized");
            return actionList;
        }
        PersistableBundle b = carrierConfigManager.getConfig();
        if (b != null) {
            String[] configs = null;
            // used for intents which allow fine-grained interpretation based on intent extras
            String arg1 = null;
            String arg2 = null;
            switch (intent.getAction()) {
                case TelephonyManager.ACTION_CARRIER_SIGNAL_REDIRECTED:
                    configs = b.getStringArray(CarrierConfigManager
                            .KEY_CARRIER_DEFAULT_ACTIONS_ON_REDIRECTION_STRING_ARRAY);
                    break;
                case TelephonyManager.ACTION_CARRIER_SIGNAL_REQUEST_NETWORK_FAILED:
                    configs = b.getStringArray(CarrierConfigManager
                            .KEY_CARRIER_DEFAULT_ACTIONS_ON_DCFAILURE_STRING_ARRAY);
                    arg1 = String.valueOf(intent.getIntExtra(TelephonyManager.EXTRA_APN_TYPE, -1));
                    arg2 = intent.getStringExtra(TelephonyManager.EXTRA_DATA_FAIL_CAUSE);
                    break;
                case TelephonyManager.ACTION_CARRIER_SIGNAL_RESET:
                    configs = b.getStringArray(CarrierConfigManager
                            .KEY_CARRIER_DEFAULT_ACTIONS_ON_RESET);
                    break;
                case TelephonyManager.ACTION_CARRIER_SIGNAL_DEFAULT_NETWORK_AVAILABLE:
                    configs = b.getStringArray(CarrierConfigManager
                            .KEY_CARRIER_DEFAULT_ACTIONS_ON_DEFAULT_NETWORK_AVAILABLE);
                    arg1 = String.valueOf(intent.getBooleanExtra(TelephonyManager
                            .EXTRA_DEFAULT_NETWORK_AVAILABLE, false));
                    break;
                default:
                    Log.e(TAG, "load carrier config failure with un-configured key: "
                            + intent.getAction());
                    break;
            }
            if (!ArrayUtils.isEmpty(configs)) {
                for (String config : configs) {
                    // parse each config until find the matching one
                    matchConfig(config, arg1, arg2, actionList);
                    if (!actionList.isEmpty()) {
                        // return the first match
                        if (VDBG) Log.d(TAG, "found match action list: " + actionList.toString());
                        return actionList;
                    }
                }
            }
            Log.d(TAG, "no matching entry for signal: " + intent.getAction() + "arg1: " + arg1
                    + "arg2: " + arg2);
        }
        return actionList;
    }

    /**
     * Match based on the config's format and input args
     * passing arg1, arg2 should match the format of the config
     * case 1: config {actionIdx1, actionIdx2...} arg1 and arg2 must be null
     * case 2: config {arg1, arg2 : actionIdx1, actionIdx2...} requires full match of non-null args
     * case 3: config {arg1 : actionIdx1, actionIdx2...} only need to match arg1
     *
     * @param config action list config obtained from CarrierConfigManager
     * @param arg1 first intent argument, set if required for config match
     * @param arg2 second intent argument, set if required for config match
     * @param actionList append each parsed action to the passing list
     */
    private static void matchConfig(String config, String arg1, String arg2,
                                    List<Integer> actionList) {
        String[] splitStr = config.trim().split(INTER_GROUP_DELIMITER, 2);
        String actionStr = null;

        if (splitStr.length == 1 && arg1 == null && arg2 == null) {
            // case 1
            actionStr = splitStr[0];
        } else if (splitStr.length == 2 && arg1 != null && arg2 != null) {
            // case 2. The only thing that uses this is CARRIER_SIGNAL_REQUEST_NETWORK_FAILED,
            // and the carrier config for that can provide either an int or string for the apn type,
            // depending on when it was introduced. Therefore, return a positive match if either
            // the int version or the string version of the apn type in the broadcast matches.
            String apnInIntFormat = arg1;
            String apnInStringFormat = null;
            try {
                int apnInt = Integer.parseInt(apnInIntFormat);
                apnInStringFormat = ApnSetting.getApnTypeString(apnInt);
            } catch (NumberFormatException e) {
                Log.e(TAG, "Got invalid apn type from broadcast: " + apnInIntFormat);
            }

            String[] args = splitStr[0].split(INTRA_GROUP_DELIMITER);
            boolean doesArg1Match = TextUtils.equals(apnInIntFormat, args[0])
                    || (apnInStringFormat != null && TextUtils.equals(apnInStringFormat, args[0]));
            if (args.length == 2 && doesArg1Match
                    && TextUtils.equals(arg2, args[1])) {
                actionStr = splitStr[1];
            }
        } else if ((splitStr.length == 2) && (arg1 != null) && (arg2 == null)) {
            // case 3
            String[] args = splitStr[0].split(INTRA_GROUP_DELIMITER);
            if (args.length == 1 && TextUtils.equals(arg1, args[0])) {
                actionStr = splitStr[1];
            }
        }
        // convert from string -> action idx list if found a matching entry
        String[] actions = null;
        if (!TextUtils.isEmpty(actionStr)) {
            actions = actionStr.split(INTRA_GROUP_DELIMITER);
        }
        if (!ArrayUtils.isEmpty(actions)) {
            for (String idx : actions) {
                try {
                    actionList.add(Integer.parseInt(idx));
                } catch (NumberFormatException e) {
                    Log.e(TAG, "NumberFormatException(string: " + idx + " config:" + config + "): "
                            + e);
                }
            }
        }
    }
}
