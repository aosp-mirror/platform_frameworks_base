/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.internal.logging;

/**
 * Constants for mestrics logs.
 *
 * @hide
 */
public interface MetricsConstants {
    // These constants must match those in the analytic pipeline, do not edit.
    // Add temporary values to the top of MetricsLogger instead.
    public static final int VIEW_UNKNOWN = 0;
    public static final int MAIN_SETTINGS = 1;
    public static final int ACCESSIBILITY = 2;
    public static final int ACCESSIBILITY_CAPTION_PROPERTIES = 3;
    public static final int ACCESSIBILITY_SERVICE = 4;
    public static final int ACCESSIBILITY_TOGGLE_DALTONIZER = 5;
    public static final int ACCESSIBILITY_TOGGLE_GLOBAL_GESTURE = 6;
    public static final int ACCESSIBILITY_TOGGLE_SCREEN_MAGNIFICATION = 7;
    public static final int ACCOUNT = 8;
    public static final int ACCOUNTS_ACCOUNT_SYNC = 9;
    public static final int ACCOUNTS_CHOOSE_ACCOUNT_ACTIVITY = 10;
    public static final int ACCOUNTS_MANAGE_ACCOUNTS = 11;
    public static final int APN = 12;
    public static final int APN_EDITOR = 13;
    public static final int APP_OPS_DETAILS = 14;
    public static final int APP_OPS_SUMMARY = 15;
    public static final int APPLICATION = 16;
    public static final int APPLICATIONS_APP_LAUNCH = 17;
    public static final int APPLICATIONS_APP_PERMISSION = 18;
    public static final int APPLICATIONS_APP_STORAGE = 19;
    public static final int APPLICATIONS_INSTALLED_APP_DETAILS = 20;
    public static final int APPLICATIONS_PROCESS_STATS_DETAIL = 21;
    public static final int APPLICATIONS_PROCESS_STATS_MEM_DETAIL = 22;
    public static final int APPLICATIONS_PROCESS_STATS_UI = 23;
    public static final int BLUETOOTH = 24;
    public static final int BLUETOOTH_DEVICE_PICKER = 25;
    public static final int BLUETOOTH_DEVICE_PROFILES = 26;
    public static final int CHOOSE_LOCK_GENERIC = 27;
    public static final int CHOOSE_LOCK_PASSWORD = 28;
    public static final int CHOOSE_LOCK_PATTERN = 29;
    public static final int CONFIRM_LOCK_PASSWORD = 30;
    public static final int CONFIRM_LOCK_PATTERN = 31;
    public static final int CRYPT_KEEPER = 32;
    public static final int CRYPT_KEEPER_CONFIRM = 33;
    public static final int DASHBOARD_SEARCH_RESULTS = 34;
    public static final int DASHBOARD_SUMMARY = 35;
    public static final int DATA_USAGE = 36;
    public static final int DATA_USAGE_SUMMARY = 37;
    public static final int DATE_TIME = 38;
    public static final int DEVELOPMENT = 39;
    public static final int DEVICEINFO = 40;
    public static final int DEVICEINFO_IMEI_INFORMATION = 41;
    public static final int DEVICEINFO_MEMORY = 42;
    public static final int DEVICEINFO_SIM_STATUS = 43;
    public static final int DEVICEINFO_STATUS = 44;
    public static final int DEVICEINFO_USB = 45;
    public static final int DISPLAY = 46;
    public static final int DREAM = 47;
    public static final int ENCRYPTION = 48;
    public static final int FINGERPRINT = 49;
    public static final int FINGERPRINT_ENROLL = 50;
    public static final int FUELGAUGE_BATTERY_HISTORY_DETAIL = 51;
    public static final int FUELGAUGE_BATTERY_SAVER = 52;
    public static final int FUELGAUGE_POWER_USAGE_DETAIL = 53;
    public static final int FUELGAUGE_POWER_USAGE_SUMMARY = 54;
    public static final int HOME = 55;
    public static final int ICC_LOCK = 56;
    public static final int INPUTMETHOD_LANGUAGE = 57;
    public static final int INPUTMETHOD_KEYBOARD = 58;
    public static final int INPUTMETHOD_SPELL_CHECKERS = 59;
    public static final int INPUTMETHOD_SUBTYPE_ENABLER = 60;
    public static final int INPUTMETHOD_USER_DICTIONARY = 61;
    public static final int INPUTMETHOD_USER_DICTIONARY_ADD_WORD = 62;
    public static final int LOCATION = 63;
    public static final int LOCATION_MODE = 64;
    public static final int MANAGE_APPLICATIONS = 65;
    public static final int MASTER_CLEAR = 66;
    public static final int MASTER_CLEAR_CONFIRM = 67;
    public static final int NET_DATA_USAGE_METERED = 68;
    public static final int NFC_BEAM = 69;
    public static final int NFC_PAYMENT = 70;
    public static final int NOTIFICATION = 71;
    public static final int NOTIFICATION_APP_NOTIFICATION = 72;
    public static final int NOTIFICATION_OTHER_SOUND = 73;
    public static final int NOTIFICATION_REDACTION = 74;
    public static final int NOTIFICATION_STATION = 75;
    public static final int NOTIFICATION_ZEN_MODE = 76;
    public static final int OWNER_INFO = 77;
    public static final int PRINT_JOB_SETTINGS = 78;
    public static final int PRINT_SERVICE_SETTINGS = 79;
    public static final int PRINT_SETTINGS = 80;
    public static final int PRIVACY = 81;
    public static final int PROXY_SELECTOR = 82;
    public static final int RESET_NETWORK = 83;
    public static final int RESET_NETWORK_CONFIRM = 84;
    public static final int RUNNING_SERVICE_DETAILS = 85;
    public static final int SCREEN_PINNING = 86;
    public static final int SECURITY = 87;
    public static final int SIM = 88;
    public static final int TESTING = 89;
    public static final int TETHER = 90;
    public static final int TRUST_AGENT = 91;
    public static final int TRUSTED_CREDENTIALS = 92;
    public static final int TTS_ENGINE_SETTINGS = 93;
    public static final int TTS_TEXT_TO_SPEECH = 94;
    public static final int USAGE_ACCESS = 95;
    public static final int USER = 96;
    public static final int USERS_APP_RESTRICTIONS = 97;
    public static final int USER_DETAILS = 98;
    public static final int VOICE_INPUT = 99;
    public static final int VPN = 100;
    public static final int WALLPAPER_TYPE = 101;
    public static final int WFD_WIFI_DISPLAY = 102;
    public static final int WIFI = 103;
    public static final int WIFI_ADVANCED = 104;
    public static final int WIFI_CALLING = 105;
    public static final int WIFI_SAVED_ACCESS_POINTS = 106;
    public static final int WIFI_APITEST = 107;
    public static final int WIFI_INFO = 108;
    public static final int WIFI_P2P = 109;
    public static final int WIRELESS = 110;
    public static final int QS_PANEL = 111;
    public static final int QS_AIRPLANEMODE = 112;
    public static final int QS_BLUETOOTH = 113;
    public static final int QS_CAST = 114;
    public static final int QS_CELLULAR = 115;
    public static final int QS_COLORINVERSION = 116;
    public static final int QS_DATAUSAGEDETAIL = 117;
    public static final int QS_DND = 118;
    public static final int QS_FLASHLIGHT = 119;
    public static final int QS_HOTSPOT = 120;
    public static final int QS_INTENT = 121;
    public static final int QS_LOCATION = 122;
    public static final int QS_ROTATIONLOCK = 123;
    public static final int QS_USERDETAILITE = 124;
    public static final int QS_USERDETAIL = 125;
    public static final int QS_WIFI = 126;
    public static final int NOTIFICATION_PANEL = 127;
    public static final int NOTIFICATION_ITEM = 128;
    public static final int NOTIFICATION_ITEM_ACTION = 129;
    public static final int APPLICATIONS_ADVANCED = 130;
    public static final int LOCATION_SCANNING = 131;
    public static final int MANAGE_APPLICATIONS_ALL = 132;
    public static final int MANAGE_APPLICATIONS_NOTIFICATIONS = 133;
    public static final int ACTION_WIFI_ADD_NETWORK = 134;
    public static final int ACTION_WIFI_CONNECT = 135;
    public static final int ACTION_WIFI_FORCE_SCAN = 136;
    public static final int ACTION_WIFI_FORGET = 137;
    public static final int ACTION_WIFI_OFF = 138;
    public static final int ACTION_WIFI_ON = 139;
    public static final int MANAGE_PERMISSIONS = 140;
    public static final int NOTIFICATION_ZEN_MODE_PRIORITY = 141;
    public static final int NOTIFICATION_ZEN_MODE_AUTOMATION = 142;
    public static final int MANAGE_DOMAIN_URLS = 143;
    public static final int NOTIFICATION_ZEN_MODE_SCHEDULE_RULE = 144;
    public static final int NOTIFICATION_ZEN_MODE_EXTERNAL_RULE = 145;
    public static final int NOTIFICATION_ZEN_MODE_EVENT_RULE = 146;
    public static final int ACTION_BAN_APP_NOTES = 147;
    public static final int ACTION_DISMISS_ALL_NOTES = 148;
    public static final int QS_DND_DETAILS = 149;
    public static final int QS_BLUETOOTH_DETAILS = 150;
    public static final int QS_CAST_DETAILS = 151;
    public static final int QS_WIFI_DETAILS = 152;
    public static final int QS_WIFI_TOGGLE = 153;
    public static final int QS_BLUETOOTH_TOGGLE = 154;
    public static final int QS_CELLULAR_TOGGLE = 155;
    public static final int QS_SWITCH_USER = 156;
    public static final int QS_CAST_SELECT = 157;
    public static final int QS_CAST_DISCONNECT = 158;
    public static final int ACTION_BLUETOOTH_TOGGLE = 159;
    public static final int ACTION_BLUETOOTH_SCAN = 160;
    public static final int ACTION_BLUETOOTH_RENAME = 161;
    public static final int ACTION_BLUETOOTH_FILES = 162;
    public static final int QS_DND_TIME = 163;
    public static final int QS_DND_CONDITION_SELECT = 164;
    public static final int QS_DND_ZEN_SELECT = 165;
    public static final int QS_DND_TOGGLE = 166;
    public static final int ACTION_ZEN_ALLOW_REMINDERS = 167;
    public static final int ACTION_ZEN_ALLOW_EVENTS = 168;
    public static final int ACTION_ZEN_ALLOW_MESSAGES = 169;
    public static final int ACTION_ZEN_ALLOW_CALLS = 170;
    public static final int ACTION_ZEN_ALLOW_REPEAT_CALLS = 171;
    public static final int ACTION_ZEN_ADD_RULE = 172;
    public static final int ACTION_ZEN_ADD_RULE_OK = 173;
    public static final int ACTION_ZEN_DELETE_RULE = 174;
    public static final int ACTION_ZEN_DELETE_RULE_OK = 175;
    public static final int ACTION_ZEN_ENABLE_RULE = 176;
    public static final int ACTION_AIRPLANE_TOGGLE = 177;
    public static final int ACTION_CELL_DATA_TOGGLE = 178;
    public static final int NOTIFICATION_ACCESS = 179;
    public static final int NOTIFICATION_ZEN_MODE_ACCESS = 180;
    public static final int APPLICATIONS_DEFAULT_APPS = 181;
    public static final int APPLICATIONS_STORAGE_APPS = 182;
    public static final int APPLICATIONS_USAGE_ACCESS_DETAIL = 183;
    public static final int APPLICATIONS_HIGH_POWER_APPS = 184;
    public static final int FUELGAUGE_HIGH_POWER_DETAILS = 185;
    public static final int ACTION_LS_UNLOCK = 186;
    public static final int ACTION_LS_SHADE = 187;
    public static final int ACTION_LS_HINT = 188;
    public static final int ACTION_LS_CAMERA = 189;
    public static final int ACTION_LS_DIALER = 190;
    public static final int ACTION_LS_LOCK = 191;
    public static final int ACTION_LS_NOTE = 192;
    public static final int ACTION_LS_QS = 193;
    public static final int ACTION_SHADE_QS_PULL = 194;
    public static final int ACTION_SHADE_QS_TAP = 195;
    public static final int LOCKSCREEN = 196;
    public static final int BOUNCER = 197;
    public static final int SCREEN = 198;
    public static final int NOTIFICATION_ALERT = 199;
    public static final int ACTION_EMERGENCY_CALL = 200;
    public static final int APPLICATIONS_MANAGE_ASSIST = 201;
    public static final int PROCESS_STATS_SUMMARY = 202;
    public static final int ACTION_ROTATION_LOCK = 203;
    public static final int ACTION_NOTE_CONTROLS = 204;
    public static final int ACTION_NOTE_INFO = 205;
    public static final int ACTION_APP_NOTE_SETTINGS = 206;
    public static final int VOLUME_DIALOG = 207;
    public static final int VOLUME_DIALOG_DETAILS = 208;
    public static final int ACTION_VOLUME_SLIDER = 209;
    public static final int ACTION_VOLUME_STREAM = 210;
    public static final int ACTION_VOLUME_KEY = 211;
    public static final int ACTION_VOLUME_ICON = 212;
    public static final int ACTION_RINGER_MODE = 213;
    public static final int ACTION_ACTIVITY_CHOOSER_SHOWN = 214;
    public static final int ACTION_ACTIVITY_CHOOSER_PICKED_APP_TARGET = 215;
    public static final int ACTION_ACTIVITY_CHOOSER_PICKED_SERVICE_TARGET = 216;
    public static final int ACTION_ACTIVITY_CHOOSER_PICKED_STANDARD_TARGET = 217;
    public static final int ACTION_BRIGHTNESS = 218;
    public static final int ACTION_BRIGHTNESS_AUTO = 219;
    public static final int BRIGHTNESS_DIALOG = 220;
    public static final int SYSTEM_ALERT_WINDOW_APPS = 221;
    public static final int DREAMING = 222;
    public static final int DOZING = 223;
    public static final int OVERVIEW_ACTIVITY = 224;
    public static final int ABOUT_LEGAL_SETTINGS = 225;
    public static final int ACTION_SEARCH_RESULTS = 226;
    public static final int TUNER = 227;
    public static final int TUNER_QS = 228;
    public static final int TUNER_DEMO_MODE = 229;
    public static final int TUNER_QS_REORDER = 230;
    public static final int TUNER_QS_ADD = 231;
    public static final int TUNER_QS_REMOVE = 232;
    public static final int TUNER_STATUS_BAR_ENABLE = 233;
    public static final int TUNER_STATUS_BAR_DISABLE = 234;
    public static final int TUNER_DEMO_MODE_ENABLED = 235;
    public static final int TUNER_DEMO_MODE_ON = 236;
    public static final int TUNER_BATTERY_PERCENTAGE = 237;
    public static final int FUELGAUGE_INACTIVE_APPS = 238;

    // These constants must match those in the analytic pipeline, do not edit.
    // Add temporary values to the top of MetricsLogger instead.

    //aliases
    public static final int DEVICEINFO_STORAGE = DEVICEINFO_MEMORY;
}
