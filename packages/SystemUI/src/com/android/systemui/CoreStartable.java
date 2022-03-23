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

import android.content.Context;
import android.content.res.Configuration;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;

/**
 * A top-level module of system UI code (sometimes called "system UI services" elsewhere in code).
 * Which CoreStartable modules are loaded can be controlled via a config resource.
 *
 * @see SystemUIApplication#startServicesIfNeeded()
 */
public abstract class CoreStartable implements Dumpable {
    protected final Context mContext;

    public CoreStartable(Context context) {
        mContext = context;
    }

    /** Main entry point for implementations. Called shortly after app startup. */
    public abstract void start();

    protected void onConfigurationChanged(Configuration newConfig) {
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
    }

    @VisibleForTesting
    protected void onBootCompleted() {
    }
}
