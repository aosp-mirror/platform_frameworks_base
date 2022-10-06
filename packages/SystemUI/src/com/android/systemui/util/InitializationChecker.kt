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

package com.android.systemui.util

import android.app.ActivityThread
import android.os.Process
import com.android.systemui.dagger.qualifiers.InstrumentationTest
import javax.inject.Inject

/**
 * Used to check whether SystemUI should be fully initialized.
 */
class InitializationChecker @Inject constructor(
    @InstrumentationTest private val instrumentationTest: Boolean
) {

    /**
     * Only initialize components for the main system ui process running as the primary user
     */
    fun initializeComponents(): Boolean =
        !instrumentationTest &&
                Process.myUserHandle().isSystem &&
                ActivityThread.currentProcessName() == ActivityThread.currentPackageName()
}
