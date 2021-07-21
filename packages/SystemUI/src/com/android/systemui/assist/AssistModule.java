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

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import androidx.slice.Clock;

import com.android.internal.app.AssistUtils;
import com.android.systemui.dagger.SysUISingleton;

import javax.inject.Named;

import dagger.Module;
import dagger.Provides;

/** Module for dagger injections related to the Assistant. */
@Module
public abstract class AssistModule {

    static final String ASSIST_HANDLE_THREAD_NAME = "assist_handle_thread";
    static final String UPTIME_NAME = "uptime";

    @Provides
    @SysUISingleton
    @Named(ASSIST_HANDLE_THREAD_NAME)
    static Handler provideBackgroundHandler() {
        final HandlerThread backgroundHandlerThread =
                new HandlerThread("AssistHandleThread");
        backgroundHandlerThread.start();
        return backgroundHandlerThread.getThreadHandler();
    }

    @Provides
    @SysUISingleton
    static AssistUtils provideAssistUtils(Context context) {
        return new AssistUtils(context);
    }

    @Provides
    @SysUISingleton
    @Named(UPTIME_NAME)
    static Clock provideSystemClock() {
        return SystemClock::uptimeMillis;
    }
}
