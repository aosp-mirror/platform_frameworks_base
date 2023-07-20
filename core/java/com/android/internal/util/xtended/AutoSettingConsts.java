/*
 * Copyright (C) 2023 Yet Another AOSP Project
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

package com.android.internal.util.xtended;

/**
 * Global constants for {@link com.android.server.AutoSettingService}
 */
public class AutoSettingConsts {
    /**
     * Disabled state (default)
     */
    public static final int MODE_DISABLED = 0;
    /**
     * Active from sunset to sunrise
     */
    public static final int MODE_NIGHT = 1;
    /**
     * Active at a user set time
     */
    public static final int MODE_TIME = 2;
    /**
     * Active from sunset till a time
     */
    public static final int MODE_MIXED_SUNSET = 3;
    /**
     * Active from a time till sunrise
     */
    public static final int MODE_MIXED_SUNRISE = 4;
}

