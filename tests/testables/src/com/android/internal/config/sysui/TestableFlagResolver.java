/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.config.sysui;

import java.util.HashMap;
import java.util.Map;

public class TestableFlagResolver implements SystemUiSystemPropertiesFlags.FlagResolver {
    private Map<String, Boolean> mOverrides = new HashMap<>();

    @Override
    public boolean isEnabled(SystemUiSystemPropertiesFlags.Flag flag) {
        return mOverrides.getOrDefault(flag.mSysPropKey, flag.mDefaultValue);
    }

    public TestableFlagResolver setFlagOverride(SystemUiSystemPropertiesFlags.Flag flag,
            boolean isEnabled) {
        mOverrides.put(flag.mSysPropKey, isEnabled);
        return this;
    }
}
