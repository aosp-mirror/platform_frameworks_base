/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.classifier;

import android.content.Context;

import com.android.systemui.Dependency;
import com.android.systemui.plugins.FalsingManager;

/**
 * When the phone is locked, listens to touch, sensor and phone events and sends them to
 * DataCollector and HumanInteractionClassifier.
 *
 * It does not collect touch events when the bouncer shows up.
 *
 * TODO: FalsingManager supports dependency injection. Use it.
 */
public class FalsingManagerFactory {
    private static FalsingManager sInstance = null;

    private FalsingManagerFactory() {}

    public static FalsingManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = Dependency.get(FalsingManager.class);
        }
        return sInstance;
    }

}
