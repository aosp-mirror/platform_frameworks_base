/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.egg.octo;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.os.Debug;

import java.util.Arrays;

public class TaperedPathStroke {
    static float sMinStepPx = 4f;
    static PathMeasure pm = new PathMeasure();
    static float[] pos = {0,0};
    static float[] tan = {0,0};
    static float lerp(float t, float a, float b) {
        return a + t*(b-a);
    }
    public static void setMinStep(float px) {
        sMinStepPx = px;
    }

    // it's the variable-width brush algorithm from the Markers app, basically
    public static void drawPath(Canvas c, Path p, float r1, float r2, Paint pt) {
        pm.setPath(p,false);
        final float len = pm.getLength();
        float t=0;
        boolean last=false;
        while (true) {
            if (t>=len) {
                t=len;
                last=true;
            }
            pm.getPosTan(t, pos, tan);
            float r = len > 0 ? lerp(t/len, r1, r2) : r1;
            c.drawCircle(pos[0], pos[1], r, pt);
            t += Math.max(r*0.25f, sMinStepPx); // walk forward 1/4 radius, not too small though
            if (last) break;
        }
    }
}
