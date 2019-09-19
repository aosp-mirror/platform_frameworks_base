/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.classifier.brightline;

import static com.android.systemui.classifier.Classifier.GENERIC;
import static com.android.systemui.classifier.Classifier.QUICK_SETTINGS;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.MotionEvent;

import androidx.test.filters.SmallTest;

import com.android.systemui.util.DeviceConfigProxyFake;
import com.android.systemui.util.sensors.ProximitySensor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ProximityClassifierTest extends ClassifierTest {

    private static final long NS_PER_MS = 1000000;

    @Mock
    private FalsingDataProvider mDataProvider;
    @Mock
    private DistanceClassifier mDistanceClassifier;
    private FalsingClassifier mClassifier;

    @Before
    public void setup() {
        super.setup();
        MockitoAnnotations.initMocks(this);
        when(mDataProvider.getInteractionType()).thenReturn(GENERIC);
        when(mDistanceClassifier.isLongSwipe()).thenReturn(false);
        mClassifier = new ProximityClassifier(
                mDistanceClassifier, mDataProvider, new DeviceConfigProxyFake());
    }

    @After
    public void tearDown() {
        super.tearDown();
    }

    @Test
    public void testPass_uncovered() {
        touchDown();
        touchUp(10);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    @Test
    public void testPass_mostlyUncovered() {
        touchDown();
        mClassifier.onProximityEvent(createSensorEvent(true, 1));
        mClassifier.onProximityEvent(createSensorEvent(false, 2));
        touchUp(20);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    @Test
    public void testPass_quickSettings() {
        touchDown();
        when(mDataProvider.getInteractionType()).thenReturn(QUICK_SETTINGS);
        mClassifier.onProximityEvent(createSensorEvent(true, 1));
        mClassifier.onProximityEvent(createSensorEvent(false, 11));
        touchUp(10);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    @Test
    public void testFail_covered() {
        touchDown();
        mClassifier.onProximityEvent(createSensorEvent(true, 1));
        mClassifier.onProximityEvent(createSensorEvent(false, 11));
        touchUp(10);
        assertThat(mClassifier.isFalseTouch(), is(true));
    }

    @Test
    public void testFail_mostlyCovered() {
        touchDown();
        mClassifier.onProximityEvent(createSensorEvent(true, 1));
        mClassifier.onProximityEvent(createSensorEvent(true, 95));
        mClassifier.onProximityEvent(createSensorEvent(true, 96));
        mClassifier.onProximityEvent(createSensorEvent(false, 100));
        touchUp(100);
        assertThat(mClassifier.isFalseTouch(), is(true));
    }

    @Test
    public void testPass_coveredWithLongSwipe() {
        touchDown();
        mClassifier.onProximityEvent(createSensorEvent(true, 1));
        mClassifier.onProximityEvent(createSensorEvent(false, 11));
        touchUp(10);
        when(mDistanceClassifier.isLongSwipe()).thenReturn(true);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    private void touchDown() {
        MotionEvent motionEvent = MotionEvent.obtain(1, 1, MotionEvent.ACTION_DOWN, 0, 0, 0);
        mClassifier.onTouchEvent(motionEvent);
        motionEvent.recycle();
    }

    private void touchUp(long duration) {
        MotionEvent motionEvent = MotionEvent.obtain(1, 1 + duration, MotionEvent.ACTION_UP, 0,
                100, 0);

        mClassifier.onTouchEvent(motionEvent);

        motionEvent.recycle();
    }

    private ProximitySensor.ProximityEvent createSensorEvent(boolean covered, long timestampMs) {
        return new ProximitySensor.ProximityEvent(covered, timestampMs * NS_PER_MS);
    }
}
