/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.content.SharedPreferences;
import android.hardware.display.AmbientDisplayConfiguration;
import android.util.DisplayMetrics;
import android.view.Choreographer;

import com.android.systemui.Prefs;
import com.android.systemui.dagger.qualifiers.Main;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

/**
 * Supplies globally scoped instances.
 *
 * Providers in this module will be accessible to both WMComponent and SysUIComponent scoped
 * classes. They are in here because they are either needed globally or are inherently universal
 * to the application.
 *
 * Note that just because a class might be used by both WM and SysUI does not necessarily mean that
 * it should got into this module. If WM and SysUI might need the class for different purposes
 * or different semantics, it may make sense to ask them to supply their own. Something like
 * threading and concurrency provide a good example. Both components need
 * Threads/Handlers/Executors, but they need separate instances of them in many cases.
 *
 * Please use discretion when adding things to the global scope.
 */
@Module
public class GlobalModule {
    /** */
    @Provides
    @Main
    public SharedPreferences provideSharePreferences(Context context) {
        return Prefs.get(context);
    }

    /** */
    @Provides
    public AmbientDisplayConfiguration provideAmbientDisplayConfiguration(Context context) {
        return new AmbientDisplayConfiguration(context);
    }

    /** */
    @Provides
    @Singleton
    public Choreographer providesChoreographer() {
        return Choreographer.getInstance();
    }

    /** */
    @Provides
    @Singleton
    public DisplayMetrics provideDisplayMetrics(Context context) {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        context.getDisplay().getMetrics(displayMetrics);
        return displayMetrics;
    }
}
