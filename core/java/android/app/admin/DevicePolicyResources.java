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

import android.annotation.IntDef;
import android.annotation.SuppressLint;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashSet;
import java.util.Set;

/**
 * Class containing the required identifiers to update device management resources.
 *
 * <p>See {@link DevicePolicyManager#getDrawable}.
 *
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
}
