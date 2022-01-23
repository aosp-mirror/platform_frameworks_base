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

import static android.app.admin.DevicePolicyResources.Strings.Core.CANT_ADD_ACCOUNT_MESSAGE;
import static android.app.admin.DevicePolicyResources.Strings.Core.FORWARD_INTENT_TO_PERSONAL;
import static android.app.admin.DevicePolicyResources.Strings.Core.FORWARD_INTENT_TO_WORK;
import static android.app.admin.DevicePolicyResources.Strings.Core.LOCATION_CHANGED_MESSAGE;
import static android.app.admin.DevicePolicyResources.Strings.Core.LOCATION_CHANGED_TITLE;
import static android.app.admin.DevicePolicyResources.Strings.Core.NETWORK_LOGGING_MESSAGE;
import static android.app.admin.DevicePolicyResources.Strings.Core.NETWORK_LOGGING_TITLE;
import static android.app.admin.DevicePolicyResources.Strings.Core.NOTIFICATION_CHANNEL_DEVICE_ADMIN;
import static android.app.admin.DevicePolicyResources.Strings.Core.NOTIFICATION_WORK_PROFILE_CONTENT_DESCRIPTION;
import static android.app.admin.DevicePolicyResources.Strings.Core.PACKAGE_DELETED_BY_DO;
import static android.app.admin.DevicePolicyResources.Strings.Core.PACKAGE_INSTALLED_BY_DO;
import static android.app.admin.DevicePolicyResources.Strings.Core.PACKAGE_UPDATED_BY_DO;
import static android.app.admin.DevicePolicyResources.Strings.Core.PERSONAL_APP_SUSPENSION_MESSAGE;
import static android.app.admin.DevicePolicyResources.Strings.Core.PERSONAL_APP_SUSPENSION_SOON_MESSAGE;
import static android.app.admin.DevicePolicyResources.Strings.Core.PERSONAL_APP_SUSPENSION_TITLE;
import static android.app.admin.DevicePolicyResources.Strings.Core.PERSONAL_APP_SUSPENSION_TURN_ON_PROFILE;
import static android.app.admin.DevicePolicyResources.Strings.Core.PRINTING_DISABLED_NAMED_ADMIN;
import static android.app.admin.DevicePolicyResources.Strings.Core.PROFILE_ENCRYPTED_DETAIL;
import static android.app.admin.DevicePolicyResources.Strings.Core.PROFILE_ENCRYPTED_MESSAGE;
import static android.app.admin.DevicePolicyResources.Strings.Core.PROFILE_ENCRYPTED_TITLE;
import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_CANT_ACCESS_PERSONAL;
import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_CANT_ACCESS_WORK;
import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_CANT_SHARE_WITH_PERSONAL;
import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_CANT_SHARE_WITH_WORK;
import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_CROSS_PROFILE_BLOCKED_TITLE;
import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_NO_PERSONAL_APPS;
import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_NO_WORK_APPS;
import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_PERSONAL_TAB;
import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_PERSONAL_TAB_ACCESSIBILITY;
import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_WORK_PAUSED_TITLE;
import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_WORK_PROFILE_NOT_SUPPORTED;
import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_WORK_TAB;
import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_WORK_TAB_ACCESSIBILITY;
import static android.app.admin.DevicePolicyResources.Strings.Core.SWITCH_TO_PERSONAL_LABEL;
import static android.app.admin.DevicePolicyResources.Strings.Core.SWITCH_TO_WORK_LABEL;
import static android.app.admin.DevicePolicyResources.Strings.Core.UNLAUNCHABLE_APP_WORK_PAUSED_MESSAGE;
import static android.app.admin.DevicePolicyResources.Strings.Core.UNLAUNCHABLE_APP_WORK_PAUSED_TITLE;
import static android.app.admin.DevicePolicyResources.Strings.Core.WORK_PROFILE_BADGED_LABEL;
import static android.app.admin.DevicePolicyResources.Strings.Core.WORK_PROFILE_DELETED_FAILED_PASSWORD_ATTEMPTS_MESSAGE;
import static android.app.admin.DevicePolicyResources.Strings.Core.WORK_PROFILE_DELETED_GENERIC_MESSAGE;
import static android.app.admin.DevicePolicyResources.Strings.Core.WORK_PROFILE_DELETED_ORG_OWNED_MESSAGE;
import static android.app.admin.DevicePolicyResources.Strings.Core.WORK_PROFILE_DELETED_TITLE;
import static android.app.admin.DevicePolicyResources.Strings.Launcher.ALL_APPS_PERSONAL_TAB;
import static android.app.admin.DevicePolicyResources.Strings.Launcher.ALL_APPS_PERSONAL_TAB_ACCESSIBILITY;
import static android.app.admin.DevicePolicyResources.Strings.Launcher.ALL_APPS_WORK_TAB;
import static android.app.admin.DevicePolicyResources.Strings.Launcher.ALL_APPS_WORK_TAB_ACCESSIBILITY;
import static android.app.admin.DevicePolicyResources.Strings.Launcher.DISABLED_BY_ADMIN_MESSAGE;
import static android.app.admin.DevicePolicyResources.Strings.Launcher.WIDGETS_PERSONAL_TAB;
import static android.app.admin.DevicePolicyResources.Strings.Launcher.WIDGETS_WORK_TAB;
import static android.app.admin.DevicePolicyResources.Strings.Launcher.WORK_FOLDER_NAME;
import static android.app.admin.DevicePolicyResources.Strings.Launcher.WORK_PROFILE_EDU;
import static android.app.admin.DevicePolicyResources.Strings.Launcher.WORK_PROFILE_EDU_ACCEPT;
import static android.app.admin.DevicePolicyResources.Strings.Launcher.WORK_PROFILE_ENABLE_BUTTON;
import static android.app.admin.DevicePolicyResources.Strings.Launcher.WORK_PROFILE_PAUSED_DESCRIPTION;
import static android.app.admin.DevicePolicyResources.Strings.Launcher.WORK_PROFILE_PAUSED_TITLE;
import static android.app.admin.DevicePolicyResources.Strings.Launcher.WORK_PROFILE_PAUSE_BUTTON;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.BIOMETRIC_DIALOG_WORK_LOCK_FAILED_ATTEMPTS;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.BIOMETRIC_DIALOG_WORK_PASSWORD_LAST_ATTEMPT;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.BIOMETRIC_DIALOG_WORK_PATTERN_LAST_ATTEMPT;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.BIOMETRIC_DIALOG_WORK_PIN_LAST_ATTEMPT;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.KEYGUARD_MANAGEMENT_DISCLOSURE;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.KEYGUARD_NAMED_MANAGEMENT_DISCLOSURE;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.ONGOING_PRIVACY_DIALOG_WORK;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_MANAGEMENT;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_MANAGEMENT_CA_CERT;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_MANAGEMENT_NAMED_VPN;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_MANAGEMENT_NETWORK;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_MANAGEMENT_TITLE;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_MANAGEMENT_TWO_NAMED_VPN;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_NAMED_MANAGEMENT;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_PERSONAL_PROFILE_NAMED_VPN;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_VIEW_POLICIES;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_WORK_PROFILE_CA_CERT;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_WORK_PROFILE_NAMED_VPN;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_WORK_PROFILE_NETWORK;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_MSG_MANAGEMENT;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_MSG_MANAGEMENT_MONITORING;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_MSG_MANAGEMENT_MULTIPLE_VPNS;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_MSG_MANAGEMENT_NAMED_VPN;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_MSG_NAMED_MANAGEMENT;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_MSG_NAMED_MANAGEMENT_MONITORING;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_MSG_NAMED_MANAGEMENT_MULTIPLE_VPNS;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_MSG_NAMED_MANAGEMENT_NAMED_VPN;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_MSG_NAMED_WORK_PROFILE_MONITORING;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_MSG_PERSONAL_PROFILE_NAMED_VPN;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_MSG_WORK_PROFILE_MONITORING;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_MSG_WORK_PROFILE_NAMED_VPN;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_MSG_WORK_PROFILE_NETWORK;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.STATUS_BAR_WORK_ICON_ACCESSIBILITY;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.WORK_LOCK_ACCESSIBILITY;

