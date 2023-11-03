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

import static android.view.contentprotection.flags.Flags.FLAG_BLOCKLIST_UPDATE_ENABLED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.platform.test.flag.junit.SetFlagsRule;
import android.service.contentcapture.IContentProtectionAllowlistCallback;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.content.PackageMonitor;
import com.android.server.contentcapture.ContentCaptureManagerService;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.List;

/**
 * Test for {@link ContentProtectionAllowlistManager}.
 *
 * <p>Run with: {@code atest FrameworksServicesTests:
 * com.android.server.contentprotection.ContentProtectionAllowlistManagerTest}
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class ContentProtectionAllowlistManagerTest {

    private static final String FIRST_PACKAGE_NAME = "com.test.first.package.name";

    private static final String SECOND_PACKAGE_NAME = "com.test.second.package.name";

    private static final long TIMEOUT_MS = 111_111_111L;

    private static final long DELAY_MS = 222_222_222L;

    @Rule public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock private ContentCaptureManagerService mMockContentCaptureManagerService;

    @Mock private PackageMonitor mMockPackageMonitor;

    @Mock private RemoteContentProtectionService mMockRemoteContentProtectionService;

    @Mock private IContentProtectionAllowlistCallback mMockAllowlistCallback;

    private final TestLooper mTestLooper = new TestLooper();

    private Handler mHandler;

    private ContentProtectionAllowlistManager mContentProtectionAllowlistManager;

    private boolean mUseMockPackageMonitor = true;

    private boolean mUseMockAllowlistCallback = true;

    @Before
    public void setup() {
        mHandler = new Handler(mTestLooper.getLooper());
        mContentProtectionAllowlistManager = new TestContentProtectionAllowlistManager();
    }

    @Test
    public void constructor() {
        assertThat(mHandler.hasMessagesOrCallbacks()).isFalse();
        verifyZeroInteractions(mMockContentCaptureManagerService);
        verifyZeroInteractions(mMockPackageMonitor);
        verifyZeroInteractions(mMockRemoteContentProtectionService);
        verifyZeroInteractions(mMockAllowlistCallback);
    }

    @Test
    public void start_updateEnabled_firstTime_beforeDelay() {
        mSetFlagsRule.enableFlags(FLAG_BLOCKLIST_UPDATE_ENABLED);

        mContentProtectionAllowlistManager.start(DELAY_MS);
        mTestLooper.dispatchAll();

        assertThat(mHandler.hasMessagesOrCallbacks()).isTrue();
        verifyZeroInteractions(mMockContentCaptureManagerService);
        verifyZeroInteractions(mMockPackageMonitor);
        verifyZeroInteractions(mMockRemoteContentProtectionService);
        verifyZeroInteractions(mMockAllowlistCallback);
    }

    @Test
    public void start_updateEnabled_firstTime_afterDelay() {
        mSetFlagsRule.enableFlags(FLAG_BLOCKLIST_UPDATE_ENABLED);

        mContentProtectionAllowlistManager.start(DELAY_MS);
        mTestLooper.moveTimeForward(DELAY_MS);
        mTestLooper.dispatchNext();

        assertThat(mHandler.hasMessagesOrCallbacks()).isFalse();
        verify(mMockContentCaptureManagerService).createRemoteContentProtectionService();
        verify(mMockPackageMonitor).register(any(), eq(UserHandle.ALL), eq(mHandler));
        verify(mMockPackageMonitor, never()).unregister();
        verifyZeroInteractions(mMockRemoteContentProtectionService);
        verifyZeroInteractions(mMockAllowlistCallback);
    }

    @Test
    public void start_updateEnabled_secondTime() {
        mSetFlagsRule.enableFlags(FLAG_BLOCKLIST_UPDATE_ENABLED);
        mContentProtectionAllowlistManager.start(DELAY_MS);
        mTestLooper.moveTimeForward(DELAY_MS);
        mTestLooper.dispatchNext();

        mContentProtectionAllowlistManager.start(DELAY_MS);

        assertThat(mHandler.hasMessagesOrCallbacks()).isFalse();
        verify(mMockContentCaptureManagerService).createRemoteContentProtectionService();
        verify(mMockPackageMonitor).register(any(), eq(UserHandle.ALL), eq(mHandler));
        verify(mMockPackageMonitor, never()).unregister();
        verifyZeroInteractions(mMockRemoteContentProtectionService);
        verifyZeroInteractions(mMockAllowlistCallback);
    }

    @Test
    public void start_updateDisabled_firstTime_beforeDelay() {
        mSetFlagsRule.disableFlags(FLAG_BLOCKLIST_UPDATE_ENABLED);

        mContentProtectionAllowlistManager.start(DELAY_MS);
        mTestLooper.dispatchAll();

        assertThat(mHandler.hasMessagesOrCallbacks()).isTrue();
        verifyZeroInteractions(mMockContentCaptureManagerService);
        verifyZeroInteractions(mMockPackageMonitor);
        verifyZeroInteractions(mMockRemoteContentProtectionService);
        verifyZeroInteractions(mMockAllowlistCallback);
    }

    @Test
    public void start_updateDisabled_firstTime_afterDelay() {
        mSetFlagsRule.disableFlags(FLAG_BLOCKLIST_UPDATE_ENABLED);

        mContentProtectionAllowlistManager.start(DELAY_MS);
        mTestLooper.moveTimeForward(DELAY_MS);
        mTestLooper.dispatchNext();

        assertThat(mHandler.hasMessagesOrCallbacks()).isFalse();
        verifyZeroInteractions(mMockContentCaptureManagerService);
        verify(mMockPackageMonitor).register(any(), eq(UserHandle.ALL), eq(mHandler));
        verify(mMockPackageMonitor, never()).unregister();
        verifyZeroInteractions(mMockRemoteContentProtectionService);
        verifyZeroInteractions(mMockAllowlistCallback);
    }

    @Test
    public void start_updateDisabled_secondTime() {
        mSetFlagsRule.disableFlags(FLAG_BLOCKLIST_UPDATE_ENABLED);
        mContentProtectionAllowlistManager.start(DELAY_MS);
        mTestLooper.moveTimeForward(DELAY_MS);
        mTestLooper.dispatchNext();

        mContentProtectionAllowlistManager.start(DELAY_MS);

        assertThat(mHandler.hasMessagesOrCallbacks()).isFalse();
        verifyZeroInteractions(mMockContentCaptureManagerService);
        verify(mMockPackageMonitor).register(any(), eq(UserHandle.ALL), eq(mHandler));
        verify(mMockPackageMonitor, never()).unregister();
        verifyZeroInteractions(mMockRemoteContentProtectionService);
        verifyZeroInteractions(mMockAllowlistCallback);
    }

    @Test
    public void stop_updateEnabled_notStarted() {
        mSetFlagsRule.enableFlags(FLAG_BLOCKLIST_UPDATE_ENABLED);
        doThrow(new IllegalStateException("NOT REGISTERED")).when(mMockPackageMonitor).unregister();

        mContentProtectionAllowlistManager.stop();

        assertThat(mHandler.hasMessagesOrCallbacks()).isFalse();
        verifyZeroInteractions(mMockContentCaptureManagerService);
        verify(mMockPackageMonitor, never()).register(any(), any(), any());
        verify(mMockPackageMonitor).unregister();
        verifyZeroInteractions(mMockRemoteContentProtectionService);
        verifyZeroInteractions(mMockAllowlistCallback);
    }

    @Test
    public void stop_updateEnabled_started_beforeDelay() {
        mSetFlagsRule.enableFlags(FLAG_BLOCKLIST_UPDATE_ENABLED);
        doThrow(new IllegalStateException("NOT REGISTERED")).when(mMockPackageMonitor).unregister();
        mContentProtectionAllowlistManager.start(DELAY_MS);
        mTestLooper.dispatchAll();

        mContentProtectionAllowlistManager.stop();

        assertThat(mHandler.hasMessagesOrCallbacks()).isFalse();
        verifyZeroInteractions(mMockContentCaptureManagerService);
        verify(mMockPackageMonitor, never()).register(any(), any(), any());
        verify(mMockPackageMonitor).unregister();
        verifyZeroInteractions(mMockRemoteContentProtectionService);
        verifyZeroInteractions(mMockAllowlistCallback);
    }

    @Test
    public void stop_updateEnabled_started_afterDelay() {
        mSetFlagsRule.enableFlags(FLAG_BLOCKLIST_UPDATE_ENABLED);
        mContentProtectionAllowlistManager.start(DELAY_MS);
        mTestLooper.moveTimeForward(DELAY_MS);
        mTestLooper.dispatchNext();

        mContentProtectionAllowlistManager.stop();

        assertThat(mHandler.hasMessagesOrCallbacks()).isFalse();
        verify(mMockContentCaptureManagerService).createRemoteContentProtectionService();
        verify(mMockPackageMonitor).register(any(), eq(UserHandle.ALL), eq(mHandler));
        verify(mMockPackageMonitor).unregister();
        verifyZeroInteractions(mMockRemoteContentProtectionService);
        verifyZeroInteractions(mMockAllowlistCallback);
    }

    @Test
    public void stop_updateDisabled_notStarted() {
        mSetFlagsRule.disableFlags(FLAG_BLOCKLIST_UPDATE_ENABLED);
        doThrow(new IllegalStateException("NOT REGISTERED")).when(mMockPackageMonitor).unregister();

        mContentProtectionAllowlistManager.stop();

        assertThat(mHandler.hasMessagesOrCallbacks()).isFalse();
        verifyZeroInteractions(mMockContentCaptureManagerService);
        verify(mMockPackageMonitor, never()).register(any(), any(), any());
        verify(mMockPackageMonitor).unregister();
        verifyZeroInteractions(mMockRemoteContentProtectionService);
        verifyZeroInteractions(mMockAllowlistCallback);
    }

    @Test
    public void stop_updateDisabled_started_beforeDelay() {
        mSetFlagsRule.disableFlags(FLAG_BLOCKLIST_UPDATE_ENABLED);
        doThrow(new IllegalStateException("NOT REGISTERED")).when(mMockPackageMonitor).unregister();
        mContentProtectionAllowlistManager.start(DELAY_MS);
        mTestLooper.dispatchAll();

        mContentProtectionAllowlistManager.stop();

        assertThat(mHandler.hasMessagesOrCallbacks()).isFalse();
        verifyZeroInteractions(mMockContentCaptureManagerService);
        verify(mMockPackageMonitor, never()).register(any(), any(), any());
        verify(mMockPackageMonitor).unregister();
        verifyZeroInteractions(mMockRemoteContentProtectionService);
        verifyZeroInteractions(mMockAllowlistCallback);
    }

    @Test
    public void stop_updateDisabled_started_afterDelay() {
        mSetFlagsRule.disableFlags(FLAG_BLOCKLIST_UPDATE_ENABLED);
        mContentProtectionAllowlistManager.start(DELAY_MS);
        mTestLooper.moveTimeForward(DELAY_MS);
        mTestLooper.dispatchNext();

        mContentProtectionAllowlistManager.stop();

        assertThat(mHandler.hasMessagesOrCallbacks()).isFalse();
        verifyZeroInteractions(mMockContentCaptureManagerService);
        verify(mMockPackageMonitor).register(any(), eq(UserHandle.ALL), eq(mHandler));
        verify(mMockPackageMonitor).unregister();
        verifyZeroInteractions(mMockRemoteContentProtectionService);
        verifyZeroInteractions(mMockAllowlistCallback);
    }

    @Test
    public void start_afterStop_beforeDelay() {
        mContentProtectionAllowlistManager.start(DELAY_MS);
        mTestLooper.dispatchAll();
        mContentProtectionAllowlistManager.stop();

        mContentProtectionAllowlistManager.start(DELAY_MS);
        mTestLooper.moveTimeForward(DELAY_MS);
        mTestLooper.dispatchNext();

        assertThat(mHandler.hasMessagesOrCallbacks()).isFalse();
        verify(mMockPackageMonitor).register(any(), eq(UserHandle.ALL), eq(mHandler));
        verify(mMockPackageMonitor).unregister();
        verifyZeroInteractions(mMockRemoteContentProtectionService);
        verifyZeroInteractions(mMockAllowlistCallback);
    }

    @Test
    public void start_afterStop_afterDelay() {
        mContentProtectionAllowlistManager.start(DELAY_MS);
        mTestLooper.moveTimeForward(DELAY_MS);
        mTestLooper.dispatchNext();
        mContentProtectionAllowlistManager.stop();

        mContentProtectionAllowlistManager.start(DELAY_MS);
        mTestLooper.moveTimeForward(DELAY_MS);
        mTestLooper.dispatchNext();

        assertThat(mHandler.hasMessagesOrCallbacks()).isFalse();
        verify(mMockPackageMonitor, times(2)).register(any(), eq(UserHandle.ALL), eq(mHandler));
        verify(mMockPackageMonitor).unregister();
        verifyZeroInteractions(mMockRemoteContentProtectionService);
        verifyZeroInteractions(mMockAllowlistCallback);
    }

    @Test
    public void isAllowed_default() {
        boolean actual = mContentProtectionAllowlistManager.isAllowed(FIRST_PACKAGE_NAME);

        assertThat(actual).isFalse();
        verifyZeroInteractions(mMockContentCaptureManagerService);
        verifyZeroInteractions(mMockPackageMonitor);
        verifyZeroInteractions(mMockRemoteContentProtectionService);
        verifyZeroInteractions(mMockAllowlistCallback);
    }

    @Test
    public void isAllowed_false() throws Exception {
        mUseMockAllowlistCallback = false;
        ContentProtectionAllowlistManager manager = new TestContentProtectionAllowlistManager();
        manager.mAllowlistCallback.setAllowlist(List.of(FIRST_PACKAGE_NAME));
        mTestLooper.dispatchNext();

        boolean actual = manager.isAllowed(SECOND_PACKAGE_NAME);

        assertThat(actual).isFalse();
        verifyZeroInteractions(mMockContentCaptureManagerService);
        verifyZeroInteractions(mMockPackageMonitor);
        verifyZeroInteractions(mMockRemoteContentProtectionService);
    }

    @Test
    public void isAllowed_true() throws Exception {
        mUseMockAllowlistCallback = false;
        ContentProtectionAllowlistManager manager = new TestContentProtectionAllowlistManager();
        manager.mAllowlistCallback.setAllowlist(List.of(FIRST_PACKAGE_NAME));
        mTestLooper.dispatchNext();

        boolean actual = manager.isAllowed(FIRST_PACKAGE_NAME);

        assertThat(actual).isTrue();
        verifyZeroInteractions(mMockContentCaptureManagerService);
        verifyZeroInteractions(mMockPackageMonitor);
        verifyZeroInteractions(mMockRemoteContentProtectionService);
    }

    @Test
    public void handlePackagesChanged_updateDisabled() {
        mSetFlagsRule.disableFlags(FLAG_BLOCKLIST_UPDATE_ENABLED);
        mUseMockPackageMonitor = false;
        ContentProtectionAllowlistManager manager = new TestContentProtectionAllowlistManager();

        manager.mPackageMonitor.onSomePackagesChanged();

        verifyZeroInteractions(mMockContentCaptureManagerService);
        verifyZeroInteractions(mMockRemoteContentProtectionService);
        verifyZeroInteractions(mMockAllowlistCallback);
    }

    @Test
    public void handlePackagesChanged_updateEnabled_noService() {
        mSetFlagsRule.enableFlags(FLAG_BLOCKLIST_UPDATE_ENABLED);
        mUseMockPackageMonitor = false;
        ContentProtectionAllowlistManager manager = new TestContentProtectionAllowlistManager();

        manager.mPackageMonitor.onSomePackagesChanged();

        verify(mMockContentCaptureManagerService).createRemoteContentProtectionService();
        verifyZeroInteractions(mMockRemoteContentProtectionService);
        verifyZeroInteractions(mMockAllowlistCallback);
    }

    @Test
    public void handlePackagesChanged_updateEnabled_withService() {
        mSetFlagsRule.enableFlags(FLAG_BLOCKLIST_UPDATE_ENABLED);
        mUseMockPackageMonitor = false;
        ContentProtectionAllowlistManager manager = new TestContentProtectionAllowlistManager();
        when(mMockContentCaptureManagerService.createRemoteContentProtectionService())
                .thenReturn(mMockRemoteContentProtectionService);

        manager.mPackageMonitor.onSomePackagesChanged();

        verify(mMockRemoteContentProtectionService)
                .onUpdateAllowlistRequest(mMockAllowlistCallback);
        verifyZeroInteractions(mMockAllowlistCallback);
    }

    @Test
    public void handlePackagesChanged_updateEnabled_withServiceException() {
        mSetFlagsRule.enableFlags(FLAG_BLOCKLIST_UPDATE_ENABLED);
        mUseMockPackageMonitor = false;
        ContentProtectionAllowlistManager manager = new TestContentProtectionAllowlistManager();
        when(mMockContentCaptureManagerService.createRemoteContentProtectionService())
                .thenReturn(mMockRemoteContentProtectionService);
        doThrow(new RuntimeException("TEST EXCEPTION"))
                .when(mMockRemoteContentProtectionService)
                .onUpdateAllowlistRequest(mMockAllowlistCallback);

        manager.mPackageMonitor.onSomePackagesChanged();

        // Does not rethrow
        verify(mMockRemoteContentProtectionService)
                .onUpdateAllowlistRequest(mMockAllowlistCallback);
        verifyZeroInteractions(mMockAllowlistCallback);
    }

    @Test
    public void handlePackagesChanged_rateLimit_noService() {
        mSetFlagsRule.enableFlags(FLAG_BLOCKLIST_UPDATE_ENABLED);
        mUseMockPackageMonitor = false;
        ContentProtectionAllowlistManager manager = new TestContentProtectionAllowlistManager();

        manager.mPackageMonitor.onSomePackagesChanged();
        manager.mPackageMonitor.onSomePackagesChanged();

        verify(mMockContentCaptureManagerService, times(2)).createRemoteContentProtectionService();
        verifyZeroInteractions(mMockRemoteContentProtectionService);
        verifyZeroInteractions(mMockAllowlistCallback);
    }

    @Test
    public void handlePackagesChanged_rateLimit_beforeTimeout() {
        mSetFlagsRule.enableFlags(FLAG_BLOCKLIST_UPDATE_ENABLED);
        mUseMockPackageMonitor = false;
        ContentProtectionAllowlistManager manager = new TestContentProtectionAllowlistManager();
        when(mMockContentCaptureManagerService.createRemoteContentProtectionService())
                .thenReturn(mMockRemoteContentProtectionService);

        manager.mPackageMonitor.onSomePackagesChanged();
        manager.mPackageMonitor.onSomePackagesChanged();

        verify(mMockContentCaptureManagerService).createRemoteContentProtectionService();
        verify(mMockRemoteContentProtectionService)
                .onUpdateAllowlistRequest(mMockAllowlistCallback);
        verifyZeroInteractions(mMockAllowlistCallback);
    }

    @Test
    public void handlePackagesChanged_rateLimit_afterTimeout() {
        mSetFlagsRule.enableFlags(FLAG_BLOCKLIST_UPDATE_ENABLED);
        mUseMockPackageMonitor = false;
        ContentProtectionAllowlistManager manager =
                new TestContentProtectionAllowlistManager(/* timeoutMs= */ 0L);
        when(mMockContentCaptureManagerService.createRemoteContentProtectionService())
                .thenReturn(mMockRemoteContentProtectionService);

        manager.mPackageMonitor.onSomePackagesChanged();
        manager.mPackageMonitor.onSomePackagesChanged();

        verify(mMockContentCaptureManagerService, times(2)).createRemoteContentProtectionService();
        verify(mMockRemoteContentProtectionService, times(2))
                .onUpdateAllowlistRequest(mMockAllowlistCallback);
        verifyZeroInteractions(mMockAllowlistCallback);
    }

    @Test
    public void handlePackagesChanged_rateLimit_afterUpdate() throws Exception {
        mSetFlagsRule.enableFlags(FLAG_BLOCKLIST_UPDATE_ENABLED);
        mUseMockPackageMonitor = false;
        mUseMockAllowlistCallback = false;
        ContentProtectionAllowlistManager manager = new TestContentProtectionAllowlistManager();
        when(mMockContentCaptureManagerService.createRemoteContentProtectionService())
                .thenReturn(mMockRemoteContentProtectionService);

        manager.mPackageMonitor.onSomePackagesChanged();
        manager.mAllowlistCallback.setAllowlist(List.of());
        mTestLooper.dispatchNext();
        manager.mPackageMonitor.onSomePackagesChanged();

        verify(mMockContentCaptureManagerService, times(2)).createRemoteContentProtectionService();
        verify(mMockRemoteContentProtectionService, times(2))
                .onUpdateAllowlistRequest(manager.mAllowlistCallback);
    }

    private class TestContentProtectionAllowlistManager extends ContentProtectionAllowlistManager {

        TestContentProtectionAllowlistManager() {
            this(TIMEOUT_MS);
        }

        TestContentProtectionAllowlistManager(long timeoutMs) {
            super(mMockContentCaptureManagerService, mHandler, timeoutMs);
        }

        @Override
        protected IContentProtectionAllowlistCallback createAllowlistCallback() {
            return mUseMockAllowlistCallback
                    ? mMockAllowlistCallback
                    : super.createAllowlistCallback();
        }

        @Override
        protected PackageMonitor createPackageMonitor() {
            return mUseMockPackageMonitor ? mMockPackageMonitor : super.createPackageMonitor();
        }
    }
}
