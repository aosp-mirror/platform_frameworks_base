/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server.appwidget;

import android.content.Context;

import com.android.server.AppWidgetBackupBridge;
import com.android.server.SystemService;

/**
 * SystemService that publishes an IAppWidgetService.
 */
public class AppWidgetService extends SystemService {
    private final AppWidgetServiceImpl mImpl;

    public AppWidgetService(Context context) {
        super(context);
        mImpl = new AppWidgetServiceImpl(context);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.APPWIDGET_SERVICE, mImpl);
        AppWidgetBackupBridge.register(mImpl);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            mImpl.setSafeMode(isSafeMode());
        }
    }
}
