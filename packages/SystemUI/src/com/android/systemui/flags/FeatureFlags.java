/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.flags;

import android.content.Context;
import android.util.FeatureFlagUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.plugins.FlagReaderPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

/**
 * Class to manage simple DeviceConfig-based feature flags.
 *
 * See {@link FeatureFlagReader} for instructions on defining and flipping flags.
 */
@SysUISingleton
public class FeatureFlags {
    private final FeatureFlagReader mFlagReader;
    private final Context mContext;
    private final Map<Integer, Flag<?>> mFlagMap = new HashMap<>();
    private final Map<Integer, List<Listener>> mListeners = new HashMap<>();

    @Inject
    public FeatureFlags(FeatureFlagReader flagReader, Context context) {
        mFlagReader = flagReader;
        mContext = context;

        flagReader.addListener(mListener);
    }

    private final FlagReaderPlugin.Listener mListener = id -> {
        if (mListeners.containsKey(id) && mFlagMap.containsKey(id)) {
            mListeners.get(id).forEach(listener -> listener.onFlagChanged(mFlagMap.get(id)));
        }
    };

    @VisibleForTesting
    void addFlag(Flag flag) {
        mFlagMap.put(flag.getId(), flag);
    }

    /**
     * @param flag The {@link BooleanFlag} of interest.
     * @return The value of the flag.
     */
    public boolean isEnabled(BooleanFlag flag) {
        return mFlagReader.isEnabled(flag);
    }

    /**
     * @param flag The {@link StringFlag} of interest.
     * @return The value of the flag.
     */
    public String getValue(StringFlag flag) {
        return mFlagReader.getValue(flag);
    }

    /**
     * @param flag The {@link IntFlag} of interest.
     * @return The value of the flag.
     */
    public int getValue(IntFlag flag) {
        return mFlagReader.getValue(flag);
    }

    /**
     * @param flag The {@link LongFlag} of interest.
     * @return The value of the flag.
     */
    public long getValue(LongFlag flag) {
        return mFlagReader.getValue(flag);
    }

    /**
     * @param flag The {@link FloatFlag} of interest.
     * @return The value of the flag.
     */
    public float getValue(FloatFlag flag) {
        return mFlagReader.getValue(flag);
    }

    /**
     * @param flag The {@link DoubleFlag} of interest.
     * @return The value of the flag.
     */
    public double getValue(DoubleFlag flag) {
        return mFlagReader.getValue(flag);
    }

    /** Add a listener for a specific flag. */
    public void addFlagListener(Flag<?> flag, Listener listener) {
        mListeners.putIfAbsent(flag.getId(), new ArrayList<>());
        mListeners.get(flag.getId()).add(listener);
        mFlagMap.putIfAbsent(flag.getId(), flag);
    }

    /** Remove a listener for a specific flag. */
    public void removeFlagListener(Flag<?> flag, Listener listener) {
        if (mListeners.containsKey(flag.getId())) {
            mListeners.get(flag.getId()).remove(listener);
        }
    }

    public boolean isNewNotifPipelineEnabled() {
        return mFlagReader.isEnabled(R.bool.flag_notification_pipeline2);
    }

    public boolean isNewNotifPipelineRenderingEnabled() {
        return mFlagReader.isEnabled(R.bool.flag_notification_pipeline2_rendering);
    }

    public boolean isKeyguardLayoutEnabled() {
        return mFlagReader.isEnabled(R.bool.flag_keyguard_layout);
    }

    /** */
    public boolean useNewLockscreenAnimations() {
        return mFlagReader.isEnabled(R.bool.flag_lockscreen_animations);
    }

    public boolean isPeopleTileEnabled() {
        return mFlagReader.isEnabled(R.bool.flag_conversations);
    }

    public boolean isMonetEnabled() {
        return mFlagReader.isEnabled(R.bool.flag_monet);
    }

    public boolean isPMLiteEnabled() {
        return mFlagReader.isEnabled(R.bool.flag_pm_lite);
    }

    public boolean isChargingRippleEnabled() {
        return mFlagReader.isEnabled(R.bool.flag_charging_ripple);
    }

    public boolean isOngoingCallStatusBarChipEnabled() {
        return mFlagReader.isEnabled(R.bool.flag_ongoing_call_status_bar_chip);
    }

    public boolean isSmartspaceEnabled() {
        return mFlagReader.isEnabled(R.bool.flag_smartspace);
    }

    public boolean isSmartspaceDedupingEnabled() {
        return isSmartspaceEnabled() && mFlagReader.isEnabled(R.bool.flag_smartspace_deduping);
    }

    public boolean isNewKeyguardSwipeAnimationEnabled() {
        return mFlagReader.isEnabled(R.bool.flag_new_unlock_swipe_animation);
    }

    public boolean isSmartSpaceSharedElementTransitionEnabled() {
        return mFlagReader.isEnabled(R.bool.flag_smartspace_shared_element_transition);
    }

    /** Whether or not to use the provider model behavior for the status bar icons */
    public boolean isCombinedStatusBarSignalIconsEnabled() {
        return mFlagReader.isEnabled(R.bool.flag_combined_status_bar_signal_icons);
    }

    /** System setting for provider model behavior */
    public boolean isProviderModelSettingEnabled() {
        return FeatureFlagUtils.isEnabled(mContext, FeatureFlagUtils.SETTINGS_PROVIDER_MODEL);
    }

    /** static method for the system setting */
    public static boolean isProviderModelSettingEnabled(Context context) {
        return FeatureFlagUtils.isEnabled(context, FeatureFlagUtils.SETTINGS_PROVIDER_MODEL);
    }

    /** Simple interface for beinga alerted when a specific flag changes value. */
    public interface Listener {
        /** */
        void onFlagChanged(Flag<?> flag);
    }
}
