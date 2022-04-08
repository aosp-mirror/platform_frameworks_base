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

import static com.android.systemui.classifier.Classifier.QUICK_SETTINGS;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import android.testing.AndroidTestingRunner;
import android.view.MotionEvent;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class PointerCountClassifierTest extends ClassifierTest {

    private FalsingClassifier mClassifier;

    @Before
    public void setup() {
        super.setup();
        mClassifier = new PointerCountClassifier(getDataProvider());
    }

    @After
    public void tearDown() {
        super.tearDown();
    }

    @Test
    public void testPass_noPointer() {
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    @Test
    public void testPass_singlePointer() {
        mClassifier.onTouchEvent(appendDownEvent(1, 1));
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    @Test
    public void testFail_multiPointer() {
        MotionEvent.PointerProperties[] pointerProperties =
                MotionEvent.PointerProperties.createArray(2);
        pointerProperties[0].id = 0;
        pointerProperties[1].id = 1;
        MotionEvent.PointerCoords[] pointerCoords = MotionEvent.PointerCoords.createArray(2);
        MotionEvent motionEvent = MotionEvent.obtain(
                1, 1, MotionEvent.ACTION_DOWN, 2, pointerProperties, pointerCoords, 0, 0, 0, 0, 0,
                0,
                0, 0);
        mClassifier.onTouchEvent(motionEvent);
        motionEvent.recycle();
        assertThat(mClassifier.isFalseTouch(), is(true));
    }

    @Test
    public void testPass_multiPointerDragDown() {
        MotionEvent.PointerProperties[] pointerProperties =
                MotionEvent.PointerProperties.createArray(2);
        pointerProperties[0].id = 0;
        pointerProperties[1].id = 1;
        MotionEvent.PointerCoords[] pointerCoords = MotionEvent.PointerCoords.createArray(2);
        MotionEvent motionEvent = MotionEvent.obtain(
                1, 1, MotionEvent.ACTION_DOWN, 2, pointerProperties, pointerCoords, 0, 0, 0, 0, 0,
                0,
                0, 0);
        mClassifier.onTouchEvent(motionEvent);
        motionEvent.recycle();
        getDataProvider().setInteractionType(QUICK_SETTINGS);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }
}
