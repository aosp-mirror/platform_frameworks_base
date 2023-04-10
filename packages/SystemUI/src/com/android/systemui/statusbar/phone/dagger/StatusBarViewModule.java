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

import android.annotation.Nullable;
import android.content.ContentResolver;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.ViewStub;

import androidx.constraintlayout.motion.widget.MotionLayout;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.LockIconView;
import com.android.systemui.R;
import com.android.systemui.battery.BatteryMeterView;
import com.android.systemui.battery.BatteryMeterViewController;
import com.android.systemui.biometrics.AuthRippleView;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.privacy.OngoingPrivacyChip;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.CombinedShadeHeadersConstraintManager;
import com.android.systemui.shade.CombinedShadeHeadersConstraintManagerImpl;
import com.android.systemui.shade.NotificationPanelView;
import com.android.systemui.shade.NotificationPanelViewController;
import com.android.systemui.shade.NotificationShadeWindowView;
import com.android.systemui.shade.NotificationsQuickSettingsContainer;
import com.android.systemui.shade.ShadeExpansionStateManager;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.NotificationShelfController;
import com.android.systemui.statusbar.OperatorNameViewController;
import com.android.systemui.statusbar.core.StatusBarInitializer.OnStatusBarViewInitializedListener;
import com.android.systemui.statusbar.events.SystemStatusAnimationScheduler;
import com.android.systemui.statusbar.notification.row.dagger.NotificationShelfComponent;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.phone.KeyguardBottomAreaView;
import com.android.systemui.statusbar.phone.LetterboxAppearanceCalculator;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.StatusBarBoundsProvider;
import com.android.systemui.statusbar.phone.StatusBarHideIconsForBouncerManager;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarLocationPublisher;
import com.android.systemui.statusbar.phone.StatusIconContainer;
import com.android.systemui.statusbar.phone.SystemBarAttributesListener;
import com.android.systemui.statusbar.phone.TapAgainView;
import com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment;
import com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragmentLogger;
import com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentComponent;
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.window.StatusBarWindowStateController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.CarrierConfigTracker;
import com.android.systemui.util.settings.SecureSettings;

import java.util.concurrent.Executor;

import javax.inject.Named;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;

@Module(subcomponents = StatusBarFragmentComponent.class)
public abstract class StatusBarViewModule {

    public static final String SHADE_HEADER = "large_screen_shade_header";
    public static final String STATUS_BAR_FRAGMENT = "status_bar_fragment";

    /** */
    @Provides
    @CentralSurfacesComponent.CentralSurfacesScope
    public static NotificationShadeWindowView providesNotificationShadeWindowView(
            LayoutInflater layoutInflater) {
        NotificationShadeWindowView notificationShadeWindowView = (NotificationShadeWindowView)
                layoutInflater.inflate(R.layout.super_notification_shade, /* root= */ null);
        if (notificationShadeWindowView == null) {
            throw new IllegalStateException(
                    "R.layout.super_notification_shade could not be properly inflated");
        }

        return notificationShadeWindowView;
    }

    /** */
    @Provides
    @CentralSurfacesComponent.CentralSurfacesScope
    public static NotificationStackScrollLayout providesNotificationStackScrollLayout(
            NotificationShadeWindowView notificationShadeWindowView) {
        return notificationShadeWindowView.findViewById(R.id.notification_stack_scroller);
    }

    /** */
    @Provides
    @CentralSurfacesComponent.CentralSurfacesScope
    public static NotificationShelf providesNotificationShelf(LayoutInflater layoutInflater,
            NotificationStackScrollLayout notificationStackScrollLayout) {
        NotificationShelf view = (NotificationShelf) layoutInflater.inflate(
                R.layout.status_bar_notification_shelf, notificationStackScrollLayout, false);

        if (view == null) {
            throw new IllegalStateException(
                    "R.layout.status_bar_notification_shelf could not be properly inflated");
        }
        return view;
    }

