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

package android.view.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.graphics.PointF;
import android.os.CancellationSignal;
import android.os.CancellationSignalBeamer;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.compatibility.common.util.ApiTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
@ApiTest(apis = {"android.view.inputmethod.InsertModeGesture.Builder#setInsertionPoint",
    "android.view.inputmethod.InsertModeGesture.Builder#setCancellationSignal",
    "android.view.inputmethod.InsertModeGesture.Builder#setFallbackText",
    "android.view.inputmethod.InsertModeGesture.Builder#build"})
public class InsertModeGestureTest {
    private static final PointF INSERTION_POINT = new PointF(1, 2);
    private static final String FALLBACK_TEXT = "fallback_text";
    private static final CancellationSignal CANCELLATION_SIGNAL = new CancellationSignal();

    @Test
    public void testBuilder() {
        InsertModeGesture.Builder builder = new InsertModeGesture.Builder();
        InsertModeGesture gesture = builder.setInsertionPoint(INSERTION_POINT)
                .setCancellationSignal(CANCELLATION_SIGNAL)
                .setFallbackText(FALLBACK_TEXT).build();
        assertNotNull(gesture);
        assertEquals(INSERTION_POINT, gesture.getInsertionPoint());
        assertEquals(FALLBACK_TEXT, gesture.getFallbackText());
        assertEquals(CANCELLATION_SIGNAL, gesture.getCancellationSignal());
    }

    @Test
    public void testCancellationSignal() {
        var cs = CANCELLATION_SIGNAL;
        var gesture = new InsertModeGesture.Builder().setInsertionPoint(INSERTION_POINT)
                .setCancellationSignal(CANCELLATION_SIGNAL)
                .setFallbackText(FALLBACK_TEXT).build();
        gesture.unbeamCancellationSignal(
                new CancellationSignalBeamer.Receiver(true /* cancelOnSenderDeath */));
        assertEquals(gesture.getCancellationSignal(), cs);
    }
}
