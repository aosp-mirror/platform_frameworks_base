/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server;

import static org.junit.Assert.assertThrows;

import android.test.AndroidTestCase;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Tests for {@link SystemServiceManager}.
 */
public class SystemServiceManagerTest extends AndroidTestCase {

    private static final String TAG = "SystemServiceManagerTest";

    @Test
    public void testSealStartedServices() throws Exception {
        SystemServiceManager manager = new SystemServiceManager(getContext());
        // must be effectively final, since it's changed from inner class below
        AtomicBoolean serviceStarted = new AtomicBoolean(false);
        SystemService service = new SystemService(getContext()) {
            @Override
            public void onStart() {
                serviceStarted.set(true);
            }
        };

        // started services have their #onStart methods called
        manager.startService(service);
        assertTrue(serviceStarted.get());

        // however, after locking started services, it is not possible to start a new service
        manager.sealStartedServices();
        assertThrows(UnsupportedOperationException.class, () -> manager.startService(service));
    }
}
