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
package com.android.systemui.flags

import android.provider.DeviceConfig
import com.android.internal.annotations.Keep
import com.android.systemui.R
import com.android.systemui.flags.FlagsFactory.releasedFlag
import com.android.systemui.flags.FlagsFactory.resourceBooleanFlag
import com.android.systemui.flags.FlagsFactory.sysPropBooleanFlag
import com.android.systemui.flags.FlagsFactory.unreleasedFlag

/**
 * List of [Flag] objects for use in SystemUI.
 *
 * Flag Ids are integers. Ids must be unique. This is enforced in a unit test. Ids need not be
 * sequential. Flags can "claim" a chunk of ids for flags in related features with a comment. This
 * is purely for organizational purposes.
 *
 * On public release builds, flags will always return their default value. There is no way to change
 * their value on release builds.
 *
 * See [FeatureFlagsDebug] for instructions on flipping the flags via adb.
 */
object Flags {
    @JvmField val TEAMFOOD = unreleasedFlag(1, "teamfood")

    // 100 - notification
    // TODO(b/254512751): Tracking Bug
    val NOTIFICATION_PIPELINE_DEVELOPER_LOGGING =
        unreleasedFlag(103, "notification_pipeline_developer_logging")

    // TODO(b/254512732): Tracking Bug
    @JvmField val NSSL_DEBUG_LINES = unreleasedFlag(105, "nssl_debug_lines")

    // TODO(b/254512505): Tracking Bug
    @JvmField val NSSL_DEBUG_REMOVE_ANIMATION = unreleasedFlag(106, "nssl_debug_remove_animation")

    // TODO(b/254512624): Tracking Bug
    @JvmField
    val NOTIFICATION_DRAG_TO_CONTENTS =
        resourceBooleanFlag(
            108,
            R.bool.config_notificationToContents,
            "notification_drag_to_contents"
        )

    // TODO(b/254512517): Tracking Bug
    val FSI_REQUIRES_KEYGUARD = unreleasedFlag(110, "fsi_requires_keyguard", teamfood = true)

    // TODO(b/259130119): Tracking Bug
    val FSI_ON_DND_UPDATE = unreleasedFlag(259130119, "fsi_on_dnd_update", teamfood = true)

    // TODO(b/254512538): Tracking Bug
    val INSTANT_VOICE_REPLY = unreleasedFlag(111, "instant_voice_reply", teamfood = true)

    // TODO(b/254512425): Tracking Bug
    val NOTIFICATION_MEMORY_MONITOR_ENABLED =
        releasedFlag(112, "notification_memory_monitor_enabled")

    val NOTIFICATION_MEMORY_LOGGING_ENABLED =
        unreleasedFlag(119, "notification_memory_logging_enabled", teamfood = true)

    // TODO(b/254512731): Tracking Bug
    @JvmField val NOTIFICATION_DISMISSAL_FADE = releasedFlag(113, "notification_dismissal_fade")

    // TODO(b/259558771): Tracking Bug
    val STABILITY_INDEX_FIX = releasedFlag(114, "stability_index_fix")

    // TODO(b/259559750): Tracking Bug
    val SEMI_STABLE_SORT = releasedFlag(115, "semi_stable_sort")

    @JvmField val USE_ROUNDNESS_SOURCETYPES = releasedFlag(116, "use_roundness_sourcetype")

    // TODO(b/259217907)
    @JvmField
    val NOTIFICATION_GROUP_DISMISSAL_ANIMATION =
        unreleasedFlag(259217907, "notification_group_dismissal_animation", teamfood = true)

    // TODO(b/257506350): Tracking Bug
    @JvmField val FSI_CHROME = unreleasedFlag(117, "fsi_chrome")

    @JvmField
    val SIMPLIFIED_APPEAR_FRACTION =
        unreleasedFlag(259395680, "simplified_appear_fraction", teamfood = true)

    // TODO(b/257315550): Tracking Bug
    val NO_HUN_FOR_OLD_WHEN = unreleasedFlag(118, "no_hun_for_old_when", teamfood = true)

