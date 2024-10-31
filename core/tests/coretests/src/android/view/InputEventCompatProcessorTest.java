/*
 * Copyright 2024 The Android Open Source Project
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


package android.view;

import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_UP;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.window.flags.Flags;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link InputEventCompatProcessor}
 *
 * Build/Install/Run:
 *  atest FrameworksCoreTests:InputEventCompatProcessorTest
 */
@SmallTest
@Presubmit
public class InputEventCompatProcessorTest {

    private InputEventCompatProcessor mInputEventCompatProcessor;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        assumeTrue("Is at least targeting Android M",
                context.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.M);

        // Set app bounds as if it was letterboxed.
        context.getResources().getConfiguration().windowConfiguration
                .setBounds(new Rect(200, 200, 600, 1000));

        Handler handler = new Handler(Looper.getMainLooper());

        mInputEventCompatProcessor = new InputEventCompatProcessor(context, handler);
    }

    @DisableFlags(Flags.FLAG_SCROLLING_FROM_LETTERBOX)
    @Test
    public void testTapGestureOutsideBoundsHasNoAdjustmentsWhenScrollingFromLetterboxDisabled() {
        // Tap-like gesture in bounds (non-scroll).
        List<MotionEvent> tapGestureEvents = createTapGestureEvents(-100f, -100f);

        for (MotionEvent motionEvent : tapGestureEvents) {
            List<InputEvent> compatProcessedEvents =
                    mInputEventCompatProcessor.processInputEventForCompatibility(motionEvent);
            // Expect null to be returned, because no adjustments should be made to these events
            // when Letterbox Scroll Processor is disabled.
            assertNull(compatProcessedEvents);
        }
    }

    @EnableFlags(Flags.FLAG_SCROLLING_FROM_LETTERBOX)
    @Test
    public void testTapGestureOutsideBoundsIsIgnoredWhenScrollingFromLetterboxEnabled() {
        // Tap-like gesture in bounds (non-scroll).
        List<MotionEvent> tapGestureEvents = createTapGestureEvents(-100f, -100f);

        for (MotionEvent motionEvent : tapGestureEvents) {
            List<InputEvent> compatProcessedEvents =
                    mInputEventCompatProcessor.processInputEventForCompatibility(motionEvent);
            // Expect no events returned because Letterbox Scroll Processor is enabled and therefore
            // should cause the out of bound events to be ignored.
            assertTrue(compatProcessedEvents.isEmpty());
        }
    }

    private List<MotionEvent> createTapGestureEvents(float startX, float startY) {
        // Events for tap-like gesture (non-scroll)
        List<MotionEvent> motionEvents = new ArrayList<>();
        motionEvents.add(createBasicMotionEvent(0, ACTION_DOWN, startX, startY));
        motionEvents.add(createBasicMotionEvent(10, ACTION_UP, startX , startY));
        return motionEvents;
    }

    private MotionEvent createBasicMotionEvent(int downTime, int action, float x, float y) {
        return MotionEvent.obtain(0, downTime, action, x, y, 0);
    }
}
