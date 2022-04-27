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

package android.app.admin;

import android.annotation.SystemApi;
import android.os.UserHandle;

/**
 * Class containing the required identifiers to update device management resources.
 *
 * <p>See {@link DevicePolicyResourcesManager#getDrawable} and
 * {@link DevicePolicyResourcesManager#getString}.
 */
public final class DevicePolicyResources {

    private DevicePolicyResources() {}

    /**
     * An identifier used for:
     * <ul>
     *     <li>un-updatable resource IDs</li>
     *     <li>undefined sources</li>
     * </ul>
     */
    public static final String UNDEFINED = "UNDEFINED";

    /**
     * Class containing the identifiers used to update device management-related system drawable.
     *
     * @hide
     */
    public static final class Drawables {

        private Drawables() {
        }

        /**
         * Specifically used to badge work profile app icons.
         */
        public static final String WORK_PROFILE_ICON_BADGE = "WORK_PROFILE_ICON_BADGE";

        /**
         * General purpose work profile icon (i.e. generic icon badging). For badging app icons
         * specifically, see {@link #WORK_PROFILE_ICON_BADGE}.
         */
        public static final String WORK_PROFILE_ICON = "WORK_PROFILE_ICON";

        /**
         * General purpose icon representing the work profile off state.
         */
        public static final String WORK_PROFILE_OFF_ICON = "WORK_PROFILE_OFF_ICON";

        /**
         * General purpose icon for the work profile user avatar.
         */
        public static final String WORK_PROFILE_USER_ICON = "WORK_PROFILE_USER_ICON";

        /**
         * Class containing the source identifiers used to update device management-related system
         * drawable.
         */
        public static final class Source {

            private Source() {
            }

            /**
             * A source identifier indicating that the updatable drawable is used in notifications.
             */
            public static final String NOTIFICATION = "NOTIFICATION";

            /**
             * A source identifier indicating that the updatable drawable is used in a cross
             * profile switching animation.
             */
            public static final String PROFILE_SWITCH_ANIMATION = "PROFILE_SWITCH_ANIMATION";

            /**
             * A source identifier indicating that the updatable drawable is used in a work
             * profile home screen widget.
             */
            public static final String HOME_WIDGET = "HOME_WIDGET";

            /**
             * A source identifier indicating that the updatable drawable is used in the launcher
             * turn off work button.
             */
            public static final String LAUNCHER_OFF_BUTTON = "LAUNCHER_OFF_BUTTON";

            /**
             * A source identifier indicating that the updatable drawable is used in quick settings.
             */
            public static final String QUICK_SETTINGS = "QUICK_SETTINGS";

            /**
             * A source identifier indicating that the updatable drawable is used in the status bar.
             */
            public static final String STATUS_BAR = "STATUS_BAR";
        }

        /**
         * Class containing the style identifiers used to update device management-related system
         * drawable.
         */
        public static final class Style {

            private Style() {
            }

            /**
             * A style identifier indicating that the updatable drawable has a solid color fill.
             */
            public static final String SOLID_COLORED = "SOLID_COLORED";

            /**
             * A style identifier indicating that the updatable drawable has a solid non-colored
             * fill.
             */
            public static final String SOLID_NOT_COLORED = "SOLID_NOT_COLORED";

            /**
             * A style identifier indicating that the updatable drawable is an outline.
             */
            public static final String OUTLINE = "OUTLINE";
        }
    }

    /**
     * Class containing the identifiers used to update device management-related system strings.
     *
     * @hide
     */
    @SystemApi
    public static final class Strings {

        private Strings() {
        }

        /**
         * Class containing the identifiers used to update device management-related system strings
         * in the Settings package
         *
         * @hide
         */
        public static final class Settings {

            private Settings() {
            }

            private static final String PREFIX = "Settings.";

            /**
             * Title shown for menu item that launches face settings or enrollment, for work profile
             */
            public static final String FACE_SETTINGS_FOR_WORK_TITLE =
                    PREFIX + "FACE_SETTINGS_FOR_WORK_TITLE";

            /**
             * Warning when removing the last fingerprint on a work profile
             */
            public static final String WORK_PROFILE_FINGERPRINT_LAST_DELETE_MESSAGE =
                    PREFIX + "WORK_PROFILE_FINGERPRINT_LAST_DELETE_MESSAGE";

            /**
             * Text letting the user know that their IT admin can't reset their screen lock if they
             * forget it, and they can choose to set another lock that would be specifically for
             * their work apps
             */
            public static final String WORK_PROFILE_IT_ADMIN_CANT_RESET_SCREEN_LOCK =
                    PREFIX + "WORK_PROFILE_IT_ADMIN_CANT_RESET_SCREEN_LOCK";

            /**
             * Message shown in screen lock picker for setting up a work profile screen lock
             */
            public static final String WORK_PROFILE_SCREEN_LOCK_SETUP_MESSAGE =
                    PREFIX + "WORK_PROFILE_SCREEN_LOCK_SETUP_MESSAGE";

            /**
             * Title for PreferenceScreen to launch picker for security method for the managed
             * profile when there is none
             */
            public static final String WORK_PROFILE_SET_UNLOCK_LAUNCH_PICKER_TITLE =
                    PREFIX + "WORK_PROFILE_SET_UNLOCK_LAUNCH_PICKER_TITLE";

            /**
             * Content of the dialog shown when the user only has one attempt left to provide the
             * work lock pattern before the work profile is removed
             */
            public static final String WORK_PROFILE_LAST_PATTERN_ATTEMPT_BEFORE_WIPE =
                    PREFIX + "WORK_PROFILE_LAST_PATTERN_ATTEMPT_BEFORE_WIPE";

            /**
             * Content of the dialog shown when the user only has one attempt left to provide the
             * work lock pattern before the work profile is removed
             */
            public static final String WORK_PROFILE_LAST_PIN_ATTEMPT_BEFORE_WIPE =
                    PREFIX + "WORK_PROFILE_LAST_PIN_ATTEMPT_BEFORE_WIPE";

            /**
             * Content of the dialog shown when the user only has one attempt left to provide the
             * work lock pattern before the work profile is removed
             */
            public static final String WORK_PROFILE_LAST_PASSWORD_ATTEMPT_BEFORE_WIPE =
                    PREFIX + "WORK_PROFILE_LAST_PASSWORD_ATTEMPT_BEFORE_WIPE";

            /**
             * Content of the dialog shown when the user has failed to provide the device lock too
             * many times and the device is wiped
             */
            public static final String WORK_PROFILE_LOCK_ATTEMPTS_FAILED =
                    PREFIX + "WORK_PROFILE_LOCK_ATTEMPTS_FAILED";

            /**
             * Content description for work profile accounts group
             */
            public static final String ACCESSIBILITY_CATEGORY_WORK =
                    PREFIX + "ACCESSIBILITY_CATEGORY_WORK";

            /**
             * Content description for personal profile accounts group
             */
            public static final String ACCESSIBILITY_CATEGORY_PERSONAL =
                    PREFIX + "ACCESSIBILITY_CATEGORY_PERSONAL";

            /**
             * Content description for work profile details page title
             */
            public static final String ACCESSIBILITY_WORK_ACCOUNT_TITLE =
                    PREFIX + "ACCESSIBILITY_WORK_ACCOUNT_TITLE";

            /**
             * Content description for personal profile details page title
             */
            public static final String ACCESSIBILITY_PERSONAL_ACCOUNT_TITLE =
                    PREFIX + "ACCESSIBILITY_PERSONAL_ACCOUNT_TITLE";

            /**
             * Title for work profile location switch
             */
            public static final String WORK_PROFILE_LOCATION_SWITCH_TITLE =
                    PREFIX + "WORK_PROFILE_LOCATION_SWITCH_TITLE";

            /**
             * Header when setting work profile password
             */
            public static final String SET_WORK_PROFILE_PASSWORD_HEADER =
                    PREFIX + "SET_WORK_PROFILE_PASSWORD_HEADER";

            /**
             * Header when setting work profile PIN
             */
            public static final String SET_WORK_PROFILE_PIN_HEADER =
                    PREFIX + "SET_WORK_PROFILE_PIN_HEADER";

            /**
             * Header when setting work profile pattern
             */
            public static final String SET_WORK_PROFILE_PATTERN_HEADER =
                    PREFIX + "SET_WORK_PROFILE_PATTERN_HEADER";

            /**
             * Header when confirming work profile password
             */
            public static final String CONFIRM_WORK_PROFILE_PASSWORD_HEADER =
                    PREFIX + "CONFIRM_WORK_PROFILE_PASSWORD_HEADER";

