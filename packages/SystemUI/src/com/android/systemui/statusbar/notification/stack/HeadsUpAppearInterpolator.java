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

package com.android.systemui.statusbar.notification.stack;

import android.graphics.Path;
import android.view.animation.PathInterpolator;

/**
 * An interpolator specifically designed for the appear animation of heads up notifications.
 */
public class HeadsUpAppearInterpolator extends PathInterpolator {

    private static float X1 = 250f;
    private static float X2 = 200f;
    private static float XTOT = (X1 + X2);;

    public HeadsUpAppearInterpolator() {
        super(getAppearPath());
    }

    private static Path getAppearPath() {
        Path path = new Path();
        path.moveTo(0, 0);
        float y1 = 90f;
        float y2 = 80f;
        path.cubicTo(X1 * 0.8f / XTOT, y1 / y2,
                X1 * 0.8f / XTOT, y1 / y2,
                X1 / XTOT, y1 / y2);
        path.cubicTo((X1 + X2 * 0.4f) / XTOT, y1 / y2,
                (X1 + X2 * 0.2f) / XTOT, 1.0f,
                1.0f , 1.0f);
        return path;
    }

    public static float getFractionUntilOvershoot() {
        return X1 / XTOT;
    }
}
