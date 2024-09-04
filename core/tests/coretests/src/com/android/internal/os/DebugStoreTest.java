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
 * To run it:
 * atest FrameworksCoreTests:com.android.internal.os.DebugStoreTest
 */
@RunWith(AndroidJUnit4.class)
@DisabledOnRavenwood(blockedBy = DebugStore.class)
@SmallTest
public class DebugStoreTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    @Mock
    private DebugStore.DebugStoreNative mDebugStoreNativeMock;

    @Captor
    private ArgumentCaptor<List<String>> mListCaptor;

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

        verify(mDebugStoreNativeMock).beginEvent(eq("SvcStart"), mListCaptor.capture());
        List<String> capturedList = mListCaptor.getValue();
        assertThat(capturedList).containsExactly(
                "stId", "1",
                "flg", "0",
                "act", "com.android.ACTION",
                "comp", "ComponentInfo{com.android/androidService}",
                "pkg", "com.android"
        ).inOrder();
        assertThat(eventId).isEqualTo(1L);
    }

    @Test
    public void testRecordServiceCreate() {
        ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.name = "androidService";
        serviceInfo.packageName = "com.android";

        when(mDebugStoreNativeMock.beginEvent(anyString(), anyList())).thenReturn(2L);

        long eventId = DebugStore.recordServiceCreate(serviceInfo);

        verify(mDebugStoreNativeMock).beginEvent(eq("SvcCreate"), mListCaptor.capture());
        List<String> capturedList = mListCaptor.getValue();
        assertThat(capturedList).containsExactly(
                "name", "androidService",
                "pkg", "com.android"
        ).inOrder();
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

        verify(mDebugStoreNativeMock).beginEvent(eq("SvcBind"), mListCaptor.capture());
        List<String> capturedList = mListCaptor.getValue();
        assertThat(capturedList).containsExactly(
                "rebind", "true",
                "act", "com.android.ACTION",
                "cmp", "ComponentInfo{com.android/androidService}",
                "pkg", "com.android"
        ).inOrder();
        assertThat(eventId).isEqualTo(3L);
    }

    @Test
    public void testRecordGoAsync() {
        DebugStore.recordGoAsync("androidReceiver");

        verify(mDebugStoreNativeMock).recordEvent(eq("GoAsync"), mListCaptor.capture());
        List<String> capturedList = mListCaptor.getValue();
        assertThat(capturedList).containsExactly(
                "tname", Thread.currentThread().getName(),
                "tid", String.valueOf(Thread.currentThread().getId()),
                "rcv", "androidReceiver"
        ).inOrder();
    }

    @Test
    public void testRecordFinish() {
        DebugStore.recordFinish("androidReceiver");

        verify(mDebugStoreNativeMock).recordEvent(eq("Finish"), mListCaptor.capture());
        List<String> capturedList = mListCaptor.getValue();
        assertThat(capturedList).containsExactly(
                "tname", Thread.currentThread().getName(),
                "tid", String.valueOf(Thread.currentThread().getId()),
                "rcv", "androidReceiver"
        ).inOrder();
    }

    @Test
    public void testRecordLongLooperMessage() {
        DebugStore.recordLongLooperMessage(100, "androidHandler", 500L);

        verify(mDebugStoreNativeMock).recordEvent(eq("LooperMsg"), mListCaptor.capture());
        List<String> capturedList = mListCaptor.getValue();
        assertThat(capturedList).containsExactly(
                "code", "100",
                "trgt", "androidHandler",
                "elapsed", "500"
        ).inOrder();
    }

    @Test
    public void testRecordBroadcastHandleReceiver() {
        Intent intent = new Intent();
        intent.setAction("com.android.ACTION");
        intent.setComponent(new ComponentName("com.android", "androidReceiver"));
        intent.setPackage("com.android");

        when(mDebugStoreNativeMock.beginEvent(anyString(), anyList())).thenReturn(4L);

        long eventId = DebugStore.recordBroadcastHandleReceiver(intent);

        verify(mDebugStoreNativeMock).beginEvent(eq("HandleReceiver"), mListCaptor.capture());
        List<String> capturedList = mListCaptor.getValue();
        assertThat(capturedList).containsExactly(
                "tname", Thread.currentThread().getName(),
                "tid", String.valueOf(Thread.currentThread().getId()),
                "act", "com.android.ACTION",
                "cmp", "ComponentInfo{com.android/androidReceiver}",
                "pkg", "com.android"
        ).inOrder();
        assertThat(eventId).isEqualTo(4L);
    }

    @Test
    public void testRecordEventEnd() {
        DebugStore.recordEventEnd(1L);

        verify(mDebugStoreNativeMock).endEvent(eq(1L), anyList());
    }

    @Test
    public void testRecordServiceOnStartWithNullIntent() {
        when(mDebugStoreNativeMock.beginEvent(anyString(), anyList())).thenReturn(5L);

        long eventId = DebugStore.recordServiceOnStart(1, 0, null);

        verify(mDebugStoreNativeMock).beginEvent(eq("SvcStart"), mListCaptor.capture());
        List<String> capturedList = mListCaptor.getValue();
        assertThat(capturedList).containsExactly(
                "stId", "1",
                "flg", "0",
                "act", "null",
                "comp", "null",
                "pkg", "null"
        ).inOrder();
        assertThat(eventId).isEqualTo(5L);
    }

    @Test
    public void testRecordServiceCreateWithNullServiceInfo() {
        when(mDebugStoreNativeMock.beginEvent(anyString(), anyList())).thenReturn(6L);

        long eventId = DebugStore.recordServiceCreate(null);

        verify(mDebugStoreNativeMock).beginEvent(eq("SvcCreate"), mListCaptor.capture());
        List<String> capturedList = mListCaptor.getValue();
        assertThat(capturedList).containsExactly(
                "name", "null",
                "pkg", "null"
        ).inOrder();
        assertThat(eventId).isEqualTo(6L);
    }

    @Test
    public void testRecordServiceBindWithNullIntent() {
        when(mDebugStoreNativeMock.beginEvent(anyString(), anyList())).thenReturn(7L);

        long eventId = DebugStore.recordServiceBind(false, null);

        verify(mDebugStoreNativeMock).beginEvent(eq("SvcBind"), mListCaptor.capture());
        List<String> capturedList = mListCaptor.getValue();
        assertThat(capturedList).containsExactly(
                "rebind", "false",
                "act", "null",
                "cmp", "null",
                "pkg", "null"
        ).inOrder();
        assertThat(eventId).isEqualTo(7L);
    }

    @Test
    public void testRecordBroadcastHandleReceiverWithNullIntent() {
        when(mDebugStoreNativeMock.beginEvent(anyString(), anyList())).thenReturn(8L);

        long eventId = DebugStore.recordBroadcastHandleReceiver(null);

        verify(mDebugStoreNativeMock).beginEvent(eq("HandleReceiver"), mListCaptor.capture());
        List<String> capturedList = mListCaptor.getValue();
        assertThat(capturedList).containsExactly(
                "tname", Thread.currentThread().getName(),
                "tid", String.valueOf(Thread.currentThread().getId()),
                "act", "null",
                "cmp", "null",
                "pkg", "null"
        ).inOrder();
        assertThat(eventId).isEqualTo(8L);
    }

    @Test
    public void testRecordGoAsyncWithNullReceiverClassName() {
        DebugStore.recordGoAsync(null);

        verify(mDebugStoreNativeMock).recordEvent(eq("GoAsync"), mListCaptor.capture());
        List<String> capturedList = mListCaptor.getValue();
        assertThat(capturedList).containsExactly(
                "tname", Thread.currentThread().getName(),
                "tid", String.valueOf(Thread.currentThread().getId()),
                "rcv", "null"
        ).inOrder();
    }

    @Test
    public void testRecordFinishWithNullReceiverClassName() {
        DebugStore.recordFinish(null);

        verify(mDebugStoreNativeMock).recordEvent(eq("Finish"), mListCaptor.capture());
        List<String> capturedList = mListCaptor.getValue();
        assertThat(capturedList).containsExactly(
                "tname", Thread.currentThread().getName(),
                "tid", String.valueOf(Thread.currentThread().getId()),
                "rcv", "null"
        ).inOrder();
    }

    @Test
    public void testRecordLongLooperMessageWithNullTargetClass() {
        DebugStore.recordLongLooperMessage(200, null, 1000L);

        verify(mDebugStoreNativeMock).recordEvent(eq("LooperMsg"), mListCaptor.capture());
        List<String> capturedList = mListCaptor.getValue();
        assertThat(capturedList).containsExactly(
                "code", "200",
                "trgt", "null",
                "elapsed", "1000"
        ).inOrder();
    }
}
