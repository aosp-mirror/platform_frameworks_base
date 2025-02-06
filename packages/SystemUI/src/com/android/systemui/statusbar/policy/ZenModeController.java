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

import android.app.NotificationManager;
import android.net.Uri;
import android.service.notification.ZenModeConfig;

import com.android.systemui.statusbar.policy.ZenModeController.Callback;
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor;

/**
 * Callback-based controller for listening to (or making) zen mode changes. Please prefer using the
 * Flow-based {@link ZenModeInteractor} for new code instead of this.
 *
 * TODO(b/308591859): This should eventually be replaced by ZenModeInteractor/ZenModeRepository.
 */
public interface ZenModeController extends CallbackController<Callback> {
    void setZen(int zen, Uri conditionId, String reason);
    int getZen();
    ZenModeConfig getConfig();
    /** Gets consolidated zen policy that will apply when DND is on in priority only mode */
    NotificationManager.Policy getConsolidatedPolicy();
    long getNextAlarm();
    boolean isZenAvailable();
    int getCurrentUser();

    public static interface Callback {
        default void onZenChanged(int zen) {}
        default void onNextAlarmChanged() {}
        default void onZenAvailableChanged(boolean available) {}
        default void onConfigChanged(ZenModeConfig config) {}
        /** Called when the consolidated zen policy changes */
        default void onConsolidatedPolicyChanged(NotificationManager.Policy policy) {}
    }

}