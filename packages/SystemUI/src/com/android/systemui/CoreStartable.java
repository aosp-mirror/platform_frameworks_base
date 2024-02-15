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
 * If your CoreStartable depends on different CoreStartables starting before it, use a
 * {@link com.android.systemui.startable.Dependencies} annotation to list out those dependencies.
 *
 * @see SystemUIApplication#startSystemUserServicesIfNeeded()
 */
public interface CoreStartable extends Dumpable {

    /** Main entry point for implementations. Called shortly after SysUI startup. */
    void start();

    @Override
    default void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
    }

    /** Called to determine if the dumpable should be registered as critical or normal priority */
    default boolean isDumpCritical() {
        return true;
    }

    /** Called immediately after the system broadcasts
     * {@link android.content.Intent#ACTION_LOCKED_BOOT_COMPLETED} or during SysUI startup if the
     * property {@code sys.boot_completed} is already set to 1. The latter typically occurs when
     * starting a new SysUI instance, such as when starting SysUI for a secondary user.
     * {@link #onBootCompleted()} will never be called before {@link #start()}. */
    default void onBootCompleted() {
    }
}