import android.annotation.IntDef;
import android.annotation.StringDef;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.os.UserHandle;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashSet;
import java.util.Set;

/**
 * Class containing the required identifiers to update device management resources.
 *
 * <p>See {@link DevicePolicyManager#getDrawable} and
 * {@code DevicePolicyManager#getString}.
 */
public final class DevicePolicyResources {

    /**
     * Resource identifiers used to update device management-related system drawable resources.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            Drawable.INVALID_ID,
            Drawable.WORK_PROFILE_ICON_BADGE,
            Drawable.WORK_PROFILE_ICON,
            Drawable.WORK_PROFILE_OFF_ICON,
            Drawable.WORK_PROFILE_USER_ICON
    })
    public @interface UpdatableDrawableId {}

    /**
     * Identifiers to specify the desired style for the updatable device management system
     * resource.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            Drawable.Style.SOLID_COLORED,
            Drawable.Style.SOLID_NOT_COLORED,
            Drawable.Style.OUTLINE,
    })
    public @interface UpdatableDrawableStyle {}

    /**
     * Identifiers to specify the location if the updatable device management system resource.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            Drawable.Source.UNDEFINED,
            Drawable.Source.NOTIFICATION,
            Drawable.Source.PROFILE_SWITCH_ANIMATION,
            Drawable.Source.HOME_WIDGET,
            Drawable.Source.LAUNCHER_OFF_BUTTON,
            Drawable.Source.QUICK_SETTINGS,
            Drawable.Source.STATUS_BAR
    })
    public @interface UpdatableDrawableSource {}

    /**
     * Resource identifiers used to update device management-related string resources.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef({
            // Launcher Strings
            WORK_PROFILE_EDU, WORK_PROFILE_EDU_ACCEPT, WORK_PROFILE_PAUSED_TITLE,
            WORK_PROFILE_PAUSED_DESCRIPTION, WORK_PROFILE_PAUSE_BUTTON, WORK_PROFILE_ENABLE_BUTTON,
            ALL_APPS_WORK_TAB, ALL_APPS_PERSONAL_TAB, ALL_APPS_WORK_TAB_ACCESSIBILITY,
            ALL_APPS_PERSONAL_TAB_ACCESSIBILITY, WORK_FOLDER_NAME, WIDGETS_WORK_TAB,
            WIDGETS_PERSONAL_TAB, DISABLED_BY_ADMIN_MESSAGE,

            // SysUI Strings
            QS_MSG_MANAGEMENT, QS_MSG_NAMED_MANAGEMENT, QS_MSG_MANAGEMENT_MONITORING,
            QS_MSG_NAMED_MANAGEMENT_MONITORING, QS_MSG_MANAGEMENT_NAMED_VPN,
            QS_MSG_NAMED_MANAGEMENT_NAMED_VPN, QS_MSG_MANAGEMENT_MULTIPLE_VPNS,
            QS_MSG_NAMED_MANAGEMENT_MULTIPLE_VPNS, QS_MSG_WORK_PROFILE_MONITORING,
            QS_MSG_NAMED_WORK_PROFILE_MONITORING, QS_MSG_WORK_PROFILE_NETWORK,
            QS_MSG_WORK_PROFILE_NAMED_VPN, QS_MSG_PERSONAL_PROFILE_NAMED_VPN,
            QS_DIALOG_MANAGEMENT_TITLE, QS_DIALOG_VIEW_POLICIES, QS_DIALOG_MANAGEMENT,
            QS_DIALOG_NAMED_MANAGEMENT, QS_DIALOG_MANAGEMENT_CA_CERT,
            QS_DIALOG_WORK_PROFILE_CA_CERT, QS_DIALOG_MANAGEMENT_NETWORK,
            QS_DIALOG_WORK_PROFILE_NETWORK, QS_DIALOG_MANAGEMENT_NAMED_VPN,
            QS_DIALOG_MANAGEMENT_TWO_NAMED_VPN, QS_DIALOG_WORK_PROFILE_NAMED_VPN,
            QS_DIALOG_PERSONAL_PROFILE_NAMED_VPN, BIOMETRIC_DIALOG_WORK_PIN_LAST_ATTEMPT,
            BIOMETRIC_DIALOG_WORK_PATTERN_LAST_ATTEMPT, BIOMETRIC_DIALOG_WORK_PASSWORD_LAST_ATTEMPT,
            BIOMETRIC_DIALOG_WORK_LOCK_FAILED_ATTEMPTS, STATUS_BAR_WORK_ICON_ACCESSIBILITY,
            ONGOING_PRIVACY_DIALOG_WORK, KEYGUARD_MANAGEMENT_DISCLOSURE,
            KEYGUARD_NAMED_MANAGEMENT_DISCLOSURE, WORK_LOCK_ACCESSIBILITY,

            // Core Strings
            WORK_PROFILE_DELETED_TITLE, WORK_PROFILE_DELETED_FAILED_PASSWORD_ATTEMPTS_MESSAGE,
            WORK_PROFILE_DELETED_GENERIC_MESSAGE, WORK_PROFILE_DELETED_ORG_OWNED_MESSAGE,
            PERSONAL_APP_SUSPENSION_TITLE, PERSONAL_APP_SUSPENSION_MESSAGE,
            PERSONAL_APP_SUSPENSION_SOON_MESSAGE, PERSONAL_APP_SUSPENSION_TURN_ON_PROFILE,
            PRINTING_DISABLED_NAMED_ADMIN, LOCATION_CHANGED_TITLE, LOCATION_CHANGED_MESSAGE,
            NETWORK_LOGGING_TITLE,  NETWORK_LOGGING_MESSAGE,
            NOTIFICATION_WORK_PROFILE_CONTENT_DESCRIPTION, NOTIFICATION_CHANNEL_DEVICE_ADMIN,
            SWITCH_TO_WORK_LABEL, SWITCH_TO_PERSONAL_LABEL, FORWARD_INTENT_TO_WORK,
            FORWARD_INTENT_TO_PERSONAL, RESOLVER_WORK_PROFILE_NOT_SUPPORTED, RESOLVER_PERSONAL_TAB,
            RESOLVER_WORK_TAB, RESOLVER_PERSONAL_TAB_ACCESSIBILITY, RESOLVER_WORK_TAB_ACCESSIBILITY,
            RESOLVER_CROSS_PROFILE_BLOCKED_TITLE, RESOLVER_CANT_SHARE_WITH_PERSONAL,
            RESOLVER_CANT_SHARE_WITH_WORK, RESOLVER_CANT_ACCESS_PERSONAL, RESOLVER_CANT_ACCESS_WORK,
            RESOLVER_WORK_PAUSED_TITLE, RESOLVER_NO_WORK_APPS, RESOLVER_NO_PERSONAL_APPS,
            CANT_ADD_ACCOUNT_MESSAGE, PACKAGE_INSTALLED_BY_DO, PACKAGE_UPDATED_BY_DO,
            PACKAGE_DELETED_BY_DO, UNLAUNCHABLE_APP_WORK_PAUSED_TITLE,
            UNLAUNCHABLE_APP_WORK_PAUSED_MESSAGE, PROFILE_ENCRYPTED_TITLE, PROFILE_ENCRYPTED_DETAIL,
            PROFILE_ENCRYPTED_MESSAGE, WORK_PROFILE_BADGED_LABEL
    })
    public @interface UpdatableStringId {
    }

    /**
     * Class containing the identifiers used to update device management-related system drawable.
     */
    public static final class Drawable {

