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
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone.dagger;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import com.android.keyguard.LockIconViewController;
import com.android.systemui.biometrics.AuthRippleController;
import com.android.systemui.statusbar.NotificationShelfController;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.phone.NotificationPanelViewController;
import com.android.systemui.statusbar.phone.NotificationShadeWindowView;
import com.android.systemui.statusbar.phone.NotificationShadeWindowViewController;
import com.android.systemui.statusbar.phone.SplitShadeHeaderController;
import com.android.systemui.statusbar.phone.StatusBarCommandQueueCallbacks;
import com.android.systemui.statusbar.phone.StatusBarDemoMode;
import com.android.systemui.statusbar.phone.StatusBarHeadsUpChangeListener;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;

import javax.inject.Scope;

import dagger.Subcomponent;

/**
 * Dagger subcomponent tied to the lifecycle of StatusBar views.
 */
@Subcomponent(modules = {StatusBarViewModule.class})
@StatusBarComponent.StatusBarScope
public interface StatusBarComponent {
    /**
     * Builder for {@link StatusBarComponent}.
     */
    @Subcomponent.Factory
    interface Factory {
        StatusBarComponent create();
    }

    /**
     * Scope annotation for singleton items within the StatusBarComponent.
     */
    @Documented
    @Retention(RUNTIME)
    @Scope
    @interface StatusBarScope {}

    /**
     * Creates a {@link NotificationShadeWindowView}/
     * @return
     */
    @StatusBarScope
    NotificationShadeWindowView getNotificationShadeWindowView();

    /** */
    @StatusBarScope
    NotificationShelfController getNotificationShelfController();

    /** */
    @StatusBarScope
    NotificationStackScrollLayoutController getNotificationStackScrollLayoutController();

    /**
     * Creates a NotificationShadeWindowViewController.
     */
    @StatusBarScope
    NotificationShadeWindowViewController getNotificationShadeWindowViewController();

    /**
     * Creates a NotificationPanelViewController.
     */
    @StatusBarScope
    NotificationPanelViewController getNotificationPanelViewController();

    /**
     * Creates a LockIconViewController. Must be init after creation.
     */
    @StatusBarScope
    LockIconViewController getLockIconViewController();

    /**
     * Creates an AuthRippleViewController. Must be init after creation.
     */
    @StatusBarScope
    AuthRippleController getAuthRippleController();

    /**
     * Creates a StatusBarDemoMode.
     */
    @StatusBarScope
    StatusBarDemoMode getStatusBarDemoMode();

    /**
     * Creates a StatusBarHeadsUpChangeListener.
     */
    @StatusBarScope
    StatusBarHeadsUpChangeListener getStatusBarHeadsUpChangeListener();

    /**
     * Creates a StatusBarCommandQueueCallbacks.
     */
    @StatusBarScope
    StatusBarCommandQueueCallbacks getStatusBarCommandQueueCallbacks();

    /**
     * Creates a SplitShadeHeaderController.
     */
    @StatusBarScope
    SplitShadeHeaderController getSplitShadeHeaderController();
}
