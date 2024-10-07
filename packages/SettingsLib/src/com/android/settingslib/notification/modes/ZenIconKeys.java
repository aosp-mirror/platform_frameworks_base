/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib.notification.modes;

import android.app.AutomaticZenRule;

import com.android.internal.R;

import com.google.common.collect.ImmutableMap;

/**
 * Known icon keys for zen modes that lack a custom {@link AutomaticZenRule#getIconResId()}, based
 * on their {@link ZenMode.Kind} and {@link ZenMode#getType}.
 */
class ZenIconKeys {

    /** The icon for Do Not Disturb mode. */
    static final ZenIcon.Key MANUAL_DND = ZenIcon.Key.forSystemResource(
            R.drawable.ic_zen_mode_type_special_dnd);

    /**
     * The default icon for implicit modes (they can also have a specific icon, if the user has
     * chosen one via Settings).
     */
    static final ZenIcon.Key IMPLICIT_MODE_DEFAULT = ZenIcon.Key.forSystemResource(
            R.drawable.ic_zen_mode_type_special_dnd);

    private static final ImmutableMap<Integer, ZenIcon.Key> TYPE_DEFAULTS = ImmutableMap.of(
            AutomaticZenRule.TYPE_UNKNOWN,
            ZenIcon.Key.forSystemResource(R.drawable.ic_zen_mode_type_unknown),
            AutomaticZenRule.TYPE_OTHER,
            ZenIcon.Key.forSystemResource(R.drawable.ic_zen_mode_type_other),
            AutomaticZenRule.TYPE_SCHEDULE_TIME,
            ZenIcon.Key.forSystemResource(R.drawable.ic_zen_mode_type_schedule_time),
            AutomaticZenRule.TYPE_SCHEDULE_CALENDAR,
            ZenIcon.Key.forSystemResource(R.drawable.ic_zen_mode_type_schedule_calendar),
            AutomaticZenRule.TYPE_BEDTIME,
            ZenIcon.Key.forSystemResource(R.drawable.ic_zen_mode_type_bedtime),
            AutomaticZenRule.TYPE_DRIVING,
            ZenIcon.Key.forSystemResource(R.drawable.ic_zen_mode_type_driving),
            AutomaticZenRule.TYPE_IMMERSIVE,
            ZenIcon.Key.forSystemResource(R.drawable.ic_zen_mode_type_immersive),
            AutomaticZenRule.TYPE_THEATER,
            ZenIcon.Key.forSystemResource(R.drawable.ic_zen_mode_type_theater),
            AutomaticZenRule.TYPE_MANAGED,
            ZenIcon.Key.forSystemResource(R.drawable.ic_zen_mode_type_managed)
    );

    private static final ZenIcon.Key FOR_UNEXPECTED_TYPE =
            ZenIcon.Key.forSystemResource(R.drawable.ic_zen_mode_type_unknown);

    /** Default icon descriptors per mode {@link AutomaticZenRule.Type}. */
    static ZenIcon.Key forType(@AutomaticZenRule.Type int ruleType) {
        return TYPE_DEFAULTS.getOrDefault(ruleType, FOR_UNEXPECTED_TYPE);
    }

    private ZenIconKeys() { }
}
