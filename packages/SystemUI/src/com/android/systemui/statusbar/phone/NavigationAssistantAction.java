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

package com.android.systemui.statusbar.phone;

import android.annotation.NonNull;
import android.os.Bundle;
import android.view.MotionEvent;

import com.android.systemui.assist.AssistManager;
import com.android.systemui.recents.OverviewProxyService;

/**
 * Assistant is triggered with this action
 */
public class NavigationAssistantAction extends NavigationGestureAction {
    private static final String TAG = "NavigationAssistantActions";

    private final AssistManager mAssistManager;

    public NavigationAssistantAction(@NonNull NavigationBarView navigationBarView,
            @NonNull OverviewProxyService service, AssistManager assistManager) {
        super(navigationBarView, service);
        mAssistManager = assistManager;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean disableProxyEvents() {
        return true;
    }

    @Override
    public void onGestureStart(MotionEvent event) {
        mAssistManager.startAssist(new Bundle());
    }
}
