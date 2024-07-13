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

package com.android.overlaytest.overlayresapp;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A test activity to verify that the assets paths configuration changes are received if the
 * overlay targeting state is changed.
 */
public class OverlayResActivity extends Activity {
    private Runnable mConfigurationChangedCallback;
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        final Runnable callback = mConfigurationChangedCallback;
        if (callback != null) {
            callback.run();
        }
    }

    /** Registers the callback of onConfigurationChanged. */
    public void setConfigurationChangedCallback(Runnable callback) {
        mConfigurationChangedCallback = callback;
    }
}