    // TODO(b/260335638): Tracking Bug
    @JvmField
    val NOTIFICATION_INLINE_REPLY_ANIMATION =
        unreleasedFlag(174148361, "notification_inline_reply_animation", teamfood = true)

    val FILTER_UNSEEN_NOTIFS_ON_KEYGUARD =
        unreleasedFlag(254647461, "filter_unseen_notifs_on_keyguard", teamfood = true)

    // 200 - keyguard/lockscreen
    // ** Flag retired **
    // public static final BooleanFlag KEYGUARD_LAYOUT =
    //         new BooleanFlag(200, true);

    // TODO(b/254512750): Tracking Bug
    val NEW_UNLOCK_SWIPE_ANIMATION = releasedFlag(202, "new_unlock_swipe_animation")
    val CHARGING_RIPPLE = resourceBooleanFlag(203, R.bool.flag_charging_ripple, "charging_ripple")

    // TODO(b/254512281): Tracking Bug
    @JvmField
    val BOUNCER_USER_SWITCHER =
        resourceBooleanFlag(204, R.bool.config_enableBouncerUserSwitcher, "bouncer_user_switcher")

    // TODO(b/254512676): Tracking Bug
    @JvmField
    val LOCKSCREEN_CUSTOM_CLOCKS = unreleasedFlag(207, "lockscreen_custom_clocks", teamfood = true)

    /**
     * Flag to enable the usage of the new bouncer data source. This is a refactor of and eventual
     * replacement of KeyguardBouncer.java.
     */
    // TODO(b/254512385): Tracking Bug
    @JvmField val MODERN_BOUNCER = releasedFlag(208, "modern_bouncer")

    /**
     * Whether the clock on a wide lock screen should use the new "stepping" animation for moving
     * the digits when the clock moves.
     */
    @JvmField
    val STEP_CLOCK_ANIMATION = unreleasedFlag(212, "step_clock_animation", teamfood = true)

    /**
     * Migration from the legacy isDozing/dozeAmount paths to the new KeyguardTransitionRepository
     * will occur in stages. This is one stage of many to come.
     */
    // TODO(b/255607168): Tracking Bug
    @JvmField val DOZING_MIGRATION_1 = unreleasedFlag(213, "dozing_migration_1")

    // TODO(b/252897742): Tracking Bug
    @JvmField val NEW_ELLIPSE_DETECTION = unreleasedFlag(214, "new_ellipse_detection")

    // TODO(b/252897742): Tracking Bug
    @JvmField val NEW_UDFPS_OVERLAY = unreleasedFlag(215, "new_udfps_overlay")

    /**
     * Whether to enable the code powering customizable lock screen quick affordances.
     *
     * This flag enables any new prebuilt quick affordances as well.
     */
    // TODO(b/255618149): Tracking Bug
    @JvmField
    val CUSTOMIZABLE_LOCK_SCREEN_QUICK_AFFORDANCES =
        unreleasedFlag(216, "customizable_lock_screen_quick_affordances", teamfood = true)

    /** Shows chipbar UI whenever the device is unlocked by ActiveUnlock (watch). */
    // TODO(b/256513609): Tracking Bug
    @JvmField
    val ACTIVE_UNLOCK_CHIPBAR =
        resourceBooleanFlag(217, R.bool.flag_active_unlock_chipbar, "active_unlock_chipbar")

    /**
     * Migrates control of the LightRevealScrim's reveal effect and amount from legacy code to the
     * new KeyguardTransitionRepository.
     */
    @JvmField
    val LIGHT_REVEAL_MIGRATION = unreleasedFlag(218, "light_reveal_migration", teamfood = false)

    /**
     * Whether to use the new alternate bouncer architecture, a refactor of and eventual replacement
     * of the Alternate/Authentication Bouncer. No visual UI changes.
     */
    // TODO(b/260619425): Tracking Bug
    @JvmField val MODERN_ALTERNATE_BOUNCER = unreleasedFlag(219, "modern_alternate_bouncer")

    /** Flag to control the migration of face auth to modern architecture. */
    // TODO(b/262838215): Tracking bug
    @JvmField val FACE_AUTH_REFACTOR = unreleasedFlag(220, "face_auth_refactor")

