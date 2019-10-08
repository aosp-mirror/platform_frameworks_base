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

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.BRIGHTLINE_FALSING_MANAGER_ENABLED;
import static com.android.systemui.Dependency.MAIN_HANDLER_NAME;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.provider.DeviceConfig;
import android.view.MotionEvent;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dependency;
import com.android.systemui.classifier.brightline.BrightLineFalsingManager;
import com.android.systemui.classifier.brightline.FalsingDataProvider;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.FalsingPlugin;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.util.AsyncSensorManager;

import java.io.PrintWriter;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 * Simple passthrough implementation of {@link FalsingManager} allowing plugins to swap in.
 *
 * {@link FalsingManagerImpl} is used when a Plugin is not loaded.
 */
@Singleton
public class FalsingManagerProxy implements FalsingManager {

    private FalsingManager mInternalFalsingManager;
    private final Handler mMainHandler;

    @Inject
    FalsingManagerProxy(Context context, PluginManager pluginManager,
            @Named(MAIN_HANDLER_NAME) Handler handler) {
        mMainHandler = handler;
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_SYSTEMUI,
                command -> mMainHandler.post(command),
                properties -> onDeviceConfigPropertiesChanged(context, properties.getNamespace())
        );
        setupFalsingManager(context);
        final PluginListener<FalsingPlugin> mPluginListener = new PluginListener<FalsingPlugin>() {
            public void onPluginConnected(FalsingPlugin plugin, Context context) {
                FalsingManager pluginFalsingManager = plugin.getFalsingManager(context);
                if (pluginFalsingManager != null) {
                    mInternalFalsingManager.cleanup();
                    mInternalFalsingManager = pluginFalsingManager;
                }
            }

            public void onPluginDisconnected(FalsingPlugin plugin) {
                mInternalFalsingManager = new FalsingManagerImpl(context);
            }
        };

        pluginManager.addPluginListener(mPluginListener, FalsingPlugin.class);
    }

    private void onDeviceConfigPropertiesChanged(Context context, String namespace) {
        if (!DeviceConfig.NAMESPACE_SYSTEMUI.equals(namespace)) {
            return;
        }

        setupFalsingManager(context);
    }

    /**
     * Chooses the FalsingManager implementation.
     */
    @VisibleForTesting
    public void setupFalsingManager(Context context) {
        boolean brightlineEnabled = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_SYSTEMUI, BRIGHTLINE_FALSING_MANAGER_ENABLED, true);
        if (mInternalFalsingManager != null) {
            mInternalFalsingManager.cleanup();
        }
        if (!brightlineEnabled) {
            mInternalFalsingManager = new FalsingManagerImpl(context);
        } else {
            mInternalFalsingManager = new BrightLineFalsingManager(
                    new FalsingDataProvider(context.getResources().getDisplayMetrics()),
                    Dependency.get(AsyncSensorManager.class)
            );
        }

    }

    /**
     * Returns the FalsingManager implementation in use.
     */
    @VisibleForTesting
    FalsingManager getInternalFalsingManager() {
        return mInternalFalsingManager;
    }

    @Override
    public void onSucccessfulUnlock() {
        mInternalFalsingManager.onSucccessfulUnlock();
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
    public boolean isFalseTouch() {
        return mInternalFalsingManager.isFalseTouch();
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
    public boolean isClassiferEnabled() {
        return mInternalFalsingManager.isClassiferEnabled();
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
    public void onNotificatonStopDismissing() {
        mInternalFalsingManager.onNotificatonStopDismissing();
    }

    @Override
    public void onNotificationDismissed() {
        mInternalFalsingManager.onNotificationDismissed();
    }

    @Override
    public void onNotificatonStartDismissing() {
        mInternalFalsingManager.onNotificatonStartDismissing();
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
    public void dump(PrintWriter pw) {
        mInternalFalsingManager.dump(pw);
    }

    @Override
    public void cleanup() {
        mInternalFalsingManager.cleanup();
    }
}
