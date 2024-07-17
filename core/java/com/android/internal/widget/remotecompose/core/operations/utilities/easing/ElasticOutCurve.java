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
package com.android.internal.widget.remotecompose.core.operations.utilities.easing;

/**
 * Provide a bouncing Easing function
 */
public class ElasticOutCurve extends Easing {
    private static final float F_PI = (float) Math.PI;
    private static final float C4 = 2 * F_PI / 3;
    private static final float TWENTY_PI = 20 * F_PI;
    private static final float LOG_8 = (float) Math.log(8.0f);

    @Override
    public float get(float x) {
        if (x <= 0) {
            return 0.0f;
        }
        if (x >= 1) {
            return 1.0f;
        } else
            return (float) (Math.pow(2.0f, -10 * x)
                    * Math.sin((x * 10 - 0.75f) * C4) + 1);
    }

    @Override
    public float getDiff(float x) {
        if (x < 0 || x > 1) {
            return 0.0f;
        } else
            return (float) ((5 * Math.pow(2.0f, (1 - 10 * x))
                    * (LOG_8 * Math.cos(TWENTY_PI * x / 3) + 2
                    * F_PI * Math.sin(TWENTY_PI * x / 3))
                    / 3));
    }
}