            /**
             * Header when confirming work profile pin
             */
            public static final String CONFIRM_WORK_PROFILE_PIN_HEADER =
                    PREFIX + "CONFIRM_WORK_PROFILE_PIN_HEADER";

            /**
             * Header when confirming work profile pattern
             */
            public static final String CONFIRM_WORK_PROFILE_PATTERN_HEADER =
                    PREFIX + "CONFIRM_WORK_PROFILE_PATTERN_HEADER";

            /**
             * Header when re-entering work profile password
             */
            public static final String REENTER_WORK_PROFILE_PASSWORD_HEADER =
                    PREFIX + "REENTER_WORK_PROFILE_PASSWORD_HEADER";

            /**
             * Header when re-entering work profile pin
             */
            public static final String REENTER_WORK_PROFILE_PIN_HEADER =
                    PREFIX + "REENTER_WORK_PROFILE_PIN_HEADER";

            /**
             * Message to be used to explain the users that they need to enter their work pattern to
             * continue a particular operation
             */
            public static final String WORK_PROFILE_CONFIRM_PATTERN =
                    PREFIX + "WORK_PROFILE_CONFIRM_PATTERN";

            /**
             * Message to be used to explain the users that they need to enter their work pin to
             * continue a particular operation
             */
            public static final String WORK_PROFILE_CONFIRM_PIN =
                    PREFIX + "WORK_PROFILE_CONFIRM_PIN";

            /**
             * Message to be used to explain the users that they need to enter their work password
             * to
             * continue a particular operation
             */
            public static final String WORK_PROFILE_CONFIRM_PASSWORD =
                    PREFIX + "WORK_PROFILE_CONFIRM_PASSWORD";

            /**
             * This string shows = PREFIX + "shows"; up on a screen where a user can enter a pattern
             * that lets them access
             * their work profile. This is an extra security measure that's required for them to
             * continue
             */
            public static final String WORK_PROFILE_PATTERN_REQUIRED =
                    PREFIX + "WORK_PROFILE_PATTERN_REQUIRED";

            /**
             * This string shows = PREFIX + "shows"; up on a screen where a user can enter a pin
             * that lets them access
             * their work profile. This is an extra security measure that's required for them to
             * continue
             */
            public static final String WORK_PROFILE_PIN_REQUIRED =
                    PREFIX + "WORK_PROFILE_PIN_REQUIRED";

            /**
             * This string shows = PREFIX + "shows"; up on a screen where a user can enter a
             * password that lets them access
             * their work profile. This is an extra security measure that's required for them to
             * continue
             */
            public static final String WORK_PROFILE_PASSWORD_REQUIRED =
                    PREFIX + "WORK_PROFILE_PASSWORD_REQUIRED";

            /**
             * Header for Work Profile security settings
             */
            public static final String WORK_PROFILE_SECURITY_TITLE =
                    PREFIX + "WORK_PROFILE_SECURITY_TITLE";

            /**
             * Header for Work Profile unify locks settings
             */
            public static final String WORK_PROFILE_UNIFY_LOCKS_TITLE =
                    PREFIX + "WORK_PROFILE_UNIFY_LOCKS_TITLE";

            /**
             * Setting option explanation to unify work and personal locks
             */
            public static final String WORK_PROFILE_UNIFY_LOCKS_SUMMARY =
                    PREFIX + "WORK_PROFILE_UNIFY_LOCKS_SUMMARY";

            /**
             * Further explanation when the user wants to unify work and personal locks
             */
            public static final String WORK_PROFILE_UNIFY_LOCKS_DETAIL =
                    PREFIX + "WORK_PROFILE_UNIFY_LOCKS_DETAIL";

            /**
             * Ask if the user wants to create a new lock for personal and work as the current work
             * lock is not enough for the device
             */
            public static final String WORK_PROFILE_UNIFY_LOCKS_NONCOMPLIANT =
                    PREFIX + "WORK_PROFILE_UNIFY_LOCKS_NONCOMPLIANT";

            /**
             * Title of 'Work profile keyboards & tools' preference category
             */
            public static final String WORK_PROFILE_KEYBOARDS_AND_TOOLS =
                    PREFIX + "WORK_PROFILE_KEYBOARDS_AND_TOOLS";

            /**
             * Label for state when work profile is not available
             */
            public static final String WORK_PROFILE_NOT_AVAILABLE =
                    PREFIX + "WORK_PROFILE_NOT_AVAILABLE";

            /**
             * Label for work profile setting (to allow turning work profile on and off)
             */
            public static final String WORK_PROFILE_SETTING = PREFIX + "WORK_PROFILE_SETTING";

            /**
             * Description of the work profile setting when the work profile is on
             */
            public static final String WORK_PROFILE_SETTING_ON_SUMMARY =
                    PREFIX + "WORK_PROFILE_SETTING_ON_SUMMARY";

            /**
             * Description of the work profile setting when the work profile is off
             */
            public static final String WORK_PROFILE_SETTING_OFF_SUMMARY =
                    PREFIX + "WORK_PROFILE_SETTING_OFF_SUMMARY";

            /**
             * Button text to remove work profile
             */
            public static final String REMOVE_WORK_PROFILE = PREFIX + "REMOVE_WORK_PROFILE";

            /**
             * Text of message to show to device owner user whose administrator has installed a SSL
             * CA Cert
             */
            public static final String DEVICE_OWNER_INSTALLED_CERTIFICATE_AUTHORITY_WARNING =
                    PREFIX + "DEVICE_OWNER_INSTALLED_CERTIFICATE_AUTHORITY_WARNING";

            /**
             * Text of message to show to work profile users whose administrator has installed a SSL
             * CA Cert
             */
            public static final String WORK_PROFILE_INSTALLED_CERTIFICATE_AUTHORITY_WARNING =
                    PREFIX + "WORK_PROFILE_INSTALLED_CERTIFICATE_AUTHORITY_WARNING";

            /**
             * Work profile removal confirmation title
             */
            public static final String WORK_PROFILE_CONFIRM_REMOVE_TITLE =
                    PREFIX + "WORK_PROFILE_CONFIRM_REMOVE_TITLE";

            /**
             * Work profile removal confirmation message
             */
            public static final String WORK_PROFILE_CONFIRM_REMOVE_MESSAGE =
                    PREFIX + "WORK_PROFILE_CONFIRM_REMOVE_MESSAGE";

            /**
             * Toast shown when an app in the work profile attempts to open notification settings
             * and apps in the work profile cannot access notification settings
             */
            public static final String WORK_APPS_CANNOT_ACCESS_NOTIFICATION_SETTINGS =
                    PREFIX + "WORK_APPS_CANNOT_ACCESS_NOTIFICATION_SETTINGS";

            /**
             * Work sound settings section header
             */
            public static final String WORK_PROFILE_SOUND_SETTINGS_SECTION_HEADER =
                    PREFIX + "WORK_PROFILE_SOUND_SETTINGS_SECTION_HEADER";

            /**
             * Title for the switch that enables syncing of personal ringtones to work profile
             */
            public static final String WORK_PROFILE_USE_PERSONAL_SOUNDS_TITLE =
                    PREFIX + "WORK_PROFILE_USE_PERSONAL_SOUNDS_TITLE";

            /**
             * Summary for the switch that enables syncing of personal ringtones to work profile
             */
            public static final String WORK_PROFILE_USE_PERSONAL_SOUNDS_SUMMARY =
                    PREFIX + "WORK_PROFILE_USE_PERSONAL_SOUNDS_SUMMARY";

            /**
             * Title for the option defining the work profile phone ringtone
             */
            public static final String WORK_PROFILE_RINGTONE_TITLE =
                    PREFIX + "WORK_PROFILE_RINGTONE_TITLE";

            /**
             * Title for the option defining the default work profile notification ringtone
             */
            public static final String WORK_PROFILE_NOTIFICATION_RINGTONE_TITLE =
                    PREFIX + "WORK_PROFILE_NOTIFICATION_RINGTONE_TITLE";

            /**
             * Title for the option defining the default work alarm ringtone
             */
            public static final String WORK_PROFILE_ALARM_RINGTONE_TITLE =
                    PREFIX + "WORK_PROFILE_ALARM_RINGTONE_TITLE";

            /**
             * Summary for sounds when sync with personal sounds is active
             */
            public static final String WORK_PROFILE_SYNC_WITH_PERSONAL_SOUNDS_ACTIVE_SUMMARY =
                    PREFIX + "WORK_PROFILE_SYNC_WITH_PERSONAL_SOUNDS_ACTIVE_SUMMARY";

