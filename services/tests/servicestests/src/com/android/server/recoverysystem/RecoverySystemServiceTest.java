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

import static android.os.RecoverySystem.RESUME_ON_REBOOT_REBOOT_ERROR_INVALID_PACKAGE_NAME;
import static android.os.RecoverySystem.RESUME_ON_REBOOT_REBOOT_ERROR_LSKF_NOT_CAPTURED;
import static android.os.RecoverySystem.RESUME_ON_REBOOT_REBOOT_ERROR_PROVIDER_PREPARATION_FAILURE;
import static android.os.RecoverySystem.RESUME_ON_REBOOT_REBOOT_ERROR_SLOT_MISMATCH;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.hardware.boot.V1_2.IBootControl;
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
    private IBootControl mIBootControl;
    private RecoverySystemServiceTestable.IMetricsReporter mMetricsReporter;
    private RecoverySystemService.PreferencesManager mSharedPreferences;

    private static final String FAKE_OTA_PACKAGE_NAME = "fake.ota.package";
    private static final String FAKE_OTHER_PACKAGE_NAME = "fake.other.package";

    @Before
    public void setup() throws Exception {
        mContext = mock(Context.class);
        mSystemProperties = new RecoverySystemServiceTestable.FakeSystemProperties();
        mUncryptSocket = mock(RecoverySystemService.UncryptSocket.class);
        mUncryptUpdateFileWriter = mock(FileWriter.class);
        mLockSettingsInternal = mock(LockSettingsInternal.class);

        doReturn(true).when(mLockSettingsInternal).prepareRebootEscrow();
        doReturn(true).when(mLockSettingsInternal).clearRebootEscrow();
        doReturn(LockSettingsInternal.ARM_REBOOT_ERROR_NONE).when(mLockSettingsInternal)
                .armRebootEscrow();

        Looper looper = InstrumentationRegistry.getContext().getMainLooper();
        mIPowerManager = mock(IPowerManager.class);
        mIThermalService = mock(IThermalService.class);
        PowerManager powerManager = new PowerManager(mock(Context.class), mIPowerManager,
                mIThermalService, new Handler(looper));

        mIBootControl = mock(IBootControl.class);
        when(mIBootControl.getCurrentSlot()).thenReturn(0);
        when(mIBootControl.getActiveBootSlot()).thenReturn(1);

        mMetricsReporter = mock(RecoverySystemServiceTestable.IMetricsReporter.class);
        mSharedPreferences = mock(RecoverySystemService.PreferencesManager.class);

        mRecoverySystemService = new RecoverySystemServiceTestable(mContext, mSystemProperties,
                powerManager, mUncryptUpdateFileWriter, mUncryptSocket, mLockSettingsInternal,
                mIBootControl, mMetricsReporter, mSharedPreferences);
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
        when(mContext.checkCallingOrSelfPermission(anyString())).thenReturn(
                PackageManager.PERMISSION_DENIED);
        mRecoverySystemService.requestLskf(FAKE_OTA_PACKAGE_NAME, null);
    }

    @Test
    public void requestLskf_reportMetrics() throws Exception {
        IntentSender intentSender = mock(IntentSender.class);
        assertThat(mRecoverySystemService.requestLskf(FAKE_OTA_PACKAGE_NAME, intentSender),
                is(true));
        verify(mMetricsReporter).reportRebootEscrowPreparationMetrics(
                eq(1000), eq(0) /* need preparation */, eq(1) /* client count */);
        verify(mSharedPreferences).putLong(eq(FAKE_OTA_PACKAGE_NAME
                + RecoverySystemService.REQUEST_LSKF_TIMESTAMP_PREF_SUFFIX), eq(100_000L));
    }

    @Test
    public void requestLskf_success() throws Exception {
        IntentSender intentSender = mock(IntentSender.class);
        assertThat(mRecoverySystemService.requestLskf(FAKE_OTA_PACKAGE_NAME, intentSender),
                is(true));

        when(mSharedPreferences.getLong(eq(FAKE_OTA_PACKAGE_NAME
                + RecoverySystemService.REQUEST_LSKF_TIMESTAMP_PREF_SUFFIX), anyLong()))
                .thenReturn(200_000L).thenReturn(5000L);
        mRecoverySystemService.onPreparedForReboot(true);
        verify(mMetricsReporter).reportRebootEscrowLskfCapturedMetrics(
                eq(1000), eq(1) /* client count */,
                eq(-1) /* invalid duration */);

        mRecoverySystemService.onPreparedForReboot(true);
        verify(intentSender).sendIntent(any(), anyInt(), any(), any(), any());
        verify(mMetricsReporter).reportRebootEscrowLskfCapturedMetrics(
                eq(1000), eq(1) /* client count */, eq(95) /* duration */);
    }

    @Test
    public void requestLskf_subsequentRequestNotClearPrepared() throws Exception {
        IntentSender intentSender = mock(IntentSender.class);
        assertThat(mRecoverySystemService.requestLskf(FAKE_OTA_PACKAGE_NAME, intentSender),
                is(true));
        mRecoverySystemService.onPreparedForReboot(true);
        verify(intentSender).sendIntent(any(), anyInt(), any(), any(), any());

        assertThat(mRecoverySystemService.requestLskf(FAKE_OTA_PACKAGE_NAME, null), is(true));
        mRecoverySystemService.rebootWithLskf(FAKE_OTA_PACKAGE_NAME, "foobar", true);
        verify(mIPowerManager).reboot(anyBoolean(), eq("foobar"), anyBoolean());
    }

    @Test
    public void requestLskf_requestedButNotPrepared() throws Exception {
        IntentSender intentSender = mock(IntentSender.class);
        assertThat(mRecoverySystemService.requestLskf(FAKE_OTA_PACKAGE_NAME, intentSender),
                is(true));
        verify(intentSender, never()).sendIntent(any(), anyInt(), any(), any(), any());
        verify(mMetricsReporter, never()).reportRebootEscrowLskfCapturedMetrics(
                anyInt(), anyInt(), anyInt());
    }

    @Test
    public void requestLskf_lockSettingsError() throws Exception {
        IntentSender intentSender = mock(IntentSender.class);

        doReturn(false).when(mLockSettingsInternal).prepareRebootEscrow();
        assertFalse(mRecoverySystemService.requestLskf(FAKE_OTA_PACKAGE_NAME, intentSender));
    }

    @Test
    public void isLskfCaptured_requestedButNotPrepared() throws Exception {
        IntentSender intentSender = mock(IntentSender.class);
        assertThat(mRecoverySystemService.requestLskf(FAKE_OTA_PACKAGE_NAME, intentSender),
                is(true));
        assertThat(mRecoverySystemService.isLskfCaptured(FAKE_OTA_PACKAGE_NAME), is(false));
    }

    @Test
    public void isLskfCaptured_Prepared() throws Exception {
        IntentSender intentSender = mock(IntentSender.class);
        assertThat(mRecoverySystemService.requestLskf(FAKE_OTA_PACKAGE_NAME, intentSender),
                is(true));
        mRecoverySystemService.onPreparedForReboot(true);
        verify(intentSender).sendIntent(any(), anyInt(), any(), any(), any());
        assertThat(mRecoverySystemService.isLskfCaptured(FAKE_OTA_PACKAGE_NAME), is(true));
    }

    @Test(expected = SecurityException.class)
    public void clearLskf_protected() {
        when(mContext.checkCallingOrSelfPermission(anyString())).thenReturn(
                PackageManager.PERMISSION_DENIED);
        mRecoverySystemService.clearLskf(FAKE_OTA_PACKAGE_NAME);
    }

    @Test
    public void clearLskf_requestedThenCleared() throws Exception {
        IntentSender intentSender = mock(IntentSender.class);
        assertThat(mRecoverySystemService.requestLskf(FAKE_OTA_PACKAGE_NAME, intentSender),
                is(true));
        mRecoverySystemService.onPreparedForReboot(true);
        verify(intentSender).sendIntent(any(), anyInt(), any(), any(), any());

        assertThat(mRecoverySystemService.clearLskf(FAKE_OTA_PACKAGE_NAME), is(true));
        verify(mLockSettingsInternal).clearRebootEscrow();
    }

    @Test
    public void clearLskf_callerNotRequested_Success() throws Exception {
        IntentSender intentSender = mock(IntentSender.class);
        assertThat(mRecoverySystemService.requestLskf(FAKE_OTA_PACKAGE_NAME, intentSender),
                is(true));
        assertThat(mRecoverySystemService.clearLskf(FAKE_OTHER_PACKAGE_NAME), is(true));
        verify(mLockSettingsInternal, never()).clearRebootEscrow();
    }

    @Test
    public void clearLskf_multiClient_BothClientsClear() throws Exception {
        IntentSender intentSender = mock(IntentSender.class);
        assertThat(mRecoverySystemService.requestLskf(FAKE_OTA_PACKAGE_NAME, intentSender),
                is(true));
        assertThat(mRecoverySystemService.requestLskf(FAKE_OTHER_PACKAGE_NAME, intentSender),
                is(true));

        assertThat(mRecoverySystemService.clearLskf(FAKE_OTA_PACKAGE_NAME), is(true));
        verify(mLockSettingsInternal, never()).clearRebootEscrow();
        assertThat(mRecoverySystemService.clearLskf(FAKE_OTHER_PACKAGE_NAME), is(true));
        verify(mLockSettingsInternal).clearRebootEscrow();
    }

    @Test
    public void startup_setRebootEscrowListener() throws Exception {
        mRecoverySystemService.onSystemServicesReady();
        verify(mLockSettingsInternal).setRebootEscrowListener(any());
    }

    @Test(expected = SecurityException.class)
    public void rebootWithLskf_protected() {
        when(mContext.checkCallingOrSelfPermission(anyString())).thenReturn(
                PackageManager.PERMISSION_DENIED);
        mRecoverySystemService.rebootWithLskf(FAKE_OTA_PACKAGE_NAME, null, true);
    }

    @Test
    public void rebootWithLskf_Success() throws Exception {
        assertThat(mRecoverySystemService.requestLskf(FAKE_OTA_PACKAGE_NAME, null), is(true));
        mRecoverySystemService.onPreparedForReboot(true);

        when(mSharedPreferences.getInt(eq(FAKE_OTA_PACKAGE_NAME
                + RecoverySystemService.REQUEST_LSKF_COUNT_PREF_SUFFIX), anyInt())).thenReturn(2);
        when(mSharedPreferences.getInt(eq(RecoverySystemService.LSKF_CAPTURED_COUNT_PREF),
                anyInt())).thenReturn(3);
        when(mSharedPreferences.getLong(eq(RecoverySystemService.LSKF_CAPTURED_TIMESTAMP_PREF),
                anyLong())).thenReturn(40_000L);
        mRecoverySystemService.rebootWithLskf(FAKE_OTA_PACKAGE_NAME, "ab-update", true);
        verify(mIPowerManager).reboot(anyBoolean(), eq("ab-update"), anyBoolean());
        verify(mMetricsReporter).reportRebootEscrowRebootMetrics(eq(0), eq(1000),
                eq(1) /* client count */, eq(2) /* request count */, eq(true) /* slot switch */,
                anyBoolean(), eq(60) /* duration */, eq(3) /* lskf capture count */);
    }


    @Test
    public void rebootWithLskf_slotMismatch_Failure() throws Exception {
        assertThat(mRecoverySystemService.requestLskf(FAKE_OTA_PACKAGE_NAME, null), is(true));
        mRecoverySystemService.onPreparedForReboot(true);
        assertEquals(RESUME_ON_REBOOT_REBOOT_ERROR_SLOT_MISMATCH,
                mRecoverySystemService.rebootWithLskf(FAKE_OTA_PACKAGE_NAME, "ab-update", false));
    }

    @Test
    public void rebootWithLskf_withoutPrepare_Failure() throws Exception {
        assertEquals(RESUME_ON_REBOOT_REBOOT_ERROR_LSKF_NOT_CAPTURED,
                mRecoverySystemService.rebootWithLskf(FAKE_OTA_PACKAGE_NAME, null, true));
    }

    @Test
    public void rebootWithLskf_withNullCallerId_Failure() throws Exception {
        assertEquals(RESUME_ON_REBOOT_REBOOT_ERROR_INVALID_PACKAGE_NAME,
                mRecoverySystemService.rebootWithLskf(null, null, true));
        verifyNoMoreInteractions(mIPowerManager);
    }

    @Test
    public void rebootWithLskf_multiClient_ClientASuccess() throws Exception {
        assertThat(mRecoverySystemService.requestLskf(FAKE_OTA_PACKAGE_NAME, null), is(true));
        assertThat(mRecoverySystemService.requestLskf(FAKE_OTHER_PACKAGE_NAME, null), is(true));
        mRecoverySystemService.onPreparedForReboot(true);

        // Client B's clear won't affect client A's preparation.
        assertThat(mRecoverySystemService.clearLskf(FAKE_OTHER_PACKAGE_NAME), is(true));
        mRecoverySystemService.rebootWithLskf(FAKE_OTA_PACKAGE_NAME, "ab-update", true);
        verify(mIPowerManager).reboot(anyBoolean(), eq("ab-update"), anyBoolean());
    }

    @Test
    public void rebootWithLskf_multiClient_success_reportMetrics() throws Exception {
        assertThat(mRecoverySystemService.requestLskf(FAKE_OTA_PACKAGE_NAME, null), is(true));
        assertThat(mRecoverySystemService.requestLskf(FAKE_OTHER_PACKAGE_NAME, null), is(true));
        mRecoverySystemService.onPreparedForReboot(true);

        when(mSharedPreferences.getInt(eq(FAKE_OTA_PACKAGE_NAME
                + RecoverySystemService.REQUEST_LSKF_COUNT_PREF_SUFFIX), anyInt())).thenReturn(2);
        when(mSharedPreferences.getInt(eq(RecoverySystemService.LSKF_CAPTURED_COUNT_PREF),
                anyInt())).thenReturn(1);
        when(mSharedPreferences.getLong(eq(RecoverySystemService.LSKF_CAPTURED_TIMESTAMP_PREF),
                anyLong())).thenReturn(60_000L);

        mRecoverySystemService.rebootWithLskf(FAKE_OTA_PACKAGE_NAME, "ab-update", true);
        verify(mIPowerManager).reboot(anyBoolean(), eq("ab-update"), anyBoolean());
        verify(mMetricsReporter).reportRebootEscrowRebootMetrics(eq(0), eq(1000),
                eq(2) /* client count */, eq(2) /* request count */, eq(true) /* slot switch */,
                anyBoolean(), eq(40), eq(1) /* lskf capture count */);
    }

    @Test
    public void rebootWithLskf_multiClient_ClientBSuccess() throws Exception {
        assertThat(mRecoverySystemService.requestLskf(FAKE_OTA_PACKAGE_NAME, null), is(true));
        mRecoverySystemService.onPreparedForReboot(true);
        assertThat(mRecoverySystemService.requestLskf(FAKE_OTHER_PACKAGE_NAME, null), is(true));

        when(mSharedPreferences.getInt(eq(FAKE_OTHER_PACKAGE_NAME
                + RecoverySystemService.REQUEST_LSKF_COUNT_PREF_SUFFIX), anyInt())).thenReturn(2);
        when(mSharedPreferences.getInt(eq(RecoverySystemService.LSKF_CAPTURED_COUNT_PREF),
                anyInt())).thenReturn(1);
        when(mSharedPreferences.getLong(eq(RecoverySystemService.LSKF_CAPTURED_TIMESTAMP_PREF),
                anyLong())).thenReturn(60_000L);

        assertThat(mRecoverySystemService.clearLskf(FAKE_OTA_PACKAGE_NAME), is(true));
        assertEquals(RESUME_ON_REBOOT_REBOOT_ERROR_LSKF_NOT_CAPTURED,
                mRecoverySystemService.rebootWithLskf(FAKE_OTA_PACKAGE_NAME, null, true));
        verifyNoMoreInteractions(mIPowerManager);
        verify(mMetricsReporter).reportRebootEscrowRebootMetrics(not(eq(0)), eq(1000),
                eq(1) /* client count */, anyInt() /* request count */, eq(true) /* slot switch */,
                anyBoolean(), eq(40), eq(1)/* lskf capture count */);

        assertThat(mRecoverySystemService.requestLskf(FAKE_OTHER_PACKAGE_NAME, null), is(true));
        mRecoverySystemService.rebootWithLskf(FAKE_OTHER_PACKAGE_NAME, "ab-update", true);
        verify(mIPowerManager).reboot(anyBoolean(), eq("ab-update"), anyBoolean());

        verify(mMetricsReporter).reportRebootEscrowRebootMetrics((eq(0)), eq(2000),
                eq(1) /* client count */, eq(2) /* request count */, eq(true) /* slot switch */,
                anyBoolean(), eq(40), eq(1) /* lskf capture count */);
    }

    @Test
    public void rebootWithLskf_multiClient_BothClientsClear_Failure() throws Exception {
        assertThat(mRecoverySystemService.requestLskf(FAKE_OTA_PACKAGE_NAME, null), is(true));
        mRecoverySystemService.onPreparedForReboot(true);
        assertThat(mRecoverySystemService.requestLskf(FAKE_OTHER_PACKAGE_NAME, null), is(true));

        // Client A clears
        assertThat(mRecoverySystemService.clearLskf(FAKE_OTA_PACKAGE_NAME), is(true));
        assertEquals(RESUME_ON_REBOOT_REBOOT_ERROR_LSKF_NOT_CAPTURED,
                mRecoverySystemService.rebootWithLskf(FAKE_OTA_PACKAGE_NAME, null, true));
        verifyNoMoreInteractions(mIPowerManager);

        // Client B clears
        assertThat(mRecoverySystemService.clearLskf(FAKE_OTHER_PACKAGE_NAME), is(true));
        verify(mLockSettingsInternal).clearRebootEscrow();
        assertEquals(RESUME_ON_REBOOT_REBOOT_ERROR_LSKF_NOT_CAPTURED,
                mRecoverySystemService.rebootWithLskf(FAKE_OTHER_PACKAGE_NAME, "ab-update", true));
        verifyNoMoreInteractions(mIPowerManager);
    }

    // TODO(xunchang) add more multi client tests

    @Test
    public void rebootWithLskf_armEscrowDataFatalError_Failure() throws Exception {
        doReturn(LockSettingsInternal.ARM_REBOOT_ERROR_PROVIDER_MISMATCH)
                .when(mLockSettingsInternal).armRebootEscrow();

        assertTrue(mRecoverySystemService.requestLskf(FAKE_OTA_PACKAGE_NAME, null));
        mRecoverySystemService.onPreparedForReboot(true);
        assertTrue(mRecoverySystemService.isLskfCaptured(FAKE_OTA_PACKAGE_NAME));

        when(mSharedPreferences.getInt(eq(FAKE_OTA_PACKAGE_NAME
                + RecoverySystemService.REQUEST_LSKF_COUNT_PREF_SUFFIX), anyInt())).thenReturn(1);
        when(mSharedPreferences.getInt(eq(RecoverySystemService.LSKF_CAPTURED_COUNT_PREF),
                anyInt())).thenReturn(1);
        assertEquals(RESUME_ON_REBOOT_REBOOT_ERROR_PROVIDER_PREPARATION_FAILURE,
                mRecoverySystemService.rebootWithLskf(FAKE_OTA_PACKAGE_NAME, "ab-update", true));
        // Verify that the RoR preparation state has been cleared.
        assertFalse(mRecoverySystemService.isLskfCaptured(FAKE_OTA_PACKAGE_NAME));
        verify(mMetricsReporter).reportRebootEscrowRebootMetrics(eq(5004 /* provider mismatch */),
                eq(1000), eq(1) /* client count */, eq(1) /* request count */,
                eq(true) /* slot switch */, anyBoolean(), anyInt(),
                eq(1) /* lskf capture count */);
    }
}
