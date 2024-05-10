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

package com.android.systemui.statusbar.ui

import android.content.applicationContext
import android.content.res.mainResources
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.res.R

/**
 * Main fixture for supplying a [SystemBarUtilsProxy]. Should be used by other fixtures. Unless
 * overridden in the test, this by default uses [fakeSystemBarUtilsProxy].
 */
var Kosmos.systemBarUtilsProxy: SystemBarUtilsProxy by Fixture { fakeSystemBarUtilsProxy }

/**
 * Fixture supplying a real [SystemBarUtilsProxyImpl] instance, for use by tests that want to use
 * the real device logic to determine system bar properties. Note this this real instance does *not*
 * support Robolectric tests; by opting in, you are explicitly opting-out of using Robolectric.
 *
 * By default, this fixture is unused; tests should override [systemBarUtilsProxy] in order to
 * utilize this fixture:
 * ```kotlin
 *   val kosmos = Kosmos()
 *   kosmos.systemBarUtilsProxy = kosmos.systemBarUtilsProxyImpl
 * ```
 */
val Kosmos.systemBarUtilsProxyImpl by Fixture { SystemBarUtilsProxyImpl(applicationContext) }

/**
 * Fixture supplying a shared [FakeSystemBarUtilsProxy] instance. Can be accessed or overridden in
 * tests in order to provide custom results.
 */
var Kosmos.fakeSystemBarUtilsProxy by Fixture {
    FakeSystemBarUtilsProxy(mainResources.getDimensionPixelSize(R.dimen.status_bar_height))
}
