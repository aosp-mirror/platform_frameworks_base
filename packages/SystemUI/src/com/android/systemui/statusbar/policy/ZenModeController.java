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

import android.net.Uri;
import android.service.notification.Condition;

public interface ZenModeController {
    void addCallback(Callback callback);
    void removeCallback(Callback callback);
    void setZen(int zen);
    int getZen();
    void requestConditions(boolean request);
    void setExitConditionId(Uri exitConditionId);
    Uri getExitConditionId();
    long getNextAlarm();
    void setUserId(int userId);
    boolean isZenAvailable();

    public static class Callback {
        public void onZenChanged(int zen) {}
        public void onExitConditionChanged(Uri exitConditionId) {}
        public void onConditionsChanged(Condition[] conditions) {}
        public void onNextAlarmChanged() {}
        public void onZenAvailableChanged(boolean available) {}
    }
}