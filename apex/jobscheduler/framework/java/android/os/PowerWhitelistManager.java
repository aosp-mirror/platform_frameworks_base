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

package android.os;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;

/**
 * Interface to access and modify the power save whitelist.
 *
 * @hide
 */
@SystemApi
@TestApi
@SystemService(Context.POWER_WHITELIST_MANAGER)
public class PowerWhitelistManager {
    private final Context mContext;

    /**
     * @hide
     */
    public PowerWhitelistManager(@NonNull Context context) {
        mContext = context;
    }
}
