/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wm.shell.flicker.legacysplitscreen

import android.app.Instrumentation
import android.os.Bundle
import android.support.test.launcherhelper.LauncherStrategyFactory
import android.view.Surface
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.openQuickStepAndClearRecentAppsFromOverview
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.startRotation
import com.android.wm.shell.flicker.helpers.SplitScreenHelper

abstract class LegacySplitScreenTransition(
    protected val instrumentation: Instrumentation
) {
    internal val splitScreenApp = SplitScreenHelper.getPrimary(instrumentation)
    internal val secondaryApp = SplitScreenHelper.getSecondary(instrumentation)
    internal val nonResizeableApp = SplitScreenHelper.getNonResizeable(instrumentation)
    internal val LAUNCHER_PACKAGE_NAME = LauncherStrategyFactory.getInstance(instrumentation)
        .launcherStrategy.supportedLauncherPackage
    internal val LIVE_WALLPAPER_PACKAGE_NAME =
        "com.breel.wallpapers18.soundviz.wallpaper.variations.SoundVizWallpaperV2"
    internal val LETTERBOX_NAME = "Letterbox"
    internal val TOAST_NAME = "Toast"
    internal val SPLASH_SCREEN_NAME = "Splash Screen"

    internal open val defaultTransitionSetup: FlickerBuilder.(Bundle) -> Unit
        get() = { configuration ->
            setup {
                eachRun {
                    device.wakeUpAndGoToHomeScreen()
                    device.openQuickStepAndClearRecentAppsFromOverview()
                    secondaryApp.launchViaIntent(wmHelper)
                    splitScreenApp.launchViaIntent(wmHelper)
                    this.setRotation(configuration.startRotation)
                }
            }
            teardown {
                eachRun {
                    splitScreenApp.exit()
                    secondaryApp.exit()
                    this.setRotation(Surface.ROTATION_0)
                }
            }
        }

    internal open val cleanSetup: FlickerBuilder.(Bundle) -> Unit
        get() = { configuration ->
            setup {
                eachRun {
                    device.wakeUpAndGoToHomeScreen()
                    device.openQuickStepAndClearRecentAppsFromOverview()
                    this.setRotation(configuration.startRotation)
                }
            }
            teardown {
                eachRun {
                    nonResizeableApp.exit()
                    this.setRotation(Surface.ROTATION_0)
                }
            }
        }

    internal open val customRotateSetup: FlickerBuilder.(Bundle) -> Unit
        get() = { configuration ->
            setup {
                eachRun {
                    device.wakeUpAndGoToHomeScreen()
                    device.openQuickStepAndClearRecentAppsFromOverview()
                    secondaryApp.launchViaIntent(wmHelper)
                    splitScreenApp.launchViaIntent(wmHelper)
                }
            }
            teardown {
                eachRun {
                    splitScreenApp.exit()
                    secondaryApp.exit()
                    this.setRotation(Surface.ROTATION_0)
                }
            }
        }
}
