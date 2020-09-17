/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.recoverysystem;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.IntentSender;
import android.os.Handler;
import android.os.IPowerManager;
import android.os.IRecoverySystemProgressListener;
import android.os.IThermalService;
import android.os.Looper;
import android.os.PowerManager;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.widget.LockSettingsInternal;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileWriter;

/**
 * atest FrameworksServicesTests:RecoverySystemServiceTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class RecoverySystemServiceTest {
    private RecoverySystemService mRecoverySystemService;
    private RecoverySystemServiceTestable.FakeSystemProperties mSystemProperties;
    private RecoverySystemService.UncryptSocket mUncryptSocket;
    private Context mContext;
    private IPowerManager mIPowerManager;
    private IThermalService mIThermalService;
    private FileWriter mUncryptUpdateFileWriter;
    private LockSettingsInternal mLockSettingsInternal;

    @Before
    public void setup() {
        mContext = mock(Context.class);
        mSystemProperties = new RecoverySystemServiceTestable.FakeSystemProperties();
        mUncryptSocket = mock(RecoverySystemService.UncryptSocket.class);
        mUncryptUpdateFileWriter = mock(FileWriter.class);
        mLockSettingsInternal = mock(LockSettingsInternal.class);

        when(mLockSettingsInternal.armRebootEscrow()).thenReturn(true);

        Looper looper = InstrumentationRegistry.getContext().getMainLooper();
        mIPowerManager = mock(IPowerManager.class);
        mIThermalService = mock(IThermalService.class);
        PowerManager powerManager = new PowerManager(mock(Context.class), mIPowerManager,
                mIThermalService, new Handler(looper));

        mRecoverySystemService = new RecoverySystemServiceTestable(mContext, mSystemProperties,
                powerManager, mUncryptUpdateFileWriter, mUncryptSocket, mLockSettingsInternal);
    }

    @Test
    public void clearBcb_success() throws Exception {
        doNothing().when(mContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.RECOVERY), any());
        when(mUncryptSocket.getPercentageUncrypted()).thenReturn(100);

        assertThat(mRecoverySystemService.clearBcb(), is(true));

        assertThat(mSystemProperties.getCtlStart(), is("clear-bcb"));
        verify(mUncryptSocket).sendAck();
        verify(mUncryptSocket).close();
    }

    @Test
    public void clearBcb_uncrypt_failure() throws Exception {
        doNothing().when(mContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.RECOVERY), any());
        when(mUncryptSocket.getPercentageUncrypted()).thenReturn(0);

        assertThat(mRecoverySystemService.clearBcb(), is(false));

        assertThat(mSystemProperties.getCtlStart(), is("clear-bcb"));
        verify(mUncryptSocket).sendAck();
        verify(mUncryptSocket).close();
    }

    @Test(expected = SecurityException.class)
    public void clearBcb_noPerm() {
        doThrow(SecurityException.class).when(mContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.RECOVERY), any());
        mRecoverySystemService.clearBcb();
    }

    @Test
    public void setupBcb_success() throws Exception {
        doNothing().when(mContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.RECOVERY), any());
        when(mUncryptSocket.getPercentageUncrypted()).thenReturn(100);

        assertThat(mRecoverySystemService.setupBcb("foo"), is(true));

        assertThat(mSystemProperties.getCtlStart(), is("setup-bcb"));
        verify(mUncryptSocket).sendCommand("foo");
        verify(mUncryptSocket).sendAck();
        verify(mUncryptSocket).close();
    }

    @Test
    public void setupBcb_uncrypt_failure() throws Exception {
        doNothing().when(mContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.RECOVERY), any());
        when(mUncryptSocket.getPercentageUncrypted()).thenReturn(0);

        assertThat(mRecoverySystemService.setupBcb("foo"), is(false));

        assertThat(mSystemProperties.getCtlStart(), is("setup-bcb"));
        verify(mUncryptSocket).sendCommand("foo");
        verify(mUncryptSocket).sendAck();
        verify(mUncryptSocket).close();
    }

    @Test(expected = SecurityException.class)
    public void setupBcb_noPerm() {
        doThrow(SecurityException.class).when(mContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.RECOVERY), any());
        mRecoverySystemService.setupBcb("foo");
    }

    @Test
    public void rebootRecoveryWithCommand_success() throws Exception {
        doNothing().when(mContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.RECOVERY), any());
        when(mUncryptSocket.getPercentageUncrypted()).thenReturn(100);

        mRecoverySystemService.rebootRecoveryWithCommand("foo");

        assertThat(mSystemProperties.getCtlStart(), is("setup-bcb"));
        verify(mUncryptSocket).sendCommand("foo");
        verify(mUncryptSocket).sendAck();
        verify(mUncryptSocket).close();
        verify(mIPowerManager).reboot(anyBoolean(), eq("recovery"), anyBoolean());
    }

    @Test
    public void rebootRecoveryWithCommand_failure() throws Exception {
        doNothing().when(mContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.RECOVERY), any());
        when(mUncryptSocket.getPercentageUncrypted()).thenReturn(0);

        mRecoverySystemService.rebootRecoveryWithCommand("foo");

        assertThat(mSystemProperties.getCtlStart(), is("setup-bcb"));
        verify(mUncryptSocket).sendCommand("foo");
        verify(mUncryptSocket).sendAck();
        verify(mUncryptSocket).close();
        verifyNoMoreInteractions(mIPowerManager);
    }

    @Test(expected = SecurityException.class)
    public void rebootRecoveryWithCommand_noPerm() {
        doThrow(SecurityException.class).when(mContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.RECOVERY), any());
        mRecoverySystemService.rebootRecoveryWithCommand("foo");
    }

    @Test
    public void uncrypt_success() throws Exception {
        doNothing().when(mContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.RECOVERY), any());
        when(mUncryptSocket.getPercentageUncrypted()).thenReturn(0, 5, 25, 50, 90, 99, 100);

        IRecoverySystemProgressListener listener = mock(IRecoverySystemProgressListener.class);
        assertThat(mRecoverySystemService.uncrypt("foo.zip", listener), is(true));

        assertThat(mSystemProperties.getCtlStart(), is("uncrypt"));
        verify(mUncryptSocket, times(7)).getPercentageUncrypted();
        verify(mUncryptSocket).sendAck();
        verify(mUncryptSocket).close();
    }

    @Test(expected = SecurityException.class)
    public void requestLskf_protected() {
        doThrow(SecurityException.class).when(mContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.RECOVERY), any());
        mRecoverySystemService.requestLskf("test", null);
    }


    @Test
    public void requestLskf_nullToken_failure() {
        assertThat(mRecoverySystemService.requestLskf(null, null), is(false));
    }

    @Test
    public void requestLskf_success() throws Exception {
        IntentSender intentSender = mock(IntentSender.class);
        assertThat(mRecoverySystemService.requestLskf("test", intentSender), is(true));
        mRecoverySystemService.onPreparedForReboot(true);
        verify(intentSender).sendIntent(any(), anyInt(), any(), any(), any());
    }

    @Test
    public void requestLskf_subsequentRequestClearsPrepared() throws Exception {
        IntentSender intentSender = mock(IntentSender.class);
        assertThat(mRecoverySystemService.requestLskf("test", intentSender), is(true));
        mRecoverySystemService.onPreparedForReboot(true);
        verify(intentSender).sendIntent(any(), anyInt(), any(), any(), any());

        assertThat(mRecoverySystemService.requestLskf("test2", null), is(true));
        assertThat(mRecoverySystemService.rebootWithLskf("test", null), is(false));
        assertThat(mRecoverySystemService.rebootWithLskf("test2", "foobar"), is(false));

        mRecoverySystemService.onPreparedForReboot(true);
        assertThat(mRecoverySystemService.rebootWithLskf("test2", "foobar"), is(true));
        verify(intentSender).sendIntent(any(), anyInt(), any(), any(), any());
        verify(mIPowerManager).reboot(anyBoolean(), eq("foobar"), anyBoolean());
    }


    @Test
    public void requestLskf_requestedButNotPrepared() throws Exception {
        IntentSender intentSender = mock(IntentSender.class);
        assertThat(mRecoverySystemService.requestLskf("test", intentSender), is(true));
        verify(intentSender, never()).sendIntent(any(), anyInt(), any(), any(), any());
    }

    @Test(expected = SecurityException.class)
    public void clearLskf_protected() {
        doThrow(SecurityException.class).when(mContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.RECOVERY), any());
        mRecoverySystemService.clearLskf();
    }

    @Test
    public void clearLskf_requestedThenCleared() throws Exception {
        IntentSender intentSender = mock(IntentSender.class);
        assertThat(mRecoverySystemService.requestLskf("test", intentSender), is(true));
        mRecoverySystemService.onPreparedForReboot(true);
        verify(intentSender).sendIntent(any(), anyInt(), any(), any(), any());

        assertThat(mRecoverySystemService.clearLskf(), is(true));
        verify(mLockSettingsInternal).clearRebootEscrow();
    }

    @Test
    public void startup_setRebootEscrowListener() throws Exception {
        mRecoverySystemService.onSystemServicesReady();
        verify(mLockSettingsInternal).setRebootEscrowListener(any());
    }

    @Test(expected = SecurityException.class)
    public void rebootWithLskf_protected() {
        doThrow(SecurityException.class).when(mContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.RECOVERY), any());
        mRecoverySystemService.rebootWithLskf("test1", null);
    }

    @Test
    public void rebootWithLskf_Success() throws Exception {
        assertThat(mRecoverySystemService.requestLskf("test", null), is(true));
        mRecoverySystemService.onPreparedForReboot(true);
        assertThat(mRecoverySystemService.rebootWithLskf("test", "ab-update"), is(true));
        verify(mIPowerManager).reboot(anyBoolean(), eq("ab-update"), anyBoolean());
    }

    @Test
    public void rebootWithLskf_withoutPrepare_Failure() throws Exception {
        assertThat(mRecoverySystemService.rebootWithLskf("test1", null), is(false));
    }

    @Test
    public void rebootWithLskf_withNullUpdateToken_Failure() throws Exception {
        assertThat(mRecoverySystemService.rebootWithLskf(null, null), is(false));
        verifyNoMoreInteractions(mIPowerManager);
    }
}
