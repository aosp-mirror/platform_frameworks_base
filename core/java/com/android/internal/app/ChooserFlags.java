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

package com.android.internal.app;

import android.app.prediction.AppPredictionManager;

/**
 * Common flags for {@link ChooserListAdapter} and {@link ChooserActivity}.
 */
public class ChooserFlags {
    /**
     * If set to true, use ShortcutManager to retrieve the matching direct share targets, instead of
     * binding to every ChooserTargetService implementation.
     */
    // TODO(b/121287573): Replace with a system flag (setprop?)
    public static final boolean USE_SHORTCUT_MANAGER_FOR_DIRECT_TARGETS = true;

    /**
     * If {@link ChooserFlags#USE_SHORTCUT_MANAGER_FOR_DIRECT_TARGETS} and this is set to true,
     * {@link AppPredictionManager} will be queried for direct share targets.
     */
    // TODO(b/123089490): Replace with system flag
    static final boolean USE_PREDICTION_MANAGER_FOR_DIRECT_TARGETS = true;
}
