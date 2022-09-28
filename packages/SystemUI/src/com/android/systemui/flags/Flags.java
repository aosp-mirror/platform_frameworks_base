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

import static android.provider.DeviceConfig.NAMESPACE_WINDOW_MANAGER;

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
 * Ids need not be sequential. Flags can "claim" a chunk of ids for flags in related features with
 * a comment. This is purely for organizational purposes.
 *
 * On public release builds, flags will always return their default value. There is no way to
 * change their value on release builds.
 *
 * See {@link FeatureFlagsDebug} for instructions on flipping the flags via adb.
 */
public class Flags {
    public static final UnreleasedFlag TEAMFOOD = new UnreleasedFlag(1);

    /***************************************/
    // 100 - notification
    public static final UnreleasedFlag NOTIFICATION_PIPELINE_DEVELOPER_LOGGING =
            new UnreleasedFlag(103);

    public static final UnreleasedFlag NSSL_DEBUG_LINES =
            new UnreleasedFlag(105);

    public static final UnreleasedFlag NSSL_DEBUG_REMOVE_ANIMATION =
            new UnreleasedFlag(106);

    public static final UnreleasedFlag NEW_PIPELINE_CRASH_ON_CALL_TO_OLD_PIPELINE =
            new UnreleasedFlag(107);

    public static final ResourceBooleanFlag NOTIFICATION_DRAG_TO_CONTENTS =
            new ResourceBooleanFlag(108, R.bool.config_notificationToContents);

    public static final ReleasedFlag REMOVE_UNRANKED_NOTIFICATIONS =
            new ReleasedFlag(109);

    public static final UnreleasedFlag FSI_REQUIRES_KEYGUARD =
            new UnreleasedFlag(110, true);

    public static final UnreleasedFlag INSTANT_VOICE_REPLY = new UnreleasedFlag(111, true);

    // next id: 112

    /***************************************/
    // 200 - keyguard/lockscreen

    // ** Flag retired **
    // public static final BooleanFlag KEYGUARD_LAYOUT =
    //         new BooleanFlag(200, true);

    public static final ReleasedFlag LOCKSCREEN_ANIMATIONS =
            new ReleasedFlag(201);

    public static final ReleasedFlag NEW_UNLOCK_SWIPE_ANIMATION =
            new ReleasedFlag(202);

    public static final ResourceBooleanFlag CHARGING_RIPPLE =
            new ResourceBooleanFlag(203, R.bool.flag_charging_ripple);

    public static final ResourceBooleanFlag BOUNCER_USER_SWITCHER =
            new ResourceBooleanFlag(204, R.bool.config_enableBouncerUserSwitcher);

    public static final ResourceBooleanFlag FACE_SCANNING_ANIM =
            new ResourceBooleanFlag(205, R.bool.config_enableFaceScanningAnimation);

    public static final UnreleasedFlag LOCKSCREEN_CUSTOM_CLOCKS = new UnreleasedFlag(207);

    /**
     * Flag to enable the usage of the new bouncer data source. This is a refactor of and
     * eventual replacement of KeyguardBouncer.java.
     */
    public static final UnreleasedFlag MODERN_BOUNCER = new UnreleasedFlag(208);

    /** Whether UserSwitcherActivity should use modern architecture. */
    public static final ReleasedFlag MODERN_USER_SWITCHER_ACTIVITY =
            new ReleasedFlag(209, true);

    /** Whether the new implementation of UserSwitcherController should be used. */
    public static final UnreleasedFlag REFACTORED_USER_SWITCHER_CONTROLLER =
            new UnreleasedFlag(210, false);

    /***************************************/
    // 300 - power menu
    public static final ReleasedFlag POWER_MENU_LITE =
            new ReleasedFlag(300);

    /***************************************/
    // 400 - smartspace
    public static final ReleasedFlag SMARTSPACE_DEDUPING =
            new ReleasedFlag(400);

    public static final ReleasedFlag SMARTSPACE_SHARED_ELEMENT_TRANSITION_ENABLED =
            new ReleasedFlag(401);

    public static final ResourceBooleanFlag SMARTSPACE =
            new ResourceBooleanFlag(402, R.bool.flag_smartspace);

    /***************************************/
    // 500 - quick settings
    /**
     * @deprecated Not needed anymore
     */
    @Deprecated
    public static final ReleasedFlag NEW_USER_SWITCHER =
            new ReleasedFlag(500);

    public static final UnreleasedFlag COMBINED_QS_HEADERS =
            new UnreleasedFlag(501, true);

    public static final ResourceBooleanFlag PEOPLE_TILE =
            new ResourceBooleanFlag(502, R.bool.flag_conversations);

    public static final ResourceBooleanFlag QS_USER_DETAIL_SHORTCUT =
            new ResourceBooleanFlag(503, R.bool.flag_lockscreen_qs_user_detail_shortcut);

    /**
     * @deprecated Not needed anymore
     */
    @Deprecated
    public static final ReleasedFlag NEW_FOOTER = new ReleasedFlag(504);

    public static final UnreleasedFlag NEW_HEADER = new UnreleasedFlag(505, true);
    public static final ResourceBooleanFlag FULL_SCREEN_USER_SWITCHER =
            new ResourceBooleanFlag(506, R.bool.config_enableFullscreenUserSwitcher);

    public static final UnreleasedFlag NEW_FOOTER_ACTIONS = new UnreleasedFlag(507, true);

