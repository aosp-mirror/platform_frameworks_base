/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.utils;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.provider.Settings;

public class TestableContext extends ContextWrapper {

    private final FakeContentResolver mFakeContentResolver;
    private final FakeSettingsProvider mSettingsProvider;

    public TestableContext(Context base) {
        super(base);
        mFakeContentResolver = new FakeContentResolver(base);
        ContentProviderClient settings = base.getContentResolver()
                .acquireContentProviderClient(Settings.AUTHORITY);
        mSettingsProvider = FakeSettingsProvider.getFakeSettingsProvider(settings,
                mFakeContentResolver);
        mFakeContentResolver.addProvider(Settings.AUTHORITY, mSettingsProvider);
    }

    public FakeSettingsProvider getSettingsProvider() {
        return mSettingsProvider;
    }

    @Override
    public FakeContentResolver getContentResolver() {
        return mFakeContentResolver;
    }

    @Override
    public Context getApplicationContext() {
        // Return this so its always a TestableContext.
        return this;
    }
}