    /** */
    @Provides
    @CentralSurfacesComponent.CentralSurfacesScope
    public static NotificationShelfController providesStatusBarWindowView(
            NotificationShelfComponent.Builder notificationShelfComponentBuilder,
            NotificationShelf notificationShelf) {
        NotificationShelfComponent component = notificationShelfComponentBuilder
                .notificationShelf(notificationShelf)
                .build();
        NotificationShelfController notificationShelfController =
                component.getNotificationShelfController();
        notificationShelfController.init();

        return notificationShelfController;
    }

    /** */
    @Provides
    @CentralSurfacesComponent.CentralSurfacesScope
    public static NotificationPanelView getNotificationPanelView(
            NotificationShadeWindowView notificationShadeWindowView) {
        return notificationShadeWindowView.getNotificationPanelView();
    }

    /** */
    @Provides
    @CentralSurfacesComponent.CentralSurfacesScope
    public static LockIconView getLockIconView(
            NotificationShadeWindowView notificationShadeWindowView) {
        return notificationShadeWindowView.findViewById(R.id.lock_icon_view);
    }

    /** */
    @Provides
    @CentralSurfacesComponent.CentralSurfacesScope
    @Nullable
    public static AuthRippleView getAuthRippleView(
            NotificationShadeWindowView notificationShadeWindowView) {
        return notificationShadeWindowView.findViewById(R.id.auth_ripple);
    }

    /** */
    @Provides
    @Named(SHADE_HEADER)
    @CentralSurfacesComponent.CentralSurfacesScope
    public static MotionLayout getLargeScreenShadeHeaderBarView(
            NotificationShadeWindowView notificationShadeWindowView,
            FeatureFlags featureFlags) {
        ViewStub stub = notificationShadeWindowView.findViewById(R.id.qs_header_stub);
        int layoutId = R.layout.combined_qs_header;
        stub.setLayoutResource(layoutId);
        MotionLayout v = (MotionLayout) stub.inflate();
        return v;
    }

    /** */
    @Provides
    @CentralSurfacesComponent.CentralSurfacesScope
    public static CombinedShadeHeadersConstraintManager
            provideCombinedShadeHeadersConstraintManager() {
        return CombinedShadeHeadersConstraintManagerImpl.INSTANCE;
    }

    /** */
    @Provides
    @CentralSurfacesComponent.CentralSurfacesScope
    public static OngoingPrivacyChip getSplitShadeOngoingPrivacyChip(
            @Named(SHADE_HEADER) MotionLayout header) {
        return header.findViewById(R.id.privacy_chip);
    }

    /** */
    @Provides
    @CentralSurfacesComponent.CentralSurfacesScope
    static StatusIconContainer providesStatusIconContainer(
            @Named(SHADE_HEADER) MotionLayout header) {
        return header.findViewById(R.id.statusIcons);
    }

    /** */
    @Provides
    @CentralSurfacesComponent.CentralSurfacesScope
    @Named(SHADE_HEADER)
    static BatteryMeterView getBatteryMeterView(@Named(SHADE_HEADER) MotionLayout view) {
        return view.findViewById(R.id.batteryRemainingIcon);
    }

    @Provides
    @CentralSurfacesComponent.CentralSurfacesScope
    @Named(SHADE_HEADER)
    static BatteryMeterViewController getBatteryMeterViewController(
            @Named(SHADE_HEADER) BatteryMeterView batteryMeterView,
            UserTracker userTracker,
            ConfigurationController configurationController,
            TunerService tunerService,
            @Main Handler mainHandler,
            ContentResolver contentResolver,
            FeatureFlags featureFlags,
            BatteryController batteryController
    ) {
        return new BatteryMeterViewController(
                batteryMeterView,
                userTracker,
                configurationController,
                tunerService,
                mainHandler,
                contentResolver,
                featureFlags,
                batteryController);

    }

