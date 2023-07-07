/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.soundtrigger;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.soundtrigger.SoundTriggerEvent.ServiceEvent;
import com.android.server.soundtrigger.SoundTriggerEvent.SessionEvent;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public final class SoundTriggerEventTest {
    private static final ServiceEvent.Type serviceEventType = ServiceEvent.Type.ATTACH;
    private static final SessionEvent.Type sessionEventType = SessionEvent.Type.DETACH;

    @Test
    public void serviceEventNoPackageNoError_getStringContainsType() {
        final var event = new ServiceEvent(serviceEventType);
        final var stringRep = event.eventToString();
        assertThat(stringRep).contains(serviceEventType.name());
        assertThat(stringRep).ignoringCase().doesNotContain("error");
    }

    @Test
    public void serviceEventPackageNoError_getStringContainsTypeAndPackage() {
        final var packageName = "com.android.package.name";
        final var event = new ServiceEvent(serviceEventType, packageName);
        final var stringRep = event.eventToString();
        assertThat(stringRep).contains(serviceEventType.name());
        assertThat(stringRep).contains(packageName);
        assertThat(stringRep).ignoringCase().doesNotContain("error");
    }

    @Test
    public void serviceEventPackageError_getStringContainsTypeAndPackageAndErrorAndMessage() {
        final var packageName = "com.android.package.name";
        final var errorString = "oh no an ERROR occurred";
        final var event = new ServiceEvent(serviceEventType, packageName, errorString);
        final var stringRep = event.eventToString();
        assertThat(stringRep).contains(serviceEventType.name());
        assertThat(stringRep).contains(packageName);
        assertThat(stringRep).contains(errorString);
        assertThat(stringRep).ignoringCase().contains("error");
    }

    @Test
    public void sessionEventUUIDNoError_getStringContainsUUID() {
        final var uuid = new UUID(5, -7);
        final var event = new SessionEvent(sessionEventType, uuid);
        final var stringRep = event.eventToString();
        assertThat(stringRep).contains(sessionEventType.name());
        assertThat(stringRep).contains(uuid.toString());
        assertThat(stringRep).ignoringCase().doesNotContain("error");
    }

    @Test
    public void sessionEventUUIDError_getStringContainsUUIDAndError() {
        final var uuid = new UUID(5, -7);
        final var errorString = "oh no an ERROR occurred";
        final var event = new SessionEvent(sessionEventType, uuid, errorString);
        final var stringRep = event.eventToString();
        assertThat(stringRep).contains(sessionEventType.name());
        assertThat(stringRep).contains(uuid.toString());
        assertThat(stringRep).ignoringCase().contains("error");
        assertThat(stringRep).contains(errorString);
    }
}
