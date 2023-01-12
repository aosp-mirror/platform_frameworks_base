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

import com.android.systemui.accessibility.accessibilitymenu.R;

/** Provides a data structure for a11y menu shortcuts. */
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
     *
     * @param id id set to shortcut
     */
    public void setId(int id) {
        mShortcutId = id;

        // TODO(jonesriley) load the proper resources based on id
        imageSrc = R.drawable.ic_logo_assistant_32dp;
        imageColor = android.R.color.darker_gray;
        imgContentDescription = R.string.empty_content;
        labelText = R.string.empty_content;
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
