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
     * Whether to use {@link AppPredictionManager} to query for direct share targets (as opposed to
     * talking directly to {@link android.content.pm.ShortcutManager}.
     */
    // TODO(b/123089490): Replace with system flag
    static final boolean USE_PREDICTION_MANAGER_FOR_DIRECT_TARGETS = true;
}

