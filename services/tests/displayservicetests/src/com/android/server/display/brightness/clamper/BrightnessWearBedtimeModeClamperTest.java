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

package com.android.server.display.brightness.clamper;

import static com.android.server.display.brightness.clamper.BrightnessWearBedtimeModeClamper.BEDTIME_MODE_OFF;
import static com.android.server.display.brightness.clamper.BrightnessWearBedtimeModeClamper.BEDTIME_MODE_ON;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.provider.Settings;
import android.testing.TestableContext;

import androidx.annotation.NonNull;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.display.BrightnessSynchronizer;
import com.android.server.testutils.TestHandler;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class BrightnessWearBedtimeModeClamperTest {

    private static final float BRIGHTNESS_CAP = 0.3f;

    @Mock
    private BrightnessClamperController.ClamperChangeListener mMockClamperChangeListener;

    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getContext());

    private final TestHandler mTestHandler = new TestHandler(null);
    private final TestInjector mInjector = new TestInjector();
    private BrightnessWearBedtimeModeClamper mClamper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mClamper = new BrightnessWearBedtimeModeClamper(mInjector, mTestHandler, mContext,
                mMockClamperChangeListener, () -> BRIGHTNESS_CAP);
        mTestHandler.flush();
    }

    @Test
    public void testBrightnessCap() {
        assertEquals(BRIGHTNESS_CAP, mClamper.getBrightnessCap(), BrightnessSynchronizer.EPSILON);
    }

    @Test
    public void testBedtimeModeOn() {
        setBedtimeModeEnabled(true);
        assertTrue(mClamper.isActive());
        verify(mMockClamperChangeListener).onChanged();
    }

    @Test
    public void testBedtimeModeOff() {
        setBedtimeModeEnabled(false);
        assertFalse(mClamper.isActive());
        verify(mMockClamperChangeListener).onChanged();
    }

    @Test
    public void testType() {
        assertEquals(BrightnessClamper.Type.BEDTIME_MODE, mClamper.getType());
    }

    @Test
    public void testOnDisplayChanged() {
        float newBrightnessCap = 0.61f;

        mClamper.onDisplayChanged(() -> newBrightnessCap);
        mTestHandler.flush();

        assertEquals(newBrightnessCap, mClamper.getBrightnessCap(), BrightnessSynchronizer.EPSILON);
        verify(mMockClamperChangeListener).onChanged();
    }

    private void setBedtimeModeEnabled(boolean enabled) {
        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.Wearable.BEDTIME_MODE,
                enabled ? BEDTIME_MODE_ON : BEDTIME_MODE_OFF);
        mInjector.notifyBedtimeModeChanged();
        mTestHandler.flush();
    }

    private static class TestInjector extends BrightnessWearBedtimeModeClamper.Injector {

        private ContentObserver mObserver;

        @Override
        void registerBedtimeModeObserver(@NonNull ContentResolver cr,
                @NonNull ContentObserver observer) {
            mObserver = observer;
        }

        private void notifyBedtimeModeChanged() {
            if (mObserver != null) {
                mObserver.dispatchChange(/* selfChange= */ false,
                        Settings.Global.getUriFor(Settings.Global.Wearable.BEDTIME_MODE));
            }
        }
    }
}