            /**
             * Title for dialog shown when enabling sync with personal sounds
             */
            public static final String
                    ENABLE_WORK_PROFILE_SYNC_WITH_PERSONAL_SOUNDS_DIALOG_TITLE =
                    PREFIX + "ENABLE_WORK_PROFILE_SYNC_WITH_PERSONAL_SOUNDS_DIALOG_TITLE";

            /**
             * Message for dialog shown when using the same sounds for work events as for personal
             * events
             */
            public static final String
                    ENABLE_WORK_PROFILE_SYNC_WITH_PERSONAL_SOUNDS_DIALOG_MESSAGE =
                    PREFIX + "ENABLE_WORK_PROFILE_SYNC_WITH_PERSONAL_SOUNDS_DIALOG_MESSAGE";

            /**
             * Work profile notifications section header
             */
            public static final String WORK_PROFILE_NOTIFICATIONS_SECTION_HEADER =
                    PREFIX + "WORK_PROFILE_NOTIFICATIONS_SECTION_HEADER";

            /**
             * Title for the option controlling notifications for work profile
             */
            public static final String WORK_PROFILE_LOCKED_NOTIFICATION_TITLE =
                    PREFIX + "WORK_PROFILE_LOCKED_NOTIFICATION_TITLE";

            /**
             * Title for redacting sensitive content on lockscreen for work profiles
             */
            public static final String WORK_PROFILE_LOCK_SCREEN_REDACT_NOTIFICATION_TITLE =
                    PREFIX + "WORK_PROFILE_LOCK_SCREEN_REDACT_NOTIFICATION_TITLE";

            /**
             * Summary for redacting sensitive content on lockscreen for work profiles
             */
            public static final String WORK_PROFILE_LOCK_SCREEN_REDACT_NOTIFICATION_SUMMARY =
                    PREFIX + "WORK_PROFILE_LOCK_SCREEN_REDACT_NOTIFICATION_SUMMARY";

            /**
             * Indicates that the work profile admin doesn't allow this notification listener to
             * access
             * work profile notifications
             */
            public static final String WORK_PROFILE_NOTIFICATION_LISTENER_BLOCKED =
                    PREFIX + "WORK_PROFILE_NOTIFICATION_LISTENER_BLOCKED";

            /**
             * This setting shows a user's connected work and personal apps.
             */
            public static final String CONNECTED_WORK_AND_PERSONAL_APPS_TITLE =
                    PREFIX + "CONNECTED_WORK_AND_PERSONAL_APPS_TITLE";

            /**
             * This text lets a user know that if they connect work and personal apps,
             * they will share permissions and can access each other's data
             */
            public static final String CONNECTED_APPS_SHARE_PERMISSIONS_AND_DATA =
                    PREFIX + "CONNECTED_APPS_SHARE_PERMISSIONS_AND_DATA";

            /**
             * This text lets a user know that they should only connect work and personal apps if
             * they
             * trust the work app with their personal data
             */
            public static final String ONLY_CONNECT_TRUSTED_APPS =
                    PREFIX + "ONLY_CONNECT_TRUSTED_APPS";

            /**
             * This text lets a user know how to disconnect work and personal apps
             */
            public static final String HOW_TO_DISCONNECT_APPS = PREFIX + "HOW_TO_DISCONNECT_APPS";

            /**
             * Title of confirmation dialog when connecting work and personal apps
             */
            public static final String CONNECT_APPS_DIALOG_TITLE =
                    PREFIX + "CONNECT_APPS_DIALOG_TITLE";

            /**
             * This dialog is shown when a user tries to connect a work app to a personal
             * app
             */
            public static final String CONNECT_APPS_DIALOG_SUMMARY =
                    PREFIX + "CONNECT_APPS_DIALOG_SUMMARY";

            /**
             * This text lets the user know that their work app will be able to access data in their
             * personal app
             */
            public static final String APP_CAN_ACCESS_PERSONAL_DATA =
                    PREFIX + "APP_CAN_ACCESS_PERSONAL_DATA";

            /**
             * This text lets the user know that their work app will be able to use permissions in
             * their personal app
             */
            public static final String APP_CAN_ACCESS_PERSONAL_PERMISSIONS =
                    PREFIX + "APP_CAN_ACCESS_PERSONAL_PERMISSIONS";

            /**
             * lets a user know that they need to install an app in their work profile in order to
             * connect it to the corresponding personal app
             */
            public static final String INSTALL_IN_WORK_PROFILE_TO_CONNECT_PROMPT =
                    PREFIX + "INSTALL_IN_WORK_PROFILE_TO_CONNECT_PROMPT";

            /**
             * lets a user know that they need to install an app in their personal profile in order
             * to
             * connect it to the corresponding work app
             */
            public static final String INSTALL_IN_PERSONAL_PROFILE_TO_CONNECT_PROMPT =
                    PREFIX + "INSTALL_IN_PERSONAL_PROFILE_TO_CONNECT_PROMPT";

            /**
             * Header for showing the organisation managing the work profile
             */
            public static final String WORK_PROFILE_MANAGED_BY = PREFIX + "WORK_PROFILE_MANAGED_BY";

            /**
             * Summary showing the enterprise who manages the device or profile.
             */
            public static final String MANAGED_BY = PREFIX + "MANAGED_BY";

            /**
             * Warning message about disabling usage access on profile owner
             */
            public static final String WORK_PROFILE_DISABLE_USAGE_ACCESS_WARNING =
                    PREFIX + "WORK_PROFILE_DISABLE_USAGE_ACCESS_WARNING";

            /**
             * Title for dialog displayed when user taps a setting on their phone that's blocked by
             * their IT admin
             */
            public static final String DISABLED_BY_IT_ADMIN_TITLE =
                    PREFIX + "DISABLED_BY_IT_ADMIN_TITLE";

            /**
             * Shown when the user tries to change phone settings that are blocked by their IT admin
             */
            public static final String CONTACT_YOUR_IT_ADMIN = PREFIX + "CONTACT_YOUR_IT_ADMIN";

            /**
             * warn user about policies the admin can set in a work profile
             */
            public static final String WORK_PROFILE_ADMIN_POLICIES_WARNING =
                    PREFIX + "WORK_PROFILE_ADMIN_POLICIES_WARNING";

            /**
             * warn user about policies the admin can set on a user
             */
            public static final String USER_ADMIN_POLICIES_WARNING =
                    PREFIX + "USER_ADMIN_POLICIES_WARNING";

            /**
             * warn user about policies the admin can set on a device
             */
            public static final String DEVICE_ADMIN_POLICIES_WARNING =
                    PREFIX + "DEVICE_ADMIN_POLICIES_WARNING";

            /**
             * Condition that work profile is off
             */
            public static final String WORK_PROFILE_OFF_CONDITION_TITLE =
                    PREFIX + "WORK_PROFILE_OFF_CONDITION_TITLE";

            /**
             * Title of work profile setting page
             */
            public static final String MANAGED_PROFILE_SETTINGS_TITLE =
                    PREFIX + "MANAGED_PROFILE_SETTINGS_TITLE";

            /**
             * Setting that lets a user's personal apps identify contacts using the user's work
             * directory
             */
            public static final String WORK_PROFILE_CONTACT_SEARCH_TITLE =
                    PREFIX + "WORK_PROFILE_CONTACT_SEARCH_TITLE";

            /**
             * This setting lets a user's personal apps identify contacts using the user's work
             * directory
             */
            public static final String WORK_PROFILE_CONTACT_SEARCH_SUMMARY =
                    PREFIX + "WORK_PROFILE_CONTACT_SEARCH_SUMMARY";

            /**
             * This setting lets the user show their work events on their personal calendar
             */
            public static final String CROSS_PROFILE_CALENDAR_TITLE =
                    PREFIX + "CROSS_PROFILE_CALENDAR_TITLE";

            /**
             * Setting description. If the user turns on this setting, they can see their work
             * events on their personal calendar
             */
            public static final String CROSS_PROFILE_CALENDAR_SUMMARY =
                    PREFIX + "CROSS_PROFILE_CALENDAR_SUMMARY";

            /**
             * Label explaining that an always-on VPN was set by the admin in the personal profile
             */
            public static final String ALWAYS_ON_VPN_PERSONAL_PROFILE =
                    PREFIX + "ALWAYS_ON_VPN_PERSONAL_PROFILE";

            /**
             * Label explaining that an always-on VPN was set by the admin for the entire device
             */
            public static final String ALWAYS_ON_VPN_DEVICE = PREFIX + "ALWAYS_ON_VPN_DEVICE";

