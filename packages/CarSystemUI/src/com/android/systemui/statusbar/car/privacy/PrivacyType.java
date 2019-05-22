/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.car.privacy;

import android.content.Context;
import android.graphics.drawable.Drawable;

import com.android.systemui.R;

/**
 * Enum for storing data for camera, mic and location.
 */
public enum PrivacyType {
    TYPE_CAMERA(R.string.privacy_type_camera, com.android.internal.R.drawable.ic_camera),
    TYPE_LOCATION(R.string.privacy_type_location, R.drawable.stat_sys_location),
    TYPE_MICROPHONE(R.string.privacy_type_microphone, R.drawable.ic_mic_white);

    private int mNameId;
    private int mIconId;

    PrivacyType(int nameId, int iconId) {
        mNameId = nameId;
        mIconId = iconId;
    }

    /**
     * Get the icon Id.
     */
    public Drawable getIconId(Context context) {
        return context.getResources().getDrawable(mIconId, null);
    }

    /**
     * Get the name Id.
     */
    public String getNameId(Context context) {
        return context.getResources().getString(mNameId);
    }
}