        private Drawable() {
        }

        /**
         * An ID for any drawable that can't be updated.
         */
        public static final int INVALID_ID = -1;

        /**
         * Specifically used to badge work profile app icons.
         */
        public static final int WORK_PROFILE_ICON_BADGE = 0;

        /**
         * General purpose work profile icon (i.e. generic icon badging). For badging app icons
         * specifically, see {@link #WORK_PROFILE_ICON_BADGE}.
         */
        public static final int WORK_PROFILE_ICON = 1;

        /**
         * General purpose icon representing the work profile off state.
         */
        public static final int WORK_PROFILE_OFF_ICON = 2;

        /**
         * General purpose icon for the work profile user avatar.
         */
        public static final int WORK_PROFILE_USER_ICON = 3;

        /**
         * @hide
         */
        public static final Set<Integer> UPDATABLE_DRAWABLE_IDS = buildDrawablesSet();

        private static Set<Integer> buildDrawablesSet() {
            Set<Integer> drawables = new HashSet<>();
            drawables.add(WORK_PROFILE_ICON_BADGE);
            drawables.add(WORK_PROFILE_ICON);
            drawables.add(WORK_PROFILE_OFF_ICON);
            drawables.add(WORK_PROFILE_USER_ICON);
            return drawables;
        }

        /**
         * Class containing the source identifiers used to update device management-related system
         * drawable.
         */
        public static final class Source {