            /**
             * Label explaining that an always-on VPN was set by the admin in the work profile
             */
            public static final String ALWAYS_ON_VPN_WORK_PROFILE =
                    PREFIX + "ALWAYS_ON_VPN_WORK_PROFILE";

            /**
             * Label explaining that the admin installed trusted CA certificates in personal profile
             */
            public static final String CA_CERTS_PERSONAL_PROFILE =
                    PREFIX + "CA_CERTS_PERSONAL_PROFILE";

            /**
             * Label explaining that the admin installed trusted CA certificates in work profile
             */
            public static final String CA_CERTS_WORK_PROFILE = PREFIX + "CA_CERTS_WORK_PROFILE";

            /**
             * Label explaining that the admin installed trusted CA certificates for the entire
             * device
             */
            public static final String CA_CERTS_DEVICE = PREFIX + "CA_CERTS_DEVICE";

            /**
             * Label explaining that the admin can lock the device and change the user's password
             */
            public static final String ADMIN_CAN_LOCK_DEVICE = PREFIX + "ADMIN_CAN_LOCK_DEVICE";

            /**
             * Label explaining that the admin can wipe the device remotely
             */
            public static final String ADMIN_CAN_WIPE_DEVICE = PREFIX + "ADMIN_CAN_WIPE_DEVICE";

            /**
             * Label explaining that the admin configured the device to wipe itself when the
             * password is mistyped too many times
             */
            public static final String ADMIN_CONFIGURED_FAILED_PASSWORD_WIPE_DEVICE =
                    PREFIX + "ADMIN_CONFIGURED_FAILED_PASSWORD_WIPE_DEVICE";

            /**
             * Label explaining that the admin configured the work profile to wipe itself when the
             * password is mistyped too many times
             */
            public static final String ADMIN_CONFIGURED_FAILED_PASSWORD_WIPE_WORK_PROFILE =
                    PREFIX + "ADMIN_CONFIGURED_FAILED_PASSWORD_WIPE_WORK_PROFILE";

            /**
             * Message indicating that the device is enterprise-managed by a Device Owner
             */
            public static final String DEVICE_MANAGED_WITHOUT_NAME =
                    PREFIX + "DEVICE_MANAGED_WITHOUT_NAME";

            /**
             * Message indicating that the device is enterprise-managed by a Device Owner
             */
            public static final String DEVICE_MANAGED_WITH_NAME =
                    PREFIX + "DEVICE_MANAGED_WITH_NAME";

            /**
             * Subtext of work profile app for current setting
             */
            public static final String WORK_PROFILE_APP_SUBTEXT =
                    PREFIX + "WORK_PROFILE_APP_SUBTEXT";

            /**
             * Subtext of personal profile app for current setting
             */
            public static final String PERSONAL_PROFILE_APP_SUBTEXT =
                    PREFIX + "PERSONAL_PROFILE_APP_SUBTEXT";

            /**
             * Title shown for work menu item that launches fingerprint settings or enrollment
             */
            public static final String FINGERPRINT_FOR_WORK = PREFIX + "FINGERPRINT_FOR_WORK";

            /**
             * Message shown in face enrollment dialog, when face unlock is disabled by device admin
             */
            public static final String FACE_UNLOCK_DISABLED = PREFIX + "FACE_UNLOCK_DISABLED";

            /**
             * message shown in fingerprint enrollment dialog, when fingerprint unlock is disabled
             * by device admin
             */
            public static final String FINGERPRINT_UNLOCK_DISABLED =
                    PREFIX + "FINGERPRINT_UNLOCK_DISABLED";

            /**
             * Text shown in fingerprint settings explaining what the fingerprint can be used for in
             * the case unlocking is disabled
             */
            public static final String FINGERPRINT_UNLOCK_DISABLED_EXPLANATION =
                    PREFIX + "FINGERPRINT_UNLOCK_DISABLED_EXPLANATION";

            /**
             * Error shown when in PIN mode and PIN has been used recently
             */
            public static final String PIN_RECENTLY_USED = PREFIX + "PIN_RECENTLY_USED";

            /**
             * Error shown when in PASSWORD mode and password has been used recently
             */
            public static final String PASSWORD_RECENTLY_USED = PREFIX + "PASSWORD_RECENTLY_USED";

            /**
             * Title of preference to manage device admin apps
             */
            public static final String MANAGE_DEVICE_ADMIN_APPS =
                    PREFIX + "MANAGE_DEVICE_ADMIN_APPS";

            /**
             * Inform the user that currently no device admin apps are installed and active
             */
            public static final String NUMBER_OF_DEVICE_ADMINS_NONE =
                    PREFIX + "NUMBER_OF_DEVICE_ADMINS_NONE";

            /**
             * Inform the user how many device admin apps are installed and active
             */
            public static final String NUMBER_OF_DEVICE_ADMINS = PREFIX + "NUMBER_OF_DEVICE_ADMINS";

            /**
             * Title that asks the user to contact the IT admin to reset password
             */
            public static final String FORGOT_PASSWORD_TITLE = PREFIX + "FORGOT_PASSWORD_TITLE";

            /**
             * Content that asks the user to contact the IT admin to reset password
             */
            public static final String FORGOT_PASSWORD_TEXT = PREFIX + "FORGOT_PASSWORD_TEXT";

            /**
             * Error message shown when trying to move device administrators to external disks, such
             * as SD card
             */
            public static final String ERROR_MOVE_DEVICE_ADMIN = PREFIX + "ERROR_MOVE_DEVICE_ADMIN";

            /**
             * Device admin app settings title
             */
            public static final String DEVICE_ADMIN_SETTINGS_TITLE =
                    PREFIX + "DEVICE_ADMIN_SETTINGS_TITLE";

            /**
             * Button to remove the active device admin app
             */
            public static final String REMOVE_DEVICE_ADMIN = PREFIX + "REMOVE_DEVICE_ADMIN";

            /**
             * Button to uninstall the device admin app
             */
            public static final String UNINSTALL_DEVICE_ADMIN = PREFIX + "UNINSTALL_DEVICE_ADMIN";

            /**
             * Button to deactivate and uninstall the device admin app
             */
            public static final String REMOVE_AND_UNINSTALL_DEVICE_ADMIN =
                    PREFIX + "REMOVE_AND_UNINSTALL_DEVICE_ADMIN";

            /**
             * Title for selecting device admin apps
             */
            public static final String SELECT_DEVICE_ADMIN_APPS =
                    PREFIX + "SELECT_DEVICE_ADMIN_APPS";

            /**
             * Message when there are no available device admin apps to display
             */
            public static final String NO_DEVICE_ADMINS = PREFIX + "NO_DEVICE_ADMINS";

            /**
             * Title for screen to add a device admin app
             */
            public static final String ACTIVATE_DEVICE_ADMIN_APP =
                    PREFIX + "ACTIVATE_DEVICE_ADMIN_APP";

            /**
             * Label for button to set the active device admin
             */
            public static final String ACTIVATE_THIS_DEVICE_ADMIN_APP =
                    PREFIX + "ACTIVATE_THIS_DEVICE_ADMIN_APP";

            /**
             * Activate a specific device admin app title
             */
            public static final String ACTIVATE_DEVICE_ADMIN_APP_TITLE =
                    PREFIX + "ACTIVATE_DEVICE_ADMIN_APP_TITLE";

            /**
             * Device admin warning message about policies a not active admin can use
             */
            public static final String NEW_DEVICE_ADMIN_WARNING =
                    PREFIX + "NEW_DEVICE_ADMIN_WARNING";

            /**
             * Simplified device admin warning message
             */
            public static final String NEW_DEVICE_ADMIN_WARNING_SIMPLIFIED =
                    PREFIX + "NEW_DEVICE_ADMIN_WARNING_SIMPLIFIED";

            /**
             * Device admin warning message about policies the active admin can use
             */
            public static final String ACTIVE_DEVICE_ADMIN_WARNING =
                    PREFIX + "ACTIVE_DEVICE_ADMIN_WARNING";

            /**
             * Title for screen to set a profile owner
             */
            public static final String SET_PROFILE_OWNER_TITLE = PREFIX + "SET_PROFILE_OWNER_TITLE";

            /**
             * Simplified title for dialog to set a profile owner
             */
            public static final String SET_PROFILE_OWNER_DIALOG_TITLE =
                    PREFIX + "SET_PROFILE_OWNER_DIALOG_TITLE";

            /**
             * Warning when trying to add a profile owner admin after setup has completed
             */
            public static final String SET_PROFILE_OWNER_POSTSETUP_WARNING =
                    PREFIX + "SET_PROFILE_OWNER_POSTSETUP_WARNING";

