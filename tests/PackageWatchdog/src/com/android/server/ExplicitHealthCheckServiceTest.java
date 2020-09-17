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

package com.android.server;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.spy;

import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteCallback;
import android.service.watchdog.ExplicitHealthCheckService;
import android.service.watchdog.IExplicitHealthCheckService;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;

public class ExplicitHealthCheckServiceTest {

    private ExplicitHealthCheckService mExplicitHealthCheckService;
    private static final String PACKAGE_NAME = "com.test.package";

    @Before
    public void setup() throws Exception {
        mExplicitHealthCheckService = spy(ExplicitHealthCheckService.class);
    }

    /**
     * Test to verify that the correct information is sent in the callback when a package has
     * passed an explicit health check.
     */
    @Test
    public void testNotifyHealthCheckPassed() throws Exception {
        IBinder binder = mExplicitHealthCheckService.onBind(new Intent());
        CountDownLatch countDownLatch = new CountDownLatch(1);
        RemoteCallback callback = new RemoteCallback(result -> {
            assertThat(result.get(ExplicitHealthCheckService.EXTRA_HEALTH_CHECK_PASSED_PACKAGE))
                    .isEqualTo(PACKAGE_NAME);
            countDownLatch.countDown();
        });
        IExplicitHealthCheckService.Stub.asInterface(binder).setCallback(callback);
        mExplicitHealthCheckService.notifyHealthCheckPassed(PACKAGE_NAME);
        countDownLatch.await();
    }
}
