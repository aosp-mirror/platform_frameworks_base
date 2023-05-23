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

package com.android.server.contentprotection;

import static android.view.contentcapture.ContentCaptureSession.FLUSH_REASON_LOGIN_DETECTED;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.os.UserHandle;
import android.view.contentcapture.ContentCaptureEvent;
import android.view.contentcapture.IContentCaptureDirectManager;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.infra.AndroidFuture;
import com.android.internal.infra.ServiceConnector;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Test for {@link RemoteContentProtectionService}.
 *
 * <p>Run with: {@code atest
 * FrameworksServicesTests:com.android.server.contentprotection.RemoteContentProtectionServiceTest}
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class RemoteContentProtectionServiceTest {

    private final Context mContext = ApplicationProvider.getApplicationContext();

    @Rule public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private IContentCaptureDirectManager mMockContentCaptureDirectManager;

    private RemoteContentProtectionService mRemoteContentProtectionService;

    private int mConnectCallCount = 0;

    @Before
    public void setup() {
        ComponentName componentName = new ComponentName(mContext.getPackageName(), "TestClass");
        mRemoteContentProtectionService =
                new TestRemoteContentProtectionService(mContext, componentName);
    }

    @Test
    public void doesNotAutoConnect() {
        assertThat(mConnectCallCount).isEqualTo(0);
        verifyZeroInteractions(mMockContentCaptureDirectManager);
    }

    @Test
    public void getAutoDisconnectTimeoutMs() {
        long actual = mRemoteContentProtectionService.getAutoDisconnectTimeoutMs();

        assertThat(actual).isEqualTo(3000L);
    }

    @Test
    public void onLoginDetected() throws Exception {
        ContentCaptureEvent event =
                new ContentCaptureEvent(/* sessionId= */ 1111, /* type= */ 2222);
        ParceledListSlice<ContentCaptureEvent> events =
                new ParceledListSlice<>(ImmutableList.of(event));

        mRemoteContentProtectionService.onLoginDetected(events);

        verify(mMockContentCaptureDirectManager)
                .sendEvents(events, FLUSH_REASON_LOGIN_DETECTED, /* options= */ null);
    }

    private final class TestRemoteContentProtectionService extends RemoteContentProtectionService {

        TestRemoteContentProtectionService(Context context, ComponentName componentName) {
            super(context, componentName, UserHandle.myUserId(), /* bindAllowInstant= */ false);
        }

        @Override // from ServiceConnector
        public synchronized AndroidFuture<IContentCaptureDirectManager> connect() {
            mConnectCallCount++;
            return AndroidFuture.completedFuture(mMockContentCaptureDirectManager);
        }

        @Override // from ServiceConnector
        public boolean run(@NonNull ServiceConnector.VoidJob<IContentCaptureDirectManager> job) {
            try {
                job.run(mMockContentCaptureDirectManager);
            } catch (Exception ex) {
                fail("Unexpected exception: " + ex);
            }
            return true;
        }
    }
}