            /**
             * Message displayed to let the user know that some of the options are disabled by admin
             */
            public static final String OTHER_OPTIONS_DISABLED_BY_ADMIN =
                    PREFIX + "OTHER_OPTIONS_DISABLED_BY_ADMIN";

            /**
             * This is shown if the authenticator for a given account fails to remove it due to
             * admin restrictions
             */
            public static final String REMOVE_ACCOUNT_FAILED_ADMIN_RESTRICTION =
                    PREFIX + "REMOVE_ACCOUNT_FAILED_ADMIN_RESTRICTION";

            /**
             * Url for learning more about IT admin policy disabling
             */
            public static final String IT_ADMIN_POLICY_DISABLING_INFO_URL =
                    PREFIX + "IT_ADMIN_POLICY_DISABLING_INFO_URL";

            /**
             * Title of dialog shown to ask for user consent for sharing a bugreport that was
             * requested
             * remotely by the IT administrator
             */
            public static final String SHARE_REMOTE_BUGREPORT_DIALOG_TITLE =
                    PREFIX + "SHARE_REMOTE_BUGREPORT_DIALOG_TITLE";

            /**
             * Message of a dialog shown to ask for user consent for sharing a bugreport that was
             * requested remotely by the IT administrator
             */
            public static final String SHARE_REMOTE_BUGREPORT_FINISHED_REQUEST_CONSENT =
                    PREFIX + "SHARE_REMOTE_BUGREPORT_FINISHED_REQUEST_CONSENT";

            /**
             * Message of a dialog shown to ask for user consent for sharing a bugreport that was
             * requested remotely by the IT administrator and it's still being taken
             */
            public static final String SHARE_REMOTE_BUGREPORT_NOT_FINISHED_REQUEST_CONSENT =
                    PREFIX + "SHARE_REMOTE_BUGREPORT_NOT_FINISHED_REQUEST_CONSENT";

            /**
             * Message of a dialog shown to inform that the remote bugreport that was requested
             * remotely by the IT administrator is still being taken and will be shared when
             * finished
             */
            public static final String SHARING_REMOTE_BUGREPORT_MESSAGE =
                    PREFIX + "SHARING_REMOTE_BUGREPORT_MESSAGE";

            /**
             * Managed device information screen title
             */
            public static final String MANAGED_DEVICE_INFO = PREFIX + "MANAGED_DEVICE_INFO";

            /**
             * Summary for managed device info section
             */
            public static final String MANAGED_DEVICE_INFO_SUMMARY =
                    PREFIX + "MANAGED_DEVICE_INFO_SUMMARY";

            /**
             * Summary for managed device info section including organization name
             */
            public static final String MANAGED_DEVICE_INFO_SUMMARY_WITH_NAME =
                    PREFIX + "MANAGED_DEVICE_INFO_SUMMARY_WITH_NAME";

            /**
             * Enterprise Privacy settings header, summarizing the powers that the admin has
             */
            public static final String ENTERPRISE_PRIVACY_HEADER =
                    PREFIX + "ENTERPRISE_PRIVACY_HEADER";

            /**
             * Types of information your organization can see section title
             */
            public static final String INFORMATION_YOUR_ORGANIZATION_CAN_SEE_TITLE =
                    PREFIX + "INFORMATION_YOUR_ORGANIZATION_CAN_SEE_TITLE";

            /**
             * Changes made by your organization's admin section title
             */
            public static final String CHANGES_MADE_BY_YOUR_ORGANIZATION_ADMIN_TITLE =
                    PREFIX + "CHANGES_MADE_BY_YOUR_ORGANIZATION_ADMIN_TITLE";

            /**
             * Your access to this device section title
             */
            public static final String YOUR_ACCESS_TO_THIS_DEVICE_TITLE =
                    PREFIX + "YOUR_ACCESS_TO_THIS_DEVICE_TITLE";

            /**
             * Things the admin can see: data associated with the work account
             */
            public static final String ADMIN_CAN_SEE_WORK_DATA_WARNING =
                    PREFIX + "ADMIN_CAN_SEE_WORK_DATA_WARNING";

            /**
             * Things the admin can see: Apps installed on the device
             */
            public static final String ADMIN_CAN_SEE_APPS_WARNING =
                    PREFIX + "ADMIN_CAN_SEE_APPS_WARNING";

            /**
             * Things the admin can see: Amount of time and data spent in each app
             */
            public static final String ADMIN_CAN_SEE_USAGE_WARNING =
                    PREFIX + "ADMIN_CAN_SEE_USAGE_WARNING";

            /**
             * Things the admin can see: Most recent network traffic log
             */
            public static final String ADMIN_CAN_SEE_NETWORK_LOGS_WARNING =
                    PREFIX + "ADMIN_CAN_SEE_NETWORK_LOGS_WARNING";
            /**
             * Things the admin can see: Most recent bug report
             */
            public static final String ADMIN_CAN_SEE_BUG_REPORT_WARNING =
                    PREFIX + "ADMIN_CAN_SEE_BUG_REPORT_WARNING";

            /**
             * Things the admin can see: Security logs
             */
            public static final String ADMIN_CAN_SEE_SECURITY_LOGS_WARNING =
                    PREFIX + "ADMIN_CAN_SEE_SECURITY_LOGS_WARNING";

            /**
             * Indicate that the admin never took a given action so far (e.g. did not retrieve
             * security logs or request bug reports).
             */
            public static final String ADMIN_ACTION_NONE = PREFIX + "ADMIN_ACTION_NONE";

            /**
             * Indicate that the admin installed one or more apps on the device
             */
            public static final String ADMIN_ACTION_APPS_INSTALLED =
                    PREFIX + "ADMIN_ACTION_APPS_INSTALLED";

            /**
             * Explaining that the number of apps is an estimation
             */
            public static final String ADMIN_ACTION_APPS_COUNT_ESTIMATED =
                    PREFIX + "ADMIN_ACTION_APPS_COUNT_ESTIMATED";

            /**
             * Indicating the minimum number of apps that a label refers to
             */
            public static final String ADMIN_ACTIONS_APPS_COUNT_MINIMUM =
                    PREFIX + "ADMIN_ACTIONS_APPS_COUNT_MINIMUM";

            /**
             * Indicate that the admin granted one or more apps access to the device's location
             */
            public static final String ADMIN_ACTION_ACCESS_LOCATION =
                    PREFIX + "ADMIN_ACTION_ACCESS_LOCATION";

            /**
             * Indicate that the admin granted one or more apps access to the microphone
             */
            public static final String ADMIN_ACTION_ACCESS_MICROPHONE =
                    PREFIX + "ADMIN_ACTION_ACCESS_MICROPHONE";

            /**
             * Indicate that the admin granted one or more apps access to the camera
             */
            public static final String ADMIN_ACTION_ACCESS_CAMERA =
                    PREFIX + "ADMIN_ACTION_ACCESS_CAMERA";

            /**
             * Indicate that the admin set one or more apps as defaults for common actions
             */
            public static final String ADMIN_ACTION_SET_DEFAULT_APPS =
                    PREFIX + "ADMIN_ACTION_SET_DEFAULT_APPS";

            /**
             * Indicate the number of apps that a label refers to
             */
            public static final String ADMIN_ACTIONS_APPS_COUNT =
                    PREFIX + "ADMIN_ACTIONS_APPS_COUNT";

            /**
             * Indicate that the current input method was set by the admin
             */
            public static final String ADMIN_ACTION_SET_CURRENT_INPUT_METHOD =
                    PREFIX + "ADMIN_ACTION_SET_CURRENT_INPUT_METHOD";

            /**
             * The input method set by the admin
             */
            public static final String ADMIN_ACTION_SET_INPUT_METHOD_NAME =
                    PREFIX + "ADMIN_ACTION_SET_INPUT_METHOD_NAME";

            /**
             * Indicate that a global HTTP proxy was set by the admin
             */
            public static final String ADMIN_ACTION_SET_HTTP_PROXY =
                    PREFIX + "ADMIN_ACTION_SET_HTTP_PROXY";

            /**
             * Summary for Enterprise Privacy settings, explaining what the user can expect to find
             * under it
             */
            public static final String WORK_PROFILE_PRIVACY_POLICY_INFO_SUMMARY =
                    PREFIX + "WORK_PROFILE_PRIVACY_POLICY_INFO_SUMMARY";

            /**
             * Setting on privacy settings screen that will show work policy info
             */
            public static final String WORK_PROFILE_PRIVACY_POLICY_INFO =
                    PREFIX + "WORK_PROFILE_PRIVACY_POLICY_INFO";

