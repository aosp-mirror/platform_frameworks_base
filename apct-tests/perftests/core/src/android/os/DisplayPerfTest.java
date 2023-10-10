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

package android.os;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.provider.Settings;
import android.view.Display;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.PollingCheck;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class DisplayPerfTest {
    private static final float DELTA = 0.001f;

    @Rule
    public final PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private DisplayManager mDisplayManager;
    private Context mContext;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
    }

    @Test
    public void testBrightnessChanges() throws Exception {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        SystemClock.sleep(20);
        float brightness = 0.3f;
        while (state.keepRunning()) {
            setAndWaitToChangeBrightness(brightness);
            brightness = toggleBrightness(brightness);
        }
    }

    private float toggleBrightness(float oldBrightness) {
        float[] brightnesses = new float[]{0.3f, 0.5f};
        if (oldBrightness == brightnesses[0]) {
            return brightnesses[1];
        }
        return brightnesses[0];
    }

    private void setAndWaitToChangeBrightness(float brightness) throws Exception {
        mDisplayManager.setBrightness(0, brightness);
        PollingCheck.check("Brightness is not set to the expected value", 500,
                () -> isInRange(mDisplayManager.getBrightness(Display.DEFAULT_DISPLAY), brightness,
                        DELTA));
    }

    private boolean isInRange(float value, float target, float delta) {
        return target - delta <= value && target + delta >= value;
    }
}