    /** */
    @Provides
    @CentralSurfacesComponent.CentralSurfacesScope
    public static TapAgainView getTapAgainView(NotificationPanelView npv) {
        return npv.getTapAgainView();
    }

    /** */
    @Provides
    @CentralSurfacesComponent.CentralSurfacesScope
    public static NotificationsQuickSettingsContainer getNotificationsQuickSettingsContainer(
            NotificationShadeWindowView notificationShadeWindowView) {
        return notificationShadeWindowView.findViewById(R.id.notification_container_parent);
    }

    @Binds
    @IntoSet
    abstract OnStatusBarViewInitializedListener statusBarInitializedListener(
            LetterboxAppearanceCalculator letterboxAppearanceCalculator
    );

    @Binds
    @IntoSet
    abstract StatusBarBoundsProvider.BoundsChangeListener sysBarAttrsListenerAsBoundsListener(
            SystemBarAttributesListener systemBarAttributesListener);

    /**
     * Creates a new {@link CollapsedStatusBarFragment}.
     *
     * **IMPORTANT**: This method intentionally does not have
     * {@link CentralSurfacesComponent.CentralSurfacesScope}, which means a new fragment *will* be
     * created each time this method is called. This is intentional because we need fragments to
     * re-created in certain lifecycle scenarios.
     *
     * This provider is {@link Named} such that it does not conflict with the provider inside of
     * {@link StatusBarFragmentComponent}.
     */
    @Provides
    @Named(STATUS_BAR_FRAGMENT)
    public static CollapsedStatusBarFragment createCollapsedStatusBarFragment(
            StatusBarFragmentComponent.Factory statusBarFragmentComponentFactory,
            OngoingCallController ongoingCallController,
            SystemStatusAnimationScheduler animationScheduler,
            StatusBarLocationPublisher locationPublisher,
            NotificationIconAreaController notificationIconAreaController,
            ShadeExpansionStateManager shadeExpansionStateManager,
            FeatureFlags featureFlags,
            StatusBarIconController statusBarIconController,
            StatusBarIconController.DarkIconManager.Factory darkIconManagerFactory,
            StatusBarHideIconsForBouncerManager statusBarHideIconsForBouncerManager,
            KeyguardStateController keyguardStateController,
            NotificationPanelViewController notificationPanelViewController,
            StatusBarStateController statusBarStateController,
            CommandQueue commandQueue,
            CarrierConfigTracker carrierConfigTracker,
            CollapsedStatusBarFragmentLogger collapsedStatusBarFragmentLogger,
            OperatorNameViewController.Factory operatorNameViewControllerFactory,
            SecureSettings secureSettings,
            @Main Executor mainExecutor,
            DumpManager dumpManager,
            StatusBarWindowStateController statusBarWindowStateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor
    ) {
        return new CollapsedStatusBarFragment(statusBarFragmentComponentFactory,
                ongoingCallController,
                animationScheduler,
                locationPublisher,
                notificationIconAreaController,
                shadeExpansionStateManager,
                featureFlags,
                statusBarIconController,
                darkIconManagerFactory,
                statusBarHideIconsForBouncerManager,
                keyguardStateController,
                notificationPanelViewController,
                statusBarStateController,
                commandQueue,
                carrierConfigTracker,
                collapsedStatusBarFragmentLogger,
                operatorNameViewControllerFactory,
                secureSettings,
                mainExecutor,
                dumpManager,
                statusBarWindowStateController,
                keyguardUpdateMonitor);
    }

    /**
     * Constructs a new, unattached {@link KeyguardBottomAreaView}.
     *
     * Note that this is explicitly _not_ a singleton, as we want to be able to reinflate it
     */
    @Provides
    public static KeyguardBottomAreaView providesKeyguardBottomAreaView(
            NotificationPanelView npv, LayoutInflater layoutInflater) {
        return (KeyguardBottomAreaView) layoutInflater.inflate(R
                .layout.keyguard_bottom_area, npv, false);
    }

}