    /** Flag to control the revamp of keyguard biometrics progress animation */
    // TODO(b/244313043): Tracking bug
    @JvmField val BIOMETRICS_ANIMATION_REVAMP = unreleasedFlag(221, "biometrics_animation_revamp")

    // TODO(b/262780002): Tracking Bug
    @JvmField
    val REVAMPED_WALLPAPER_UI = unreleasedFlag(222, "revamped_wallpaper_ui", teamfood = false)

    /** A different path for unocclusion transitions back to keyguard */
    // TODO(b/262859270): Tracking Bug
    @JvmField
    val UNOCCLUSION_TRANSITION = unreleasedFlag(223, "unocclusion_transition", teamfood = true)

    // flag for controlling auto pin confirmation and material u shapes in bouncer
    @JvmField
    val AUTO_PIN_CONFIRMATION =
        unreleasedFlag(224, "auto_pin_confirmation", "auto_pin_confirmation")

    // TODO(b/262859270): Tracking Bug
    @JvmField val FALSING_OFF_FOR_UNFOLDED = releasedFlag(225, "falsing_off_for_unfolded")

    /** Enables code to show contextual loyalty cards in wallet entrypoints */
    // TODO(b/247587924): Tracking Bug
    @JvmField
    val ENABLE_WALLET_CONTEXTUAL_LOYALTY_CARDS =
        unreleasedFlag(226, "enable_wallet_contextual_loyalty_cards", teamfood = false)

    // 300 - power menu
    // TODO(b/254512600): Tracking Bug
    @JvmField val POWER_MENU_LITE = releasedFlag(300, "power_menu_lite")

    // 400 - smartspace

    // TODO(b/254513100): Tracking Bug
    val SMARTSPACE_SHARED_ELEMENT_TRANSITION_ENABLED =
        releasedFlag(401, "smartspace_shared_element_transition_enabled")
    val SMARTSPACE = resourceBooleanFlag(402, R.bool.flag_smartspace, "smartspace")

    // TODO(b/258517050): Clean up after the feature is launched.
    @JvmField
    val SMARTSPACE_DATE_WEATHER_DECOUPLED = unreleasedFlag(403, "smartspace_date_weather_decoupled")

    // 500 - quick settings

    // TODO(b/254512321): Tracking Bug
    @JvmField val COMBINED_QS_HEADERS = releasedFlag(501, "combined_qs_headers")
    val PEOPLE_TILE = resourceBooleanFlag(502, R.bool.flag_conversations, "people_tile")

    @JvmField
    val QS_USER_DETAIL_SHORTCUT =
        resourceBooleanFlag(
            503,
            R.bool.flag_lockscreen_qs_user_detail_shortcut,
            "qs_user_detail_shortcut"
        )

    // TODO(b/254512383): Tracking Bug
    @JvmField
    val FULL_SCREEN_USER_SWITCHER =
        resourceBooleanFlag(
            506,
            R.bool.config_enableFullscreenUserSwitcher,
            "full_screen_user_switcher"
        )

    // TODO(b/244064524): Tracking Bug
    @JvmField val QS_SECONDARY_DATA_SUB_INFO = releasedFlag(508, "qs_secondary_data_sub_info")

    // 600- status bar

    // TODO(b/256614753): Tracking Bug
    val NEW_STATUS_BAR_MOBILE_ICONS = unreleasedFlag(606, "new_status_bar_mobile_icons")

    // TODO(b/256614210): Tracking Bug
    val NEW_STATUS_BAR_WIFI_ICON = unreleasedFlag(607, "new_status_bar_wifi_icon")

    // TODO(b/256614751): Tracking Bug
    val NEW_STATUS_BAR_MOBILE_ICONS_BACKEND =
        unreleasedFlag(608, "new_status_bar_mobile_icons_backend", teamfood = true)

    // TODO(b/256613548): Tracking Bug
    val NEW_STATUS_BAR_WIFI_ICON_BACKEND =
        unreleasedFlag(609, "new_status_bar_wifi_icon_backend", teamfood = true)

