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

package com.android.systemui;

import android.annotation.StringDef;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;

import com.android.systemui.settings.UserContextProvider;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Map;
import java.util.Set;

/**
 * A helper class to store simple preferences for SystemUI. Its main use case is things such as
 * feature education, e.g. "has the user seen this tooltip".
 *
 * As of this writing, feature education settings are *intentionally exempted* from backup and
 * restore because there is not a great way to know which subset of features the user _should_ see
 * again if, for instance, they are coming from multiple OSes back or switching OEMs.
 *
 * NOTE: Clients of this class should take care to pass in the correct user context when querying
 * settings, otherwise you will always read/write for user 0 which is almost never what you want.
 * See {@link UserContextProvider} for a simple way to get the current context
 */
public final class Prefs {
    private Prefs() {} // no instantation

    @Retention(RetentionPolicy.SOURCE)
    @StringDef({
            Key.OVERVIEW_LAST_STACK_TASK_ACTIVE_TIME,
            Key.DEBUG_MODE_ENABLED,
            Key.HOTSPOT_TILE_LAST_USED,
            Key.COLOR_INVERSION_TILE_LAST_USED,
            Key.DND_TILE_VISIBLE,
            Key.DND_TILE_COMBINED_ICON,
            Key.DND_CONFIRMED_PRIORITY_INTRODUCTION,
            Key.DND_CONFIRMED_SILENCE_INTRODUCTION,
            Key.DND_FAVORITE_BUCKET_INDEX,
            Key.DND_NONE_SELECTED,
            Key.DND_FAVORITE_ZEN,
            Key.QS_HOTSPOT_ADDED,
            Key.QS_DATA_SAVER_ADDED,
            Key.QS_DATA_SAVER_DIALOG_SHOWN,
            Key.QS_INVERT_COLORS_ADDED,
            Key.QS_WORK_ADDED,
            Key.QS_NIGHTDISPLAY_ADDED,
            Key.QS_LONG_PRESS_TOOLTIP_SHOWN_COUNT,
            Key.SEEN_RINGER_GUIDANCE_COUNT,
            Key.QS_HAS_TURNED_OFF_MOBILE_DATA,
            Key.TOUCHED_RINGER_TOGGLE,
            Key.HAS_SEEN_ODI_CAPTIONS_TOOLTIP,
            Key.HAS_SEEN_REVERSE_BOTTOM_SHEET,
            Key.CONTROLS_STRUCTURE_SWIPE_TOOLTIP_COUNT,
            Key.HAS_SEEN_ACCESSIBILITY_FLOATING_MENU_DOCK_TOOLTIP,
            Key.ACCESSIBILITY_FLOATING_MENU_POSITION,
            Key.HAS_CLICKED_NUDGE_TO_SETUP_DREAM,
            Key.HAS_DISMISSED_NUDGE_TO_SETUP_DREAM,
            Key.HAS_ACCESSIBILITY_FLOATING_MENU_TUCKED,
            Key.BLUETOOTH_TILE_DIALOG_CONTENT_HEIGHT,
    })
    // TODO: annotate these with their types so {@link PrefsCommandLine} can know how to set them
    public @interface Key {
        @Deprecated
        String OVERVIEW_LAST_STACK_TASK_ACTIVE_TIME = "OverviewLastStackTaskActiveTime";
        String DEBUG_MODE_ENABLED = "debugModeEnabled";
        String HOTSPOT_TILE_LAST_USED = "HotspotTileLastUsed";
        String COLOR_INVERSION_TILE_LAST_USED = "ColorInversionTileLastUsed";
        String DND_TILE_VISIBLE = "DndTileVisible";
        String DND_TILE_COMBINED_ICON = "DndTileCombinedIcon";
        String DND_CONFIRMED_PRIORITY_INTRODUCTION = "DndConfirmedPriorityIntroduction";
        String DND_CONFIRMED_SILENCE_INTRODUCTION = "DndConfirmedSilenceIntroduction";
        String DND_CONFIRMED_ALARM_INTRODUCTION = "DndConfirmedAlarmIntroduction";
        String DND_FAVORITE_BUCKET_INDEX = "DndCountdownMinuteIndex";
        String DND_NONE_SELECTED = "DndNoneSelected";
        String DND_FAVORITE_ZEN = "DndFavoriteZen";
        String QS_DATA_SAVER_DIALOG_SHOWN = "QsDataSaverDialogShown";
        @Deprecated
        String QS_HOTSPOT_ADDED = "QsHotspotAdded";
        @Deprecated
        String QS_DATA_SAVER_ADDED = "QsDataSaverAdded";
        @Deprecated
        String QS_INVERT_COLORS_ADDED = "QsInvertColorsAdded";
        @Deprecated
        String QS_WORK_ADDED = "QsWorkAdded";
        @Deprecated
        String QS_NIGHTDISPLAY_ADDED = "QsNightDisplayAdded";
        /**
         * Used for tracking how many times the user has seen the long press tooltip in the Quick
         * Settings panel.
         */
        String QS_LONG_PRESS_TOOLTIP_SHOWN_COUNT = "QsLongPressTooltipShownCount";
        String SEEN_RINGER_GUIDANCE_COUNT = "RingerGuidanceCount";
        String QS_TILE_SPECS_REVEALED = "QsTileSpecsRevealed";
        String QS_HAS_TURNED_OFF_MOBILE_DATA = "QsHasTurnedOffMobileData";
        String TOUCHED_RINGER_TOGGLE = "TouchedRingerToggle";
        String HAS_SEEN_ODI_CAPTIONS_TOOLTIP = "HasSeenODICaptionsTooltip";
        String HAS_SEEN_REVERSE_BOTTOM_SHEET = "HasSeenReverseBottomSheet";
        String CONTROLS_STRUCTURE_SWIPE_TOOLTIP_COUNT = "ControlsStructureSwipeTooltipCount";
        String HAS_SEEN_ACCESSIBILITY_FLOATING_MENU_DOCK_TOOLTIP =
                "HasSeenAccessibilityFloatingMenuDockTooltip";
        String ACCESSIBILITY_FLOATING_MENU_POSITION = "AccessibilityFloatingMenuPosition";
        String HAS_CLICKED_NUDGE_TO_SETUP_DREAM = "HasClickedNudgeToSetupDream";
        String HAS_DISMISSED_NUDGE_TO_SETUP_DREAM = "HasDismissedNudgeToSetupDream";
        String HAS_ACCESSIBILITY_FLOATING_MENU_TUCKED = "HasAccessibilityFloatingMenuTucked";
        String BLUETOOTH_TILE_DIALOG_CONTENT_HEIGHT = "BluetoothTileDialogContentHeight";
    }