            private Source() {
            }

            /**
             * A source identifier indicating that the updatable resource is used in a generic
             * undefined location.
             */
            public static final int UNDEFINED = -1;

            /**
             * A source identifier indicating that the updatable drawable is used in notifications.
             */
            public static final int NOTIFICATION = 0;

            /**
             * A source identifier indicating that the updatable drawable is used in a cross
             * profile switching animation.
             */
            public static final int PROFILE_SWITCH_ANIMATION = 1;

            /**
             * A source identifier indicating that the updatable drawable is used in a work
             * profile home screen widget.
             */
            public static final int HOME_WIDGET = 2;

            /**
             * A source identifier indicating that the updatable drawable is used in the launcher
             * turn off work button.
             */
            public static final int LAUNCHER_OFF_BUTTON = 3;

            /**
             * A source identifier indicating that the updatable drawable is used in quick settings.
             */
            public static final int QUICK_SETTINGS = 4;

            /**
             * A source identifier indicating that the updatable drawable is used in the status bar.
             */
            public static final int STATUS_BAR = 5;

            /**
             * @hide
             */
            public static final Set<Integer> UPDATABLE_DRAWABLE_SOURCES = buildSourcesSet();

            private static Set<Integer> buildSourcesSet() {
                Set<Integer> sources = new HashSet<>();
                sources.add(UNDEFINED);
                sources.add(NOTIFICATION);
                sources.add(PROFILE_SWITCH_ANIMATION);
                sources.add(HOME_WIDGET);
                sources.add(LAUNCHER_OFF_BUTTON);
                sources.add(QUICK_SETTINGS);
                sources.add(STATUS_BAR);
                return sources;
            }
        }