    // TODO(b/256623670): Tracking Bug
    @JvmField
    val BATTERY_SHIELD_ICON =
        resourceBooleanFlag(610, R.bool.flag_battery_shield_icon, "battery_shield_icon")

    // TODO(b/260881289): Tracking Bug
    val NEW_STATUS_BAR_ICONS_DEBUG_COLORING =
        unreleasedFlag(611, "new_status_bar_icons_debug_coloring")

    // 700 - dialer/calls
    // TODO(b/254512734): Tracking Bug
    val ONGOING_CALL_STATUS_BAR_CHIP = releasedFlag(700, "ongoing_call_status_bar_chip")

    // TODO(b/254512681): Tracking Bug
    val ONGOING_CALL_IN_IMMERSIVE = releasedFlag(701, "ongoing_call_in_immersive")

    // TODO(b/254512753): Tracking Bug
    val ONGOING_CALL_IN_IMMERSIVE_CHIP_TAP = releasedFlag(702, "ongoing_call_in_immersive_chip_tap")

    // 800 - general visual/theme
    @JvmField val MONET = resourceBooleanFlag(800, R.bool.flag_monet, "monet")

    // 801 - region sampling
    // TODO(b/254512848): Tracking Bug
    val REGION_SAMPLING = unreleasedFlag(801, "region_sampling", teamfood = true)

    // 803 - screen contents translation
    // TODO(b/254513187): Tracking Bug
    val SCREEN_CONTENTS_TRANSLATION = unreleasedFlag(803, "screen_contents_translation")

    // 804 - monochromatic themes
    @JvmField
    val MONOCHROMATIC_THEMES =
        sysPropBooleanFlag(804, "persist.sysui.monochromatic", default = false)

    // 900 - media
    // TODO(b/254512697): Tracking Bug
    val MEDIA_TAP_TO_TRANSFER = releasedFlag(900, "media_tap_to_transfer")

    // TODO(b/254512502): Tracking Bug
    val MEDIA_SESSION_ACTIONS = unreleasedFlag(901, "media_session_actions")

    // TODO(b/254512726): Tracking Bug
    val MEDIA_NEARBY_DEVICES = releasedFlag(903, "media_nearby_devices")

    // TODO(b/254512695): Tracking Bug
    val MEDIA_MUTE_AWAIT = releasedFlag(904, "media_mute_await")

    // TODO(b/254512654): Tracking Bug
    @JvmField val DREAM_MEDIA_COMPLICATION = unreleasedFlag(905, "dream_media_complication")

    // TODO(b/254512673): Tracking Bug
    @JvmField val DREAM_MEDIA_TAP_TO_OPEN = unreleasedFlag(906, "dream_media_tap_to_open")

    // TODO(b/254513168): Tracking Bug
    @JvmField val UMO_SURFACE_RIPPLE = unreleasedFlag(907, "umo_surface_ripple")

    @JvmField
    val MEDIA_FALSING_PENALTY = unreleasedFlag(908, "media_falsing_media", teamfood = true)

    // TODO(b/261734857): Tracking Bug
    @JvmField val UMO_TURBULENCE_NOISE = unreleasedFlag(909, "umo_turbulence_noise")

    // TODO(b/263272731): Tracking Bug
    val MEDIA_TTT_RECEIVER_SUCCESS_RIPPLE =
        unreleasedFlag(910, "media_ttt_receiver_success_ripple", teamfood = true)

    // TODO(b/263512203): Tracking Bug
    val MEDIA_EXPLICIT_INDICATOR = unreleasedFlag(911, "media_explicit_indicator", teamfood = true)

    // 1000 - dock
    val SIMULATE_DOCK_THROUGH_CHARGING = releasedFlag(1000, "simulate_dock_through_charging")

    // TODO(b/254512758): Tracking Bug
    @JvmField val ROUNDED_BOX_RIPPLE = releasedFlag(1002, "rounded_box_ripple")

    // TODO(b/265045965): Tracking Bug
    val SHOW_LOWLIGHT_ON_DIRECT_BOOT = releasedFlag(1003, "show_lowlight_on_direct_boot")

