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
import com.android.systemui.flags.FlagsFactory.releasedFlag
import com.android.systemui.flags.FlagsFactory.resourceBooleanFlag
import com.android.systemui.flags.FlagsFactory.sysPropBooleanFlag
import com.android.systemui.flags.FlagsFactory.unreleasedFlag
import com.android.systemui.res.R

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
 * See [FeatureFlagsClassicDebug] for instructions on flipping the flags via adb.
 */
object Flags {
    // IGNORE ME!
    // Because flags are static, we need an ever-present flag to reference in some of the internal
    // code that ensure that other flags are referenced and available.
    @JvmField val NULL_FLAG = unreleasedFlag("null_flag")

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

    // TODO(b/280783617): Tracking Bug
    @Keep
    @JvmField
    val BUILDER_EXTRAS_OVERRIDE =
        sysPropBooleanFlag(
            "persist.sysui.notification.builder_extras_override",
            default = true
        )

    // 200 - keyguard/lockscreen
    // ** Flag retired **
    // public static final BooleanFlag KEYGUARD_LAYOUT =
    //         new BooleanFlag(true);

    // TODO(b/254512750): Tracking Bug
    val NEW_UNLOCK_SWIPE_ANIMATION = releasedFlag("new_unlock_swipe_animation")
    val CHARGING_RIPPLE = resourceBooleanFlag(R.bool.flag_charging_ripple, "charging_ripple")

    // TODO(b/254512676): Tracking Bug
    @JvmField
    val LOCKSCREEN_CUSTOM_CLOCKS =
        resourceBooleanFlag(
            R.bool.config_enableLockScreenCustomClocks,
            "lockscreen_custom_clocks"
        )

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

    // TODO(b/305984787):
    @JvmField
    val REFACTOR_GETCURRENTUSER = unreleasedFlag("refactor_getcurrentuser", teamfood = true)

    /** Flag to control the revamp of keyguard biometrics progress animation */
    // TODO(b/244313043): Tracking bug
    @JvmField val BIOMETRICS_ANIMATION_REVAMP = unreleasedFlag("biometrics_animation_revamp")

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

    /** Inflate and bind views upon emitting a blueprint value . */
    // TODO(b/297365780): Tracking Bug
    @JvmField
    val LAZY_INFLATE_KEYGUARD = releasedFlag("lazy_inflate_keyguard")

    /** Enables UI updates for AI wallpapers in the wallpaper picker. */
    // TODO(b/267722622): Tracking Bug
    @JvmField val WALLPAPER_PICKER_UI_FOR_AIWP = releasedFlag("wallpaper_picker_ui_for_aiwp")

    /** Whether to allow long-press on the lock screen to directly open wallpaper picker. */
    // TODO(b/277220285): Tracking bug.
    @JvmField
    val LOCK_SCREEN_LONG_PRESS_DIRECT_TO_WPP =
        unreleasedFlag("lock_screen_long_press_directly_opens_wallpaper_picker")

    /** Whether page transition animations in the wallpaper picker are enabled */
    // TODO(b/291710220): Tracking bug.
    @JvmField
    val WALLPAPER_PICKER_PAGE_TRANSITIONS = releasedFlag("wallpaper_picker_page_transitions")

    /** Add "Apply" button to wall paper picker's grid preview page. */
    // TODO(b/294866904): Tracking bug.
    @JvmField
    val WALLPAPER_PICKER_GRID_APPLY_BUTTON =
            unreleasedFlag("wallpaper_picker_grid_apply_button")

    /** Flag meant to guard the talkback fix for the KeyguardIndicationTextView */
    // TODO(b/286563884): Tracking bug
    @JvmField val KEYGUARD_TALKBACK_FIX = unreleasedFlag("keyguard_talkback_fix")

    // TODO(b/287268101): Tracking bug.
    @JvmField val TRANSIT_CLOCK = releasedFlag("lockscreen_custom_transit_clock")

    /** Enables preview loading animation in the wallpaper picker. */
    // TODO(b/274443705): Tracking Bug
    @JvmField
    val WALLPAPER_PICKER_PREVIEW_ANIMATION = releasedFlag("wallpaper_picker_preview_animation")

    // 300 - power menu
    // TODO(b/254512600): Tracking Bug
    @JvmField val POWER_MENU_LITE = releasedFlag("power_menu_lite")

    // 400 - smartspace

    // TODO(b/254513100): Tracking Bug
    val SMARTSPACE_SHARED_ELEMENT_TRANSITION_ENABLED =
        releasedFlag("smartspace_shared_element_transition_enabled")

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

    // TODO(b/254512383): Tracking Bug
    @JvmField
    val FULL_SCREEN_USER_SWITCHER =
        resourceBooleanFlag(
            R.bool.config_enableFullscreenUserSwitcher,
            "full_screen_user_switcher"
        )

    // TODO(b/244064524): Tracking Bug
    @JvmField val QS_SECONDARY_DATA_SUB_INFO = releasedFlag("qs_secondary_data_sub_info")

    /** Enables new QS Edit Mode visual refresh */
    // TODO(b/269787742): Tracking Bug
    @JvmField
    val ENABLE_NEW_QS_EDIT_MODE = unreleasedFlag("enable_new_qs_edit_mode", teamfood = false)

    // 600- status bar

    // TODO(b/291315866): Tracking Bug
    @JvmField val SIGNAL_CALLBACK_DEPRECATION = releasedFlag("signal_callback_deprecation")

    // TODO(b/301610137): Tracking bug
    @JvmField val NEW_NETWORK_SLICE_UI = releasedFlag("new_network_slice_ui")

    // TODO(b/311222557): Tracking bug
    val ROAMING_INDICATOR_VIA_DISPLAY_INFO =
        releasedFlag("roaming_indicator_via_display_info")

