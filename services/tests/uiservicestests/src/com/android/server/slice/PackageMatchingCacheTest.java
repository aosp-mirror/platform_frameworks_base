/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.server.slice;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import com.android.server.UiServiceTestCase;
import com.android.server.slice.SliceManagerService.PackageMatchingCache;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.Supplier;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class PackageMatchingCacheTest extends UiServiceTestCase {

    private final Supplier<String> supplier = mock(Supplier.class);
    private final PackageMatchingCache cache = new PackageMatchingCache(supplier);

    @Test
    public void testNulls() {
        // Doesn't get for a null input
        cache.matches(null);
        verify(supplier, never()).get();

        // Gets once valid input in sent.
        cache.matches("");
        verify(supplier).get();
    }

    @Test
    public void testCaching() {
        when(supplier.get()).thenReturn("ret.pkg");

        assertTrue(cache.matches("ret.pkg"));
        assertTrue(cache.matches("ret.pkg"));
        assertTrue(cache.matches("ret.pkg"));

        verify(supplier, times(1)).get();
    }

    @Test
    public void testGetOnFailure() {
        when(supplier.get()).thenReturn("ret.pkg");
        assertTrue(cache.matches("ret.pkg"));

        when(supplier.get()).thenReturn("other.pkg");
        assertTrue(cache.matches("other.pkg"));
        verify(supplier, times(2)).get();
    }
}
