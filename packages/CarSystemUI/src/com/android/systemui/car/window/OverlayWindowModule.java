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

package com.android.systemui.car.window;

import com.android.systemui.car.keyguard.CarKeyguardViewMediator;
import com.android.systemui.car.notification.BottomNotificationPanelViewMediator;
import com.android.systemui.car.notification.NotificationPanelViewMediator;
import com.android.systemui.car.notification.TopNotificationPanelViewMediator;
import com.android.systemui.car.userswitcher.FullscreenUserSwitcherViewMediator;
import com.android.systemui.car.userswitcher.UserSwitchTransitionViewMediator;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;

/**
 * Dagger injection module for {@link SystemUIOverlayWindowManager}
 */
@Module
public abstract class OverlayWindowModule {

    /** Injects NotificationPanelViewMediator. */
    @Binds
    @IntoMap
    @ClassKey(NotificationPanelViewMediator.class)
    public abstract OverlayViewMediator bindNotificationPanelViewMediator(
            NotificationPanelViewMediator notificationPanelViewMediator);

    /** Injects TopNotificationPanelViewMediator. */
    @Binds
    @IntoMap
    @ClassKey(TopNotificationPanelViewMediator.class)
    public abstract OverlayViewMediator bindTopNotificationPanelViewMediator(
            TopNotificationPanelViewMediator topNotificationPanelViewMediator);

    /** Injects BottomNotificationPanelViewMediator. */
    @Binds
    @IntoMap
    @ClassKey(BottomNotificationPanelViewMediator.class)
    public abstract OverlayViewMediator bindBottomNotificationPanelViewMediator(
            BottomNotificationPanelViewMediator bottomNotificationPanelViewMediator);

    /** Inject into CarKeyguardViewMediator. */
    @Binds
    @IntoMap
    @ClassKey(CarKeyguardViewMediator.class)
    public abstract OverlayViewMediator bindCarKeyguardViewMediator(
            CarKeyguardViewMediator carKeyguardViewMediator);

    /** Injects FullscreenUserSwitcherViewsMediator. */
    @Binds
    @IntoMap
    @ClassKey(FullscreenUserSwitcherViewMediator.class)
    public abstract OverlayViewMediator bindFullscreenUserSwitcherViewsMediator(
            FullscreenUserSwitcherViewMediator overlayViewsMediator);

    /** Injects CarUserSwitchingDialogMediator. */
    @Binds
    @IntoMap
    @ClassKey(UserSwitchTransitionViewMediator.class)
    public abstract OverlayViewMediator bindUserSwitchTransitionViewMediator(
            UserSwitchTransitionViewMediator userSwitchTransitionViewMediator);
}
