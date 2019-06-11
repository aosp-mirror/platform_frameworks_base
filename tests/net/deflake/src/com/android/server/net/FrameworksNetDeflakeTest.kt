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
 * limitations under the License
 */

package com.android.server.net

import com.android.testutils.host.DeflakeHostTestBase
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner
import org.junit.runner.RunWith

@RunWith(DeviceJUnit4ClassRunner::class)
class FrameworksNetDeflakeTest: DeflakeHostTestBase() {
    override val runCount = 20
    override val testApkFilename = "FrameworksNetTests.apk"
    override val testClasses = listOf("com.android.server.ConnectivityServiceTest")
}