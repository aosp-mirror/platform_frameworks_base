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

import static com.android.systemui.statusbar.phone.dagger.StatusBarViewModule.STATUS_BAR_FRAGMENT;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import com.android.systemui.scene.ui.view.WindowRootView;
import com.android.systemui.shade.NotificationPanelViewController;
import com.android.systemui.shade.NotificationShadeWindowView;
import com.android.systemui.shade.NotificationShadeWindowViewController;
import com.android.systemui.shade.QuickSettingsController;
import com.android.systemui.shade.ShadeHeaderController;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutListContainerModule;
import com.android.systemui.statusbar.phone.CentralSurfacesCommandQueueCallbacks;
import com.android.systemui.statusbar.phone.CentralSurfacesImpl;
import com.android.systemui.statusbar.phone.StatusBarHeadsUpChangeListener;
import com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarterModule;
import com.android.systemui.statusbar.phone.StatusBarNotificationPresenterModule;
import com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment;

import dagger.Subcomponent;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;

import javax.inject.Named;
import javax.inject.Scope;

/**
 * Dagger subcomponent for classes (semi-)related to the status bar. The component is created once
 * inside {@link CentralSurfacesImpl} and never re-created.
 *
 * TODO(b/197137564): This should likely be re-factored a bit. It includes classes that aren't
 * directly related to status bar functionality, like multiple notification classes. And, the fact
 * that it has many getter methods indicates that we need to access many of these classes from
 * outside the component. Should more items be moved *into* this component to avoid so many getters?
 */
@Subcomponent(modules = {
        NotificationStackScrollLayoutListContainerModule.class,
        StatusBarViewModule.class,
        StatusBarNotificationActivityStarterModule.class,
        StatusBarNotificationPresenterModule.class,
})
@CentralSurfacesComponent.CentralSurfacesScope
public interface CentralSurfacesComponent {
    /**
     * Builder for {@link CentralSurfacesComponent}.
     */
    @Subcomponent.Factory
    interface Factory {
        CentralSurfacesComponent create();
    }

    /**
     * Scope annotation for singleton items within the CentralSurfacesComponent.
     */
    @Documented
    @Retention(RUNTIME)
    @Scope
    @interface CentralSurfacesScope {}

    /** Creates the root view of the main SysUI window}. */
    WindowRootView getWindowRootView();

    /**
     * Creates or returns a {@link NotificationShadeWindowView}.
     */
    NotificationShadeWindowView getNotificationShadeWindowView();

    /** */
    NotificationStackScrollLayoutController getNotificationStackScrollLayoutController();

    /**
     * Creates a NotificationShadeWindowViewController.
     */
    NotificationShadeWindowViewController getNotificationShadeWindowViewController();

    /**
     * Creates a NotificationPanelViewController.
     */
    NotificationPanelViewController getNotificationPanelViewController();

    /** Creates a QuickSettingsController. */
    QuickSettingsController getQuickSettingsController();

    /**
     * Creates a StatusBarHeadsUpChangeListener.
     */
    StatusBarHeadsUpChangeListener getStatusBarHeadsUpChangeListener();

    /**
     * Creates a CentralSurfacesCommandQueueCallbacks.
     */
    CentralSurfacesCommandQueueCallbacks getCentralSurfacesCommandQueueCallbacks();

    /**
     * Creates a {@link ShadeHeaderController}.
     */
    ShadeHeaderController getLargeScreenShadeHeaderController();

    /**
     * Creates a new {@link CollapsedStatusBarFragment} each time it's called. See
     * {@link StatusBarViewModule#createCollapsedStatusBarFragment}.
     */
    @Named(STATUS_BAR_FRAGMENT)
    CollapsedStatusBarFragment createCollapsedStatusBarFragment();

    NotificationActivityStarter getNotificationActivityStarter();

    NotificationPresenter getNotificationPresenter();

    NotificationListContainer getNotificationListContainer();
}
