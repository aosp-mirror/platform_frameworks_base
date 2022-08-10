/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.flags;

import com.android.internal.annotations.Keep;
import com.android.systemui.R;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * List of {@link Flag} objects for use in SystemUI.
 *
 * Flag Ids are integers.
 * Ids must be unique. This is enforced in a unit test.
 * Ids need not be sequential. Flags can "claim" a chunk of ids for flags in related featurs with
 * a comment. This is purely for organizational purposes.
 *
 * On public release builds, flags will always return their default value. There is no way to
 * change their value on release builds.
 *
 * See {@link FeatureFlagsDebug} for instructions on flipping the flags via adb.
 */
public class Flags {
    public static final BooleanFlag TEAMFOOD = new BooleanFlag(1, false);

    /***************************************/
    // 100 - notification
    public static final BooleanFlag NEW_NOTIFICATION_PIPELINE_RENDERING =
            new BooleanFlag(101, true);

    public static final BooleanFlag NOTIFICATION_PIPELINE_DEVELOPER_LOGGING =
            new BooleanFlag(103, false);

    public static final BooleanFlag NSSL_DEBUG_LINES =
            new BooleanFlag(105, false);

    public static final BooleanFlag NSSL_DEBUG_REMOVE_ANIMATION =
            new BooleanFlag(106, false);

    public static final BooleanFlag NEW_PIPELINE_CRASH_ON_CALL_TO_OLD_PIPELINE =
            new BooleanFlag(107, false);

    public static final ResourceBooleanFlag NOTIFICATION_DRAG_TO_CONTENTS =
            new ResourceBooleanFlag(108, R.bool.config_notificationToContents);

    /***************************************/
    // 200 - keyguard/lockscreen

    // ** Flag retired **
    // public static final BooleanFlag KEYGUARD_LAYOUT =
    //         new BooleanFlag(200, true);

    public static final BooleanFlag LOCKSCREEN_ANIMATIONS =
            new BooleanFlag(201, true);

    public static final BooleanFlag NEW_UNLOCK_SWIPE_ANIMATION =
            new BooleanFlag(202, true);

    public static final ResourceBooleanFlag CHARGING_RIPPLE =
            new ResourceBooleanFlag(203, R.bool.flag_charging_ripple);

    public static final ResourceBooleanFlag BOUNCER_USER_SWITCHER =
            new ResourceBooleanFlag(204, R.bool.config_enableBouncerUserSwitcher);

    /***************************************/
    // 300 - power menu
    public static final BooleanFlag POWER_MENU_LITE =
            new BooleanFlag(300, true);

    /***************************************/
    // 400 - smartspace
    public static final BooleanFlag SMARTSPACE_DEDUPING =
            new BooleanFlag(400, true);

    public static final BooleanFlag SMARTSPACE_SHARED_ELEMENT_TRANSITION_ENABLED =
            new BooleanFlag(401, true);

    public static final ResourceBooleanFlag SMARTSPACE =
            new ResourceBooleanFlag(402, R.bool.flag_smartspace);

    /***************************************/
    // 500 - quick settings
    /**
     * @deprecated Not needed anymore
     */
    @Deprecated
    public static final BooleanFlag NEW_USER_SWITCHER =
            new BooleanFlag(500, true);

    public static final BooleanFlag COMBINED_QS_HEADERS =
            new BooleanFlag(501, false);

    public static final ResourceBooleanFlag PEOPLE_TILE =
            new ResourceBooleanFlag(502, R.bool.flag_conversations);

    public static final ResourceBooleanFlag QS_USER_DETAIL_SHORTCUT =
            new ResourceBooleanFlag(503, R.bool.flag_lockscreen_qs_user_detail_shortcut);

    /**
     * @deprecated Not needed anymore
     */
    @Deprecated
    public static final BooleanFlag NEW_FOOTER = new BooleanFlag(504, true);

