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
import android.net.Uri;
import android.provider.DeviceConfig;

import androidx.annotation.NonNull;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.FalsingPlugin;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.util.DeviceConfigProxy;

import java.io.PrintWriter;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Simple passthrough implementation of {@link FalsingManager} allowing plugins to swap in.
 *
 * {@link BrightLineFalsingManager} is used when a Plugin is not loaded.
 */
@SysUISingleton
public class FalsingManagerProxy implements FalsingManager, Dumpable {

    private static final String DUMPABLE_TAG = "FalsingManager";
    public static final String FALSING_REMAIN_LOCKED = "falsing_failure_after_attempts";
    public static final String FALSING_SUCCESS = "falsing_success_after_attempts";

    private final PluginManager mPluginManager;
    private final DeviceConfigProxy mDeviceConfig;
    private final Provider<BrightLineFalsingManager> mBrightLineFalsingManagerProvider;
    private final DumpManager mDumpManager;
    final PluginListener<FalsingPlugin> mPluginListener;

    private FalsingManager mInternalFalsingManager;

    private final DeviceConfig.OnPropertiesChangedListener mDeviceConfigListener =
            properties -> onDeviceConfigPropertiesChanged(properties.getNamespace());

    @Inject
    FalsingManagerProxy(PluginManager pluginManager, @Main Executor executor,
            DeviceConfigProxy deviceConfig, DumpManager dumpManager,
            Provider<BrightLineFalsingManager> brightLineFalsingManagerProvider) {
        mPluginManager = pluginManager;
        mDumpManager = dumpManager;
        mDeviceConfig = deviceConfig;
        mBrightLineFalsingManagerProvider = brightLineFalsingManagerProvider;
        setupFalsingManager();
        mDeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_SYSTEMUI, executor, mDeviceConfigListener
        );

        mPluginListener = new PluginListener<FalsingPlugin>() {
            public void onPluginConnected(FalsingPlugin plugin, Context context) {
                FalsingManager pluginFalsingManager = plugin.getFalsingManager(context);
                if (pluginFalsingManager != null) {
                    mInternalFalsingManager.cleanupInternal();
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
            mInternalFalsingManager.cleanupInternal();
        }
        mInternalFalsingManager = mBrightLineFalsingManagerProvider.get();
    }

    @Override
    public void onSuccessfulUnlock() {
        mInternalFalsingManager.onSuccessfulUnlock();
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
    public boolean isSimpleTap() {
        return mInternalFalsingManager.isSimpleTap();
    }

    @Override
    public boolean isFalseTap(@Penalty int penalty) {
        return mInternalFalsingManager.isFalseTap(penalty);
    }

    @Override
    public boolean isFalseLongTap(int penalty) {
        return mInternalFalsingManager.isFalseLongTap(penalty);
    }

    @Override
    public boolean isFalseDoubleTap() {
        return mInternalFalsingManager.isFalseDoubleTap();
    }

    @Override
    public boolean isProximityNear() {
        return mInternalFalsingManager.isProximityNear();
    }

    @Override
    public boolean isClassifierEnabled() {
        return mInternalFalsingManager.isClassifierEnabled();
    }

    @Override
    public boolean shouldEnforceBouncer() {
        return mInternalFalsingManager.shouldEnforceBouncer();
    }

    @Override
    public Uri reportRejectedTouch() {
        return mInternalFalsingManager.reportRejectedTouch();
    }

    @Override
    public boolean isReportingEnabled() {
        return mInternalFalsingManager.isReportingEnabled();
    }

    @Override
    public void addFalsingBeliefListener(FalsingBeliefListener listener) {
        mInternalFalsingManager.addFalsingBeliefListener(listener);
    }

    @Override
    public void removeFalsingBeliefListener(FalsingBeliefListener listener) {
        mInternalFalsingManager.removeFalsingBeliefListener(listener);
    }

    @Override
    public void addTapListener(FalsingTapListener listener) {
        mInternalFalsingManager.addTapListener(listener);
    }

    @Override
    public void removeTapListener(FalsingTapListener listener) {
        mInternalFalsingManager.removeTapListener(listener);
    }

    @Override
    public void onProximityEvent(ProximityEvent proximityEvent) {
        mInternalFalsingManager.onProximityEvent(proximityEvent);
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        mInternalFalsingManager.dump(pw, args);
    }

    @Override
    public void cleanupInternal() {
        mDeviceConfig.removeOnPropertiesChangedListener(mDeviceConfigListener);
        mPluginManager.removePluginListener(mPluginListener);
        mDumpManager.unregisterDumpable(DUMPABLE_TAG);
        mInternalFalsingManager.cleanupInternal();
    }
}
