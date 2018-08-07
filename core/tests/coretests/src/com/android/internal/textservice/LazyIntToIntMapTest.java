/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.textservice;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntUnaryOperator;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class LazyIntToIntMapTest {
    @Test
    public void testLaziness() {
        final IntUnaryOperator func = mock(IntUnaryOperator.class);
        when(func.applyAsInt(eq(1))).thenReturn(11);
        when(func.applyAsInt(eq(2))).thenReturn(22);

        final LazyIntToIntMap map = new LazyIntToIntMap(func);

        verify(func, never()).applyAsInt(anyInt());

        assertEquals(22, map.get(2));
        verify(func, times(0)).applyAsInt(eq(1));
        verify(func, times(1)).applyAsInt(eq(2));

        // Accessing to the same key does not evaluate the function again.
        assertEquals(22, map.get(2));
        verify(func, times(0)).applyAsInt(eq(1));
        verify(func, times(1)).applyAsInt(eq(2));
    }

    @Test
    public void testDelete() {
        final IntUnaryOperator func1 = mock(IntUnaryOperator.class);
        when(func1.applyAsInt(eq(1))).thenReturn(11);
        when(func1.applyAsInt(eq(2))).thenReturn(22);

        final IntUnaryOperator func2 = mock(IntUnaryOperator.class);
        when(func2.applyAsInt(eq(1))).thenReturn(111);
        when(func2.applyAsInt(eq(2))).thenReturn(222);

        final AtomicReference<IntUnaryOperator> funcRef = new AtomicReference<>(func1);
        final LazyIntToIntMap map = new LazyIntToIntMap(i -> funcRef.get().applyAsInt(i));

        verify(func1, never()).applyAsInt(anyInt());
        verify(func2, never()).applyAsInt(anyInt());

        assertEquals(22, map.get(2));
        verify(func1, times(1)).applyAsInt(eq(2));
        verify(func2, times(0)).applyAsInt(eq(2));

        // Swap func1 with func2 then invalidate the key=2
        funcRef.set(func2);
        map.delete(2);

        // Calling get(2) again should re-evaluate the value.
        assertEquals(222, map.get(2));
        verify(func1, times(1)).applyAsInt(eq(2));
        verify(func2, times(1)).applyAsInt(eq(2));

        // Trying to delete non-existing keys does nothing.
        map.delete(1);
    }
}
