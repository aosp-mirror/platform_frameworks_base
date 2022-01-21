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
            KEYGUARD_NAMED_MANAGEMENT_DISCLOSURE, WORK_LOCK_ACCESSIBILITY
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

            /**
             * User on-boarding title for work profile apps.
             */
            public static final String WORK_PROFILE_EDU = "WORK_PROFILE_EDU";

            /**
             * Action label to finish work profile edu.
             */
            public static final String WORK_PROFILE_EDU_ACCEPT = "WORK_PROFILE_EDU_ACCEPT";

            /**
             * Title shown when user opens work apps tab while work profile is paused.
             */
            public static final String WORK_PROFILE_PAUSED_TITLE = "WORK_PROFILE_PAUSED_TITLE";

            /**
             * Description shown when user opens work apps tab while work profile is paused.
             */
            public static final String WORK_PROFILE_PAUSED_DESCRIPTION =
                    "WORK_PROFILE_PAUSED_DESCRIPTION";

            /**
             * Shown on the button to pause work profile.
             */
            public static final String WORK_PROFILE_PAUSE_BUTTON = "WORK_PROFILE_PAUSE_BUTTON";

            /**
             * Shown on the button to enable work profile.
             */
            public static final String WORK_PROFILE_ENABLE_BUTTON = "WORK_PROFILE_ENABLE_BUTTON";

            /**
             * Label on launcher tab to indicate work apps.
             */
            public static final String ALL_APPS_WORK_TAB = "ALL_APPS_WORK_TAB";

            /**
             * Label on launcher tab to indicate personal apps.
             */
            public static final String ALL_APPS_PERSONAL_TAB = "ALL_APPS_PERSONAL_TAB";

            /**
             * Accessibility description for launcher tab to indicate work apps.
             */
            public static final String ALL_APPS_WORK_TAB_ACCESSIBILITY =
                    "ALL_APPS_WORK_TAB_ACCESSIBILITY";

            /**
             * Accessibility description for launcher tab to indicate personal apps.
             */
            public static final String ALL_APPS_PERSONAL_TAB_ACCESSIBILITY =
                    "ALL_APPS_PERSONAL_TAB_ACCESSIBILITY";

            /**
             * Work folder name.
             */
            public static final String WORK_FOLDER_NAME = "WORK_FOLDER_NAME";

            /**
             * Label on widget tab to indicate work app widgets.
             */
            public static final String WIDGETS_WORK_TAB = "WIDGETS_WORK_TAB";

            /**
             * Label on widget tab to indicate personal app widgets.
             */
            public static final String WIDGETS_PERSONAL_TAB = "WIDGETS_PERSONAL_TAB";

            /**
             * Message shown when a feature is disabled by the admin (e.g. changing wallpaper).
             */
            public static final String DISABLED_BY_ADMIN_MESSAGE = "DISABLED_BY_ADMIN_MESSAGE";

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

            /**
             * Label in quick settings for toggling work profile on/off.
             */
            public static final String QS_WORK_PROFILE_LABEL = "QS_WORK_PROFILE_LABEL";

            /**
             * Disclosure at the bottom of Quick Settings to indicate device management.
             */
            public static final String QS_MSG_MANAGEMENT = "QS_MSG_MANAGEMENT";

            /**
             * Similar to {@link #QS_MSG_MANAGEMENT} but accepts the organization name as a
             * param.
             */
            public static final String QS_MSG_NAMED_MANAGEMENT = "QS_MSG_NAMED_MANAGEMENT";

            /**
             * Disclosure at the bottom of Quick Settings to indicate device management monitoring.
             */
            public static final String QS_MSG_MANAGEMENT_MONITORING =
                    "QS_MSG_MANAGEMENT_MONITORING";

            /**
             * Similar to {@link #QS_MSG_MANAGEMENT_MONITORING} but accepts the
             * organization name as a param.
             */
            public static final String QS_MSG_NAMED_MANAGEMENT_MONITORING =
                    "QS_MSG_NAMED_MANAGEMENT_MONITORING";

            /**
             * Disclosure at the bottom of Quick Settings to indicate device management and the
             * device is connected to a VPN, accepts VPN name as a param.
             */
            public static final String QS_MSG_MANAGEMENT_NAMED_VPN =
                    "QS_MSG_MANAGEMENT_NAMED_VPN";

            /**
             * Similar to {@link #QS_MSG_MANAGEMENT_NAMED_VPN} but also accepts the
             * organization name as a param.
             */
            public static final String QS_MSG_NAMED_MANAGEMENT_NAMED_VPN =
                    "QS_MSG_NAMED_MANAGEMENT_NAMED_VPN";

            /**
             * Disclosure at the bottom of Quick Settings to indicate device management and the
             * device is connected to multiple VPNs.
             */
            public static final String QS_MSG_MANAGEMENT_MULTIPLE_VPNS =
                    "QS_MSG_MANAGEMENT_MULTIPLE_VPNS";

            /**
             * Similar to {@link #QS_MSG_MANAGEMENT_MULTIPLE_VPNS} but also accepts the
             * organization name as a param.
             */
            public static final String QS_MSG_NAMED_MANAGEMENT_MULTIPLE_VPNS =
                    "QS_MSG_NAMED_MANAGEMENT_MULTIPLE_VPNS";

            /**
             * Disclosure at the bottom of Quick Settings to indicate work profile monitoring.
             */
            public static final String QS_MSG_WORK_PROFILE_MONITORING =
                    "QS_MSG_WORK_PROFILE_MONITORING";

            /**
             * Similar to {@link #QS_MSG_WORK_PROFILE_MONITORING} but accepts the
             * organization name as a param.
             */
            public static final String QS_MSG_NAMED_WORK_PROFILE_MONITORING =
                    "QS_MSG_NAMED_WORK_PROFILE_MONITORING";

            /**
            * Disclosure at the bottom of Quick Settings to indicate network activity is visible to
             * admin.
            */
            public static final String QS_MSG_WORK_PROFILE_NETWORK = "QS_MSG_WORK_PROFILE_NETWORK";

            /**
             * Disclosure at the bottom of Quick Settings to indicate work profile is connected to a
             * VPN, accepts VPN name as a param.
             */
            public static final String QS_MSG_WORK_PROFILE_NAMED_VPN =
                    "QS_MSG_WORK_PROFILE_NAMED_VPN";

            /**
             * Disclosure at the bottom of Quick Settings to indicate personal profile is connected
             * to a VPN, accepts VPN name as a param.
             */
            public static final String QS_MSG_PERSONAL_PROFILE_NAMED_VPN =
                    "QS_MSG_PERSONAL_PROFILE_NAMED_VPN";

            /**
             * Title for dialog to indicate device management.
             */
            public static final String QS_DIALOG_MANAGEMENT_TITLE = "QS_DIALOG_MANAGEMENT_TITLE";

            /**
             * Label for button in the device management dialog to open a page with more information
             * on the admin's abilities.
             */
            public static final String QS_DIALOG_VIEW_POLICIES = "QS_DIALOG_VIEW_POLICIES";

            /**
             * Description for device management dialog to indicate admin abilities.
             */
            public static final String QS_DIALOG_MANAGEMENT = "QS_DIALOG_MANAGEMENT";

            /**
             * Similar to {@link #QS_DIALOG_MANAGEMENT} but accepts the organization name as a
             * param.
             */
            public static final String QS_DIALOG_NAMED_MANAGEMENT = "QS_DIALOG_NAMED_MANAGEMENT";

            /**
             * Description for the managed device certificate authorities in the device management
             * dialog.
             */
            public static final String QS_DIALOG_MANAGEMENT_CA_CERT =
                    "QS_DIALOG_MANAGEMENT_CA_CERT";

            /**
             * Description for the work profile certificate authorities in the device management
             * dialog.
             */
            public static final String QS_DIALOG_WORK_PROFILE_CA_CERT =
                    "QS_DIALOG_WORK_PROFILE_CA_CERT";

            /**
             * Description for the managed device network logging in the device management dialog.
             */
            public static final String QS_DIALOG_MANAGEMENT_NETWORK =
                    "QS_DIALOG_MANAGEMENT_NETWORK";

            /**
             * Description for the work profile network logging in the device management dialog.
             */
            public static final String QS_DIALOG_WORK_PROFILE_NETWORK =
                    "QS_DIALOG_WORK_PROFILE_NETWORK";

            /**
             * Description for an active VPN in the device management dialog, accepts VPN name as a
             * param.
             */
            public static final String QS_DIALOG_MANAGEMENT_NAMED_VPN =
                    "QS_DIALOG_MANAGEMENT_NAMED_VPN";

            /**
             * Description for two active VPN in the device management dialog, accepts two VPN names
             * as params.
             */
            public static final String QS_DIALOG_MANAGEMENT_TWO_NAMED_VPN =
                    "QS_DIALOG_MANAGEMENT_TWO_NAMED_VPN";

            /**
             * Description for an active work profile VPN in the device management dialog, accepts
             * VPN name as a param.
             */
            public static final String QS_DIALOG_WORK_PROFILE_NAMED_VPN =
                    "QS_DIALOG_WORK_PROFILE_NAMED_VPN";

            /**
             * Description for an active personal profile VPN in the device management dialog,
             * accepts VPN name as a param.
             */
            public static final String QS_DIALOG_PERSONAL_PROFILE_NAMED_VPN =
                    "QS_DIALOG_PERSONAL_PROFILE_NAMED_VPN";

            /**
             * Content of a dialog shown when the user only has one attempt left to provide the
             * correct pin before the work profile is removed.
             */
            public static final String BIOMETRIC_DIALOG_WORK_PIN_LAST_ATTEMPT =
                    "BIOMETRIC_DIALOG_WORK_PIN_LAST_ATTEMPT";

            /**
             * Content of a dialog shown when the user only has one attempt left to provide the
             * correct pattern before the work profile is removed.
             */
            public static final String BIOMETRIC_DIALOG_WORK_PATTERN_LAST_ATTEMPT =
                    "BIOMETRIC_DIALOG_WORK_PATTERN_LAST_ATTEMPT";

            /**
             * Content of a dialog shown when the user only has one attempt left to provide the
             * correct password before the work profile is removed.
             */
            public static final String BIOMETRIC_DIALOG_WORK_PASSWORD_LAST_ATTEMPT =
                    "BIOMETRIC_DIALOG_WORK_PASSWORD_LAST_ATTEMPT";

            /**
             * Content of a dialog shown when the user has failed to provide the work lock too many
             * times and the work profile is removed.
             */
            public static final String BIOMETRIC_DIALOG_WORK_LOCK_FAILED_ATTEMPTS =
                    "BIOMETRIC_DIALOG_WORK_LOCK_FAILED_ATTEMPTS";

            /**
             * Accessibility label for managed profile icon in the status bar
             */
            public static final String STATUS_BAR_WORK_ICON_ACCESSIBILITY =
                    "STATUS_BAR_WORK_ICON_ACCESSIBILITY";

            /**
             * Text appended to privacy dialog, indicating that the application is in the work
             * profile.
             */
            public static final String ONGOING_PRIVACY_DIALOG_WORK =
                    "ONGOING_PRIVACY_DIALOG_WORK";

            /**
             * Text on keyguard screen indicating device management.
             */
            public static final String KEYGUARD_MANAGEMENT_DISCLOSURE =
                    "KEYGUARD_MANAGEMENT_DISCLOSURE";

            /**
             * Similar to {@link #KEYGUARD_MANAGEMENT_DISCLOSURE} but also accepts organization name
             * as a param.
             */
            public static final String KEYGUARD_NAMED_MANAGEMENT_DISCLOSURE =
                    "KEYGUARD_NAMED_MANAGEMENT_DISCLOSURE";

            /**
             * Content description for the work profile lock screen.
             */
            public static final String WORK_LOCK_ACCESSIBILITY = "WORK_LOCK_ACCESSIBILITY";

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
    }
}
