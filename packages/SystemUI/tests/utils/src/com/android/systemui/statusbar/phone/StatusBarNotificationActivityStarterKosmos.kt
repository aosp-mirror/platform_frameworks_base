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

package com.android.systemui.statusbar.phone

import android.app.keyguardManager
import android.content.applicationContext
import android.os.fakeExecutorHandler
import android.service.dream.dreamManager
import com.android.internal.logging.metricsLogger
import com.android.internal.widget.lockPatternUtils
import com.android.systemui.activityIntentHelper
import com.android.systemui.animation.activityLaunchAnimator
import com.android.systemui.assist.assistManager
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.plugins.activityStarter
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.settings.userTracker
import com.android.systemui.shade.domain.interactor.shadeAnimationInteractor
import com.android.systemui.shade.shadeController
import com.android.systemui.shade.shadeViewController
import com.android.systemui.statusbar.notification.collection.provider.launchFullScreenIntentProvider
import com.android.systemui.statusbar.notification.collection.render.notificationVisibilityProvider
import com.android.systemui.statusbar.notification.notificationLaunchAnimatorControllerProvider
import com.android.systemui.statusbar.notification.row.onUserInteractionCallback
import com.android.systemui.statusbar.notificationClickNotifier
import com.android.systemui.statusbar.notificationLockscreenUserManager
import com.android.systemui.statusbar.notificationPresenter
import com.android.systemui.statusbar.notificationRemoteInputManager
import com.android.systemui.statusbar.notificationShadeWindowController
import com.android.systemui.statusbar.policy.headsUpManager
import com.android.systemui.statusbar.policy.keyguardStateController
import com.android.systemui.wmshell.bubblesManager
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
val Kosmos.statusBarNotificationActivityStarter by
    Kosmos.Fixture {
        StatusBarNotificationActivityStarter(
            applicationContext,
            applicationContext.displayId,
            fakeExecutorHandler,
            fakeExecutor,
            notificationVisibilityProvider,
            headsUpManager,
            activityStarter,
            notificationClickNotifier,
            statusBarKeyguardViewManager,
            keyguardManager,
            dreamManager,
            Optional.of(bubblesManager),
            { assistManager },
            notificationRemoteInputManager,
            notificationLockscreenUserManager,
            shadeController,
            keyguardStateController,
            lockPatternUtils,
            statusBarRemoteInputCallback,
            activityIntentHelper,
            metricsLogger,
            statusBarNotificationActivityStarterLogger,
            onUserInteractionCallback,
            notificationPresenter,
            shadeViewController,
            notificationShadeWindowController,
            activityLaunchAnimator,
            shadeAnimationInteractor,
            notificationLaunchAnimatorControllerProvider,
            launchFullScreenIntentProvider,
            powerInteractor,
            userTracker,
        )
    }
