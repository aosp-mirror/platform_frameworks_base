/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.notification;

import android.service.notification.ZenModeConfig;

/** Enum version of {@link ZenModeConfig.ConfigOrigin}, for test parameterization. */
public enum ZenChangeOrigin {
    ORIGIN_UNKNOWN(ZenModeConfig.ORIGIN_UNKNOWN),
    ORIGIN_INIT(ZenModeConfig.ORIGIN_INIT),
    ORIGIN_INIT_USER(ZenModeConfig.ORIGIN_INIT_USER),
    ORIGIN_APP(ZenModeConfig.ORIGIN_APP),
    ORIGIN_USER_IN_APP(ZenModeConfig.ORIGIN_USER_IN_APP),
    ORIGIN_SYSTEM(ZenModeConfig.ORIGIN_SYSTEM),
    ORIGIN_USER_IN_SYSTEMUI(ZenModeConfig.ORIGIN_USER_IN_SYSTEMUI),
    ORIGIN_RESTORE_BACKUP(ZenModeConfig.ORIGIN_RESTORE_BACKUP);

    private final int mValue;

    ZenChangeOrigin(@ZenModeConfig.ConfigOrigin int value) {
        mValue = value;
    }

    /** Gets the {@link ZenModeConfig.ConfigOrigin} int value corresponding to the enum. */
    @ZenModeConfig.ConfigOrigin
    public int value() {
        return mValue;
    }
}
