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

package com.android.systemui.dagger;

import com.android.systemui.broadcast.BroadcastDispatcherModule;
import com.android.systemui.theme.ThemeModule;
import com.android.systemui.util.leak.LeakModule;

import dagger.Module;

/**
 * @deprecated This module is going away. Don't put anything in here.
 */
@Deprecated
@Module(includes = {
        BroadcastDispatcherModule.class,
        LeakModule.class,
        NightDisplayListenerModule.class,
        SharedLibraryModule.class,
        SettingsLibraryModule.class,
        ThemeModule.class
})
public class DependencyProvider {
}
