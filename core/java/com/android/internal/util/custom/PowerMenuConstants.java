/*
 * Copyright (C) 2015 The CyanogenMod Project
 * Copyright (C) 2017-2018 The LineageOS Project
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

package com.android.internal.util.custom;

/* Master list of all actions for the power menu */
public class PowerMenuConstants {
    public static final String GLOBAL_ACTION_KEY_POWER = "power";
    public static final String GLOBAL_ACTION_KEY_RESTART = "restart";
    public static final String GLOBAL_ACTION_KEY_SCREENSHOT = "screenshot";
    public static final String GLOBAL_ACTION_KEY_AIRPLANE = "airplane";
    public static final String GLOBAL_ACTION_KEY_USERS = "users";
    public static final String GLOBAL_ACTION_KEY_SETTINGS = "settings";
    public static final String GLOBAL_ACTION_KEY_LOCKDOWN = "lockdown";
    public static final String GLOBAL_ACTION_KEY_BUGREPORT = "bugreport";
    public static final String GLOBAL_ACTION_KEY_SILENT = "silent";
    public static final String GLOBAL_ACTION_KEY_VOICEASSIST = "voiceassist";
    public static final String GLOBAL_ACTION_KEY_ASSIST = "assist";
    public static final String GLOBAL_ACTION_KEY_LOGOUT = "logout";
    public static final String GLOBAL_ACTION_KEY_EMERGENCY = "emergency";

    /**
     * Advanced restart menu actions
     */
    public static final String GLOBAL_ACTION_KEY_RESTART_RECOVERY = "restart_recovery";
    public static final String GLOBAL_ACTION_KEY_RESTART_BOOTLOADER = "restart_bootloader";
    public static final String GLOBAL_ACTION_KEY_RESTART_DOWNLOAD = "restart_download";
    public static final String GLOBAL_ACTION_KEY_RESTART_FASTBOOT = "restart_fastboot";

    private static String[] ALL_ACTIONS = {
        GLOBAL_ACTION_KEY_POWER,
        GLOBAL_ACTION_KEY_RESTART,
        GLOBAL_ACTION_KEY_SCREENSHOT,
        GLOBAL_ACTION_KEY_AIRPLANE,
        GLOBAL_ACTION_KEY_USERS,
        GLOBAL_ACTION_KEY_SETTINGS,
        GLOBAL_ACTION_KEY_LOCKDOWN,
        GLOBAL_ACTION_KEY_BUGREPORT,
        GLOBAL_ACTION_KEY_SILENT,
        GLOBAL_ACTION_KEY_VOICEASSIST,
        GLOBAL_ACTION_KEY_ASSIST,
        GLOBAL_ACTION_KEY_LOGOUT,
        GLOBAL_ACTION_KEY_EMERGENCY,
    };

    public static String[] getAllActions() {
        return ALL_ACTIONS;
    }
}