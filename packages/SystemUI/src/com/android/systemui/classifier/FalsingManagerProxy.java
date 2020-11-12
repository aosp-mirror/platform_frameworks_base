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

package com.android.systemui.classifier;

import android.content.Context;
import android.content.res.Resources;
import android.hardware.SensorManager;
import android.net.Uri;
import android.provider.DeviceConfig;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.Dumpable;
import com.android.systemui.classifier.brightline.BrightLineFalsingManager;
import com.android.systemui.classifier.brightline.FalsingDataProvider;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dock.DockManager;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.FalsingPlugin;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.util.DeviceConfigProxy;
import com.android.systemui.util.sensors.ProximitySensor;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Simple passthrough implementation of {@link FalsingManager} allowing plugins to swap in.
 *
 * {@link BrightLineFalsingManager} is used when a Plugin is not loaded.
 */
@SysUISingleton
public class FalsingManagerProxy implements FalsingManager, Dumpable {

    private static final String PROXIMITY_SENSOR_TAG = "FalsingManager";
    private static final String DUMPABLE_TAG = "FalsingManager";
    public static final String FALSING_REMAIN_LOCKED = "falsing_failure_after_attempts";
    public static final String FALSING_SUCCESS = "falsing_success_after_attempts";

    private final PluginManager mPluginManager;
    private final ProximitySensor mProximitySensor;
    private final Resources mResources;
    private final ViewConfiguration mViewConfiguration;
    private final FalsingDataProvider mFalsingDataProvider;
    private final DeviceConfigProxy mDeviceConfig;
    private final DockManager mDockManager;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final DumpManager mDumpManager;
    private final StatusBarStateController mStatusBarStateController;
    final PluginListener<FalsingPlugin> mPluginListener;

    private FalsingManager mInternalFalsingManager;

    private final DeviceConfig.OnPropertiesChangedListener mDeviceConfigListener =
            properties -> onDeviceConfigPropertiesChanged(properties.getNamespace());

    @Inject
    FalsingManagerProxy(PluginManager pluginManager, @Main Executor executor,
            ProximitySensor proximitySensor,
            DeviceConfigProxy deviceConfig, DockManager dockManager,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            DumpManager dumpManager,
            StatusBarStateController statusBarStateController,
            @Main Resources resources,
            ViewConfiguration viewConfiguration,
            FalsingDataProvider falsingDataProvider) {
        mPluginManager = pluginManager;
        mProximitySensor = proximitySensor;
        mDockManager = dockManager;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mDumpManager = dumpManager;
        mStatusBarStateController = statusBarStateController;
        mResources = resources;
        mViewConfiguration = viewConfiguration;
        mFalsingDataProvider = falsingDataProvider;
        mProximitySensor.setTag(PROXIMITY_SENSOR_TAG);
        mProximitySensor.setDelay(SensorManager.SENSOR_DELAY_GAME);
        mDeviceConfig = deviceConfig;
        setupFalsingManager();
        mDeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                executor,
                mDeviceConfigListener
        );

        mPluginListener = new PluginListener<FalsingPlugin>() {
            public void onPluginConnected(FalsingPlugin plugin, Context context) {
                FalsingManager pluginFalsingManager = plugin.getFalsingManager(context);
                if (pluginFalsingManager != null) {
                    mInternalFalsingManager.cleanup();
                    mInternalFalsingManager = pluginFalsingManager;
                }
            }

            public void onPluginDisconnected(FalsingPlugin plugin) {
                setupFalsingManager();
            }
        };

        mPluginManager.addPluginListener(mPluginListener, FalsingPlugin.class);

