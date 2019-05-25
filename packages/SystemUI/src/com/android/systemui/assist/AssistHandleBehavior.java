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

package com.android.systemui.assist;


import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.assist.AssistHandleBehaviorController.BehaviorController;

public enum AssistHandleBehavior {

    TEST(new AssistHandleOffBehavior()),
    OFF(new AssistHandleOffBehavior()),
    LIKE_HOME(new AssistHandleLikeHomeBehavior()),
    REMINDER_EXP(new AssistHandleReminderExpBehavior());

    private BehaviorController mController;

    AssistHandleBehavior(BehaviorController controller) {
        mController = controller;
    }

    BehaviorController getController() {
        return mController;
    }

    @VisibleForTesting
    void setTestController(BehaviorController controller) {
        if (this.equals(TEST)) {
            mController = controller;
        }
    }
}
