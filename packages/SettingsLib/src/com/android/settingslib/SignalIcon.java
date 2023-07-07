/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.settingslib;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

/**
 * Icons for SysUI and Settings.
 */
public class SignalIcon {

    /**
     * Holds icons for a given state. Arrays are generally indexed as inet
     * state (full connectivity or not) first, and second dimension as
     * signal strength.
     */
    public static class IconGroup {
        public final int[][] sbIcons;
        public final int[][] qsIcons;
        public final int[] contentDesc;
        public final int sbNullState;
        public final int qsNullState;
        public final int sbDiscState;
        public final int qsDiscState;
        public final int discContentDesc;
        // For logging.
        public final String name;

        public IconGroup(
                String name,
                int[][] sbIcons,
                int[][] qsIcons,
                int[] contentDesc,
                int sbNullState,
                int qsNullState,
                int sbDiscState,
                int qsDiscState,
                int discContentDesc
        ) {
            this.name = name;
            this.sbIcons = sbIcons;
            this.qsIcons = qsIcons;
            this.contentDesc = contentDesc;
            this.sbNullState = sbNullState;
            this.qsNullState = qsNullState;
            this.sbDiscState = sbDiscState;
            this.qsDiscState = qsDiscState;
            this.discContentDesc = discContentDesc;
        }

        @Override
        public String toString() {
            return "IconGroup(" + name + ")";
        }
    }

    /**
     * Holds RAT icons for a given MobileState.
     */
    public static class MobileIconGroup extends IconGroup {
        @StringRes public final int dataContentDescription;
        @DrawableRes public final int dataType;

        public MobileIconGroup(
                String name,
                int dataContentDesc,
                int dataType
        ) {
            super(name,
                    // The rest of the values are the same for every type of MobileIconGroup, so
                    // just provide them here.
                    // TODO(b/238425913): Eventually replace with {@link MobileNetworkTypeIcon} so
                    //  that we don't have to fill in these superfluous fields.
                    /* sbIcons= */ null,
                    /* qsIcons= */ null,
                    /* contentDesc= */ AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
                    /* sbNullState= */ 0,
                    /* qsNullState= */ 0,
                    /* sbDiscState= */ 0,
                    /* qsDiscState= */ 0,
                    /* discContentDesc= */
                        AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH_NONE);
            this.dataContentDescription = dataContentDesc;
            this.dataType = dataType;
        }
    }
}
