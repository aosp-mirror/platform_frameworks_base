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
package com.android.systemui.accessibility.accessibilitymenu.model;

import android.util.Log;

import com.android.systemui.accessibility.accessibilitymenu.R;

import java.util.Map;

/**
 * Provides a data structure for a11y menu shortcuts.
 */
public class A11yMenuShortcut {

    public enum ShortcutId {
        UNSPECIFIED_ID_VALUE,
        ID_ASSISTANT_VALUE,
        ID_A11YSETTING_VALUE,
        ID_POWER_VALUE,
        ID_VOLUME_DOWN_VALUE,
        ID_VOLUME_UP_VALUE,
        ID_RECENT_VALUE,
        ID_BRIGHTNESS_DOWN_VALUE,
        ID_BRIGHTNESS_UP_VALUE,
        ID_LOCKSCREEN_VALUE,
        ID_QUICKSETTING_VALUE,
        ID_NOTIFICATION_VALUE,
        ID_SCREENSHOT_VALUE
    }

    private static final String TAG = "A11yMenuShortcut";

    // Index of resource ID in the array, shortcutResource.
    private static final int IMG_SRC_INDEX = 0;
    private static final int IMG_COLOR_INDEX = 1;
    private static final int CONTENT_DESCRIPTION_INDEX = 2;
    private static final int LABEL_TEXT_INDEX = 3;

    /** Map stores all shortcut resource IDs that is in matching order of defined shortcut. */
    private static final Map<ShortcutId, int[]> sShortcutResource = Map.ofEntries(
            Map.entry(ShortcutId.ID_ASSISTANT_VALUE, new int[] {
                    R.drawable.ic_logo_a11y_assistant,
                    R.color.assistant_color,
                    R.string.assistant_utterance,
                    R.string.assistant_label,
            }),
            Map.entry(ShortcutId.ID_A11YSETTING_VALUE, new int[] {
                    R.drawable.ic_logo_a11y_settings,
                    R.color.a11y_settings_color,
                    R.string.a11y_settings_label,
                    R.string.a11y_settings_label,
            }),
            Map.entry(ShortcutId.ID_POWER_VALUE, new int[] {
                    R.drawable.ic_logo_a11y_power,
                    R.color.power_color,
                    R.string.power_utterance,
                    R.string.power_label,
            }),
            Map.entry(ShortcutId.ID_RECENT_VALUE, new int[] {
                    R.drawable.ic_logo_a11y_recent_apps,
                    R.color.recent_apps_color,
                    R.string.recent_apps_label,
                    R.string.recent_apps_label,
            }),
            Map.entry(ShortcutId.ID_LOCKSCREEN_VALUE, new int[] {
                    R.drawable.ic_logo_a11y_lock,
                    R.color.lockscreen_color,
                    R.string.lockscreen_label,
                    R.string.lockscreen_label,
            }),
            Map.entry(ShortcutId.ID_QUICKSETTING_VALUE, new int[] {
                    R.drawable.ic_logo_a11y_quick_settings,
                    R.color.quick_settings_color,
                    R.string.quick_settings_label,
                    R.string.quick_settings_label,
            }),
            Map.entry(ShortcutId.ID_NOTIFICATION_VALUE, new int[] {
                    R.drawable.ic_logo_a11y_notifications,
                    R.color.notifications_color,
                    R.string.notifications_label,
                    R.string.notifications_label,
            }),
            Map.entry(ShortcutId.ID_SCREENSHOT_VALUE, new int[] {
                    R.drawable.ic_logo_a11y_screenshot,
                    R.color.screenshot_color,
                    R.string.screenshot_utterance,
                    R.string.screenshot_label,
            }),
            Map.entry(ShortcutId.ID_BRIGHTNESS_UP_VALUE, new int[] {
                    R.drawable.ic_logo_a11y_brightness_up,
                    R.color.brightness_color,
                    R.string.brightness_up_label,
                    R.string.brightness_up_label,
            }),
            Map.entry(ShortcutId.ID_BRIGHTNESS_DOWN_VALUE, new int[] {
                    R.drawable.ic_logo_a11y_brightness_down,
                    R.color.brightness_color,
                    R.string.brightness_down_label,
                    R.string.brightness_down_label,
            }),
            Map.entry(ShortcutId.ID_VOLUME_UP_VALUE, new int[] {
                    R.drawable.ic_logo_a11y_volume_up,
                    R.color.volume_color,
                    R.string.volume_up_label,
                    R.string.volume_up_label,
            }),
            Map.entry(ShortcutId.ID_VOLUME_DOWN_VALUE, new int[] {
                    R.drawable.ic_logo_a11y_volume_down,
                    R.color.volume_color,
                    R.string.volume_down_label,
                    R.string.volume_down_label,
            })
    );

    /** Shortcut id used to identify. */
    private int mShortcutId = ShortcutId.UNSPECIFIED_ID_VALUE.ordinal();

    // Resource IDs of shortcut button and label.
    public int imageSrc;
    public int imageColor;
    public int imgContentDescription;
    public int labelText;

    public A11yMenuShortcut(int id) {
        setId(id);
    }

    /**
     * Sets Id to shortcut, checks the value first and updates shortcut resources. It will set id to
     * default value {@link ShortcutId.UNSPECIFIED_ID_VALUE} if invalid.
     *
     * @param id id set to shortcut
     */
    public void setId(int id) {
        mShortcutId = id;

        if (id < ShortcutId.UNSPECIFIED_ID_VALUE.ordinal()
                || id > ShortcutId.values().length) {
            mShortcutId = ShortcutId.UNSPECIFIED_ID_VALUE.ordinal();
            Log.w(
                    TAG, String.format(
                            "setId to default UNSPECIFIED_ID as id is invalid. "
                                    + "Max value is %d while id is %d",
                            ShortcutId.values().length, id
                    ));
        }
        int[] resources = sShortcutResource.getOrDefault(ShortcutId.values()[id], new int[] {
                R.drawable.ic_add_32dp,
                android.R.color.darker_gray,
                R.string.empty_content,
                R.string.empty_content,
        });
        imageSrc = resources[IMG_SRC_INDEX];
        imageColor = resources[IMG_COLOR_INDEX];
        imgContentDescription = resources[CONTENT_DESCRIPTION_INDEX];
        labelText = resources[LABEL_TEXT_INDEX];
    }

    public int getId() {
        return mShortcutId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof A11yMenuShortcut)) {
            return false;
        }

        A11yMenuShortcut targetObject = (A11yMenuShortcut) o;

        return mShortcutId == targetObject.mShortcutId;
    }

    @Override
    public int hashCode() {
        return mShortcutId;
    }
}