    // 1100 - windowing
    @Keep
    @JvmField
    val WM_ENABLE_SHELL_TRANSITIONS =
        sysPropBooleanFlag(1100, "persist.wm.debug.shell_transit", default = false)

    // TODO(b/254513207): Tracking Bug
    @Keep
    @JvmField
    val WM_ENABLE_PARTIAL_SCREEN_SHARING =
        unreleasedFlag(
            1102,
            name = "record_task_content",
            namespace = DeviceConfig.NAMESPACE_WINDOW_MANAGER,
            teamfood = true
        )

    // TODO(b/254512674): Tracking Bug
    @Keep
    @JvmField
    val HIDE_NAVBAR_WINDOW =
        sysPropBooleanFlag(1103, "persist.wm.debug.hide_navbar_window", default = false)

    @Keep
    @JvmField
    val WM_DESKTOP_WINDOWING =
        sysPropBooleanFlag(1104, "persist.wm.debug.desktop_mode", default = false)

    @Keep
    @JvmField
    val WM_CAPTION_ON_SHELL =
        sysPropBooleanFlag(1105, "persist.wm.debug.caption_on_shell", default = false)

    @Keep
    @JvmField
    val ENABLE_FLING_TO_DISMISS_BUBBLE =
        sysPropBooleanFlag(1108, "persist.wm.debug.fling_to_dismiss_bubble", default = true)

    @Keep
    @JvmField
    val ENABLE_FLING_TO_DISMISS_PIP =
        sysPropBooleanFlag(1109, "persist.wm.debug.fling_to_dismiss_pip", default = true)

    @Keep
    @JvmField
    val ENABLE_PIP_KEEP_CLEAR_ALGORITHM =
        sysPropBooleanFlag(
            1110,
            "persist.wm.debug.enable_pip_keep_clear_algorithm",
            default = false
        )

    // TODO(b/256873975): Tracking Bug
    @JvmField @Keep val WM_BUBBLE_BAR = unreleasedFlag(1111, "wm_bubble_bar")

    // TODO(b/260271148): Tracking bug
    @Keep
    @JvmField
    val WM_DESKTOP_WINDOWING_2 =
        sysPropBooleanFlag(1112, "persist.wm.debug.desktop_mode_2", default = false)

    // 1200 - predictive back
    @Keep
    @JvmField
    val WM_ENABLE_PREDICTIVE_BACK =
        sysPropBooleanFlag(1200, "persist.wm.debug.predictive_back", default = true)

    @Keep
    @JvmField
    val WM_ENABLE_PREDICTIVE_BACK_ANIM =
        sysPropBooleanFlag(1201, "persist.wm.debug.predictive_back_anim", default = false)

    @Keep
    @JvmField
    val WM_ALWAYS_ENFORCE_PREDICTIVE_BACK =
        sysPropBooleanFlag(1202, "persist.wm.debug.predictive_back_always_enforce", default = false)

    // TODO(b/254512728): Tracking Bug
    @JvmField
    val NEW_BACK_AFFORDANCE = unreleasedFlag(1203, "new_back_affordance", teamfood = false)

    // TODO(b/255854141): Tracking Bug
    @JvmField
    val WM_ENABLE_PREDICTIVE_BACK_SYSUI =
        unreleasedFlag(1204, "persist.wm.debug.predictive_back_sysui_enable", teamfood = true)

    // TODO(b/255697805): Tracking Bug
    @JvmField
    val TRACKPAD_GESTURE_BACK = unreleasedFlag(1205, "trackpad_gesture_back", teamfood = false)

    // TODO(b/263826204): Tracking Bug
    @JvmField
    val WM_ENABLE_PREDICTIVE_BACK_BOUNCER_ANIM =
        unreleasedFlag(1206, "persist.wm.debug.predictive_back_bouncer_anim", teamfood = true)

    // 1300 - screenshots
    // TODO(b/254513155): Tracking Bug
    @JvmField
    val SCREENSHOT_WORK_PROFILE_POLICY =
        unreleasedFlag(1301, "screenshot_work_profile_policy", teamfood = true)

