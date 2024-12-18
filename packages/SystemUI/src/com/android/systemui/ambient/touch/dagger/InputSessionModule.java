/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.ambient.touch.dagger;

import static com.android.systemui.ambient.touch.dagger.AmbientTouchModule.INPUT_SESSION_NAME;

import android.view.GestureDetector;

import com.android.systemui.settings.DisplayTracker;
import com.android.systemui.shared.system.InputMonitorCompat;

import dagger.Module;
import dagger.Provides;

import javax.inject.Named;

/**
 * Module for providing dependencies to {@link com.android.systemui.dreams.touch.InputSession}.
 */
@Module
public interface InputSessionModule {
    /** */
    @Provides
    static InputMonitorCompat providesInputMonitorCompat(@Named(INPUT_SESSION_NAME) String name,
            DisplayTracker displayTracker) {
        return new InputMonitorCompat(name, displayTracker.getDefaultDisplayId());
    }

    /** */
    @Provides
    static GestureDetector providesGestureDetector(
            android.view.GestureDetector.OnGestureListener gestureListener) {
        return new GestureDetector(gestureListener);
    }
}
