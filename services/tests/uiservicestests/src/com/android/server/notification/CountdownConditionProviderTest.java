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

package com.android.server.notification;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import android.app.Application;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.server.UiServiceTestCase;
import com.android.server.pm.PackageManagerService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@SmallTest
@RunWithLooper
public class CountdownConditionProviderTest extends UiServiceTestCase {

    CountdownConditionProvider mService;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        Intent startIntent =
                new Intent("com.android.server.notification.CountdownConditionProvider");
        startIntent.setPackage("android");
        CountdownConditionProvider service = new CountdownConditionProvider();
        service.attach(
                getContext(),
                null,               // ActivityThread not actually used in Service
                CountdownConditionProvider.class.getName(),
                null,               // token not needed when not talking with the activity manager
                mock(Application.class),
                null                // mocked services don't talk with the activity manager
                );
        service.onCreate();
        service.onBind(startIntent);
        mService = spy(service);
   }

    @Test
    public void getComponent_returnsComponent() {
        assertThat(mService.getComponent()).isEqualTo(new ComponentName("android",
                "com.android.server.notification.CountdownConditionProvider"));
    }

    @Test
    public void testGetPendingIntent() {
        PendingIntent pi = mService.getPendingIntent(Uri.EMPTY);
        assertEquals(PackageManagerService.PLATFORM_PACKAGE_NAME, pi.getIntent().getPackage());
    }
}