    public static final BooleanFlag NEW_HEADER = new BooleanFlag(505, false);
    public static final ResourceBooleanFlag FULL_SCREEN_USER_SWITCHER =
            new ResourceBooleanFlag(506, R.bool.config_enableFullscreenUserSwitcher);

    /***************************************/
    // 600- status bar
    public static final BooleanFlag COMBINED_STATUS_BAR_SIGNAL_ICONS =
            new BooleanFlag(601, false);

    public static final ResourceBooleanFlag STATUS_BAR_USER_SWITCHER =
            new ResourceBooleanFlag(602, R.bool.flag_user_switcher_chip);

    /***************************************/
    // 700 - dialer/calls
    public static final BooleanFlag ONGOING_CALL_STATUS_BAR_CHIP =
            new BooleanFlag(700, true);

    public static final BooleanFlag ONGOING_CALL_IN_IMMERSIVE =
            new BooleanFlag(701, true);

    public static final BooleanFlag ONGOING_CALL_IN_IMMERSIVE_CHIP_TAP =
            new BooleanFlag(702, true);

    /***************************************/
    // 800 - general visual/theme
    public static final ResourceBooleanFlag MONET =
            new ResourceBooleanFlag(800, R.bool.flag_monet);

    /***************************************/
    // 900 - media
    public static final BooleanFlag MEDIA_TAP_TO_TRANSFER = new BooleanFlag(900, false);
    public static final BooleanFlag MEDIA_SESSION_ACTIONS = new BooleanFlag(901, false);
    public static final BooleanFlag MEDIA_NEARBY_DEVICES = new BooleanFlag(903, true);
    public static final BooleanFlag MEDIA_MUTE_AWAIT = new BooleanFlag(904, true);

    // 1000 - dock
    public static final BooleanFlag SIMULATE_DOCK_THROUGH_CHARGING =
            new BooleanFlag(1000, true);

    // 1100 - windowing
    @Keep
    public static final SysPropBooleanFlag WM_ENABLE_SHELL_TRANSITIONS =
            new SysPropBooleanFlag(1100, "persist.wm.debug.shell_transit", false);

    // 1200 - predictive back
    @Keep
    public static final SysPropBooleanFlag WM_ENABLE_PREDICTIVE_BACK = new SysPropBooleanFlag(
            1200, "persist.wm.debug.predictive_back", true);
    @Keep
    public static final SysPropBooleanFlag WM_ENABLE_PREDICTIVE_BACK_ANIM = new SysPropBooleanFlag(
            1201, "persist.wm.debug.predictive_back_anim", false);
    @Keep
    public static final SysPropBooleanFlag WM_ALWAYS_ENFORCE_PREDICTIVE_BACK =
            new SysPropBooleanFlag(1202, "persist.wm.debug.predictive_back_always_enforce", false);

    // Pay no attention to the reflection behind the curtain.
    // ========================== Curtain ==========================
    // |                                                           |
    // |  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  |
    private static Map<Integer, Flag<?>> sFlagMap;
    static Map<Integer, Flag<?>> collectFlags() {
        if (sFlagMap != null) {
            return sFlagMap;
        }

        Map<Integer, Flag<?>> flags = new HashMap<>();
        List<Field> flagFields = getFlagFields();

        for (Field field : flagFields) {
            try {
                Flag<?> flag = (Flag<?>) field.get(null);
                flags.put(flag.getId(), flag);
            } catch (IllegalAccessException e) {
                // no-op
            }
        }

        sFlagMap = flags;

        return sFlagMap;
    }

    static List<Field> getFlagFields() {
        Field[] fields = Flags.class.getFields();
        List<Field> result = new ArrayList<>();

        for (Field field : fields) {
            Class<?> t = field.getType();
            if (Flag.class.isAssignableFrom(t)) {
                result.add(field);
            }
        }

        return result;
    }
    // |  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  |
    // |                                                           |
    // \_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/

}
