/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.pip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.ComponentName;
import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.Size;

import androidx.test.filters.SmallTest;

import com.android.internal.util.function.TriConsumer;
import com.android.wm.shell.ShellTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.Consumer;

/**
 * Tests for {@link PipBoundsState}.
 */
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class PipBoundsStateTest extends ShellTestCase {

    private static final Size DEFAULT_SIZE = new Size(10, 10);
    private static final float DEFAULT_SNAP_FRACTION = 1.0f;

    private PipBoundsState mPipBoundsState;
    private ComponentName mTestComponentName1;
    private ComponentName mTestComponentName2;

    @Before
    public void setUp() {
        mPipBoundsState = new PipBoundsState(mContext);
        mTestComponentName1 = new ComponentName(mContext, "component1");
        mTestComponentName2 = new ComponentName(mContext, "component2");
    }

    @Test
    public void testSetBounds() {
        final Rect bounds = new Rect(0, 0, 100, 100);
        mPipBoundsState.setBounds(bounds);

        assertEquals(bounds, mPipBoundsState.getBounds());
    }

    @Test
    public void testSetReentryState() {
        final Size size = new Size(100, 100);
        final float snapFraction = 0.5f;

        mPipBoundsState.saveReentryState(size, snapFraction);

        final PipBoundsState.PipReentryState state = mPipBoundsState.getReentryState();
        assertEquals(size, state.getSize());
        assertEquals(snapFraction, state.getSnapFraction(), 0.01);
    }

    @Test
    public void testClearReentryState() {
        final Size size = new Size(100, 100);
        final float snapFraction = 0.5f;

        mPipBoundsState.saveReentryState(size, snapFraction);
        mPipBoundsState.clearReentryState();

        assertNull(mPipBoundsState.getReentryState());
    }

    @Test
    public void testSetLastPipComponentName_notChanged_doesNotClearReentryState() {
        mPipBoundsState.setLastPipComponentName(mTestComponentName1);
        mPipBoundsState.saveReentryState(DEFAULT_SIZE, DEFAULT_SNAP_FRACTION);

        mPipBoundsState.setLastPipComponentName(mTestComponentName1);

        final PipBoundsState.PipReentryState state = mPipBoundsState.getReentryState();
        assertNotNull(state);
        assertEquals(DEFAULT_SIZE, state.getSize());
        assertEquals(DEFAULT_SNAP_FRACTION, state.getSnapFraction(), 0.01);
    }

    @Test
    public void testSetLastPipComponentName_changed_clearReentryState() {
        mPipBoundsState.setLastPipComponentName(mTestComponentName1);
        mPipBoundsState.saveReentryState(DEFAULT_SIZE, DEFAULT_SNAP_FRACTION);

        mPipBoundsState.setLastPipComponentName(mTestComponentName2);

        assertNull(mPipBoundsState.getReentryState());
    }

    @Test
    public void testSetShelfVisibility_changed_callbackInvoked() {
        final TriConsumer<Boolean, Integer, Boolean> callback = mock(TriConsumer.class);
        mPipBoundsState.setOnShelfVisibilityChangeCallback(callback);

        mPipBoundsState.setShelfVisibility(true, 100);

        verify(callback).accept(true, 100, true);
    }

    @Test
    public void testSetShelfVisibility_changedWithoutUpdateMovBounds_callbackInvoked() {
        final TriConsumer<Boolean, Integer, Boolean> callback = mock(TriConsumer.class);
        mPipBoundsState.setOnShelfVisibilityChangeCallback(callback);

        mPipBoundsState.setShelfVisibility(true, 100, false);

        verify(callback).accept(true, 100, false);
    }

    @Test
    public void testSetShelfVisibility_notChanged_callbackNotInvoked() {
        final TriConsumer<Boolean, Integer, Boolean> callback = mock(TriConsumer.class);
        mPipBoundsState.setShelfVisibility(true, 100);
        mPipBoundsState.setOnShelfVisibilityChangeCallback(callback);

        mPipBoundsState.setShelfVisibility(true, 100);

        verify(callback, never()).accept(true, 100, true);
    }

    @Test
    public void testSetOverrideMinSize_changed_callbackInvoked() {
        final Runnable callback = mock(Runnable.class);
        mPipBoundsState.setOverrideMinSize(new Size(5, 5));
        mPipBoundsState.setOnMinimalSizeChangeCallback(callback);

        mPipBoundsState.setOverrideMinSize(new Size(10, 10));

        verify(callback).run();
    }

    @Test
    public void testSetOverrideMinSize_notChanged_callbackNotInvoked() {
        final Runnable callback = mock(Runnable.class);
        mPipBoundsState.setOverrideMinSize(new Size(5, 5));
        mPipBoundsState.setOnMinimalSizeChangeCallback(callback);

        mPipBoundsState.setOverrideMinSize(new Size(5, 5));

        verify(callback, never()).run();
    }

    @Test
    public void testGetOverrideMinEdgeSize() {
        mPipBoundsState.setOverrideMinSize(null);
        assertEquals(0, mPipBoundsState.getOverrideMinEdgeSize());

        mPipBoundsState.setOverrideMinSize(new Size(5, 10));
        assertEquals(5, mPipBoundsState.getOverrideMinEdgeSize());

        mPipBoundsState.setOverrideMinSize(new Size(15, 10));
        assertEquals(10, mPipBoundsState.getOverrideMinEdgeSize());
    }

    @Test
    public void testSetBounds_updatesPipExclusionBounds() {
        final Consumer<Rect> callback = mock(Consumer.class);
        final Rect currentBounds = new Rect(10, 10, 20, 15);
        final Rect newBounds = new Rect(50, 50, 100, 75);
        mPipBoundsState.setBounds(currentBounds);

        mPipBoundsState.setPipExclusionBoundsChangeCallback(callback);
        // Setting the listener immediately calls back with the current bounds.
        verify(callback).accept(currentBounds);

        mPipBoundsState.setBounds(newBounds);
        // Updating the bounds makes the listener call back back with the new rect.
        verify(callback).accept(newBounds);
    }
}
