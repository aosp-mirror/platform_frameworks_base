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

package com.android.server.wm;

import static com.android.server.wm.ActivityInterceptorCallback.MAINLINE_FIRST_ORDERED_ID;
import static com.android.server.wm.ActivityInterceptorCallback.MAINLINE_LAST_ORDERED_ID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.spy;

import android.os.Process;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.MediumTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

/**
 * Tests for the {@link ActivityInterceptorCallbackRegistry} class.
 */
@Presubmit
@MediumTest
@RunWith(WindowTestRunner.class)
public final class ActivityInterceptorCallbackRegistryTest extends WindowTestsBase {

    private ActivityInterceptorCallbackRegistry mRegistry;

    @Before
    public void setUp() {
        mRegistry = spy(ActivityInterceptorCallbackRegistry.getInstance());
        Mockito.doReturn(Process.SYSTEM_UID).when(mRegistry).getCallingUid();
    }

    @Test
    public void registerActivityInterceptorCallbackFailIfNotSystemId() {
        // default registry with test app uid
        ActivityInterceptorCallbackRegistry registry = spy(
                ActivityInterceptorCallbackRegistry.getInstance());
        assertThrows(
                SecurityException.class,
                () ->  registry.registerActivityInterceptorCallback(MAINLINE_LAST_ORDERED_ID + 1,
                        info -> null)
        );
    }

    @Test
    public void registerActivityInterceptorCallbackFailIfIdNotInRange() {
        assertThrows(
                IllegalArgumentException.class,
                () ->  mRegistry.registerActivityInterceptorCallback(MAINLINE_LAST_ORDERED_ID + 1,
                        info -> null)
        );

        assertThrows(
                IllegalArgumentException.class,
                () ->  mRegistry.registerActivityInterceptorCallback(MAINLINE_FIRST_ORDERED_ID - 1,
                        info -> null)
        );
    }

    @Test
    public void registerActivityInterceptorCallbackFailIfCallbackIsNull() {
        assertThrows(
                IllegalArgumentException.class,
                () ->  mRegistry.registerActivityInterceptorCallback(MAINLINE_FIRST_ORDERED_ID,
                        null)
        );
    }

    @Test
    public void registerActivityInterceptorCallbackSuccessfully() {
        int size = mAtm.getActivityInterceptorCallbacks().size();
        int orderId = MAINLINE_FIRST_ORDERED_ID;
        mRegistry.registerActivityInterceptorCallback(orderId,
                info -> null);
        assertEquals(size + 1, mAtm.getActivityInterceptorCallbacks().size());
        assertTrue(mAtm.getActivityInterceptorCallbacks().contains(orderId));
    }

    @Test
    public void unregisterActivityInterceptorCallbackFailIfNotSystemId() {
        // default registry with test app uid
        ActivityInterceptorCallbackRegistry registry = spy(
                ActivityInterceptorCallbackRegistry.getInstance());
        assertThrows(
                SecurityException.class,
                () ->  registry.unregisterActivityInterceptorCallback(MAINLINE_LAST_ORDERED_ID + 1)
        );
    }

    @Test
    public void unRegisterActivityInterceptorCallbackFailIfIdNotInRange() {
        assertThrows(
                IllegalArgumentException.class,
                () ->  mRegistry.unregisterActivityInterceptorCallback(
                        MAINLINE_LAST_ORDERED_ID + 1));
    }

    @Test
    public void unregisterActivityInterceptorCallbackFailIfNotRegistered() {
        assertThrows(
                IllegalArgumentException.class,
                () ->  mRegistry.unregisterActivityInterceptorCallback(MAINLINE_FIRST_ORDERED_ID)
        );
    }

    @Test
    public void unregisterActivityInterceptorCallbackSuccessfully() {
        int size = mAtm.getActivityInterceptorCallbacks().size();
        int orderId = MAINLINE_FIRST_ORDERED_ID;
        mRegistry.registerActivityInterceptorCallback(orderId,
                info -> null);
        assertEquals(size + 1, mAtm.getActivityInterceptorCallbacks().size());
        assertTrue(mAtm.getActivityInterceptorCallbacks().contains(orderId));

        mRegistry.unregisterActivityInterceptorCallback(orderId);
        assertEquals(size, mAtm.getActivityInterceptorCallbacks().size());
        assertFalse(mAtm.getActivityInterceptorCallbacks().contains(orderId));

    }
}
