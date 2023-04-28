/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.soundpicker;

import android.content.Context;
import android.media.RingtoneManager;

/**
 * A factory class used to create {@link RingtoneManager}.
 */
public class RingtoneManagerFactory {

    private final Context mApplicationContext;

    RingtoneManagerFactory(Context context) {
        mApplicationContext = context.getApplicationContext();
    }

    /**
     * Creates a new {@link RingtoneManager} and returns it.
     *
     * @return a {@link RingtoneManager}
     */
    public RingtoneManager create() {
        return new RingtoneManager(mApplicationContext, /* includeParentRingtones */ true);
    }
}

