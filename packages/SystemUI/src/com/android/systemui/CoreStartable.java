/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui;

import android.content.res.Configuration;

import androidx.annotation.NonNull;

import java.io.PrintWriter;

/**
 * Code that needs to be run when SystemUI is started.
 *
 * Which CoreStartable modules are loaded is controlled via the dagger graph. Bind them into the
 * CoreStartable map with code such as:
 *
 *  <pre>
 *  &#64;Binds
 *  &#64;IntoMap
 *  &#64;ClassKey(FoobarStartable::class)
 *  abstract fun bind(impl: FoobarStartable): CoreStartable
 *  </pre>
 *
 * @see SystemUIApplication#startServicesIfNeeded()
 */
public interface CoreStartable extends Dumpable {

    /** Main entry point for implementations. Called shortly after app startup. */
    void start();

    /** */
    default void onConfigurationChanged(Configuration newConfig) {
    }

    @Override
    default void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
    }

    /** Called when the device reports BOOT_COMPLETED. */
    default void onBootCompleted() {
    }
}
