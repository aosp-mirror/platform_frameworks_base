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
package com.android.ravenwood;

public class RavenwoodRuntimeState {
    // This must match VMRuntime.SDK_VERSION_CUR_DEVELOPMENT.
    public static final int CUR_DEVELOPMENT = 10000;

    public static volatile int sUid;
    public static volatile int sPid;
    public static volatile int sTargetSdkLevel;

    static {
        reset();
    }

    public static void reset() {
        sUid = -1;
        sPid = -1;
        sTargetSdkLevel = CUR_DEVELOPMENT;
    }
}
