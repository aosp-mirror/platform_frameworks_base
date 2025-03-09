/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.os;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.platform.test.annotations.DisabledOnRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

/**
 * Test class for {@link DebugStore}.
 *
 * <p>To run it: atest FrameworksCoreTests:com.android.internal.os.DebugStoreTest
 */
@RunWith(AndroidJUnit4.class)
@DisabledOnRavenwood(blockedBy = DebugStore.class)
@SmallTest
public class DebugStoreTest {
    @Rule public final RavenwoodRule mRavenwood = new RavenwoodRule();

    @Mock private DebugStore.DebugStoreNative mDebugStoreNativeMock;

    @Captor private ArgumentCaptor<List<String>> mListCaptor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        DebugStore.setDebugStoreNative(mDebugStoreNativeMock);
    }

    @Test
    public void testRecordServiceOnStart() {
        Intent intent = new Intent();
        intent.setAction("com.android.ACTION");
        intent.setComponent(new ComponentName("com.android", "androidService"));
        intent.setPackage("com.android");

        when(mDebugStoreNativeMock.beginEvent(anyString(), anyList())).thenReturn(1L);

        long eventId = DebugStore.recordServiceOnStart(1, 0, intent);
        assertThat(paramsForBeginEvent("SvcStart"))
                .containsExactly(
                        "stId", "1",
                        "flg", "0",
                        "act", "com.android.ACTION",
                        "comp", "ComponentInfo{com.android/androidService}",
                        "pkg", "com.android")
                .inOrder();
        assertThat(eventId).isEqualTo(1L);
    }

    @Test
    public void testRecordServiceCreate() {
        ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.name = "androidService";
        serviceInfo.packageName = "com.android";

        when(mDebugStoreNativeMock.beginEvent(anyString(), anyList())).thenReturn(2L);

        long eventId = DebugStore.recordServiceCreate(serviceInfo);
        assertThat(paramsForBeginEvent("SvcCreate"))
                .containsExactly(
                        "name", "androidService",
                        "pkg", "com.android")
                .inOrder();
        assertThat(eventId).isEqualTo(2L);
    }

    @Test
    public void testRecordServiceBind() {
        Intent intent = new Intent();
        intent.setAction("com.android.ACTION");
        intent.setComponent(new ComponentName("com.android", "androidService"));
        intent.setPackage("com.android");

        when(mDebugStoreNativeMock.beginEvent(anyString(), anyList())).thenReturn(3L);

        long eventId = DebugStore.recordServiceBind(true, intent);
        assertThat(paramsForBeginEvent("SvcBind"))
                .containsExactly(
                        "rebind", "true",
                        "act", "com.android.ACTION",
                        "cmp", "ComponentInfo{com.android/androidService}",
                        "pkg", "com.android")
                .inOrder();
        assertThat(eventId).isEqualTo(3L);
    }

    @Test
    public void testRecordGoAsync() {
        DebugStore.recordGoAsync(3840 /* 0xf00 */);

        assertThat(paramsForRecordEvent("GoAsync"))
                .containsExactly(
                        "tname",
                        Thread.currentThread().getName(),
                        "tid",
                        String.valueOf(Thread.currentThread().getId()),
                        "prid",
                        "f00")
                .inOrder();
    }

    @Test
    public void testRecordFinish() {
        DebugStore.recordFinish(3840 /* 0xf00 */);

        assertThat(paramsForRecordEvent("Finish"))
                .containsExactly(
                        "tname",
                        Thread.currentThread().getName(),
                        "tid",
                        String.valueOf(Thread.currentThread().getId()),
                        "prid",
                        "f00")
                .inOrder();
    }

    @Test
    public void testRecordLongLooperMessage() {
        DebugStore.recordLongLooperMessage(100, "androidHandler", 500L);

        assertThat(paramsForRecordEvent("LooperMsg"))
                .containsExactly(
                        "code", "100",
                        "trgt", "androidHandler",
                        "elapsed", "500")
                .inOrder();
    }

    @Test
    public void testRecordBroadcastReceive() {
        Intent intent = new Intent();
        intent.setAction("com.android.ACTION");
        intent.setComponent(new ComponentName("com.android", "androidReceiver"));
        intent.setPackage("com.android");

        when(mDebugStoreNativeMock.beginEvent(anyString(), anyList())).thenReturn(4L);

        long eventId = DebugStore.recordBroadcastReceive(intent, 3840 /* 0xf00 */);
        assertThat(paramsForBeginEvent("BcRcv"))
                .containsExactly(
                        "tname", Thread.currentThread().getName(),
                        "tid", String.valueOf(Thread.currentThread().getId()),
                        "act", "com.android.ACTION",
                        "cmp", "ComponentInfo{com.android/androidReceiver}",
                        "pkg", "com.android",
                        "prid", "f00")
                .inOrder();
        assertThat(eventId).isEqualTo(4L);
    }

    @Test
    public void testRecordBroadcastReceiveReg() {
        Intent intent = new Intent();
        intent.setAction("com.android.ACTION");
        intent.setComponent(new ComponentName("com.android", "androidReceiver"));
        intent.setPackage("com.android");

        when(mDebugStoreNativeMock.beginEvent(anyString(), anyList())).thenReturn(5L);

        long eventId = DebugStore.recordBroadcastReceiveReg(intent, 3840 /* 0xf00 */);
        assertThat(paramsForBeginEvent("BcRcvReg"))
                .containsExactly(
                        "tname",
                        Thread.currentThread().getName(),
                        "tid",
                        String.valueOf(Thread.currentThread().getId()),
                        "act",
                        "com.android.ACTION",
                        "cmp",
                        "ComponentInfo{com.android/androidReceiver}",
                        "pkg",
                        "com.android",
                        "prid",
                        "f00")
                .inOrder();
        assertThat(eventId).isEqualTo(5L);
    }

    @Test
    public void testRecordHandleBindApplication() {
        when(mDebugStoreNativeMock.beginEvent(anyString(), anyList())).thenReturn(6L);
        long eventId = DebugStore.recordHandleBindApplication();

        assertThat(paramsForBeginEvent("BindApp")).isEmpty();
        assertThat(eventId).isEqualTo(6L);
    }

      @Test
    public void testRecordScheduleReceiver() {
        when(mDebugStoreNativeMock.beginEvent(anyString(), anyList())).thenReturn(7L);
        long eventId = DebugStore.recordScheduleReceiver();

        assertThat(paramsForBeginEvent("SchRcv"))
                .containsExactly(
                        "tname",
                        Thread.currentThread().getName(),
                        "tid",
                        String.valueOf(Thread.currentThread().getId()))
                .inOrder();
        assertThat(eventId).isEqualTo(7L);
    }

        @Test
    public void testRecordScheduleRegisteredReceiver() {
        when(mDebugStoreNativeMock.beginEvent(anyString(), anyList())).thenReturn(8L);
        long eventId = DebugStore.recordScheduleRegisteredReceiver();

        assertThat(paramsForBeginEvent("SchRcvReg"))
                .containsExactly(
                        "tname",
                        Thread.currentThread().getName(),
                        "tid",
                        String.valueOf(Thread.currentThread().getId()))
                .inOrder();
        assertThat(eventId).isEqualTo(8L);
    }

    @Test
    public void testRecordEventEnd() {
        DebugStore.recordEventEnd(1L);

        verify(mDebugStoreNativeMock).endEvent(eq(1L), anyList());
    }

    @Test
    public void testRecordServiceOnStart_withNullIntent() {
        when(mDebugStoreNativeMock.beginEvent(anyString(), anyList())).thenReturn(5L);

        long eventId = DebugStore.recordServiceOnStart(1, 0, null);
        assertThat(paramsForBeginEvent("SvcStart"))
                .containsExactly(
                        "stId", "1",
                        "flg", "0",
                        "act", "null",
                        "comp", "null",
                        "pkg", "null")
                .inOrder();
        assertThat(eventId).isEqualTo(5L);
    }

    @Test
    public void testRecordServiceCreate_withNullServiceInfo() {
        when(mDebugStoreNativeMock.beginEvent(anyString(), anyList())).thenReturn(6L);

        long eventId = DebugStore.recordServiceCreate(null);
        assertThat(paramsForBeginEvent("SvcCreate"))
                .containsExactly(
                        "name", "null",
                        "pkg", "null")
                .inOrder();
        assertThat(eventId).isEqualTo(6L);
    }

    @Test
    public void testRecordServiceBind_withNullIntent() {
        when(mDebugStoreNativeMock.beginEvent(anyString(), anyList())).thenReturn(7L);

        long eventId = DebugStore.recordServiceBind(false, null);
        assertThat(paramsForBeginEvent("SvcBind"))
                .containsExactly(
                        "rebind", "false",
                        "act", "null",
                        "cmp", "null",
                        "pkg", "null")
                .inOrder();
        assertThat(eventId).isEqualTo(7L);
    }

    @Test
    public void testRecordBroadcastReceive_withNullIntent() {
        when(mDebugStoreNativeMock.beginEvent(anyString(), anyList())).thenReturn(8L);

        long eventId = DebugStore.recordBroadcastReceive(null, 3840 /* 0xf00 */);
        assertThat(paramsForBeginEvent("BcRcv"))
                .containsExactly(
                        "tname", Thread.currentThread().getName(),
                        "tid", String.valueOf(Thread.currentThread().getId()),
                        "act", "null",
                        "cmp", "null",
                        "pkg", "null",
                        "prid", "f00")
                .inOrder();
        assertThat(eventId).isEqualTo(8L);
    }

    @Test
    public void testRecordBroadcastReceiveReg_withNullIntent() {
        when(mDebugStoreNativeMock.beginEvent(anyString(), anyList())).thenReturn(8L);

        long eventId = DebugStore.recordBroadcastReceiveReg(null, 3840 /* 0xf00 */);
        assertThat(paramsForBeginEvent("BcRcvReg"))
                .containsExactly(
                        "tname",
                        Thread.currentThread().getName(),
                        "tid",
                        String.valueOf(Thread.currentThread().getId()),
                        "act",
                        "null",
                        "cmp",
                        "null",
                        "pkg",
                        "null",
                        "prid",
                        "f00")
                .inOrder();
        assertThat(eventId).isEqualTo(8L);
    }

    @Test
    public void testRecordFinish_withNullReceiverClassName() {
        DebugStore.recordFinish(3840 /* 0xf00 */);

        assertThat(paramsForRecordEvent("Finish"))
                .containsExactly(
                        "tname",
                        Thread.currentThread().getName(),
                        "tid",
                        String.valueOf(Thread.currentThread().getId()),
                        "prid",
                        "f00")
                .inOrder();
    }

    @Test
    public void testRecordLongLooperMessage_withNullTargetClass() {
        DebugStore.recordLongLooperMessage(200, null, 1000L);

        assertThat(paramsForRecordEvent("LooperMsg"))
                .containsExactly(
                        "code", "200",
                        "trgt", "null",
                        "elapsed", "1000")
                .inOrder();
    }

    private List<String> paramsForBeginEvent(String eventName) {
        verify(mDebugStoreNativeMock).beginEvent(eq(eventName), mListCaptor.capture());
        return mListCaptor.getValue();
    }

    private List<String> paramsForRecordEvent(String eventName) {
        verify(mDebugStoreNativeMock).recordEvent(eq(eventName), mListCaptor.capture());
        return mListCaptor.getValue();
    }

}