        /**
         * Class containing the style identifiers used to update device management-related system
         * drawable.
         */
        @SuppressLint("StaticUtils")
        public static final class Style {

            private Style() {
            }

            /**
             * A style identifier indicating that the updatable drawable should use the default
             * style.
             */
            public static final int DEFAULT = -1;

            /**
             * A style identifier indicating that the updatable drawable has a solid color fill.
             */
            public static final int SOLID_COLORED = 0;

            /**
             * A style identifier indicating that the updatable drawable has a solid non-colored
             * fill.
             */
            public static final int SOLID_NOT_COLORED = 1;

            /**
             * A style identifier indicating that the updatable drawable is an outline.
             */
            public static final int OUTLINE = 2;

            /**
             * @hide
             */
            public static final Set<Integer> UPDATABLE_DRAWABLE_STYLES = buildStylesSet();

            private static Set<Integer> buildStylesSet() {
                Set<Integer> styles = new HashSet<>();
                styles.add(DEFAULT);
                styles.add(SOLID_COLORED);
                styles.add(SOLID_NOT_COLORED);
                styles.add(OUTLINE);
                return styles;
            }
        }
    }

    /**
     * Class containing the identifiers used to update device management-related system strings.
     *
     * @hide
     */
    @SystemApi
    public static final class Strings {

