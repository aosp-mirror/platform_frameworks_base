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

package com.android.server.ambientcontext;

import static com.google.common.truth.Truth.assertThat;

import android.app.PendingIntent;
import android.app.ambientcontext.AmbientContextEvent;
import android.app.ambientcontext.AmbientContextEventRequest;
import android.app.ambientcontext.IAmbientContextObserver;
import android.content.Intent;
import android.os.UserHandle;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Test;

import java.util.List;

/**
 * Unit test for {@link AmbientContextManagerService}.
 * atest FrameworksServicesTests:AmbientContextManagerServiceTest
 */
public class AmbientContextManagerServiceTest {
    public static final String SYSTEM_PACKAGE_NAME = "com.android.frameworks.servicestests";
    private static final int USER_ID = UserHandle.USER_SYSTEM;

    @SmallTest
    @Test
    public void testClientRequest() {
        AmbientContextEventRequest request = new AmbientContextEventRequest.Builder()
                .addEventType(AmbientContextEvent.EVENT_COUGH)
                .build();
        Intent intent = new Intent();
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                InstrumentationRegistry.getTargetContext(), 0,
                intent, PendingIntent.FLAG_IMMUTABLE);
        IAmbientContextObserver observer = new IAmbientContextObserver.Stub() {
            @Override
            public void onEvents(List<AmbientContextEvent> events) {
            }

            @Override
            public void onRegistrationComplete(int statusCode) {
            }
        };
        AmbientContextManagerService.ClientRequest clientRequest =
                new AmbientContextManagerService.ClientRequest(USER_ID, request,
                        pendingIntent.getCreatorPackage(), observer);

        assertThat(clientRequest.getRequest()).isEqualTo(request);
        assertThat(clientRequest.getPackageName()).isEqualTo(SYSTEM_PACKAGE_NAME);
        assertThat(clientRequest.getObserver()).isEqualTo(observer);
        assertThat(clientRequest.hasUserId(USER_ID)).isTrue();
        assertThat(clientRequest.hasUserId(-1)).isFalse();
        assertThat(clientRequest.hasUserIdAndPackageName(USER_ID, SYSTEM_PACKAGE_NAME)).isTrue();
        assertThat(clientRequest.hasUserIdAndPackageName(-1, SYSTEM_PACKAGE_NAME)).isFalse();
        assertThat(clientRequest.hasUserIdAndPackageName(USER_ID, "random.package.name"))
                .isFalse();
    }
}
