/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.server.input;

import static android.view.InputDevice.SOURCE_MOUSE;
import static android.view.InputDevice.SOURCE_ROTARY_ENCODER;
import static android.view.MotionEvent.ACTION_SCROLL;
import static android.view.MotionEvent.AXIS_HSCROLL;
import static android.view.MotionEvent.AXIS_SCROLL;
import static android.view.MotionEvent.AXIS_VSCROLL;
import static android.view.MotionEvent.AXIS_X;
import static android.view.MotionEvent.AXIS_Y;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.os.Binder;
import android.view.InputEvent;
import android.view.MotionEvent;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Build/Install/Run:
 * atest InputShellCommandTest
 */
@RunWith(AndroidJUnit4.class)
public class InputShellCommandTest {
    private TestInputEventInjector mInputEventInjector = new TestInputEventInjector();

    private InputShellCommand mCommand;

    @Before
    public void setUp() throws Exception {
        mCommand = new InputShellCommand(mInputEventInjector);
    }

    @Test
    public void testScroll_withPointerSource_noAxisOption() {
        runCommand("mouse scroll 2 -3");

        MotionEvent event = (MotionEvent) getSingleInjectedInputEvent();

        assertSourceAndAction(event, SOURCE_MOUSE, ACTION_SCROLL);
        assertAxisValues(event, Map.of(AXIS_X, 2f, AXIS_Y, -3f));
    }

    @Test
    public void testScroll_withPointerSource_withScrollAxisOptions() {
        runCommand("mouse scroll 1 -2 --axis HSCROLL,3 --axis VSCROLL,1.7 --axis SCROLL,-4");

        MotionEvent event = (MotionEvent) getSingleInjectedInputEvent();

        assertSourceAndAction(event, SOURCE_MOUSE, ACTION_SCROLL);
        assertAxisValues(
                event,
                Map.of(
                        AXIS_X, 1f,
                        AXIS_Y, -2f,
                        AXIS_HSCROLL, 3f,
                        AXIS_VSCROLL, 1.7f,
                        AXIS_SCROLL, -4f));
    }

    @Test
    public void testScroll_withNonPointerSource_noAxisOption() {
        runCommand("rotaryencoder scroll");

        MotionEvent event = (MotionEvent) getSingleInjectedInputEvent();

        assertSourceAndAction(event, SOURCE_ROTARY_ENCODER, ACTION_SCROLL);
    }

    @Test
    public void testScroll_withNonPointerSource_withScrollAxisOptions() {
        runCommand("rotaryencoder scroll --axis HSCROLL,3 --axis VSCROLL,1.7 --axis SCROLL,-4");

        MotionEvent event = (MotionEvent) getSingleInjectedInputEvent();

        assertSourceAndAction(event, SOURCE_ROTARY_ENCODER, ACTION_SCROLL);
        assertAxisValues(event, Map.of(AXIS_HSCROLL, 3f, AXIS_VSCROLL, 1.7f, AXIS_SCROLL, -4f));
    }

    @Test
    public void testDefaultScrollSource() {
        runCommand("scroll --axis SCROLL,-4");

        MotionEvent event = (MotionEvent) getSingleInjectedInputEvent();

        assertSourceAndAction(event, SOURCE_ROTARY_ENCODER, ACTION_SCROLL);
        assertAxisValues(event, Map.of(AXIS_SCROLL, -4f));
    }

    @Test
    public void testInvalidScrollCommands() {
        runCommand("scroll --sdaxis SCROLL,-4"); // invalid option
        runCommand("scroll --axis MYAXIS,-4"); // invalid axis
        runCommand("scroll --AXIS SCROLL,-4"); // invalid axis option key
        runCommand("scroll --axis SCROLL,-4abc"); // invalid axis value

        assertThat(mInputEventInjector.mInjectedEvents).isEmpty();
    }

    private InputEvent getSingleInjectedInputEvent() {
        assertThat(mInputEventInjector.mInjectedEvents).hasSize(1);
        return mInputEventInjector.mInjectedEvents.get(0);
    }

    private void assertSourceAndAction(MotionEvent event, int source, int action) {
        assertThat(event.getSource()).isEqualTo(source);
        assertThat(event.getAction()).isEqualTo(action);
    }

    private void assertAxisValues(MotionEvent event, Map<Integer, Float> expectedValues) {
        for (var entry : expectedValues.entrySet()) {
            final int axis = entry.getKey();
            final float expectedValue = entry.getValue();
            final float axisValue = event.getAxisValue(axis);
            assertWithMessage(
                    String.format(
                            "Expected [%f], found [%f] for axis %s",
                            expectedValue,
                            axisValue,
                            MotionEvent.axisToString(axis)))
                    .that(axisValue).isEqualTo(expectedValue);
        }
    }

    private void runCommand(String cmd) {
        mCommand.exec(
                new Binder(), new FileDescriptor(), new FileDescriptor(), new FileDescriptor(),
                cmd.split(" ") /* args */);
    }

    private static class TestInputEventInjector implements BiConsumer<InputEvent, Integer> {
        List<InputEvent> mInjectedEvents = new ArrayList<>();

        @Override
        public void accept(InputEvent event, Integer injectMode) {
            mInjectedEvents.add(event);
        }
    }
}