        private Strings() {}

        /**
         * An ID for any string that can't be updated.
         */
        public static final String INVALID_ID = "INVALID_ID";

        /**
         * @hide
         */
        public static final Set<String> UPDATABLE_STRING_IDS = buildStringsSet();

        private static Set<String> buildStringsSet() {
            Set<String> strings = new HashSet<>();
            strings.addAll(Launcher.buildStringsSet());
            strings.addAll(SystemUi.buildStringsSet());
            strings.addAll(Core.buildStringsSet());
            return strings;
        }

        /**
         * Class containing the identifiers used to update device management-related system strings
         * in the Launcher package.
         *
         * @hide
         */
        public static final class Launcher {

            private Launcher(){}

            private static final String PREFIX = "Launcher.";

            /**
             * User on-boarding title for work profile apps.
             */
            public static final String WORK_PROFILE_EDU = PREFIX + "WORK_PROFILE_EDU";

            /**
             * Action label to finish work profile edu.
             */
            public static final String WORK_PROFILE_EDU_ACCEPT = PREFIX + "WORK_PROFILE_EDU_ACCEPT";

            /**
             * Title shown when user opens work apps tab while work profile is paused.
             */
            public static final String WORK_PROFILE_PAUSED_TITLE =
                    PREFIX + "WORK_PROFILE_PAUSED_TITLE";

            /**
             * Description shown when user opens work apps tab while work profile is paused.
             */
            public static final String WORK_PROFILE_PAUSED_DESCRIPTION =
                    PREFIX + "WORK_PROFILE_PAUSED_DESCRIPTION";

            /**
             * Shown on the button to pause work profile.
             */
            public static final String WORK_PROFILE_PAUSE_BUTTON =
                    PREFIX + "WORK_PROFILE_PAUSE_BUTTON";

            /**
             * Shown on the button to enable work profile.
             */
            public static final String WORK_PROFILE_ENABLE_BUTTON =
                    PREFIX + "WORK_PROFILE_ENABLE_BUTTON";

            /**
             * Label on launcher tab to indicate work apps.
             */
            public static final String ALL_APPS_WORK_TAB = PREFIX + "ALL_APPS_WORK_TAB";

            /**
             * Label on launcher tab to indicate personal apps.
             */
            public static final String ALL_APPS_PERSONAL_TAB = PREFIX + "ALL_APPS_PERSONAL_TAB";

            /**
             * Accessibility description for launcher tab to indicate work apps.
             */
            public static final String ALL_APPS_WORK_TAB_ACCESSIBILITY =
                    PREFIX + "ALL_APPS_WORK_TAB_ACCESSIBILITY";

            /**
             * Accessibility description for launcher tab to indicate personal apps.
             */
            public static final String ALL_APPS_PERSONAL_TAB_ACCESSIBILITY =
                    PREFIX + "ALL_APPS_PERSONAL_TAB_ACCESSIBILITY";

            /**
             * Work folder name.
             */
            public static final String WORK_FOLDER_NAME = PREFIX + "WORK_FOLDER_NAME";

            /**
             * Label on widget tab to indicate work app widgets.
             */
            public static final String WIDGETS_WORK_TAB = PREFIX + "WIDGETS_WORK_TAB";

            /**
             * Label on widget tab to indicate personal app widgets.
             */
            public static final String WIDGETS_PERSONAL_TAB = PREFIX + "WIDGETS_PERSONAL_TAB";

