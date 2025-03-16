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

package android.app.jank.tests;

import android.app.jank.AppJankStats;
import android.app.jank.FrameOverrunHistogram;

public class JankUtils {
    private static final int APP_ID = 25;

    /**
     * Returns a mock AppJankStats object to be used in tests.
     */
    public static AppJankStats getAppJankStats() {
        AppJankStats jankStats = new AppJankStats(
                /*App Uid*/APP_ID,
                /*Widget Id*/"test widget id",
                /*Widget Category*/AppJankStats.SCROLL,
                /*Widget State*/AppJankStats.SCROLLING,
                /*Total Frames*/100,
                /*Janky Frames*/25,
                getOverrunHistogram()
        );
        return jankStats;
    }

    /**
     * Returns a mock histogram to be used with an AppJankStats object.
     */
    public static FrameOverrunHistogram getOverrunHistogram() {
        FrameOverrunHistogram overrunHistogram = new FrameOverrunHistogram();
        overrunHistogram.addFrameOverrunMillis(-2);
        overrunHistogram.addFrameOverrunMillis(1);
        overrunHistogram.addFrameOverrunMillis(5);
        overrunHistogram.addFrameOverrunMillis(25);
        return overrunHistogram;
    }
}
