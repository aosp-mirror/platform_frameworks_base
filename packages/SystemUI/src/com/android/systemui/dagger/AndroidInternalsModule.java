/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.dagger;

import android.content.Context;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.NotificationMessagingUtil;
import com.android.internal.widget.LockPatternUtils;

import dagger.Module;
import dagger.Provides;

/**
 * Provides items imported from com.android.internal.
 */
@Module
public class AndroidInternalsModule {
    /** */
    @Provides
    @SysUISingleton
    public LockPatternUtils provideLockPatternUtils(Context context) {
        return new LockPatternUtils(context);
    }

    /** */
    @Provides
    @SysUISingleton
    public MetricsLogger provideMetricsLogger() {
        return new MetricsLogger();
    }

    /** */
    @Provides
    public NotificationMessagingUtil provideNotificationMessagingUtil(Context context) {
        return new NotificationMessagingUtil(context);
    }

}