            /**
             * Message shown when a feature is disabled by the admin (e.g. changing wallpaper).
             */
            public static final String DISABLED_BY_ADMIN_MESSAGE =
                    PREFIX + "DISABLED_BY_ADMIN_MESSAGE";

            /**
             * @hide
             */
            static Set<String> buildStringsSet() {
                Set<String> strings = new HashSet<>();
                strings.add(WORK_PROFILE_EDU);
                strings.add(WORK_PROFILE_EDU_ACCEPT);
                strings.add(WORK_PROFILE_PAUSED_TITLE);
                strings.add(WORK_PROFILE_PAUSED_DESCRIPTION);
                strings.add(WORK_PROFILE_PAUSE_BUTTON);
                strings.add(WORK_PROFILE_ENABLE_BUTTON);
                strings.add(ALL_APPS_WORK_TAB);
                strings.add(ALL_APPS_PERSONAL_TAB);
                strings.add(ALL_APPS_PERSONAL_TAB_ACCESSIBILITY);
                strings.add(ALL_APPS_WORK_TAB_ACCESSIBILITY);
                strings.add(WORK_FOLDER_NAME);
                strings.add(WIDGETS_WORK_TAB);
                strings.add(WIDGETS_PERSONAL_TAB);
                strings.add(DISABLED_BY_ADMIN_MESSAGE);
                return strings;
            }
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

            /**
             * @hide
             */
            static Set<String> buildStringsSet() {
                Set<String> strings = new HashSet<>();
                strings.add(QS_WORK_PROFILE_LABEL);
                strings.add(QS_MSG_MANAGEMENT);
                strings.add(QS_MSG_NAMED_MANAGEMENT);
                strings.add(QS_MSG_MANAGEMENT_MONITORING);
                strings.add(QS_MSG_NAMED_MANAGEMENT_MONITORING);
                strings.add(QS_MSG_MANAGEMENT_NAMED_VPN);
                strings.add(QS_MSG_NAMED_MANAGEMENT_NAMED_VPN);
                strings.add(QS_MSG_MANAGEMENT_MULTIPLE_VPNS);
                strings.add(QS_MSG_NAMED_MANAGEMENT_MULTIPLE_VPNS);
                strings.add(QS_MSG_WORK_PROFILE_MONITORING);
                strings.add(QS_MSG_NAMED_WORK_PROFILE_MONITORING);
                strings.add(QS_MSG_WORK_PROFILE_NETWORK);
                strings.add(QS_MSG_WORK_PROFILE_NAMED_VPN);
                strings.add(QS_MSG_PERSONAL_PROFILE_NAMED_VPN);
                strings.add(QS_DIALOG_MANAGEMENT_TITLE);
                strings.add(QS_DIALOG_VIEW_POLICIES);
                strings.add(QS_DIALOG_MANAGEMENT);
                strings.add(QS_DIALOG_NAMED_MANAGEMENT);
                strings.add(QS_DIALOG_MANAGEMENT_CA_CERT);
                strings.add(QS_DIALOG_WORK_PROFILE_CA_CERT);
                strings.add(QS_DIALOG_MANAGEMENT_NETWORK);
                strings.add(QS_DIALOG_WORK_PROFILE_NETWORK);
                strings.add(QS_DIALOG_MANAGEMENT_NAMED_VPN);
                strings.add(QS_DIALOG_MANAGEMENT_TWO_NAMED_VPN);
                strings.add(QS_DIALOG_WORK_PROFILE_NAMED_VPN);
                strings.add(QS_DIALOG_PERSONAL_PROFILE_NAMED_VPN);
                strings.add(BIOMETRIC_DIALOG_WORK_PIN_LAST_ATTEMPT);
                strings.add(BIOMETRIC_DIALOG_WORK_PATTERN_LAST_ATTEMPT);
                strings.add(BIOMETRIC_DIALOG_WORK_PASSWORD_LAST_ATTEMPT);
                strings.add(BIOMETRIC_DIALOG_WORK_LOCK_FAILED_ATTEMPTS);
                strings.add(STATUS_BAR_WORK_ICON_ACCESSIBILITY);
                strings.add(ONGOING_PRIVACY_DIALOG_WORK);
                strings.add(KEYGUARD_MANAGEMENT_DISCLOSURE);
                strings.add(KEYGUARD_NAMED_MANAGEMENT_DISCLOSURE);
                strings.add(WORK_LOCK_ACCESSIBILITY);
                return strings;
            }
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