        mDumpManager.registerDumpable(DUMPABLE_TAG, this);
    }

    private void onDeviceConfigPropertiesChanged(String namespace) {
        if (!DeviceConfig.NAMESPACE_SYSTEMUI.equals(namespace)) {
            return;
        }

        setupFalsingManager();
    }

    /**
     * Setup the FalsingManager implementation.
     *
     * If multiple implementations are available, this is where the choice is made.
     */
    private void setupFalsingManager() {
        if (mInternalFalsingManager != null) {
            mInternalFalsingManager.cleanup();
        }
        mInternalFalsingManager = new BrightLineFalsingManager(
                mFalsingDataProvider,
                mKeyguardUpdateMonitor,
                mProximitySensor,
                mDeviceConfig,
                mResources,
                mViewConfiguration,
                mDockManager,
                mStatusBarStateController
        );
    }

    /**
     * Returns the FalsingManager implementation in use.
     */
    @VisibleForTesting
    FalsingManager getInternalFalsingManager() {
        return mInternalFalsingManager;
    }

    @Override
    public void onSuccessfulUnlock() {
        mInternalFalsingManager.onSuccessfulUnlock();
    }

    @Override
    public void onNotificationActive() {
        mInternalFalsingManager.onNotificationActive();
    }

    @Override
    public void setShowingAod(boolean showingAod) {
        mInternalFalsingManager.setShowingAod(showingAod);
    }

    @Override
    public void onNotificatonStartDraggingDown() {
        mInternalFalsingManager.onNotificatonStartDraggingDown();
    }

    @Override
    public boolean isUnlockingDisabled() {
        return mInternalFalsingManager.isUnlockingDisabled();
    }

    @Override
    public boolean isFalseTouch(@Classifier.InteractionType int interactionType) {
        return mInternalFalsingManager.isFalseTouch(interactionType);
    }


    @Override
    public boolean isFalseTap(boolean robustCheck) {
        return mInternalFalsingManager.isFalseTap(robustCheck);
    }

    @Override
    public boolean isFalseDoubleTap() {
        return mInternalFalsingManager.isFalseDoubleTap();
    }

    @Override
    public void onNotificatonStopDraggingDown() {
        mInternalFalsingManager.onNotificatonStartDraggingDown();
    }

    @Override
    public void setNotificationExpanded() {
        mInternalFalsingManager.setNotificationExpanded();
    }

    @Override
    public boolean isClassifierEnabled() {
        return mInternalFalsingManager.isClassifierEnabled();
    }

    @Override
    public void onQsDown() {
        mInternalFalsingManager.onQsDown();
    }

    @Override
    public void setQsExpanded(boolean expanded) {
        mInternalFalsingManager.setQsExpanded(expanded);
    }

    @Override
    public boolean shouldEnforceBouncer() {
        return mInternalFalsingManager.shouldEnforceBouncer();
    }

    @Override
    public void onTrackingStarted(boolean secure) {
        mInternalFalsingManager.onTrackingStarted(secure);
    }

    @Override
    public void onTrackingStopped() {
        mInternalFalsingManager.onTrackingStopped();
    }

    @Override
    public void onLeftAffordanceOn() {
        mInternalFalsingManager.onLeftAffordanceOn();
    }

    @Override
    public void onCameraOn() {
        mInternalFalsingManager.onCameraOn();
    }

    @Override
    public void onAffordanceSwipingStarted(boolean rightCorner) {
        mInternalFalsingManager.onAffordanceSwipingStarted(rightCorner);
    }

    @Override
    public void onAffordanceSwipingAborted() {
        mInternalFalsingManager.onAffordanceSwipingAborted();
    }

    @Override
    public void onStartExpandingFromPulse() {
        mInternalFalsingManager.onStartExpandingFromPulse();
    }

    @Override
    public void onExpansionFromPulseStopped() {
        mInternalFalsingManager.onExpansionFromPulseStopped();
    }

    @Override
    public Uri reportRejectedTouch() {
        return mInternalFalsingManager.reportRejectedTouch();
    }

    @Override
    public void onScreenOnFromTouch() {
        mInternalFalsingManager.onScreenOnFromTouch();
    }

    @Override
    public boolean isReportingEnabled() {
        return mInternalFalsingManager.isReportingEnabled();
    }

    @Override
    public void onUnlockHintStarted() {
        mInternalFalsingManager.onUnlockHintStarted();
    }

    @Override
    public void onCameraHintStarted() {
        mInternalFalsingManager.onCameraHintStarted();
    }

    @Override
    public void onLeftAffordanceHintStarted() {
        mInternalFalsingManager.onLeftAffordanceHintStarted();
    }

    @Override
    public void onScreenTurningOn() {
        mInternalFalsingManager.onScreenTurningOn();
    }

    @Override
    public void onScreenOff() {
        mInternalFalsingManager.onScreenOff();
    }

    @Override
    public void onNotificationStopDismissing() {
        mInternalFalsingManager.onNotificationStopDismissing();
    }

    @Override
    public void onNotificationDismissed() {
        mInternalFalsingManager.onNotificationDismissed();
    }

    @Override
    public void onNotificationStartDismissing() {
        mInternalFalsingManager.onNotificationStartDismissing();
    }

    @Override
    public void onNotificationDoubleTap(boolean accepted, float dx, float dy) {
        mInternalFalsingManager.onNotificationDoubleTap(accepted, dx, dy);
    }

    @Override
    public void onBouncerShown() {
        mInternalFalsingManager.onBouncerShown();
    }

    @Override
    public void onBouncerHidden() {
        mInternalFalsingManager.onBouncerHidden();
    }

    @Override
    public void onTouchEvent(MotionEvent ev, int width, int height) {
        mInternalFalsingManager.onTouchEvent(ev, width, height);
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        mInternalFalsingManager.dump(fd, pw, args);
    }

    @Override
    public void cleanup() {
        mDeviceConfig.removeOnPropertiesChangedListener(mDeviceConfigListener);
        mPluginManager.removePluginListener(mPluginListener);
        mDumpManager.unregisterDumpable(DUMPABLE_TAG);
        mInternalFalsingManager.cleanup();
    }
}
