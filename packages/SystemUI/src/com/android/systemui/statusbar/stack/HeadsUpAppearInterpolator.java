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
 * limitations under the License
 */

package com.android.systemui.statusbar.stack;

import android.graphics.Path;
import android.view.animation.PathInterpolator;

/**
 * An interpolator specifically designed for the appear animation of heads up notifications.
 */
public class HeadsUpAppearInterpolator extends PathInterpolator {
    public HeadsUpAppearInterpolator() {
        super(getAppearPath());
    }

    private static Path getAppearPath() {
        Path path = new Path();
        path.moveTo(0, 0);
        float x1 = 250f;
        float x2 = 150f;
        float x3 = 100f;
        float y1 = 90f;
        float y2 = 78f;
        float y3 = 80f;
        float xTot = (x1 + x2 + x3);
        path.cubicTo(x1 * 0.9f / xTot, 0f,
                x1 * 0.8f / xTot, y1 / y3,
                x1 / xTot , y1 / y3);
        path.cubicTo((x1 + x2 * 0.4f) / xTot, y1 / y3,
                (x1 + x2 * 0.2f) / xTot, y2 / y3,
                (x1 + x2) / xTot, y2 / y3);
        path.cubicTo((x1 + x2 + x3 * 0.4f) / xTot, y2 / y3,
                (x1 + x2 + x3 * 0.2f) / xTot, 1f,
                1f, 1f);
        return path;
    }
}
