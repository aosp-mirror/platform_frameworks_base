/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.testutils.shadow;

import static org.robolectric.internal.Shadow.directlyOn;

import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.support.annotation.ArrayRes;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowResources;

/**
 * Shadow Resources to handle resource references that Robolectric shadows cannot
 * handle because they are too new or private.
 */
@Implements(Resources.class)
public class SettingsLibShadowResources extends ShadowResources {

    @RealObject
    public Resources realResources;

    @Implementation
    public int[] getIntArray(@ArrayRes int id) throws NotFoundException {
        // The Robolectric has resource mismatch for these values, so we need to stub it here
        if (id == com.android.settingslib.R.array.batterymeter_bolt_points
                || id == com.android.settingslib.R.array.batterymeter_plus_points) {
            return new int[2];
        }
        return directlyOn(realResources, Resources.class).getIntArray(id);
    }
}