            /**
             * Search keywords for connected work and personal apps
             */
            public static final String CONNECTED_APPS_SEARCH_KEYWORDS =
                    PREFIX + "CONNECTED_APPS_SEARCH_KEYWORDS";

            /**
             * Work profile unification keywords
             */
            public static final String WORK_PROFILE_UNIFICATION_SEARCH_KEYWORDS =
                    PREFIX + "WORK_PROFILE_UNIFICATION_SEARCH_KEYWORDS";

            /**
             * Accounts keywords
             */
            public static final String ACCOUNTS_SEARCH_KEYWORDS =
                    PREFIX + "ACCOUNTS_SEARCH_KEYWORDS";

            /**
             * Summary for settings preference disabled by administrator
             */
            public static final String CONTROLLED_BY_ADMIN_SUMMARY =
                    PREFIX + "CONTROLLED_BY_ADMIN_SUMMARY";

            /**
             * User label for a work profile
             */
            public static final String WORK_PROFILE_USER_LABEL = PREFIX + "WORK_PROFILE_USER_LABEL";

            /**
             * Header for items under the work user
             */
            public static final String WORK_CATEGORY_HEADER = PREFIX + "WORK_CATEGORY_HEADER";

            /**
             * Header for items under the personal user
             */
            public static final String PERSONAL_CATEGORY_HEADER = PREFIX + "category_personal";
        }

        /**
         * Class containing the identifiers used to update device management-related system strings
         * in the SystemUi package.
         *
         * @hide
         */
        public static final class SystemUi {

            private SystemUi() {
            }

            private static final String PREFIX = "SystemUi.";

            /**
             * Label in quick settings for toggling work profile on/off.
             */
            public static final String QS_WORK_PROFILE_LABEL = PREFIX + "QS_WORK_PROFILE_LABEL";

            /**
             * Disclosure at the bottom of Quick Settings to indicate device management.
             */
            public static final String QS_MSG_MANAGEMENT = PREFIX + "QS_MSG_MANAGEMENT";

            /**
             * Similar to {@link #QS_MSG_MANAGEMENT} but accepts the organization name as a
             * param.
             */
            public static final String QS_MSG_NAMED_MANAGEMENT = PREFIX + "QS_MSG_NAMED_MANAGEMENT";

            /**
             * Disclosure at the bottom of Quick Settings to indicate device management monitoring.
             */
            public static final String QS_MSG_MANAGEMENT_MONITORING =
                    PREFIX + "QS_MSG_MANAGEMENT_MONITORING";

            /**
             * Similar to {@link #QS_MSG_MANAGEMENT_MONITORING} but accepts the
             * organization name as a param.
             */
            public static final String QS_MSG_NAMED_MANAGEMENT_MONITORING =
                    PREFIX + "QS_MSG_NAMED_MANAGEMENT_MONITORING";

            /**
             * Disclosure at the bottom of Quick Settings to indicate device management and the
             * device is connected to a VPN, accepts VPN name as a param.
             */
            public static final String QS_MSG_MANAGEMENT_NAMED_VPN =
                    PREFIX + "QS_MSG_MANAGEMENT_NAMED_VPN";

            /**
             * Similar to {@link #QS_MSG_MANAGEMENT_NAMED_VPN} but also accepts the
             * organization name as a param.
             */
            public static final String QS_MSG_NAMED_MANAGEMENT_NAMED_VPN =
                    PREFIX + "QS_MSG_NAMED_MANAGEMENT_NAMED_VPN";

            /**
             * Disclosure at the bottom of Quick Settings to indicate device management and the
             * device is connected to multiple VPNs.
             */
            public static final String QS_MSG_MANAGEMENT_MULTIPLE_VPNS =
                    PREFIX + "QS_MSG_MANAGEMENT_MULTIPLE_VPNS";

            /**
             * Similar to {@link #QS_MSG_MANAGEMENT_MULTIPLE_VPNS} but also accepts the
             * organization name as a param.
             */
            public static final String QS_MSG_NAMED_MANAGEMENT_MULTIPLE_VPNS =
                    PREFIX + "QS_MSG_NAMED_MANAGEMENT_MULTIPLE_VPNS";

            /**
             * Disclosure at the bottom of Quick Settings to indicate work profile monitoring.
             */
            public static final String QS_MSG_WORK_PROFILE_MONITORING =
                    PREFIX + "QS_MSG_WORK_PROFILE_MONITORING";

            /**
             * Similar to {@link #QS_MSG_WORK_PROFILE_MONITORING} but accepts the
             * organization name as a param.
             */
            public static final String QS_MSG_NAMED_WORK_PROFILE_MONITORING =
                    PREFIX + "QS_MSG_NAMED_WORK_PROFILE_MONITORING";

            /**
             * Disclosure at the bottom of Quick Settings to indicate network activity is visible to
             * admin.
             */
            public static final String QS_MSG_WORK_PROFILE_NETWORK =
                    PREFIX + "QS_MSG_WORK_PROFILE_NETWORK";

            /**
             * Disclosure at the bottom of Quick Settings to indicate work profile is connected to a
             * VPN, accepts VPN name as a param.
             */
            public static final String QS_MSG_WORK_PROFILE_NAMED_VPN =
                    PREFIX + "QS_MSG_WORK_PROFILE_NAMED_VPN";

            /**
             * Disclosure at the bottom of Quick Settings to indicate personal profile is connected
             * to a VPN, accepts VPN name as a param.
             */
            public static final String QS_MSG_PERSONAL_PROFILE_NAMED_VPN =
                    PREFIX + "QS_MSG_PERSONAL_PROFILE_NAMED_VPN";

            /**
             * Title for dialog to indicate device management.
             */
            public static final String QS_DIALOG_MANAGEMENT_TITLE =
                    PREFIX + "QS_DIALOG_MANAGEMENT_TITLE";

            /**
             * Label for button in the device management dialog to open a page with more information
             * on the admin's abilities.
             */
            public static final String QS_DIALOG_VIEW_POLICIES =
                    PREFIX + "QS_DIALOG_VIEW_POLICIES";

            /**
             * Description for device management dialog to indicate admin abilities.
             */
            public static final String QS_DIALOG_MANAGEMENT = PREFIX + "QS_DIALOG_MANAGEMENT";

            /**
             * Similar to {@link #QS_DIALOG_MANAGEMENT} but accepts the organization name as a
             * param.
             */
            public static final String QS_DIALOG_NAMED_MANAGEMENT =
                    PREFIX + "QS_DIALOG_NAMED_MANAGEMENT";

            /**
             * Description for the managed device certificate authorities in the device management
             * dialog.
             */
            public static final String QS_DIALOG_MANAGEMENT_CA_CERT =
                    PREFIX + "QS_DIALOG_MANAGEMENT_CA_CERT";

            /**
             * Description for the work profile certificate authorities in the device management
             * dialog.
             */
            public static final String QS_DIALOG_WORK_PROFILE_CA_CERT =
                    PREFIX + "QS_DIALOG_WORK_PROFILE_CA_CERT";

            /**
             * Description for the managed device network logging in the device management dialog.
             */
            public static final String QS_DIALOG_MANAGEMENT_NETWORK =
                    PREFIX + "QS_DIALOG_MANAGEMENT_NETWORK";

            /**
             * Description for the work profile network logging in the device management dialog.
             */
            public static final String QS_DIALOG_WORK_PROFILE_NETWORK =
                    PREFIX + "QS_DIALOG_WORK_PROFILE_NETWORK";

            /**
             * Description for an active VPN in the device management dialog, accepts VPN name as a
             * param.
             */
            public static final String QS_DIALOG_MANAGEMENT_NAMED_VPN =
                    PREFIX + "QS_DIALOG_MANAGEMENT_NAMED_VPN";

            /**
             * Description for two active VPN in the device management dialog, accepts two VPN names
             * as params.
             */
            public static final String QS_DIALOG_MANAGEMENT_TWO_NAMED_VPN =
                    PREFIX + "QS_DIALOG_MANAGEMENT_TWO_NAMED_VPN";

            /**
             * Description for an active work profile VPN in the device management dialog, accepts
             * VPN name as a param.
             */
            public static final String QS_DIALOG_WORK_PROFILE_NAMED_VPN =
                    PREFIX + "QS_DIALOG_WORK_PROFILE_NAMED_VPN";

            /**
             * Description for an active personal profile VPN in the device management dialog,
             * accepts VPN name as a param.
             */
            public static final String QS_DIALOG_PERSONAL_PROFILE_NAMED_VPN =
                    PREFIX + "QS_DIALOG_PERSONAL_PROFILE_NAMED_VPN";