            /**
             * @hide
             */
            static Set<String> buildStringsSet() {
                Set<String> strings = new HashSet<>();
                strings.add(WORK_PROFILE_DELETED_TITLE);
                strings.add(WORK_PROFILE_DELETED_FAILED_PASSWORD_ATTEMPTS_MESSAGE);
                strings.add(WORK_PROFILE_DELETED_GENERIC_MESSAGE);
                strings.add(WORK_PROFILE_DELETED_ORG_OWNED_MESSAGE);
                strings.add(PERSONAL_APP_SUSPENSION_TITLE);
                strings.add(PERSONAL_APP_SUSPENSION_MESSAGE);
                strings.add(PERSONAL_APP_SUSPENSION_SOON_MESSAGE);
                strings.add(PERSONAL_APP_SUSPENSION_TURN_ON_PROFILE);
                strings.add(PRINTING_DISABLED_NAMED_ADMIN);
                strings.add(LOCATION_CHANGED_TITLE);
                strings.add(LOCATION_CHANGED_MESSAGE);
                strings.add(NETWORK_LOGGING_TITLE);
                strings.add(NETWORK_LOGGING_MESSAGE);
                strings.add(NOTIFICATION_WORK_PROFILE_CONTENT_DESCRIPTION);
                strings.add(NOTIFICATION_CHANNEL_DEVICE_ADMIN);
                strings.add(SWITCH_TO_WORK_LABEL);
                strings.add(SWITCH_TO_PERSONAL_LABEL);
                strings.add(FORWARD_INTENT_TO_WORK);
                strings.add(FORWARD_INTENT_TO_PERSONAL);
                strings.add(RESOLVER_WORK_PROFILE_NOT_SUPPORTED);
                strings.add(RESOLVER_PERSONAL_TAB);
                strings.add(RESOLVER_WORK_TAB);
                strings.add(RESOLVER_PERSONAL_TAB_ACCESSIBILITY);
                strings.add(RESOLVER_WORK_TAB_ACCESSIBILITY);
                strings.add(RESOLVER_CROSS_PROFILE_BLOCKED_TITLE);
                strings.add(RESOLVER_CANT_SHARE_WITH_PERSONAL);
                strings.add(RESOLVER_CANT_SHARE_WITH_WORK);
                strings.add(RESOLVER_CANT_ACCESS_PERSONAL);
                strings.add(RESOLVER_CANT_ACCESS_WORK);
                strings.add(RESOLVER_WORK_PAUSED_TITLE);
                strings.add(RESOLVER_NO_WORK_APPS);
                strings.add(RESOLVER_NO_PERSONAL_APPS);
                strings.add(CANT_ADD_ACCOUNT_MESSAGE);
                strings.add(PACKAGE_INSTALLED_BY_DO);
                strings.add(PACKAGE_UPDATED_BY_DO);
                strings.add(PACKAGE_DELETED_BY_DO);
                strings.add(UNLAUNCHABLE_APP_WORK_PAUSED_TITLE);
                strings.add(UNLAUNCHABLE_APP_WORK_PAUSED_MESSAGE);
                strings.add(PROFILE_ENCRYPTED_TITLE);
                strings.add(PROFILE_ENCRYPTED_DETAIL);
                strings.add(PROFILE_ENCRYPTED_MESSAGE);
                strings.add(WORK_PROFILE_BADGED_LABEL);
                return strings;
            }
        }
    }
}