    // 1400 - columbus
    // TODO(b/254512756): Tracking Bug
    val QUICK_TAP_IN_PCC = releasedFlag(1400, "quick_tap_in_pcc")

    // TODO(b/261979569): Tracking Bug
    val QUICK_TAP_FLOW_FRAMEWORK =
        unreleasedFlag(1401, "quick_tap_flow_framework", teamfood = false)

    // 1500 - chooser
    // TODO(b/254512507): Tracking Bug
    val CHOOSER_UNBUNDLED = unreleasedFlag(1500, "chooser_unbundled", teamfood = true)

    // 1600 - accessibility
    // TODO(b/262224538): Tracking Bug
    @JvmField
    val A11Y_FLOATING_MENU_FLING_SPRING_ANIMATIONS =
        releasedFlag(1600, "a11y_floating_menu_fling_spring_animations")

    // 1700 - clipboard
    @JvmField val CLIPBOARD_OVERLAY_REFACTOR = releasedFlag(1700, "clipboard_overlay_refactor")
    @JvmField val CLIPBOARD_REMOTE_BEHAVIOR = releasedFlag(1701, "clipboard_remote_behavior")

    // 1800 - shade container
    @JvmField
    val LEAVE_SHADE_OPEN_FOR_BUGREPORT =
        unreleasedFlag(1800, "leave_shade_open_for_bugreport", teamfood = true)

    // 1900
    @JvmField val NOTE_TASKS = unreleasedFlag(1900, "keycode_flag")

    // 2000 - device controls
    @Keep @JvmField val USE_APP_PANELS = releasedFlag(2000, "use_app_panels", teamfood = true)

    @JvmField
    val APP_PANELS_ALL_APPS_ALLOWED =
        releasedFlag(2001, "app_panels_all_apps_allowed", teamfood = true)

    // 2100 - Falsing Manager
    @JvmField val FALSING_FOR_LONG_TAPS = releasedFlag(2100, "falsing_for_long_taps")

    // 2200 - udfps
    // TODO(b/259264861): Tracking Bug
    @JvmField val UDFPS_NEW_TOUCH_DETECTION = unreleasedFlag(2200, "udfps_new_touch_detection")
    @JvmField val UDFPS_ELLIPSE_DEBUG_UI = unreleasedFlag(2201, "udfps_ellipse_debug")
    @JvmField val UDFPS_ELLIPSE_DETECTION = unreleasedFlag(2202, "udfps_ellipse_detection")

    // 2300 - stylus
    @JvmField val TRACK_STYLUS_EVER_USED = unreleasedFlag(2300, "track_stylus_ever_used")
    @JvmField val ENABLE_STYLUS_CHARGING_UI = unreleasedFlag(2301, "enable_stylus_charging_ui")
    @JvmField
    val ENABLE_USI_BATTERY_NOTIFICATIONS = unreleasedFlag(2302, "enable_usi_battery_notifications")
    @JvmField val ENABLE_STYLUS_EDUCATION = unreleasedFlag(2303, "enable_stylus_education")

    // 2400 - performance tools and debugging info
    // TODO(b/238923086): Tracking Bug
    @JvmField
    val WARN_ON_BLOCKING_BINDER_TRANSACTIONS =
        unreleasedFlag(2400, "warn_on_blocking_binder_transactions")

    // 2500 - output switcher
    // TODO(b/261538825): Tracking Bug
    @JvmField
    val OUTPUT_SWITCHER_ADVANCED_LAYOUT = unreleasedFlag(2500, "output_switcher_advanced_layout")
    @JvmField
    val OUTPUT_SWITCHER_ROUTES_PROCESSING =
        unreleasedFlag(2501, "output_switcher_routes_processing")
    @JvmField
    val OUTPUT_SWITCHER_DEVICE_STATUS = unreleasedFlag(2502, "output_switcher_device_status")

    // TODO(b259590361): Tracking bug
    val EXPERIMENTAL_FLAG = unreleasedFlag(2, "exp_flag_release")
}
