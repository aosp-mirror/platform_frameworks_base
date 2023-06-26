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

    // TODO(b/254512538): Tracking Bug
    val INSTANT_VOICE_REPLY = unreleasedFlag(111, "instant_voice_reply")

    // TODO(b/279735475): Tracking Bug
    @JvmField
    val NEW_LIGHT_BAR_LOGIC = releasedFlag(279735475, "new_light_bar_logic")

    /**
     * This flag is server-controlled and should stay as [unreleasedFlag] since we never want to
     * enable it on release builds.
     */
    val NOTIFICATION_MEMORY_LOGGING_ENABLED =
        unreleasedFlag(119, "notification_memory_logging_enabled")

    // TODO(b/257315550): Tracking Bug
    val NO_HUN_FOR_OLD_WHEN = releasedFlag(118, "no_hun_for_old_when")

    // TODO(b/260335638): Tracking Bug
    @JvmField
    val NOTIFICATION_INLINE_REPLY_ANIMATION =
        unreleasedFlag(174148361, "notification_inline_reply_animation")

    /** Makes sure notification panel is updated before the user switch is complete. */
    // TODO(b/278873737): Tracking Bug
    @JvmField
    val LOAD_NOTIFICATIONS_BEFORE_THE_USER_SWITCH_IS_COMPLETE =
            releasedFlag(278873737, "load_notifications_before_the_user_switch_is_complete")

    // TODO(b/277338665): Tracking Bug
    @JvmField
    val NOTIFICATION_SHELF_REFACTOR =
        unreleasedFlag(271161129, "notification_shelf_refactor", teamfood = true)

    @JvmField
    val ANIMATED_NOTIFICATION_SHADE_INSETS =
        releasedFlag(270682168, "animated_notification_shade_insets")

    // TODO(b/268005230): Tracking Bug
    @JvmField
    val SENSITIVE_REVEAL_ANIM =
        unreleasedFlag(268005230, "sensitive_reveal_anim", teamfood = true)

    // TODO(b/280783617): Tracking Bug
    @Keep
    @JvmField
    val BUILDER_EXTRAS_OVERRIDE =
            sysPropBooleanFlag(
                    128,
                    "persist.sysui.notification.builder_extras_override",
                    default = false
            )

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
    val LOCKSCREEN_CUSTOM_CLOCKS = resourceBooleanFlag(
        207,
        R.bool.config_enableLockScreenCustomClocks,
        "lockscreen_custom_clocks"
    )

    // TODO(b/275694445): Tracking Bug
    @JvmField
    val LOCKSCREEN_WITHOUT_SECURE_LOCK_WHEN_DREAMING = releasedFlag(208,
        "lockscreen_without_secure_lock_when_dreaming")

    /**
     * Whether the clock on a wide lock screen should use the new "stepping" animation for moving
     * the digits when the clock moves.
     */
    @JvmField
    val STEP_CLOCK_ANIMATION = releasedFlag(212, "step_clock_animation")

    /**
     * Migration from the legacy isDozing/dozeAmount paths to the new KeyguardTransitionRepository
     * will occur in stages. This is one stage of many to come.
     */
    // TODO(b/255607168): Tracking Bug
    @JvmField val DOZING_MIGRATION_1 = unreleasedFlag(213, "dozing_migration_1")

    /**
     * Whether to enable the code powering customizable lock screen quick affordances.
     *
     * This flag enables any new prebuilt quick affordances as well.
     */
    // TODO(b/255618149): Tracking Bug
    @JvmField
    val CUSTOMIZABLE_LOCK_SCREEN_QUICK_AFFORDANCES =
        releasedFlag(216, "customizable_lock_screen_quick_affordances")

    /**
     * Migrates control of the LightRevealScrim's reveal effect and amount from legacy code to the
     * new KeyguardTransitionRepository.
     */
    // TODO(b/281655028): Tracking bug
    @JvmField
    val LIGHT_REVEAL_MIGRATION = unreleasedFlag(218, "light_reveal_migration", teamfood = false)

    /** Flag to control the migration of face auth to modern architecture. */
    // TODO(b/262838215): Tracking bug
    @JvmField val FACE_AUTH_REFACTOR = unreleasedFlag(220, "face_auth_refactor")

    /** Flag to control the revamp of keyguard biometrics progress animation */
    // TODO(b/244313043): Tracking bug
    @JvmField val BIOMETRICS_ANIMATION_REVAMP = unreleasedFlag(221, "biometrics_animation_revamp")

    // TODO(b/262780002): Tracking Bug
    @JvmField
    val REVAMPED_WALLPAPER_UI = releasedFlag(222, "revamped_wallpaper_ui")

    // flag for controlling auto pin confirmation and material u shapes in bouncer
    @JvmField
    val AUTO_PIN_CONFIRMATION =
        releasedFlag(224, "auto_pin_confirmation", "auto_pin_confirmation")

    // TODO(b/262859270): Tracking Bug
    @JvmField val FALSING_OFF_FOR_UNFOLDED = releasedFlag(225, "falsing_off_for_unfolded")

    /** Enables code to show contextual loyalty cards in wallet entrypoints */
    // TODO(b/247587924): Tracking Bug
    @JvmField
    val ENABLE_WALLET_CONTEXTUAL_LOYALTY_CARDS =
        unreleasedFlag(226, "enable_wallet_contextual_loyalty_cards", teamfood = false)

    // TODO(b/242908637): Tracking Bug
    @JvmField val WALLPAPER_FULLSCREEN_PREVIEW = releasedFlag(227, "wallpaper_fullscreen_preview")

    /** Whether the long-press gesture to open wallpaper picker is enabled. */
    // TODO(b/266242192): Tracking Bug
    @JvmField
    val LOCK_SCREEN_LONG_PRESS_ENABLED =
        releasedFlag(
            228,
            "lock_screen_long_press_enabled"
        )

    /** Enables UI updates for AI wallpapers in the wallpaper picker. */
    // TODO(b/267722622): Tracking Bug
    @JvmField
    val WALLPAPER_PICKER_UI_FOR_AIWP =
            releasedFlag(
                    229,
                    "wallpaper_picker_ui_for_aiwp"
            )

    /** Whether to use a new data source for intents to run on keyguard dismissal. */
    // TODO(b/275069969): Tracking bug.
    @JvmField
    val REFACTOR_KEYGUARD_DISMISS_INTENT = unreleasedFlag(231, "refactor_keyguard_dismiss_intent")

    /** Whether to allow long-press on the lock screen to directly open wallpaper picker. */
    // TODO(b/277220285): Tracking bug.
    @JvmField
    val LOCK_SCREEN_LONG_PRESS_DIRECT_TO_WPP =
        unreleasedFlag(232, "lock_screen_long_press_directly_opens_wallpaper_picker")

    /** Whether to run the new udfps keyguard refactor code. */
    // TODO(b/279440316): Tracking bug.
    @JvmField
    val REFACTOR_UDFPS_KEYGUARD_VIEWS = unreleasedFlag(233, "refactor_udfps_keyguard_views")

    /** Provide new auth messages on the bouncer. */
    // TODO(b/277961132): Tracking bug.
    @JvmField
    val REVAMPED_BOUNCER_MESSAGES =
        unreleasedFlag(234, "revamped_bouncer_messages")

    /** Whether to delay showing bouncer UI when face auth or active unlock are enrolled. */
    // TODO(b/279794160): Tracking bug.
    @JvmField
    val DELAY_BOUNCER = unreleasedFlag(235, "delay_bouncer", teamfood = true)


    /** Keyguard Migration */

    /** Migrate the indication area to the new keyguard root view. */
    // TODO(b/280067944): Tracking bug.
    @JvmField
    val MIGRATE_INDICATION_AREA = unreleasedFlag(236, "migrate_indication_area", teamfood = true)

    /** Migrate the lock icon view to the new keyguard root view. */
    // TODO(b/286552209): Tracking bug.
    @JvmField
    val MIGRATE_LOCK_ICON = unreleasedFlag(238, "migrate_lock_icon")

    /** Whether to listen for fingerprint authentication over keyguard occluding activities. */
    // TODO(b/283260512): Tracking bug.
    @JvmField
    val FP_LISTEN_OCCLUDING_APPS = unreleasedFlag(237, "fp_listen_occluding_apps")

    /** Flag meant to guard the talkback fix for the KeyguardIndicationTextView */
    // TODO(b/286563884): Tracking bug
    @JvmField
    val KEYGUARD_TALKBACK_FIX = releasedFlag(238, "keyguard_talkback_fix")

    // TODO(b/287268101): Tracking bug.
    @JvmField
    val TRANSIT_CLOCK = unreleasedFlag(239, "lockscreen_custom_transit_clock")

    // 300 - power menu
    // TODO(b/254512600): Tracking Bug
    @JvmField val POWER_MENU_LITE = releasedFlag(300, "power_menu_lite")

    // 400 - smartspace

    // TODO(b/254513100): Tracking Bug
    val SMARTSPACE_SHARED_ELEMENT_TRANSITION_ENABLED =
        releasedFlag(401, "smartspace_shared_element_transition_enabled")

    // TODO(b/258517050): Clean up after the feature is launched.
    @JvmField
    val SMARTSPACE_DATE_WEATHER_DECOUPLED =
        sysPropBooleanFlag(403, "persist.sysui.ss.dw_decoupled", default = true)

    // TODO(b/270223352): Tracking Bug
    @JvmField
    val HIDE_SMARTSPACE_ON_DREAM_OVERLAY =
        releasedFlag(404, "hide_smartspace_on_dream_overlay")

    // TODO(b/271460958): Tracking Bug
    @JvmField
    val SHOW_WEATHER_COMPLICATION_ON_DREAM_OVERLAY =
        releasedFlag(405, "show_weather_complication_on_dream_overlay")

    // 500 - quick settings

    val PEOPLE_TILE = resourceBooleanFlag(502, R.bool.flag_conversations, "people_tile")

    @JvmField
    val QS_USER_DETAIL_SHORTCUT =
        resourceBooleanFlag(
            503,
            R.bool.flag_lockscreen_qs_user_detail_shortcut,
            "qs_user_detail_shortcut"
        )

    @JvmField
    val QS_PIPELINE_NEW_HOST = releasedFlag(504, "qs_pipeline_new_host")

    // TODO(b/278068252): Tracking Bug
    @JvmField
    val QS_PIPELINE_AUTO_ADD = unreleasedFlag(505, "qs_pipeline_auto_add", teamfood = false)

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

    /** Enables Font Scaling Quick Settings tile */
    // TODO(b/269341316): Tracking Bug
    @JvmField
    val ENABLE_FONT_SCALING_TILE = releasedFlag(509, "enable_font_scaling_tile")

    /** Enables new QS Edit Mode visual refresh */
    // TODO(b/269787742): Tracking Bug
    @JvmField
    val ENABLE_NEW_QS_EDIT_MODE = unreleasedFlag(510, "enable_new_qs_edit_mode", teamfood = false)

    // 600- status bar

    // TODO(b/256614753): Tracking Bug
    val NEW_STATUS_BAR_MOBILE_ICONS = releasedFlag(606, "new_status_bar_mobile_icons")

    // TODO(b/256614210): Tracking Bug
    val NEW_STATUS_BAR_WIFI_ICON = releasedFlag(607, "new_status_bar_wifi_icon")

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

    // TODO(b/265892345): Tracking Bug
    val PLUG_IN_STATUS_BAR_CHIP = releasedFlag(265892345, "plug_in_status_bar_chip")

    // TODO(b/280426085): Tracking Bug
    @JvmField
    val NEW_BLUETOOTH_REPOSITORY = releasedFlag(612, "new_bluetooth_repository")

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
    val REGION_SAMPLING = unreleasedFlag(801, "region_sampling")

    // 803 - screen contents translation
    // TODO(b/254513187): Tracking Bug
    val SCREEN_CONTENTS_TRANSLATION = unreleasedFlag(803, "screen_contents_translation")

    // 804 - monochromatic themes
    @JvmField val MONOCHROMATIC_THEME = releasedFlag(804, "monochromatic")

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
    @JvmField val UMO_SURFACE_RIPPLE = releasedFlag(907, "umo_surface_ripple")

    // TODO(b/261734857): Tracking Bug
    @JvmField val UMO_TURBULENCE_NOISE = releasedFlag(909, "umo_turbulence_noise")

    // TODO(b/263272731): Tracking Bug
    val MEDIA_TTT_RECEIVER_SUCCESS_RIPPLE = releasedFlag(910, "media_ttt_receiver_success_ripple")

    // TODO(b/263512203): Tracking Bug
    val MEDIA_EXPLICIT_INDICATOR = releasedFlag(911, "media_explicit_indicator")

    // TODO(b/265813373): Tracking Bug
    val MEDIA_TAP_TO_TRANSFER_DISMISS_GESTURE = releasedFlag(912, "media_ttt_dismiss_gesture")

    // TODO(b/266157412): Tracking Bug
    val MEDIA_RETAIN_SESSIONS = unreleasedFlag(913, "media_retain_sessions")

    // TODO(b/266739309): Tracking Bug
    @JvmField
    val MEDIA_RECOMMENDATION_CARD_UPDATE = releasedFlag(914, "media_recommendation_card_update")

    // TODO(b/267007629): Tracking Bug
    val MEDIA_RESUME_PROGRESS = releasedFlag(915, "media_resume_progress")

    // TODO(b/267166152) : Tracking Bug
    val MEDIA_RETAIN_RECOMMENDATIONS = unreleasedFlag(916, "media_retain_recommendations")

    // TODO(b/270437894): Tracking Bug
    val MEDIA_REMOTE_RESUME = unreleasedFlag(917, "media_remote_resume")

    // 1000 - dock
    val SIMULATE_DOCK_THROUGH_CHARGING = releasedFlag(1000, "simulate_dock_through_charging")

    // TODO(b/254512758): Tracking Bug
    @JvmField val ROUNDED_BOX_RIPPLE = releasedFlag(1002, "rounded_box_ripple")

    // TODO(b/273509374): Tracking Bug
    @JvmField
    val ALWAYS_SHOW_HOME_CONTROLS_ON_DREAMS = releasedFlag(1006,
        "always_show_home_controls_on_dreams")

    // 1100 - windowing
    @Keep
    @JvmField
    val WM_ENABLE_SHELL_TRANSITIONS =
        sysPropBooleanFlag(1100, "persist.wm.debug.shell_transit", default = true)

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
        sysPropBooleanFlag(1105, "persist.wm.debug.caption_on_shell", default = true)

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
            default = true
        )

    // TODO(b/256873975): Tracking Bug
    @JvmField
    @Keep
    val WM_BUBBLE_BAR = sysPropBooleanFlag(1111, "persist.wm.debug.bubble_bar", default = false)

    // TODO(b/260271148): Tracking bug
    @Keep
    @JvmField
    val WM_DESKTOP_WINDOWING_2 =
        sysPropBooleanFlag(1112, "persist.wm.debug.desktop_mode_2", default = false)

    // TODO(b/254513207): Tracking Bug to delete
    @Keep
    @JvmField
    val WM_ENABLE_PARTIAL_SCREEN_SHARING_ENTERPRISE_POLICIES =
        unreleasedFlag(
            1113,
            name = "screen_record_enterprise_policies",
            namespace = DeviceConfig.NAMESPACE_WINDOW_MANAGER,
            teamfood = false
        )

    // TODO(b/198643358): Tracking bug
    @Keep
    @JvmField
    val ENABLE_PIP_SIZE_LARGE_SCREEN =
        sysPropBooleanFlag(1114, "persist.wm.debug.enable_pip_size_large_screen", default = true)

    // TODO(b/265998256): Tracking bug
    @Keep
    @JvmField
    val ENABLE_PIP_APP_ICON_OVERLAY =
        sysPropBooleanFlag(1115, "persist.wm.debug.enable_pip_app_icon_overlay", default = true)

    // TODO(b/273443374): Tracking Bug
    @Keep
    @JvmField val LOCKSCREEN_LIVE_WALLPAPER =
        sysPropBooleanFlag(1117, "persist.wm.debug.lockscreen_live_wallpaper", default = true)

    // TODO(b/281648899): Tracking bug
    @Keep
    @JvmField
    val WALLPAPER_MULTI_CROP =
        sysPropBooleanFlag(1118, "persist.wm.debug.wallpaper_multi_crop", default = false)


    // 1200 - predictive back
    @Keep
    @JvmField
    val WM_ENABLE_PREDICTIVE_BACK =
        sysPropBooleanFlag(1200, "persist.wm.debug.predictive_back", default = true)

    @Keep
    @JvmField
    val WM_ENABLE_PREDICTIVE_BACK_ANIM =
        sysPropBooleanFlag(1201, "persist.wm.debug.predictive_back_anim", default = true)

    @Keep
    @JvmField
    val WM_ALWAYS_ENFORCE_PREDICTIVE_BACK =
        sysPropBooleanFlag(1202, "persist.wm.debug.predictive_back_always_enforce", default = false)

    // TODO(b/254512728): Tracking Bug
    @JvmField val NEW_BACK_AFFORDANCE = releasedFlag(1203, "new_back_affordance")

    // TODO(b/255854141): Tracking Bug
    @JvmField
    val WM_ENABLE_PREDICTIVE_BACK_SYSUI =
        unreleasedFlag(1204, "persist.wm.debug.predictive_back_sysui_enable", teamfood = true)

    // TODO(b/270987164): Tracking Bug
    @JvmField
    val TRACKPAD_GESTURE_FEATURES = releasedFlag(1205, "trackpad_gesture_features")

    // TODO(b/263826204): Tracking Bug
    @JvmField
    val WM_ENABLE_PREDICTIVE_BACK_BOUNCER_ANIM =
        unreleasedFlag(1206, "persist.wm.debug.predictive_back_bouncer_anim", teamfood = true)

    // TODO(b/238475428): Tracking Bug
    @JvmField
    val WM_SHADE_ALLOW_BACK_GESTURE =
        sysPropBooleanFlag(1207, "persist.wm.debug.shade_allow_back_gesture", default = false)

    // TODO(b/238475428): Tracking Bug
    @JvmField
    val WM_SHADE_ANIMATE_BACK_GESTURE =
        unreleasedFlag(1208, "persist.wm.debug.shade_animate_back_gesture", teamfood = false)

    // TODO(b/265639042): Tracking Bug
    @JvmField
    val WM_ENABLE_PREDICTIVE_BACK_QS_DIALOG_ANIM =
        unreleasedFlag(1209, "persist.wm.debug.predictive_back_qs_dialog_anim", teamfood = true)

    // TODO(b/273800936): Tracking Bug
    @JvmField
    val TRACKPAD_GESTURE_COMMON = releasedFlag(1210, "trackpad_gesture_common")

    // 1300 - screenshots
    // TODO(b/264916608): Tracking Bug
    @JvmField val SCREENSHOT_METADATA = unreleasedFlag(1302, "screenshot_metadata")

    // TODO(b/266955521): Tracking bug
    @JvmField val SCREENSHOT_DETECTION = releasedFlag(1303, "screenshot_detection")

    // TODO(b/251205791): Tracking Bug
    @JvmField val SCREENSHOT_APP_CLIPS = releasedFlag(1304, "screenshot_app_clips")

    // 1400 - columbus
    // TODO(b/254512756): Tracking Bug
    val QUICK_TAP_IN_PCC = releasedFlag(1400, "quick_tap_in_pcc")

    // TODO(b/261979569): Tracking Bug
    val QUICK_TAP_FLOW_FRAMEWORK =
        unreleasedFlag(1401, "quick_tap_flow_framework", teamfood = false)

    // 1500 - chooser aka sharesheet

    // 1700 - clipboard
    @JvmField val CLIPBOARD_REMOTE_BEHAVIOR = releasedFlag(1701, "clipboard_remote_behavior")
    // TODO(b/278714186) Tracking Bug
    @JvmField val CLIPBOARD_IMAGE_TIMEOUT =
            unreleasedFlag(1702, "clipboard_image_timeout", teamfood = true)
    // TODO(b/279405451): Tracking Bug
    @JvmField
    val CLIPBOARD_SHARED_TRANSITIONS = unreleasedFlag(1703, "clipboard_shared_transitions")

    // 1800 - shade container
    // TODO(b/265944639): Tracking Bug
    @JvmField val DUAL_SHADE = unreleasedFlag(1801, "dual_shade")

    // TODO(b/283300105): Tracking Bug
    @JvmField val SCENE_CONTAINER = unreleasedFlag(1802, "scene_container")

    // 1900
    @JvmField val NOTE_TASKS = releasedFlag(1900, "keycode_flag")

    // 2000 - device controls
    @Keep @JvmField val USE_APP_PANELS = releasedFlag(2000, "use_app_panels")

    @JvmField
    val APP_PANELS_ALL_APPS_ALLOWED =
        releasedFlag(2001, "app_panels_all_apps_allowed")

    @JvmField
    val CONTROLS_MANAGEMENT_NEW_FLOWS =
        releasedFlag(2002, "controls_management_new_flows")

    // Enables removing app from Home control panel as a part of a new flow
    // TODO(b/269132640): Tracking Bug
    @JvmField
    val APP_PANELS_REMOVE_APPS_ALLOWED =
        releasedFlag(2003, "app_panels_remove_apps_allowed")

    // 2200 - biometrics (udfps, sfps, BiometricPrompt, etc.)
    // TODO(b/259264861): Tracking Bug
    @JvmField val UDFPS_NEW_TOUCH_DETECTION = releasedFlag(2200, "udfps_new_touch_detection")
    @JvmField val UDFPS_ELLIPSE_DETECTION = releasedFlag(2201, "udfps_ellipse_detection")
    // TODO(b/278622168): Tracking Bug
    @JvmField val BIOMETRIC_BP_STRONG = releasedFlag(2202, "biometric_bp_strong")

    // 2300 - stylus
    @JvmField val TRACK_STYLUS_EVER_USED = releasedFlag(2300, "track_stylus_ever_used")
    @JvmField
    val ENABLE_STYLUS_CHARGING_UI =
        unreleasedFlag(2301, "enable_stylus_charging_ui", teamfood = true)
    @JvmField
    val ENABLE_USI_BATTERY_NOTIFICATIONS =
        unreleasedFlag(2302, "enable_usi_battery_notifications", teamfood = true)
    @JvmField val ENABLE_STYLUS_EDUCATION =
        unreleasedFlag(2303, "enable_stylus_education", teamfood = true)

    // 2400 - performance tools and debugging info
    // TODO(b/238923086): Tracking Bug
    @JvmField
    val WARN_ON_BLOCKING_BINDER_TRANSACTIONS =
        unreleasedFlag(2400, "warn_on_blocking_binder_transactions")

    // TODO(b/283071711): Tracking bug
    @JvmField
    val TRIM_RESOURCES_WITH_BACKGROUND_TRIM_AT_LOCK =
            releasedFlag(2401, "trim_resources_with_background_trim_on_lock")

    // TODO:(b/283203305): Tracking bug
    @JvmField
    val TRIM_FONT_CACHES_AT_UNLOCK = releasedFlag(2402, "trim_font_caches_on_unlock")

    // 2700 - unfold transitions
    // TODO(b/265764985): Tracking Bug
    @Keep
    @JvmField
    val ENABLE_DARK_VIGNETTE_WHEN_FOLDING =
        unreleasedFlag(2700, "enable_dark_vignette_when_folding")

    // TODO(b/265764985): Tracking Bug
    @Keep
    @JvmField
    val ENABLE_UNFOLD_STATUS_BAR_ANIMATIONS =
        unreleasedFlag(2701, "enable_unfold_status_bar_animations")

    // TODO(b259590361): Tracking bug
    val EXPERIMENTAL_FLAG = unreleasedFlag(2, "exp_flag_release")

    // 2600 - keyboard
    // TODO(b/259352579): Tracking Bug
    @JvmField val SHORTCUT_LIST_SEARCH_LAYOUT = releasedFlag(2600, "shortcut_list_search_layout")

    // TODO(b/259428678): Tracking Bug
    @JvmField
    val KEYBOARD_BACKLIGHT_INDICATOR = releasedFlag(2601, "keyboard_backlight_indicator")

    // TODO(b/277192623): Tracking Bug
    @JvmField
    val KEYBOARD_EDUCATION =
        unreleasedFlag(2603, "keyboard_education", teamfood = false)

    // TODO(b/277201412): Tracking Bug
    @JvmField
    val SPLIT_SHADE_SUBPIXEL_OPTIMIZATION =
            releasedFlag(2805, "split_shade_subpixel_optimization")

    // TODO(b/288868056): Tracking Bug
    @JvmField
    val PARTIAL_SCREEN_SHARING_TASK_SWITCHER =
            unreleasedFlag(288868056, "pss_task_switcher")

    // TODO(b/278761837): Tracking Bug
    @JvmField
    val USE_NEW_ACTIVITY_STARTER = releasedFlag(2801, name = "use_new_activity_starter")

    // 2900 - Zero Jank fixes. Naming convention is: zj_<bug number>_<cuj name>

    // TODO:(b/285623104): Tracking bug
    @JvmField
    val ZJ_285570694_LOCKSCREEN_TRANSITION_FROM_AOD =
        releasedFlag(2900, "zj_285570694_lockscreen_transition_from_aod")

    // 3000 - dream
    // TODO(b/285059790) : Tracking Bug
    @JvmField
    val LOCKSCREEN_WALLPAPER_DREAM_ENABLED =
        unreleasedFlag(3000, name = "enable_lockscreen_wallpaper_dream")

    // TODO(b/283084712): Tracking Bug
    @JvmField
    val IMPROVED_HUN_ANIMATIONS = unreleasedFlag(283084712, "improved_hun_animations")

    // TODO(b/283447257): Tracking bug
    @JvmField
    val BIGPICTURE_NOTIFICATION_LAZY_LOADING =
            unreleasedFlag(283447257, "bigpicture_notification_lazy_loading")

    // TODO(b/283740863): Tracking Bug
    @JvmField
    val ENABLE_NEW_PRIVACY_DIALOG =
            unreleasedFlag(283740863, "enable_new_privacy_dialog", teamfood = false)

    // 2900 - CentralSurfaces-related flags

    // TODO(b/285174336): Tracking Bug
    @JvmField
    val USE_REPOS_FOR_BOUNCER_SHOWING = unreleasedFlag(2900, "use_repos_for_bouncer_showing")
}