            /**
             * Content of a dialog shown when the user only has one attempt left to provide the
             * correct pin before the work profile is removed.
             */
            public static final String BIOMETRIC_DIALOG_WORK_PIN_LAST_ATTEMPT =
                    PREFIX + "BIOMETRIC_DIALOG_WORK_PIN_LAST_ATTEMPT";

            /**
             * Content of a dialog shown when the user only has one attempt left to provide the
             * correct pattern before the work profile is removed.
             */
            public static final String BIOMETRIC_DIALOG_WORK_PATTERN_LAST_ATTEMPT =
                    PREFIX + "BIOMETRIC_DIALOG_WORK_PATTERN_LAST_ATTEMPT";

            /**
             * Content of a dialog shown when the user only has one attempt left to provide the
             * correct password before the work profile is removed.
             */
            public static final String BIOMETRIC_DIALOG_WORK_PASSWORD_LAST_ATTEMPT =
                    PREFIX + "BIOMETRIC_DIALOG_WORK_PASSWORD_LAST_ATTEMPT";

            /**
             * Content of a dialog shown when the user has failed to provide the work lock too many
             * times and the work profile is removed.
             */
            public static final String BIOMETRIC_DIALOG_WORK_LOCK_FAILED_ATTEMPTS =
                    PREFIX + "BIOMETRIC_DIALOG_WORK_LOCK_FAILED_ATTEMPTS";

            /**
             * Accessibility label for managed profile icon in the status bar
             */
            public static final String STATUS_BAR_WORK_ICON_ACCESSIBILITY =
                    PREFIX + "STATUS_BAR_WORK_ICON_ACCESSIBILITY";

            /**
             * Text appended to privacy dialog, indicating that the application is in the work
             * profile.
             */
            public static final String ONGOING_PRIVACY_DIALOG_WORK =
                    PREFIX + "ONGOING_PRIVACY_DIALOG_WORK";

            /**
             * Text on keyguard screen indicating device management.
             */
            public static final String KEYGUARD_MANAGEMENT_DISCLOSURE =
                    PREFIX + "KEYGUARD_MANAGEMENT_DISCLOSURE";

            /**
             * Similar to {@link #KEYGUARD_MANAGEMENT_DISCLOSURE} but also accepts organization name
             * as a param.
             */
            public static final String KEYGUARD_NAMED_MANAGEMENT_DISCLOSURE =
                    PREFIX + "KEYGUARD_NAMED_MANAGEMENT_DISCLOSURE";

            /**
             * Content description for the work profile lock screen.
             */
            public static final String WORK_LOCK_ACCESSIBILITY = PREFIX + "WORK_LOCK_ACCESSIBILITY";
        }

        /**
         * Class containing the identifiers used to update device management-related system strings
         * in the android core package.
         *
         * @hide
         */
        public static final class Core {

            private Core() {
            }

            private static final String PREFIX = "Core.";
            /**
             * Notification title when the system deletes the work profile.
             */
            public static final String WORK_PROFILE_DELETED_TITLE =
                    PREFIX + "WORK_PROFILE_DELETED_TITLE";

            /**
             * Content text for the "Work profile deleted" notification to indicates that a work
             * profile has been deleted because the maximum failed password attempts as been
             * reached.
             */
            public static final String WORK_PROFILE_DELETED_FAILED_PASSWORD_ATTEMPTS_MESSAGE =
                    PREFIX + "WORK_PROFILE_DELETED_FAILED_PASSWORD_ATTEMPTS_MESSAGE";

            /**
             * Content text for the "Work profile deleted" notification to indicate that a work
             * profile has been deleted.
             */
            public static final String WORK_PROFILE_DELETED_GENERIC_MESSAGE =
                    PREFIX + "WORK_PROFILE_DELETED_GENERIC_MESSAGE";

            /**
             * Content text for the "Work profile deleted" notification to indicates that a work
             * profile has been deleted because the admin of an organization-owned device has
             * relinquishes it.
             */
            public static final String WORK_PROFILE_DELETED_ORG_OWNED_MESSAGE =
                    PREFIX + "WORK_PROFILE_DELETED_ORG_OWNED_MESSAGE";

            /**
             * Notification title for when personal apps are either blocked or will be blocked
             * soon due to a work policy from their admin.
             */
            public static final String PERSONAL_APP_SUSPENSION_TITLE =
                    PREFIX + "PERSONAL_APP_SUSPENSION_TITLE";

            /**
             * Content text for the personal app suspension notification to indicate that personal
             * apps are blocked due to a work policy from the admin.
             */
            public static final String PERSONAL_APP_SUSPENSION_MESSAGE =
                    PREFIX + "PERSONAL_APP_SUSPENSION_MESSAGE";

            /**
             * Content text for the personal app suspension notification to indicate that personal
             * apps will be blocked at a particular time due to a work policy from their admin.
             * It also explains for how many days the profile is allowed to be off.
             * <ul>Takes in the following as params:
             * <li> The date that the personal apps will get suspended at</li>
             * <li> The time that the personal apps will get suspended at</li>
             * <li> The max allowed days for the work profile stay switched off</li>
             * </ul>
             */
            public static final String PERSONAL_APP_SUSPENSION_SOON_MESSAGE =
                    PREFIX + "PERSONAL_APP_SUSPENSION_SOON_MESSAGE";

            /**
             * Title for the button that turns work profile in the personal app suspension
             * notification.
             */
            public static final String PERSONAL_APP_SUSPENSION_TURN_ON_PROFILE =
                    PREFIX + "PERSONAL_APP_SUSPENSION_TURN_ON_PROFILE";

            /**
             * A toast message displayed when printing is attempted but disabled by policy, accepts
             * admin name as a param.
             */
            public static final String PRINTING_DISABLED_NAMED_ADMIN =
                    PREFIX + "PRINTING_DISABLED_NAMED_ADMIN";

            /**
             * Notification title to indicate that the device owner has changed the location
             * settings.
             */
            public static final String LOCATION_CHANGED_TITLE = PREFIX + "LOCATION_CHANGED_TITLE";

            /**
             * Content text for the location changed notification to indicate that the device owner
             * has changed the location settings.
             */
            public static final String LOCATION_CHANGED_MESSAGE =
                    PREFIX + "LOCATION_CHANGED_MESSAGE";

            /**
             * Notification title to indicate that the device is managed and network logging was
             * activated by a device owner.
             */
            public static final String NETWORK_LOGGING_TITLE = PREFIX + "NETWORK_LOGGING_TITLE";

            /**
             * Content text for the network logging notification to indicate that the device is
             * managed and network logging was activated by a device owner.
             */
            public static final String NETWORK_LOGGING_MESSAGE = PREFIX + "NETWORK_LOGGING_MESSAGE";

            /**
             * Content description of the work profile icon in the notifications.
             */
            public static final String NOTIFICATION_WORK_PROFILE_CONTENT_DESCRIPTION =
                    PREFIX + "NOTIFICATION_WORK_PROFILE_CONTENT_DESCRIPTION";

            /**
             * Notification channel name for high-priority alerts from the user's IT admin for key
             * updates about the device.
             */
            public static final String NOTIFICATION_CHANNEL_DEVICE_ADMIN =
                    PREFIX + "NOTIFICATION_CHANNEL_DEVICE_ADMIN";

            /**
             * Label returned from
             * {@link android.content.pm.CrossProfileApps#getProfileSwitchingLabel(UserHandle)}
             * that calling app can show to user for the semantic of switching to work profile.
             */
            public static final String SWITCH_TO_WORK_LABEL = PREFIX + "SWITCH_TO_WORK_LABEL";

            /**
             * Label returned from
             * {@link android.content.pm.CrossProfileApps#getProfileSwitchingLabel(UserHandle)}
             * that calling app can show to user for the semantic of switching to personal profile.
             */
            public static final String SWITCH_TO_PERSONAL_LABEL =
                    PREFIX + "SWITCH_TO_PERSONAL_LABEL";

            /**
             * Message to show when an intent automatically switches users into the work profile.
             */
            public static final String FORWARD_INTENT_TO_WORK = PREFIX + "FORWARD_INTENT_TO_WORK";

            /**
             * Message to show when an intent automatically switches users into the personal
             * profile.
             */
            public static final String FORWARD_INTENT_TO_PERSONAL =
                    PREFIX + "FORWARD_INTENT_TO_PERSONAL";

            /**
             * Text for the toast that is shown when the user clicks on a launcher that doesn't
             * support the work profile, takes in the launcher name as a param.
             */
            public static final String RESOLVER_WORK_PROFILE_NOT_SUPPORTED =
                    PREFIX + "RESOLVER_WORK_PROFILE_NOT_SUPPORTED";

