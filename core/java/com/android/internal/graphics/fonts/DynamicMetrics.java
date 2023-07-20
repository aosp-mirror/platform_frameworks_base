/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.graphics.fonts;

import android.app.ActivityThread;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;

public class DynamicMetrics {
    // https://rsms.me/inter/dynmetrics/
    private static final float A = -0.0223f;
    private static final float B = 0.185f;
    private static final float C = -0.1745f;

    private static float sDensity = 0.0f;

    // Precalculated tracking LUT up to 32 dp, in steps of 0.5 dp to minimize rounding errors.
    // Sizes are close enough that we can cast them to ints for lookup.
    // In most cases, we should never have to calculate tracking at runtime because of this.
    private static final float[] TRACKING_LUT = {
        /* 0.0dp */ 0.1627f,
        /* 0.5dp */ 0.147242871675558f,
        /* 1.0dp */ 0.1330772180324039f,
        /* 1.5dp */ 0.12009513371985438f,
        /* 2.0dp */ 0.10819772909994156f,
        /* 2.5dp */ 0.09729437696617904f,
        /* 3.0dp */ 0.08730202220051463f,
        /* 3.5dp */ 0.0781445491098568f,
        /* 4.0dp */ 0.06975220162292829f,
        /* 4.5dp */ 0.062061051930857966f,
        /* 5.0dp */ 0.055012513523938045f,
        /* 5.5dp */ 0.04855289491515605f,
        /* 6.0dp */ 0.04263299065103837f,
        /* 6.5dp */ 0.03720770649437408f,
        /* 7.0dp */ 0.032235715923688846f,
        /* 7.5dp */ 0.027679145332890072f,
        /* 8.0dp */ 0.02350328553312564f,
        /* 8.5dp */ 0.019676327359252226f,
        /* 9.0dp */ 0.016169119366923862f,
        /* 9.5dp */ 0.012954945774584309f,
        /* 10.0dp */ 0.010009322958860013f,
        /* 10.5dp */ 0.007309812953179257f,
        /* 11.0dp */ 0.004835852528962955f,
        /* 11.5dp */ 0.002568596557431524f,
        /* 12.0dp */ 0.0004907744588531701f,
        /* 12.5dp */ -0.0014134413542490412f,
        /* 13.0dp */ -0.0031585560420509667f,
        /* 13.5dp */ -0.004757862828932768f,
        /* 14.0dp */ -0.006223544263193034f,
        /* 14.5dp */ -0.007566765016306747f,
        /* 15.0dp */ -0.008797756928615424f,
        /* 15.5dp */ -0.00992589694927596f,
        /* 16.0dp */ -0.010959778564167369f,
        /* 16.5dp */ -0.01190727725584982f,
        /* 17.0dp */ -0.012775610494210232f,
        /* 17.5dp */ -0.013571392714766779f,
        /* 18.0dp */ -0.014300685703423587f,
        /* 18.5dp */ -0.014969044771476151f,
        /* 19.0dp */ -0.01558156107260065f,
        /* 19.5dp */ -0.01614290038417221f,
        /* 20.0dp */ -0.016657338648324763f,
        /* 20.5dp */ -0.017128794543482675f,
        /* 21.0dp */ -0.01756085933447426f,
        /* 21.5dp */ -0.017956824228607303f,
        /* 22.0dp */ -0.018319705446088512f,
        /* 22.5dp */ -0.018652267195758174f,
        /* 23.0dp */ -0.01895704273115516f,
        /* 23.5dp */ -0.01923635364730468f,
        /* 24.0dp */ -0.019492327565219923f,
        /* 24.5dp */ -0.01972691433882746f,
        /* 25.0dp */ -0.019941900907770843f,
        /* 25.5dp */ -0.02013892490923212f,
        /* 26.0dp */ -0.020319487152457818f,
        /* 26.5dp */ -0.02048496305101277f,
        /* 27.0dp */ -0.020636613099845737f,
        /* 27.5dp */ -0.02077559247697482f,
        /* 28.0dp */ -0.020902959842932358f,
        /* 28.5dp */ -0.021019685404998267f,
        /* 29.0dp */ -0.021126658307650148f,
        /* 29.5dp */ -0.0212246934055262f,
        /* 30.0dp */ -0.02131453747049323f,
        /* 30.5dp */ -0.02139687488010142f,
        /* 31.0dp */ -0.021472332830757092f,
        /* 31.5dp */ -0.0215414861153242f,
        /* 32.0dp */ -0.02160486150154747f,
    };

    private static final ComponentCallbacks callbacks = new ComponentCallbacks() {
        @Override
        public void onConfigurationChanged(Configuration newConfig) {
            sDensity = getDensity();
        }

        @Override
        public void onLowMemory() {}
    };

    private DynamicMetrics() {}

    public static float calcTracking(float sizePx) {
        if (sDensity == 0.0f) {
            Context context = ActivityThread.currentApplication();
            if (context == null) {
                return 0.0f;
            }

            sDensity = getDensity();
            context.registerComponentCallbacks(callbacks);
        }

        // Pixels -> sp
        float sizeDp = sizePx / sDensity;
        int lutIndex = (int) (sizeDp * 2); // 0.5dp steps

        // Precalculated lookup
        if (lutIndex < TRACKING_LUT.length) {
            return TRACKING_LUT[lutIndex];
        }

        return A + B * (float) Math.exp(C * sizeDp);
    }

    public static boolean shouldModifyFont(Typeface typeface) {
        return typeface == null || typeface.isSystemFont();
    }

    private static float getDensity() {
        Context context = ActivityThread.currentApplication();
        if (context == null) {
            return 1.0f;
        }

        return context.getResources().getDisplayMetrics().scaledDensity;
    }
}
