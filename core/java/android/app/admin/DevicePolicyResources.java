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
            WORK_PROFILE_EDU, WORK_PROFILE_EDU_ACCEPT, WORK_PROFILE_PAUSED_TITLE,
            WORK_PROFILE_PAUSED_DESCRIPTION, WORK_PROFILE_PAUSE_BUTTON, WORK_PROFILE_ENABLE_BUTTON,
            ALL_APPS_WORK_TAB, ALL_APPS_PERSONAL_TAB, ALL_APPS_WORK_TAB_ACCESSIBILITY,
            ALL_APPS_PERSONAL_TAB_ACCESSIBILITY, WORK_FOLDER_NAME, WIDGETS_WORK_TAB,
            WIDGETS_PERSONAL_TAB, DISABLED_BY_ADMIN_MESSAGE
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
    }
}
