/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.view.animation;

import android.content.pm.ActivityInfo.Config;

/**
 * An abstract class which is extended by default interpolators.
 */
abstract public class BaseInterpolator implements Interpolator {
    private @Config int mChangingConfiguration;
    /**
     * @hide
     */
    public @Config int getChangingConfiguration() {
        return mChangingConfiguration;
    }

    /**
     * @hide
     */
    void setChangingConfiguration(@Config int changingConfiguration) {
        mChangingConfiguration = changingConfiguration;
    }
}