    public static boolean getBoolean(Context context, @Key String key, boolean defaultValue) {
        return get(context).getBoolean(key, defaultValue);
    }

    public static void putBoolean(Context context, @Key String key, boolean value) {
        get(context).edit().putBoolean(key, value).apply();
    }

    public static int getInt(Context context, @Key String key, int defaultValue) {
        return get(context).getInt(key, defaultValue);
    }

    public static void putInt(Context context, @Key String key, int value) {
        get(context).edit().putInt(key, value).apply();
    }

    public static long getLong(Context context, @Key String key, long defaultValue) {
        return get(context).getLong(key, defaultValue);
    }

    public static void putLong(Context context, @Key String key, long value) {
        get(context).edit().putLong(key, value).apply();
    }

    public static String getString(Context context, @Key String key, String defaultValue) {
        return get(context).getString(key, defaultValue);
    }

    public static void putString(Context context, @Key String key, String value) {
        get(context).edit().putString(key, value).apply();
    }

    public static void putStringSet(Context context, @Key String key, Set<String> value) {
        get(context).edit().putStringSet(key, value).apply();
    }

    public static Set<String> getStringSet(
            Context context, @Key String key, Set<String> defaultValue) {
        return get(context).getStringSet(key, defaultValue);
    }

    public static Map<String, ?> getAll(Context context) {
        return get(context).getAll();
    }

    public static void remove(Context context, @Key String key) {
        get(context).edit().remove(key).apply();
    }

    public static void registerListener(Context context,
            OnSharedPreferenceChangeListener listener) {
        get(context).registerOnSharedPreferenceChangeListener(listener);
    }

    public static void unregisterListener(Context context,
            OnSharedPreferenceChangeListener listener) {
        get(context).unregisterOnSharedPreferenceChangeListener(listener);
    }

    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(context.getPackageName(), Context.MODE_PRIVATE);
    }
}
