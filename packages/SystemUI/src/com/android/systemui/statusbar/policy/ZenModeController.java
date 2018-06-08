/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.content.ComponentName;
import android.net.Uri;
import android.service.notification.Condition;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.ZenRule;

import com.android.systemui.statusbar.policy.ZenModeController.Callback;

public interface ZenModeController extends CallbackController<Callback> {
    void setZen(int zen, Uri conditionId, String reason);
    int getZen();
    ZenRule getManualRule();
    ZenModeConfig getConfig();
    long getNextAlarm();
    boolean isZenAvailable();
    ComponentName getEffectsSuppressor();
    boolean isCountdownConditionSupported();
    int getCurrentUser();
    boolean isVolumeRestricted();
    boolean areNotificationsHiddenInShade();

    public static interface Callback {
        default void onZenChanged(int zen) {}
        default void onConditionsChanged(Condition[] conditions) {}
        default void onNextAlarmChanged() {}
        default void onZenAvailableChanged(boolean available) {}
        default void onEffectsSupressorChanged() {}
        default void onManualRuleChanged(ZenRule rule) {}
        default void onConfigChanged(ZenModeConfig config) {}
    }

}