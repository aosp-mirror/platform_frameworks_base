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

package com.android.systemui.demomode;

import android.os.Bundle;

/** Defines the basic DemoMode command interface  */
public interface DemoModeCommandReceiver {
    /** Override point to observe demo mode commands  */
    void dispatchDemoCommand(String command, Bundle args);

    /**
     * Demo mode starts due to receiving [COMMAND_ENTER] or receiving any other demo mode command
     * while [DEMO_MODE_ALLOWED] is true but demo mode is off
     */
    default void onDemoModeStarted() {}

    /** Demo mode exited  */
    default void onDemoModeFinished() {}

}
