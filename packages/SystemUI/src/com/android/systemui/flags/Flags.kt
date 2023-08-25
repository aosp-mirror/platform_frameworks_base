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
    @JvmField val TEAMFOOD = unreleasedFlag("teamfood")

    // 100 - notification
    // TODO(b/254512751): Tracking Bug
    val NOTIFICATION_PIPELINE_DEVELOPER_LOGGING =
        unreleasedFlag("notification_pipeline_developer_logging")

    // TODO(b/254512732): Tracking Bug
    @JvmField val NSSL_DEBUG_LINES = unreleasedFlag("nssl_debug_lines")

    // TODO(b/254512505): Tracking Bug
    @JvmField val NSSL_DEBUG_REMOVE_ANIMATION = unreleasedFlag("nssl_debug_remove_animation")

    // TODO(b/254512624): Tracking Bug
    @JvmField
    val NOTIFICATION_DRAG_TO_CONTENTS =
        resourceBooleanFlag(
            R.bool.config_notificationToContents,
            "notification_drag_to_contents"
        )

    // TODO(b/254512538): Tracking Bug
    val INSTANT_VOICE_REPLY = unreleasedFlag("instant_voice_reply")

    /**
     * This flag controls whether we register a listener for StatsD notification memory reports.
     * For statsd to actually call the listener however, a server-side toggle needs to be
     * enabled as well.
     */
    val NOTIFICATION_MEMORY_LOGGING_ENABLED =
            releasedFlag("notification_memory_logging_enabled")

    // TODO(b/260335638): Tracking Bug
    @JvmField
    val NOTIFICATION_INLINE_REPLY_ANIMATION =
        unreleasedFlag("notification_inline_reply_animation")

    /** Makes sure notification panel is updated before the user switch is complete. */
    // TODO(b/278873737): Tracking Bug
    @JvmField
    val LOAD_NOTIFICATIONS_BEFORE_THE_USER_SWITCH_IS_COMPLETE =
        releasedFlag("load_notifications_before_the_user_switch_is_complete")

    // TODO(b/277338665): Tracking Bug
    @JvmField
    val NOTIFICATION_SHELF_REFACTOR =
        unreleasedFlag("notification_shelf_refactor", teamfood = true)

    // TODO(b/290787599): Tracking Bug
    @JvmField
    val NOTIFICATION_ICON_CONTAINER_REFACTOR =
        unreleasedFlag("notification_icon_container_refactor")

    // TODO(b/288326013): Tracking Bug
    @JvmField
    val NOTIFICATION_ASYNC_HYBRID_VIEW_INFLATION =
        unreleasedFlag("notification_async_hybrid_view_inflation", teamfood = false)

    @JvmField
    val ANIMATED_NOTIFICATION_SHADE_INSETS =
        releasedFlag("animated_notification_shade_insets")

    // TODO(b/268005230): Tracking Bug
    @JvmField
    val SENSITIVE_REVEAL_ANIM = unreleasedFlag("sensitive_reveal_anim", teamfood = true)

    // TODO(b/280783617): Tracking Bug
    @Keep
    @JvmField
    val BUILDER_EXTRAS_OVERRIDE =
        sysPropBooleanFlag(
            "persist.sysui.notification.builder_extras_override",
            default = true
        )

    /** Only notify group expansion listeners when a change happens. */
    // TODO(b/292213543): Tracking Bug
    @JvmField
    val NOTIFICATION_GROUP_EXPANSION_CHANGE =
            unreleasedFlag("notification_group_expansion_change", teamfood = true)

    // 200 - keyguard/lockscreen
    // ** Flag retired **
    // public static final BooleanFlag KEYGUARD_LAYOUT =
    //         new BooleanFlag(true);

    // TODO(b/254512750): Tracking Bug
    val NEW_UNLOCK_SWIPE_ANIMATION = releasedFlag("new_unlock_swipe_animation")
    val CHARGING_RIPPLE = resourceBooleanFlag(R.bool.flag_charging_ripple, "charging_ripple")

    // TODO(b/254512281): Tracking Bug
    @JvmField
    val BOUNCER_USER_SWITCHER =
        resourceBooleanFlag(R.bool.config_enableBouncerUserSwitcher, "bouncer_user_switcher")

    // TODO(b/254512676): Tracking Bug
    @JvmField
    val LOCKSCREEN_CUSTOM_CLOCKS =
        resourceBooleanFlag(
            R.bool.config_enableLockScreenCustomClocks,
            "lockscreen_custom_clocks"
        )

    // TODO(b/275694445): Tracking Bug
    @JvmField
    val LOCKSCREEN_WITHOUT_SECURE_LOCK_WHEN_DREAMING =
        releasedFlag("lockscreen_without_secure_lock_when_dreaming")

    // TODO(b/286092087): Tracking Bug
    @JvmField
    val ENABLE_SYSTEM_UI_DREAM_CONTROLLER = unreleasedFlag("enable_system_ui_dream_controller")

    // TODO(b/288287730): Tracking Bug
    @JvmField
    val ENABLE_SYSTEM_UI_DREAM_HOSTING = unreleasedFlag("enable_system_ui_dream_hosting")

    /**
     * Whether the clock on a wide lock screen should use the new "stepping" animation for moving
     * the digits when the clock moves.
     */
    @JvmField val STEP_CLOCK_ANIMATION = releasedFlag("step_clock_animation")

    /**
     * Migration from the legacy isDozing/dozeAmount paths to the new KeyguardTransitionRepository
     * will occur in stages. This is one stage of many to come.
     */
    // TODO(b/255607168): Tracking Bug
    @JvmField val DOZING_MIGRATION_1 = unreleasedFlag("dozing_migration_1")

    /**
     * Migrates control of the LightRevealScrim's reveal effect and amount from legacy code to the
     * new KeyguardTransitionRepository.
     */
    // TODO(b/281655028): Tracking bug
    @JvmField
    val LIGHT_REVEAL_MIGRATION = unreleasedFlag("light_reveal_migration", teamfood = false)

    /** Flag to control the migration of face auth to modern architecture. */
    // TODO(b/262838215): Tracking bug
    @JvmField val FACE_AUTH_REFACTOR = unreleasedFlag("face_auth_refactor")

    /** Flag to control the revamp of keyguard biometrics progress animation */
    // TODO(b/244313043): Tracking bug
    @JvmField val BIOMETRICS_ANIMATION_REVAMP = unreleasedFlag("biometrics_animation_revamp")

    // TODO(b/262780002): Tracking Bug
    @JvmField val REVAMPED_WALLPAPER_UI = releasedFlag("revamped_wallpaper_ui")

    // flag for controlling auto pin confirmation and material u shapes in bouncer
    @JvmField
    val AUTO_PIN_CONFIRMATION = releasedFlag("auto_pin_confirmation", "auto_pin_confirmation")

    // TODO(b/262859270): Tracking Bug
    @JvmField val FALSING_OFF_FOR_UNFOLDED = releasedFlag("falsing_off_for_unfolded")

    /** Enables code to show contextual loyalty cards in wallet entrypoints */
    // TODO(b/294110497): Tracking Bug
    @JvmField
    val ENABLE_WALLET_CONTEXTUAL_LOYALTY_CARDS =
        releasedFlag("enable_wallet_contextual_loyalty_cards")

    // TODO(b/242908637): Tracking Bug
    @JvmField val WALLPAPER_FULLSCREEN_PREVIEW = releasedFlag("wallpaper_fullscreen_preview")

    /** Whether the long-press gesture to open wallpaper picker is enabled. */
    // TODO(b/266242192): Tracking Bug
    @JvmField
    val LOCK_SCREEN_LONG_PRESS_ENABLED = releasedFlag("lock_screen_long_press_enabled")

    /** Enables UI updates for AI wallpapers in the wallpaper picker. */
    // TODO(b/267722622): Tracking Bug
    @JvmField val WALLPAPER_PICKER_UI_FOR_AIWP = releasedFlag("wallpaper_picker_ui_for_aiwp")

    /** Whether to use a new data source for intents to run on keyguard dismissal. */
    // TODO(b/275069969): Tracking bug.
    @JvmField
    val REFACTOR_KEYGUARD_DISMISS_INTENT = unreleasedFlag("refactor_keyguard_dismiss_intent")

    /** Whether to allow long-press on the lock screen to directly open wallpaper picker. */
    // TODO(b/277220285): Tracking bug.
    @JvmField
    val LOCK_SCREEN_LONG_PRESS_DIRECT_TO_WPP =
        unreleasedFlag("lock_screen_long_press_directly_opens_wallpaper_picker")

    /** Whether page transition animations in the wallpaper picker are enabled */
    // TODO(b/291710220): Tracking bug.
    @JvmField
    val WALLPAPER_PICKER_PAGE_TRANSITIONS =
        unreleasedFlag("wallpaper_picker_page_transitions")

    /** Add "Apply" button to wall paper picker's grid preview page. */
    // TODO(b/294866904): Tracking bug.
    @JvmField
    val WALLPAPER_PICKER_GRID_APPLY_BUTTON =
            unreleasedFlag("wallpaper_picker_grid_apply_button")

    /** Whether to run the new udfps keyguard refactor code. */
    // TODO(b/279440316): Tracking bug.
    @JvmField
    val REFACTOR_UDFPS_KEYGUARD_VIEWS = unreleasedFlag("refactor_udfps_keyguard_views")

    /** Provide new auth messages on the bouncer. */
    // TODO(b/277961132): Tracking bug.
    @JvmField val REVAMPED_BOUNCER_MESSAGES = unreleasedFlag("revamped_bouncer_messages")

    /** Whether to delay showing bouncer UI when face auth or active unlock are enrolled. */
    // TODO(b/279794160): Tracking bug.
    @JvmField val DELAY_BOUNCER = releasedFlag("delay_bouncer")

    /** Keyguard Migration */

    /**
     * Migrate the bottom area to the new keyguard root view. Because there is no such thing as a
     * "bottom area" after this, this also breaks it up into many smaller, modular pieces.
     */
    // TODO(b/290652751): Tracking bug.
    @JvmField
    val MIGRATE_SPLIT_KEYGUARD_BOTTOM_AREA =
        unreleasedFlag("migrate_split_keyguard_bottom_area")

    /** Whether to listen for fingerprint authentication over keyguard occluding activities. */
    // TODO(b/283260512): Tracking bug.
    @JvmField val FP_LISTEN_OCCLUDING_APPS = releasedFlag("fp_listen_occluding_apps")

    /** Flag meant to guard the talkback fix for the KeyguardIndicationTextView */
    // TODO(b/286563884): Tracking bug
    @JvmField val KEYGUARD_TALKBACK_FIX = releasedFlag("keyguard_talkback_fix")

    // TODO(b/287268101): Tracking bug.
    @JvmField val TRANSIT_CLOCK = releasedFlag("lockscreen_custom_transit_clock")

    /** Migrate the lock icon view to the new keyguard root view. */
    // TODO(b/286552209): Tracking bug.
    @JvmField val MIGRATE_LOCK_ICON = unreleasedFlag("migrate_lock_icon")

    // TODO(b/288276738): Tracking bug.
    @JvmField val WIDGET_ON_KEYGUARD = unreleasedFlag("widget_on_keyguard")

    /** Migrate the NSSL to the a sibling to both the panel and keyguard root view. */
    // TODO(b/288074305): Tracking bug.
    @JvmField val MIGRATE_NSSL = unreleasedFlag("migrate_nssl")

    /** Migrate the status view from the notification panel to keyguard root view. */
    // TODO(b/291767565): Tracking bug.
    @JvmField val MIGRATE_KEYGUARD_STATUS_VIEW = unreleasedFlag("migrate_keyguard_status_view")

    /** Enables preview loading animation in the wallpaper picker. */
    // TODO(b/274443705): Tracking Bug
    @JvmField
    val WALLPAPER_PICKER_PREVIEW_ANIMATION = releasedFlag("wallpaper_picker_preview_animation")

    /** Stop running face auth when the display state changes to OFF. */
    // TODO(b/294221702): Tracking bug.
    @JvmField val STOP_FACE_AUTH_ON_DISPLAY_OFF = resourceBooleanFlag(
            R.bool.flag_stop_face_auth_on_display_off, "stop_face_auth_on_display_off")

    /** Flag to disable the face scanning animation pulsing. */
    // TODO(b/295245791): Tracking bug.
    @JvmField val STOP_PULSING_FACE_SCANNING_ANIMATION = resourceBooleanFlag(
            R.bool.flag_stop_pulsing_face_scanning_animation,
            "stop_pulsing_face_scanning_animation")

    /**
     * TODO(b/278086361): Tracking bug
     * Complete rewrite of the interactions between System UI and Window Manager involving keyguard
     * state. When enabled, calls to ActivityTaskManagerService from System UI will exclusively
     * occur from [WmLockscreenVisibilityManager] rather than the legacy KeyguardViewMediator.
     *
     * This flag is under development; some types of unlock may not animate properly if you enable
     * it.
     */
    @JvmField
    val KEYGUARD_WM_STATE_REFACTOR: UnreleasedFlag =
            unreleasedFlag("keyguard_wm_state_refactor")

    // 300 - power menu
    // TODO(b/254512600): Tracking Bug
    @JvmField val POWER_MENU_LITE = releasedFlag("power_menu_lite")

    // 400 - smartspace

    // TODO(b/254513100): Tracking Bug
    val SMARTSPACE_SHARED_ELEMENT_TRANSITION_ENABLED =
        releasedFlag("smartspace_shared_element_transition_enabled")

    // TODO(b/258517050): Clean up after the feature is launched.
    @JvmField
    val SMARTSPACE_DATE_WEATHER_DECOUPLED =
        sysPropBooleanFlag("persist.sysui.ss.dw_decoupled", default = true)

    // TODO(b/270223352): Tracking Bug
    @JvmField
    val HIDE_SMARTSPACE_ON_DREAM_OVERLAY = releasedFlag("hide_smartspace_on_dream_overlay")

    // TODO(b/271460958): Tracking Bug
    @JvmField
    val SHOW_WEATHER_COMPLICATION_ON_DREAM_OVERLAY =
        releasedFlag("show_weather_complication_on_dream_overlay")

    // 500 - quick settings

    val PEOPLE_TILE = resourceBooleanFlag(R.bool.flag_conversations, "people_tile")

    @JvmField
    val QS_USER_DETAIL_SHORTCUT =
        resourceBooleanFlag(
            R.bool.flag_lockscreen_qs_user_detail_shortcut,
            "qs_user_detail_shortcut"
        )

    @JvmField
    val QS_PIPELINE_NEW_HOST = unreleasedFlag("qs_pipeline_new_host", teamfood = true)

    // TODO(b/278068252): Tracking Bug
    @JvmField
    val QS_PIPELINE_AUTO_ADD = unreleasedFlag("qs_pipeline_auto_add", teamfood = false)

    // TODO(b/254512383): Tracking Bug
    @JvmField
    val FULL_SCREEN_USER_SWITCHER =
        resourceBooleanFlag(
            R.bool.config_enableFullscreenUserSwitcher,
            "full_screen_user_switcher"
        )

    // TODO(b/244064524): Tracking Bug
    @JvmField val QS_SECONDARY_DATA_SUB_INFO = releasedFlag("qs_secondary_data_sub_info")

    /** Enables Font Scaling Quick Settings tile */
    // TODO(b/269341316): Tracking Bug
    @JvmField val ENABLE_FONT_SCALING_TILE = releasedFlag("enable_font_scaling_tile")

    /** Enables new QS Edit Mode visual refresh */
    // TODO(b/269787742): Tracking Bug
    @JvmField
    val ENABLE_NEW_QS_EDIT_MODE = unreleasedFlag("enable_new_qs_edit_mode", teamfood = false)

    // 600- status bar

    // TODO(b/265892345): Tracking Bug
    val PLUG_IN_STATUS_BAR_CHIP = releasedFlag("plug_in_status_bar_chip")

    // TODO(b/280426085): Tracking Bug
    @JvmField val NEW_BLUETOOTH_REPOSITORY = releasedFlag("new_bluetooth_repository")

    // TODO(b/292533677): Tracking Bug
    val WIFI_TRACKER_LIB_FOR_WIFI_ICON = releasedFlag("wifi_tracker_lib_for_wifi_icon")

    // TODO(b/293863612): Tracking Bug
    @JvmField val INCOMPATIBLE_CHARGING_BATTERY_ICON =
        releasedFlag("incompatible_charging_battery_icon")

    // TODO(b/293585143): Tracking Bug
    val INSTANT_TETHER = unreleasedFlag("instant_tether")

    // TODO(b/294588085): Tracking Bug
    val WIFI_SECONDARY_NETWORKS = releasedFlag("wifi_secondary_networks")

    // TODO(b/290676905): Tracking Bug
    val NEW_SHADE_CARRIER_GROUP_MOBILE_ICONS =
        unreleasedFlag("new_shade_carrier_group_mobile_icons")

    // 700 - dialer/calls
    // TODO(b/254512734): Tracking Bug
    val ONGOING_CALL_STATUS_BAR_CHIP = releasedFlag("ongoing_call_status_bar_chip")

    // TODO(b/254512681): Tracking Bug
    val ONGOING_CALL_IN_IMMERSIVE = releasedFlag("ongoing_call_in_immersive")

    // TODO(b/254512753): Tracking Bug
    val ONGOING_CALL_IN_IMMERSIVE_CHIP_TAP = releasedFlag("ongoing_call_in_immersive_chip_tap")

    // 800 - general visual/theme
    @JvmField val MONET = resourceBooleanFlag(R.bool.flag_monet, "monet")

    // 801 - region sampling
    // TODO(b/254512848): Tracking Bug
    val REGION_SAMPLING = unreleasedFlag("region_sampling")

    // 803 - screen contents translation
    // TODO(b/254513187): Tracking Bug
    val SCREEN_CONTENTS_TRANSLATION = unreleasedFlag("screen_contents_translation")

    // 804 - monochromatic themes
    @JvmField val MONOCHROMATIC_THEME = releasedFlag("monochromatic")

    // TODO(b/293380347): Tracking Bug
    @JvmField val COLOR_FIDELITY = unreleasedFlag("color_fidelity")

    // 900 - media
    // TODO(b/254512697): Tracking Bug
    val MEDIA_TAP_TO_TRANSFER = releasedFlag("media_tap_to_transfer")

    // TODO(b/254512502): Tracking Bug
    val MEDIA_SESSION_ACTIONS = unreleasedFlag("media_session_actions")

    // TODO(b/254512654): Tracking Bug
    @JvmField val DREAM_MEDIA_COMPLICATION = unreleasedFlag("dream_media_complication")

    // TODO(b/254512673): Tracking Bug
    @JvmField val DREAM_MEDIA_TAP_TO_OPEN = unreleasedFlag("dream_media_tap_to_open")

    // TODO(b/254513168): Tracking Bug
    @JvmField val UMO_SURFACE_RIPPLE = releasedFlag("umo_surface_ripple")

    // TODO(b/261734857): Tracking Bug
    @JvmField val UMO_TURBULENCE_NOISE = releasedFlag("umo_turbulence_noise")

    // TODO(b/263272731): Tracking Bug
    val MEDIA_TTT_RECEIVER_SUCCESS_RIPPLE = releasedFlag("media_ttt_receiver_success_ripple")

    // TODO(b/266157412): Tracking Bug
    val MEDIA_RETAIN_SESSIONS = unreleasedFlag("media_retain_sessions")

    // TODO(b/267007629): Tracking Bug
    val MEDIA_RESUME_PROGRESS = releasedFlag("media_resume_progress")

    // TODO(b/267166152) : Tracking Bug
    val MEDIA_RETAIN_RECOMMENDATIONS = unreleasedFlag("media_retain_recommendations")

    // TODO(b/270437894): Tracking Bug
    val MEDIA_REMOTE_RESUME = unreleasedFlag("media_remote_resume")

    // 1000 - dock
    val SIMULATE_DOCK_THROUGH_CHARGING = releasedFlag("simulate_dock_through_charging")

    // TODO(b/254512758): Tracking Bug
    @JvmField val ROUNDED_BOX_RIPPLE = releasedFlag("rounded_box_ripple")

    // TODO(b/273509374): Tracking Bug
    @JvmField
    val ALWAYS_SHOW_HOME_CONTROLS_ON_DREAMS =
        releasedFlag("always_show_home_controls_on_dreams")

    // 1100 - windowing
    @Keep
    @JvmField
    val WM_ENABLE_SHELL_TRANSITIONS =
        sysPropBooleanFlag("persist.wm.debug.shell_transit", default = true)

    // TODO(b/254513207): Tracking Bug
    @Keep
    @JvmField
    val WM_ENABLE_PARTIAL_SCREEN_SHARING =
        unreleasedFlag(
            name = "record_task_content",
            namespace = DeviceConfig.NAMESPACE_WINDOW_MANAGER,
            teamfood = true
        )

    // TODO(b/254512674): Tracking Bug
    @Keep
    @JvmField
    val HIDE_NAVBAR_WINDOW =
        sysPropBooleanFlag("persist.wm.debug.hide_navbar_window", default = false)

    @Keep
    @JvmField
    val WM_DESKTOP_WINDOWING =
        sysPropBooleanFlag("persist.wm.debug.desktop_mode", default = false)

    @Keep
    @JvmField
    val WM_CAPTION_ON_SHELL =
        sysPropBooleanFlag("persist.wm.debug.caption_on_shell", default = true)

    // TODO(b/256873975): Tracking Bug
    @JvmField
    @Keep
    val WM_BUBBLE_BAR = sysPropBooleanFlag("persist.wm.debug.bubble_bar", default = false)

    // TODO(b/260271148): Tracking bug
    @Keep
    @JvmField
    val WM_DESKTOP_WINDOWING_2 =
        sysPropBooleanFlag("persist.wm.debug.desktop_mode_2", default = false)

    // TODO(b/254513207): Tracking Bug to delete
    @Keep
    @JvmField
    val WM_ENABLE_PARTIAL_SCREEN_SHARING_ENTERPRISE_POLICIES =
        unreleasedFlag(
            name = "screen_record_enterprise_policies",
            namespace = DeviceConfig.NAMESPACE_WINDOW_MANAGER,
            teamfood = false
        )

    // TODO(b/293252410) : Tracking Bug
    @JvmField
    val LOCKSCREEN_ENABLE_LANDSCAPE =
            unreleasedFlag("lockscreen.enable_landscape")

    // TODO(b/273443374): Tracking Bug
    @Keep
    @JvmField
    val LOCKSCREEN_LIVE_WALLPAPER =
        sysPropBooleanFlag("persist.wm.debug.lockscreen_live_wallpaper", default = true)

    // TODO(b/281648899): Tracking bug
    @Keep
    @JvmField
    val WALLPAPER_MULTI_CROP =
        sysPropBooleanFlag("persist.wm.debug.wallpaper_multi_crop", default = false)

    // TODO(b/290220798): Tracking Bug
    @Keep
    @JvmField
    val ENABLE_PIP2_IMPLEMENTATION =
        sysPropBooleanFlag("persist.wm.debug.enable_pip2_implementation", default = false)

    // 1200 - predictive back
    @Keep
    @JvmField
    val WM_ENABLE_PREDICTIVE_BACK =
        sysPropBooleanFlag("persist.wm.debug.predictive_back", default = true)

    @Keep
    @JvmField
    val WM_ENABLE_PREDICTIVE_BACK_ANIM =
        sysPropBooleanFlag("persist.wm.debug.predictive_back_anim", default = true)

    @Keep
    @JvmField
    val WM_ALWAYS_ENFORCE_PREDICTIVE_BACK =
        sysPropBooleanFlag("persist.wm.debug.predictive_back_always_enforce", default = false)

    // TODO(b/254512728): Tracking Bug
    @JvmField val NEW_BACK_AFFORDANCE = releasedFlag("new_back_affordance")

    // TODO(b/255854141): Tracking Bug
    @JvmField
    val WM_ENABLE_PREDICTIVE_BACK_SYSUI =
        unreleasedFlag("persist.wm.debug.predictive_back_sysui_enable", teamfood = true)

    // TODO(b/270987164): Tracking Bug
    @JvmField val TRACKPAD_GESTURE_FEATURES = releasedFlag("trackpad_gesture_features")

    // TODO(b/263826204): Tracking Bug
    @JvmField
    val WM_ENABLE_PREDICTIVE_BACK_BOUNCER_ANIM =
        unreleasedFlag("persist.wm.debug.predictive_back_bouncer_anim", teamfood = true)

    // TODO(b/238475428): Tracking Bug
    @JvmField
    val WM_SHADE_ALLOW_BACK_GESTURE =
        sysPropBooleanFlag("persist.wm.debug.shade_allow_back_gesture", default = false)

    // TODO(b/238475428): Tracking Bug
    @JvmField
    val WM_SHADE_ANIMATE_BACK_GESTURE =
        unreleasedFlag("persist.wm.debug.shade_animate_back_gesture", teamfood = false)

    // TODO(b/265639042): Tracking Bug
    @JvmField
    val WM_ENABLE_PREDICTIVE_BACK_QS_DIALOG_ANIM =
        unreleasedFlag("persist.wm.debug.predictive_back_qs_dialog_anim", teamfood = true)

    // TODO(b/273800936): Tracking Bug
    @JvmField val TRACKPAD_GESTURE_COMMON = releasedFlag("trackpad_gesture_common")

    // 1300 - screenshots
    // TODO(b/264916608): Tracking Bug
    @JvmField val SCREENSHOT_METADATA = unreleasedFlag("screenshot_metadata")

    // TODO(b/266955521): Tracking bug
    @JvmField val SCREENSHOT_DETECTION = releasedFlag("screenshot_detection")

    // TODO(b/251205791): Tracking Bug
    @JvmField val SCREENSHOT_APP_CLIPS = releasedFlag("screenshot_app_clips")

    /** TODO(b/295143676): Tracking bug. When enable, captures a screenshot for each display. */
    @JvmField
    val MULTI_DISPLAY_SCREENSHOT = unreleasedFlag("multi_display_screenshot")

    // 1400 - columbus
    // TODO(b/254512756): Tracking Bug
    val QUICK_TAP_IN_PCC = releasedFlag("quick_tap_in_pcc")

    // TODO(b/261979569): Tracking Bug
    val QUICK_TAP_FLOW_FRAMEWORK =
        unreleasedFlag("quick_tap_flow_framework", teamfood = false)

    // 1500 - chooser aka sharesheet

    // 1700 - clipboard
    @JvmField val CLIPBOARD_REMOTE_BEHAVIOR = releasedFlag("clipboard_remote_behavior")
    // TODO(b/278714186) Tracking Bug
    @JvmField
    val CLIPBOARD_IMAGE_TIMEOUT = unreleasedFlag("clipboard_image_timeout", teamfood = true)
    // TODO(b/279405451): Tracking Bug
    @JvmField
    val CLIPBOARD_SHARED_TRANSITIONS =
            unreleasedFlag("clipboard_shared_transitions", teamfood = true)

    // TODO(b/283300105): Tracking Bug
    @JvmField val SCENE_CONTAINER = unreleasedFlag("scene_container")

    // 1900
    @JvmField val NOTE_TASKS = releasedFlag("keycode_flag")

    // 2000 - device controls
    @JvmField val APP_PANELS_ALL_APPS_ALLOWED = releasedFlag("app_panels_all_apps_allowed")

    // 2200 - biometrics (udfps, sfps, BiometricPrompt, etc.)
    // TODO(b/259264861): Tracking Bug
    @JvmField val UDFPS_NEW_TOUCH_DETECTION = releasedFlag("udfps_new_touch_detection")
    @JvmField val UDFPS_ELLIPSE_DETECTION = releasedFlag("udfps_ellipse_detection")
    // TODO(b/278622168): Tracking Bug
    @JvmField val BIOMETRIC_BP_STRONG = releasedFlag("biometric_bp_strong")

    // 2300 - stylus
    @JvmField val TRACK_STYLUS_EVER_USED = releasedFlag("track_stylus_ever_used")
    @JvmField val ENABLE_STYLUS_CHARGING_UI = releasedFlag("enable_stylus_charging_ui")
    @JvmField
    val ENABLE_USI_BATTERY_NOTIFICATIONS = releasedFlag("enable_usi_battery_notifications")
    @JvmField val ENABLE_STYLUS_EDUCATION = releasedFlag("enable_stylus_education")

    // 2400 - performance tools and debugging info
    // TODO(b/238923086): Tracking Bug
    @JvmField
    val WARN_ON_BLOCKING_BINDER_TRANSACTIONS =
        unreleasedFlag("warn_on_blocking_binder_transactions")

    // TODO(b/283071711): Tracking bug
    @JvmField
    val TRIM_RESOURCES_WITH_BACKGROUND_TRIM_AT_LOCK =
        unreleasedFlag("trim_resources_with_background_trim_on_lock")

    // TODO:(b/283203305): Tracking bug
    @JvmField val TRIM_FONT_CACHES_AT_UNLOCK = unreleasedFlag("trim_font_caches_on_unlock")

    // 2700 - unfold transitions
    // TODO(b/265764985): Tracking Bug
    @Keep
    @JvmField
    val ENABLE_DARK_VIGNETTE_WHEN_FOLDING =
        unreleasedFlag("enable_dark_vignette_when_folding")

    // TODO(b/265764985): Tracking Bug
    @Keep
    @JvmField
    val ENABLE_UNFOLD_STATUS_BAR_ANIMATIONS =
        unreleasedFlag("enable_unfold_status_bar_animations")

    // TODO(b259590361): Tracking bug
    val EXPERIMENTAL_FLAG = unreleasedFlag("exp_flag_release")

    // 2600 - keyboard
    // TODO(b/259352579): Tracking Bug
    @JvmField val SHORTCUT_LIST_SEARCH_LAYOUT = releasedFlag("shortcut_list_search_layout")

    // TODO(b/259428678): Tracking Bug
    @JvmField val KEYBOARD_BACKLIGHT_INDICATOR = releasedFlag("keyboard_backlight_indicator")

    // TODO(b/277192623): Tracking Bug
    @JvmField val KEYBOARD_EDUCATION = unreleasedFlag("keyboard_education", teamfood = false)

    // TODO(b/277201412): Tracking Bug
    @JvmField
    val SPLIT_SHADE_SUBPIXEL_OPTIMIZATION = releasedFlag("split_shade_subpixel_optimization")

    // TODO(b/288868056): Tracking Bug
    @JvmField
    val PARTIAL_SCREEN_SHARING_TASK_SWITCHER = unreleasedFlag("pss_task_switcher")

    // TODO(b/278761837): Tracking Bug
    @JvmField val USE_NEW_ACTIVITY_STARTER = releasedFlag(name = "use_new_activity_starter")

    // 2900 - Zero Jank fixes. Naming convention is: zj_<bug number>_<cuj name>

    // TODO:(b/285623104): Tracking bug
    @JvmField
    val ZJ_285570694_LOCKSCREEN_TRANSITION_FROM_AOD =
        releasedFlag("zj_285570694_lockscreen_transition_from_aod")

    // 3000 - dream
    // TODO(b/285059790) : Tracking Bug
    @JvmField
    val LOCKSCREEN_WALLPAPER_DREAM_ENABLED =
        unreleasedFlag(name = "enable_lockscreen_wallpaper_dream")

    // TODO(b/283084712): Tracking Bug
    @JvmField val IMPROVED_HUN_ANIMATIONS = unreleasedFlag("improved_hun_animations")

    // TODO(b/283447257): Tracking bug
    @JvmField
    val BIGPICTURE_NOTIFICATION_LAZY_LOADING =
        unreleasedFlag("bigpicture_notification_lazy_loading")

    // TODO(b/292062937): Tracking bug
    @JvmField
    val NOTIFICATION_CLEARABLE_REFACTOR =
            unreleasedFlag("notification_clearable_refactor")

    // TODO(b/283740863): Tracking Bug
    @JvmField
    val ENABLE_NEW_PRIVACY_DIALOG =
        unreleasedFlag("enable_new_privacy_dialog", teamfood = true)

    // TODO(b/289573946): Tracking Bug
    @JvmField val PRECOMPUTED_TEXT = unreleasedFlag("precomputed_text")

    // 2900 - CentralSurfaces-related flags

    // TODO(b/285174336): Tracking Bug
    @JvmField
    val USE_REPOS_FOR_BOUNCER_SHOWING =
        unreleasedFlag("use_repos_for_bouncer_showing", teamfood = true)

    // 3100 - Haptic interactions

    // TODO(b/290213663): Tracking Bug
    @JvmField
    val ONE_WAY_HAPTICS_API_MIGRATION = unreleasedFlag("oneway_haptics_api_migration")

    /** TODO(b/296223317): Enables the new keyguard presentation containing a clock. */
    @JvmField
    val ENABLE_CLOCK_KEYGUARD_PRESENTATION = unreleasedFlag("enable_clock_keyguard_presentation")

    /** Enable the Compose implementation of the PeopleSpaceActivity. */
    @JvmField
    val COMPOSE_PEOPLE_SPACE = unreleasedFlag("compose_people_space")

    /** Enable the Compose implementation of the Quick Settings footer actions. */
    @JvmField
    val COMPOSE_QS_FOOTER_ACTIONS = unreleasedFlag("compose_qs_footer_actions")

    /** Enable the share wifi button in Quick Settings internet dialog. */
    @JvmField
    val SHARE_WIFI_QS_BUTTON = unreleasedFlag("share_wifi_qs_button")

    /** Enable haptic slider component in the brightness slider */
    @JvmField
    val HAPTIC_BRIGHTNESS_SLIDER = unreleasedFlag("haptic_brightness_slider")
}