    /***************************************/
    // 600- status bar
    public static final ResourceBooleanFlag STATUS_BAR_USER_SWITCHER =
            new ResourceBooleanFlag(602, R.bool.flag_user_switcher_chip);

    public static final ReleasedFlag STATUS_BAR_LETTERBOX_APPEARANCE =
            new ReleasedFlag(603, false);

    public static final UnreleasedFlag NEW_STATUS_BAR_PIPELINE_BACKEND =
            new UnreleasedFlag(604, false);

    public static final UnreleasedFlag NEW_STATUS_BAR_PIPELINE_FRONTEND =
            new UnreleasedFlag(605, false);

    /***************************************/
    // 700 - dialer/calls
    public static final ReleasedFlag ONGOING_CALL_STATUS_BAR_CHIP =
            new ReleasedFlag(700);

    public static final ReleasedFlag ONGOING_CALL_IN_IMMERSIVE =
            new ReleasedFlag(701);

    public static final ReleasedFlag ONGOING_CALL_IN_IMMERSIVE_CHIP_TAP =
            new ReleasedFlag(702);

    /***************************************/
    // 800 - general visual/theme
    public static final ResourceBooleanFlag MONET =
            new ResourceBooleanFlag(800, R.bool.flag_monet);

    /***************************************/
    // 801 - region sampling
    public static final UnreleasedFlag REGION_SAMPLING = new UnreleasedFlag(801);

    // 802 - wallpaper rendering
    public static final UnreleasedFlag USE_CANVAS_RENDERER = new UnreleasedFlag(802);

    // 803 - screen contents translation
    public static final UnreleasedFlag SCREEN_CONTENTS_TRANSLATION = new UnreleasedFlag(803);

    /***************************************/
    // 900 - media
    public static final UnreleasedFlag MEDIA_TAP_TO_TRANSFER = new UnreleasedFlag(900);
    public static final UnreleasedFlag MEDIA_SESSION_ACTIONS = new UnreleasedFlag(901);
    public static final ReleasedFlag MEDIA_NEARBY_DEVICES = new ReleasedFlag(903);
    public static final ReleasedFlag MEDIA_MUTE_AWAIT = new ReleasedFlag(904);
    public static final UnreleasedFlag DREAM_MEDIA_COMPLICATION = new UnreleasedFlag(905);
    public static final UnreleasedFlag DREAM_MEDIA_TAP_TO_OPEN = new UnreleasedFlag(906);

    // 1000 - dock
    public static final ReleasedFlag SIMULATE_DOCK_THROUGH_CHARGING =
            new ReleasedFlag(1000);
    public static final ReleasedFlag DOCK_SETUP_ENABLED = new ReleasedFlag(1001);

    public static final UnreleasedFlag ROUNDED_BOX_RIPPLE = new UnreleasedFlag(1002, false);

    // 1100 - windowing
    @Keep
    public static final SysPropBooleanFlag WM_ENABLE_SHELL_TRANSITIONS =
            new SysPropBooleanFlag(1100, "persist.wm.debug.shell_transit", false);

    /**
     * b/170163464: animate bubbles expanded view collapse with home gesture
     */
    @Keep
    public static final SysPropBooleanFlag BUBBLES_HOME_GESTURE =
            new SysPropBooleanFlag(1101, "persist.wm.debug.bubbles_home_gesture", true);

    @Keep
    public static final DeviceConfigBooleanFlag WM_ENABLE_PARTIAL_SCREEN_SHARING =
            new DeviceConfigBooleanFlag(1102, "record_task_content",
                    NAMESPACE_WINDOW_MANAGER, false, true);

    @Keep
    public static final SysPropBooleanFlag HIDE_NAVBAR_WINDOW =
            new SysPropBooleanFlag(1103, "persist.wm.debug.hide_navbar_window", false);

    @Keep
    public static final SysPropBooleanFlag WM_DESKTOP_WINDOWING =
            new SysPropBooleanFlag(1104, "persist.wm.debug.desktop_mode", false);

    @Keep
    public static final SysPropBooleanFlag WM_CAPTION_ON_SHELL =
            new SysPropBooleanFlag(1105, "persist.wm.debug.caption_on_shell", false);

    @Keep
    public static final SysPropBooleanFlag FLOATING_TASKS_ENABLED =
            new SysPropBooleanFlag(1106, "persist.wm.debug.floating_tasks", false);

    @Keep
    public static final SysPropBooleanFlag SHOW_FLOATING_TASKS_AS_BUBBLES =
            new SysPropBooleanFlag(1107, "persist.wm.debug.floating_tasks_as_bubbles", false);

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

    public static final UnreleasedFlag NEW_BACK_AFFORDANCE =
            new UnreleasedFlag(1203, false /* teamfood */);

    // 1300 - screenshots

    public static final UnreleasedFlag SCREENSHOT_REQUEST_PROCESSOR = new UnreleasedFlag(1300);
    public static final UnreleasedFlag SCREENSHOT_WORK_PROFILE_POLICY = new UnreleasedFlag(1301);

    // 1400 - columbus, b/242800729
    public static final UnreleasedFlag QUICK_TAP_IN_PCC = new UnreleasedFlag(1400);

    // 1500 - chooser
    public static final UnreleasedFlag CHOOSER_UNBUNDLED = new UnreleasedFlag(1500);

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
