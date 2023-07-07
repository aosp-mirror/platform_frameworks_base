/*
 * Copyright 2022 The Android Open Source Project
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

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/**
 * Placeholder widget for testing
 */
public class TestAppWidgetProvider extends AppWidgetProvider {
    private boolean mEnabled;
    private boolean mUpdated;

    TestAppWidgetProvider() {
        super();
        mEnabled = false;
        mUpdated = false;
    }

    public boolean isBehaviorSuccess() {
        return mEnabled && mUpdated;
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager,
            int[] appWidgetids) {
        mUpdated = true;
    }

    @Override
    public void onEnabled(Context context) {
        mEnabled = true;
    }
}
