/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.wm

import android.content.Context
import android.os.StrictMode
import android.view.SurfaceControl
import android.window.ConfigurationChangeSetting
import com.android.server.DisplayThread
import com.android.server.LocalServices
import com.android.server.input.InputManagerService
import com.android.server.policy.WindowManagerPolicy
import com.android.window.flags.Flags

/**
 * Provides support for tests that require a [WindowManagerService].
 *
 * It provides functionalities for setting up and tearing down the service with proper dependencies,
 * which can be used across different test modules.
 */
object WindowManagerServiceTestSupport {

    /**
     * Sets up and initializes a [WindowManagerService] instance with the provided dependencies.
     *
     * This method constructs a [WindowManagerService] using the provided dependencies for testing.
     * It's marked as `internal` due to the package-private classes [DisplayWindowSettingsProvider]
     * and [AppCompatConfiguration]. The `@JvmName` annotation is used to bypass name mangling and
     * allow access from Java via `WindowManagerServiceTestSupport.setUpService`.
     *
     * **Important:** Before calling this method, ensure that any previous [WindowManagerService]
     * instance and its related services are properly torn down. In your test's setup, it is
     * recommended to call [tearDownService] before calling [setUpService] to handle cases where a
     * previous test might have crashed and left services in an inconsistent state. This is crucial
     * for test reliability.
     *
     * Example usage in a test's `setUp()` method:
     * ```
     * @Before
     * fun setUp() {
     *     WindowManagerServiceTestSupport.tearDownService() // Clean up before setup.
     *     mWindowManagerService = WindowManagerServiceTestSupport.setUpService(...)
     *     // ... rest of your setup logic ...
     * }
     * ```
     *
     * @param context the [Context] for the service.
     * @param im the [InputManagerService] to use.
     * @param policy the [WindowManagerPolicy] to use.
     * @param atm the [ActivityTaskManagerService] to use.
     * @param displayWindowSettingsProvider the [DisplayWindowSettingsProvider] to use.
     * @param surfaceControlTransaction the [SurfaceControl.Transaction] instance to use.
     * @param surfaceControlBuilder the [SurfaceControl.Builder] instance to use.
     * @param appCompat the [AppCompatConfiguration] to use.
     * @return the created [WindowManagerService] instance.
     */
    @JvmStatic
    @JvmName("setUpService")
    internal fun setUpService(
        context: Context,
        im: InputManagerService,
        policy: WindowManagerPolicy,
        atm: ActivityTaskManagerService,
        displayWindowSettingsProvider: DisplayWindowSettingsProvider,
        surfaceControlTransaction: SurfaceControl.Transaction,
        surfaceControlBuilder: SurfaceControl.Builder,
        appCompat: AppCompatConfiguration,
    ): WindowManagerService {
        // Suppress StrictMode violation (DisplayWindowSettings) to avoid log flood.
        DisplayThread.getHandler().post { StrictMode.allowThreadDiskWritesMask() }

        return WindowManagerService.main(
            context,
            im,
            false, /* showBootMsgs */
            policy,
            atm,
            displayWindowSettingsProvider,
            { surfaceControlTransaction },
            { surfaceControlBuilder },
            appCompat,
        )
    }

    /** Tears down the [WindowManagerService] and removes related local services. */
    @JvmStatic
    fun tearDownService() {
        LocalServices.removeServiceForTest(WindowManagerPolicy::class.java)
        LocalServices.removeServiceForTest(WindowManagerInternal::class.java)
        LocalServices.removeServiceForTest(ImeTargetVisibilityPolicy::class.java)

        if (Flags.condenseConfigurationChangeForSimpleMode()) {
            LocalServices.removeServiceForTest(
                ConfigurationChangeSetting.ConfigurationChangeSettingInternal::class.java,
            )
        }
    }
}
