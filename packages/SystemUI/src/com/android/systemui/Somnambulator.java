/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.app.Activity;
import android.service.dreams.Sandman;

/**
 * A simple activity that launches a dream.
 * <p>
 *
 * This activity has been deprecated and no longer used. The system uses its presence to determine
 * whether a dock app should be started on dock through intent resolution.
 *
 * Note: This Activity is special.  If this class is moved to another package or
 * renamed, be sure to update the component name in {@link Sandman}.
 * </p>
 */
public class Somnambulator extends Activity {
    public Somnambulator() {
    }

    @Override
    public void onStart() {
        super.onStart();
        finish();
    }
}