    // TODO(b/308138154): Tracking bug
    val FILTER_PROVISIONING_NETWORK_SUBSCRIPTIONS =
        releasedFlag("filter_provisioning_network_subscriptions")

    // TODO(b/293863612): Tracking Bug
    @JvmField val INCOMPATIBLE_CHARGING_BATTERY_ICON =
        releasedFlag("incompatible_charging_battery_icon")

    // TODO(b/293585143): Tracking Bug
    val INSTANT_TETHER = releasedFlag("instant_tether")

    // TODO(b/294588085): Tracking Bug
    val WIFI_SECONDARY_NETWORKS = releasedFlag("wifi_secondary_networks")

    // TODO(b/290676905): Tracking Bug
    val NEW_SHADE_CARRIER_GROUP_MOBILE_ICONS =
        releasedFlag("new_shade_carrier_group_mobile_icons")

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
        releasedFlag(
            name = "enable_record_task_content",
            namespace = DeviceConfig.NAMESPACE_WINDOW_MANAGER,
        )

    // TODO(b/256873975): Tracking Bug
    @JvmField
    @Keep
    val WM_BUBBLE_BAR = sysPropBooleanFlag("persist.wm.debug.bubble_bar", default = false)

    // TODO(b/254513207): Tracking Bug to delete
    @Keep
    @JvmField
    val WM_ENABLE_PARTIAL_SCREEN_SHARING_ENTERPRISE_POLICIES =
        releasedFlag(
            name = "enable_screen_record_enterprise_policies",
            namespace = DeviceConfig.NAMESPACE_WINDOW_MANAGER,
        )

    // TODO(b/293252410) : Tracking Bug
    @JvmField
    val LOCKSCREEN_ENABLE_LANDSCAPE =
            unreleasedFlag("lockscreen.enable_landscape")

    // 1200 - predictive back
    @Keep
    @JvmField
    val WM_ENABLE_PREDICTIVE_BACK_ANIM =
        sysPropBooleanFlag("persist.wm.debug.predictive_back_anim", default = true)

    @Keep
    @JvmField
    val WM_ALWAYS_ENFORCE_PREDICTIVE_BACK =
        sysPropBooleanFlag("persist.wm.debug.predictive_back_always_enforce", default = false)

    // TODO(b/251205791): Tracking Bug
    @JvmField val SCREENSHOT_APP_CLIPS = releasedFlag("screenshot_app_clips")

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

    // 1900
    @JvmField val NOTE_TASKS = releasedFlag("keycode_flag")

    // 2200 - biometrics (udfps, sfps, BiometricPrompt, etc.)

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

    // TODO:(b/283203305): Tracking bug
    @JvmField val TRIM_FONT_CACHES_AT_UNLOCK = unreleasedFlag("trim_font_caches_on_unlock")

    // TODO(b/298380520): Tracking Bug
    @JvmField
    val USER_TRACKER_BACKGROUND_CALLBACKS = unreleasedFlag("user_tracker_background_callbacks")

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

    // TODO(b/316157842): Tracking Bug
    // Adds extra delay to notifications measure
    @Keep
    @JvmField
    val ENABLE_NOTIFICATIONS_SIMULATE_SLOW_MEASURE =
        unreleasedFlag("enable_notifications_simulate_slow_measure")

    // TODO(b259590361): Tracking bug
    val EXPERIMENTAL_FLAG = unreleasedFlag("exp_flag_release")

    // 2600 - keyboard
    // TODO(b/259352579): Tracking Bug
    @JvmField val SHORTCUT_LIST_SEARCH_LAYOUT = releasedFlag("shortcut_list_search_layout")

    // TODO(b/259428678): Tracking Bug
    @JvmField val KEYBOARD_BACKLIGHT_INDICATOR = releasedFlag("keyboard_backlight_indicator")

    // TODO(b/277201412): Tracking Bug
    @JvmField
    val SPLIT_SHADE_SUBPIXEL_OPTIMIZATION = unreleasedFlag("split_shade_subpixel_optimization")

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
    val LOCKSCREEN_WALLPAPER_DREAM_ENABLED = unreleasedFlag("enable_lockscreen_wallpaper_dream")

    // TODO(b/283447257): Tracking bug
    @JvmField
    val BIGPICTURE_NOTIFICATION_LAZY_LOADING =
        unreleasedFlag("bigpicture_notification_lazy_loading")

    // TODO(b/283740863): Tracking Bug
    @JvmField
    val ENABLE_NEW_PRIVACY_DIALOG = releasedFlag("enable_new_privacy_dialog")

    // TODO(b/302144438): Tracking Bug
    @JvmField val DECOUPLE_REMOTE_INPUT_DELEGATE_AND_CALLBACK_UPDATE =
            unreleasedFlag("decouple_remote_input_delegate_and_callback_update")

    /** TODO(b/296223317): Enables the new keyguard presentation containing a clock. */
    @JvmField
    val ENABLE_CLOCK_KEYGUARD_PRESENTATION = releasedFlag("enable_clock_keyguard_presentation")

    /** Enable the share wifi button in Quick Settings internet dialog. */
    @JvmField
    val SHARE_WIFI_QS_BUTTON = releasedFlag("share_wifi_qs_button")

    /** Enable showing a dialog when clicking on Quick Settings bluetooth tile. */
    @JvmField
    val BLUETOOTH_QS_TILE_DIALOG = releasedFlag("bluetooth_qs_tile_dialog")

    // TODO(b/300995746): Tracking Bug
    /** A resource flag for whether the communal service is enabled. */
    @JvmField
    val COMMUNAL_SERVICE_ENABLED = resourceBooleanFlag(R.bool.config_communalServiceEnabled,
        "communal_service_enabled")
}