            /**
             * Label for the personal tab in the {@link com.android.internal.app.ResolverActivity).
             */
            public static final String RESOLVER_PERSONAL_TAB = PREFIX + "RESOLVER_PERSONAL_TAB";

            /**
             * Label for the work tab in the {@link com.android.internal.app.ResolverActivity).
             */
            public static final String RESOLVER_WORK_TAB = PREFIX + "RESOLVER_WORK_TAB";

            /**
             * Accessibility Label for the personal tab in the
             * {@link com.android.internal.app.ResolverActivity).
             */
            public static final String RESOLVER_PERSONAL_TAB_ACCESSIBILITY =
                    PREFIX + "RESOLVER_PERSONAL_TAB_ACCESSIBILITY";

            /**
             * Accessibility Label for the work tab in the
             * {@link com.android.internal.app.ResolverActivity).
             */
            public static final String RESOLVER_WORK_TAB_ACCESSIBILITY =
                    PREFIX + "RESOLVER_WORK_TAB_ACCESSIBILITY";

            /**
             * Title for resolver screen to let the user know that their IT admin doesn't allow
             * them to share this content across profiles.
             */
            public static final String RESOLVER_CROSS_PROFILE_BLOCKED_TITLE =
                    PREFIX + "RESOLVER_CROSS_PROFILE_BLOCKED_TITLE";

            /**
             * Description for resolver screen to let the user know that their IT admin doesn't
             * allow them to share this content with apps in their personal profile.
             */
            public static final String RESOLVER_CANT_SHARE_WITH_PERSONAL =
                    PREFIX + "RESOLVER_CANT_SHARE_WITH_PERSONAL";

            /**
             * Description for resolver screen to let the user know that their IT admin doesn't
             * allow them to share this content with apps in their work profile.
             */
            public static final String RESOLVER_CANT_SHARE_WITH_WORK =
                    PREFIX + "RESOLVER_CANT_SHARE_WITH_WORK";

            /**
             * Description for resolver screen to let the user know that their IT admin doesn't
             * allow them to open this specific content with an app in their personal profile.
             */
            public static final String RESOLVER_CANT_ACCESS_PERSONAL =
                    PREFIX + "RESOLVER_CANT_ACCESS_PERSONAL";

            /**
             * Description for resolver screen to let the user know that their IT admin doesn't
             * allow them to open this specific content with an app in their work profile.
             */
            public static final String RESOLVER_CANT_ACCESS_WORK =
                    PREFIX + "RESOLVER_CANT_ACCESS_WORK";

            /**
             * Title for resolver screen to let the user know that they need to turn on work apps
             * in order to share or open content
             */
            public static final String RESOLVER_WORK_PAUSED_TITLE =
                    PREFIX + "RESOLVER_WORK_PAUSED_TITLE";

            /**
             * Text on resolver screen to let the user know that their current work apps don't
             * support the specific content.
             */
            public static final String RESOLVER_NO_WORK_APPS = PREFIX + "RESOLVER_NO_WORK_APPS";

            /**
             * Text on resolver screen to let the user know that their current personal apps don't
             * support the specific content.
             */
            public static final String RESOLVER_NO_PERSONAL_APPS =
                    PREFIX + "RESOLVER_NO_PERSONAL_APPS";

            /**
             * Message informing user that the adding the account is disallowed by an administrator.
             */
            public static final String CANT_ADD_ACCOUNT_MESSAGE =
                    PREFIX + "CANT_ADD_ACCOUNT_MESSAGE";

            /**
             * Notification shown when device owner silently installs a package.
             */
            public static final String PACKAGE_INSTALLED_BY_DO = PREFIX + "PACKAGE_INSTALLED_BY_DO";

            /**
             * Notification shown when device owner silently updates a package.
             */
            public static final String PACKAGE_UPDATED_BY_DO = PREFIX + "PACKAGE_UPDATED_BY_DO";

            /**
             * Notification shown when device owner silently deleted a package.
             */
            public static final String PACKAGE_DELETED_BY_DO = PREFIX + "PACKAGE_DELETED_BY_DO";

            /**
             * Title for dialog shown when user tries to open a work app when the work profile is
             * turned off, confirming that the user wants to turn on access to their
             * work apps.
             */
            public static final String UNLAUNCHABLE_APP_WORK_PAUSED_TITLE =
                    PREFIX + "UNLAUNCHABLE_APP_WORK_PAUSED_TITLE";

            /**
             * Text for dialog shown when user tries to open a work app when the work profile is
             * turned off, confirming that the user wants to turn on access to their
             * work apps.
             */
            public static final String UNLAUNCHABLE_APP_WORK_PAUSED_MESSAGE =
                    PREFIX + "UNLAUNCHABLE_APP_WORK_PAUSED_MESSAGE";

            /**
             * Notification title shown when work profile is credential encrypted and requires
             * the user to unlock before it's usable.
             */
            public static final String PROFILE_ENCRYPTED_TITLE = PREFIX + "PROFILE_ENCRYPTED_TITLE";

            /**
             * Notification detail shown when work profile is credential encrypted and requires
             * the user to unlock before it's usable.
             */
            public static final String PROFILE_ENCRYPTED_DETAIL =
                    PREFIX + "PROFILE_ENCRYPTED_DETAIL";

            /**
             * Notification message shown when work profile is credential encrypted and requires
             * the user to unlock before it's usable.
             */
            public static final String PROFILE_ENCRYPTED_MESSAGE =
                    PREFIX + "PROFILE_ENCRYPTED_MESSAGE";

            /**
             * Used to badge a string with "Work" for work profile content, e.g. "Work Email".
             * Accepts the string to badge as an argument.
             * <p>See {@link android.content.pm.PackageManager#getUserBadgedLabel}</p>
             */
            public static final String WORK_PROFILE_BADGED_LABEL =
                    PREFIX + "WORK_PROFILE_BADGED_LABEL";
        }

        /**
         * Class containing the identifiers used to update device management-related system strings
         * in the Dialer app.
         *
         * @hide
         */
        public static final class Telecomm {

            private Telecomm() {
            }

            private static final String PREFIX = "Telecomm.";

            /**
             * Missed call notification label, used when there's exactly one missed call from work
             * contact.
             */
            public static final String NOTIFICATION_MISSED_WORK_CALL_TITLE =
                    PREFIX + "NOTIFICATION_MISSED_WORK_CALL_TITLE";
        }

        /**
         * Class containing the identifiers used to update device management-related system strings
         * for the permission settings.
         */
        public static final class PermissionSettings {

            private PermissionSettings() {
            }

            private static final String PREFIX = "PermissionSettings.";

            /**
             * Summary of a permission switch in Settings when the background access is denied by an
             * admin.
             */
            public static final String BACKGROUND_ACCESS_DISABLED_BY_ADMIN_MESSAGE =
                    PREFIX + "BACKGROUND_ACCESS_DISABLED_BY_ADMIN_MESSAGE";

            /**
             * Summary of a permission switch in Settings when the background access is enabled by
             * an admin.
             */
            public static final String BACKGROUND_ACCESS_ENABLED_BY_ADMIN_MESSAGE =
                    PREFIX + "BACKGROUND_ACCESS_ENABLED_BY_ADMIN_MESSAGE";

            /**
             * Summary of a permission switch in Settings when the foreground access is enabled by
             * an admin.
             */
            public static final String FOREGROUND_ACCESS_ENABLED_BY_ADMIN_MESSAGE =
                    PREFIX + "FOREGROUND_ACCESS_ENABLED_BY_ADMIN_MESSAGE";

            /**
             * Body of the notification shown to notify the user that the location permission has
             * been granted to an app, accepts app name as a param.
             */
            public static final String LOCATION_AUTO_GRANTED_MESSAGE =
                    PREFIX + "LOCATION_AUTO_GRANTED_MESSAGE";
        }

        /**
         * Class containing the identifiers used to update device management-related system strings
         * for the default app settings.
         */
        public static final class DefaultAppSettings {

            private DefaultAppSettings() {
            }

            private static final String PREFIX = "DefaultAppSettings.";

            /**
             * Title for settings page to show default apps for work.
             */
            public static final String WORK_PROFILE_DEFAULT_APPS_TITLE =
                    PREFIX + "WORK_PROFILE_DEFAULT_APPS_TITLE";

            /**
             * Summary indicating that a home role holder app is missing work profile support.
             */
            public static final String HOME_MISSING_WORK_PROFILE_SUPPORT_MESSAGE =
                    PREFIX + "HOME_MISSING_WORK_PROFILE_SUPPORT_MESSAGE";
        }
    }
}
