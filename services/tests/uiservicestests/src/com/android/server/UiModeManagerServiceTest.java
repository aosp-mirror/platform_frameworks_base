/*
 * Copyright (C) 20019 The Android Open Source Project
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

package com.android.server;

import android.app.IUiModeManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.PowerManager;
import android.os.RemoteException;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import com.android.server.twilight.TwilightManager;
import com.android.server.wm.WindowManagerInternal;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.HashSet;
import java.util.Set;

import static android.app.UiModeManager.MODE_NIGHT_AUTO;
import static android.app.UiModeManager.MODE_NIGHT_NO;
import static android.app.UiModeManager.MODE_NIGHT_YES;
import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class UiModeManagerServiceTest extends UiServiceTestCase {
    private UiModeManagerService mUiManagerService;
    private IUiModeManager mService;
    @Mock
    private ContentResolver mContentResolver;
    @Mock
    private WindowManagerInternal mWindowManager;
    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;
    @Mock
    TwilightManager mTwilightManager;
    @Mock
    PowerManager.WakeLock mWakeLock;
    private Set<BroadcastReceiver> mScreenOffRecievers;

    @Before
    public void setUp() {
        mUiManagerService = new UiModeManagerService(mContext, mWindowManager, mWakeLock,
                mTwilightManager, true);
        mScreenOffRecievers = new HashSet<>();
        mService = mUiManagerService.getService();
        when(mContext.checkCallingOrSelfPermission(anyString()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        when(mContext.registerReceiver(any(), any())).then(inv -> {
            mScreenOffRecievers.add(inv.getArgument(0));
            return null;
        });
    }

    @Test
    public void setAutoMode_screenOffRegistered() throws RemoteException {
        try {
            mService.setNightMode(MODE_NIGHT_NO);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }
        mService.setNightMode(MODE_NIGHT_AUTO);
        verify(mContext).registerReceiver(any(BroadcastReceiver.class), any());
    }

    @Test
    public void setAutoMode_screenOffUnRegistered() throws RemoteException {
        try {
            mService.setNightMode(MODE_NIGHT_AUTO);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }
        try {
            mService.setNightMode(MODE_NIGHT_NO);
        } catch (SecurityException e) { /*we should ignore this update config exception*/ }
        given(mContext.registerReceiver(any(), any())).willThrow(SecurityException.class);
        verify(mContext).unregisterReceiver(any(BroadcastReceiver.class));
    }

    @Test
    public void setNightModeActive_fromNightModeYesToNoWhenFalse() throws RemoteException {
        try {
            mService.setNightMode(MODE_NIGHT_YES);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }
        try {
            mService.setNightModeActivated(false);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }
        assertEquals(MODE_NIGHT_NO, mService.getNightMode());
    }

    @Test
    public void setNightModeActive_fromNightModeNoToYesWhenTrue() throws RemoteException {
        try {
            mService.setNightMode(MODE_NIGHT_NO);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }
        try {
            mService.setNightModeActivated(true);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }
        assertEquals(MODE_NIGHT_YES, mService.getNightMode());
    }

    @Test
    public void setNightModeActive_autoNightModeNoChanges() throws RemoteException {
        try {
            mService.setNightMode(MODE_NIGHT_AUTO);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }
        try {
            mService.setNightModeActivated(true);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }
        assertEquals(MODE_NIGHT_AUTO, mService.getNightMode());
    }

    @Test
    public void isNightModeActive_nightModeYes() throws RemoteException {
        try {
            mService.setNightMode(MODE_NIGHT_YES);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }
        assertTrue(isNightModeActivated());
    }

    @Test
    public void isNightModeActive_nightModeNo() throws RemoteException {
        try {
            mService.setNightMode(MODE_NIGHT_NO);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }
        assertFalse(isNightModeActivated());
    }

    private boolean isNightModeActivated() {
        return (mUiManagerService.getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_YES) != 0;
    }
}
