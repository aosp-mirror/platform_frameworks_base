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

package com.android.server.soundtrigger_middleware;

import static com.android.server.soundtrigger_middleware.SoundTriggerMiddlewareLogging.ServiceEvent;
import static com.android.server.soundtrigger_middleware.SoundTriggerMiddlewareLogging.SessionEvent;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class SoundTriggerMiddlewareLoggingTest {
    private static final ServiceEvent.Type SERVICE_TYPE = ServiceEvent.Type.ATTACH;
    private static final SessionEvent.Type SESSION_TYPE = SessionEvent.Type.LOAD_MODEL;

    @Test
    public void serviceEventException_getStringContainsInfo() {
        String packageName = "com.android.test";
        Exception exception = new Exception("test");
        Object param1 = new Object();
        Object param2 = new Object();
        final var event =
                ServiceEvent.createForException(
                        SERVICE_TYPE, packageName, exception, param1, param2);
        final var stringRep = event.eventToString();
        assertThat(stringRep).contains(SERVICE_TYPE.name());
        assertThat(stringRep).contains(packageName);
        assertThat(stringRep).contains(exception.toString());
        assertThat(stringRep).contains(param1.toString());
        assertThat(stringRep).contains(param2.toString());
        assertThat(stringRep).ignoringCase().contains("error");
    }

    @Test
    public void serviceEventExceptionNoArgs_getStringContainsInfo() {
        String packageName = "com.android.test";
        Exception exception = new Exception("test");
        final var event = ServiceEvent.createForException(SERVICE_TYPE, packageName, exception);
        final var stringRep = event.eventToString();
        assertThat(stringRep).contains(SERVICE_TYPE.name());
        assertThat(stringRep).contains(packageName);
        assertThat(stringRep).contains(exception.toString());
        assertThat(stringRep).ignoringCase().contains("error");
    }

    @Test
    public void serviceEventReturn_getStringContainsInfo() {
        String packageName = "com.android.test";
        Object param1 = new Object();
        Object param2 = new Object();
        Object retValue = new Object();
        final var event =
                ServiceEvent.createForReturn(SERVICE_TYPE, packageName, retValue, param1, param2);
        final var stringRep = event.eventToString();
        assertThat(stringRep).contains(SERVICE_TYPE.name());
        assertThat(stringRep).contains(packageName);
        assertThat(stringRep).contains(retValue.toString());
        assertThat(stringRep).contains(param1.toString());
        assertThat(stringRep).contains(param2.toString());
        assertThat(stringRep).ignoringCase().doesNotContain("error");
    }

    @Test
    public void serviceEventReturnNoArgs_getStringContainsInfo() {
        String packageName = "com.android.test";
        Object retValue = new Object();
        final var event = ServiceEvent.createForReturn(SERVICE_TYPE, packageName, retValue);
        final var stringRep = event.eventToString();
        assertThat(stringRep).contains(SERVICE_TYPE.name());
        assertThat(stringRep).contains(packageName);
        assertThat(stringRep).contains(retValue.toString());
        assertThat(stringRep).ignoringCase().doesNotContain("error");
    }

    @Test
    public void sessionEventException_getStringContainsInfo() {
        Object param1 = new Object();
        Object param2 = new Object();
        Exception exception = new Exception("test");
        final var event = SessionEvent.createForException(SESSION_TYPE, exception, param1, param2);
        final var stringRep = event.eventToString();
        assertThat(stringRep).contains(SESSION_TYPE.name());
        assertThat(stringRep).contains(exception.toString());
        assertThat(stringRep).contains(param1.toString());
        assertThat(stringRep).contains(param2.toString());
        assertThat(stringRep).ignoringCase().contains("error");
    }

    @Test
    public void sessionEventExceptionNoArgs_getStringContainsInfo() {
        Exception exception = new Exception("test");
        final var event = SessionEvent.createForException(SESSION_TYPE, exception);
        final var stringRep = event.eventToString();
        assertThat(stringRep).contains(SESSION_TYPE.name());
        assertThat(stringRep).contains(exception.toString());
        assertThat(stringRep).ignoringCase().contains("error");
    }

    @Test
    public void sessionEventReturn_getStringContainsInfo() {
        Object param1 = new Object();
        Object param2 = new Object();
        Object retValue = new Object();
        final var event = SessionEvent.createForReturn(SESSION_TYPE, retValue, param1, param2);
        final var stringRep = event.eventToString();
        assertThat(stringRep).contains(SESSION_TYPE.name());
        assertThat(stringRep).contains(retValue.toString());
        assertThat(stringRep).contains(param1.toString());
        assertThat(stringRep).contains(param2.toString());
        assertThat(stringRep).ignoringCase().doesNotContain("error");
    }

    @Test
    public void sessionEventReturnNoArgs_getStringContainsInfo() {
        Object retValue = new Object();
        final var event = SessionEvent.createForReturn(SESSION_TYPE, retValue);
        final var stringRep = event.eventToString();
        assertThat(stringRep).contains(SESSION_TYPE.name());
        assertThat(stringRep).contains(retValue.toString());
        assertThat(stringRep).ignoringCase().doesNotContain("error");
    }

    @Test
    public void sessionEventVoid_getStringContainsInfo() {
        Object param1 = new Object();
        Object param2 = new Object();
        final var event = SessionEvent.createForVoid(SESSION_TYPE, param1, param2);
        final var stringRep = event.eventToString();
        assertThat(stringRep).contains(SESSION_TYPE.name());
        assertThat(stringRep).contains(param1.toString());
        assertThat(stringRep).contains(param2.toString());
        assertThat(stringRep).ignoringCase().doesNotContain("error");
    }

    @Test
    public void sessionEventVoidNoArgs_getStringContainsInfo() {
        final var event = SessionEvent.createForVoid(SESSION_TYPE);
        final var stringRep = event.eventToString();
        assertThat(stringRep).contains(SESSION_TYPE.name());
        assertThat(stringRep).ignoringCase().doesNotContain("error");
    }
}
