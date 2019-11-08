/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.locksettings;

import static android.content.pm.UserInfo.FLAG_FULL;
import static android.content.pm.UserInfo.FLAG_PRIMARY;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.UserInfo;
import android.hardware.rebootescrow.IRebootEscrow;
import android.os.RemoteException;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.widget.RebootEscrowListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class RebootEscrowManagerTests {
    protected static final int PRIMARY_USER_ID = 0;
    protected static final int NONSECURE_USER_ID = 10;
    private static final byte FAKE_SP_VERSION = 1;
    private static final byte[] FAKE_AUTH_TOKEN = new byte[] {
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
    };

    private Context mContext;
    private UserManager mUserManager;
    private RebootEscrowManager.Callbacks mCallbacks;
    private IRebootEscrow mRebootEscrow;

    LockSettingsStorageTestable mStorage;

    private RebootEscrowManager mService;

    static class MockInjector extends RebootEscrowManager.Injector {
        private final IRebootEscrow mRebootEscrow;
        private final UserManager mUserManager;

        MockInjector(Context context, UserManager userManager, IRebootEscrow rebootEscrow) {
            super(context);
            mRebootEscrow = rebootEscrow;
            mUserManager = userManager;
        }

        @Override
        public UserManager getUserManager() {
            return mUserManager;
        }

        @Override
        public IRebootEscrow getRebootEscrow() {
            return mRebootEscrow;
        }
    }

    @Before
    public void setUp_baseServices() throws Exception {
        mContext = mock(Context.class);
        mUserManager = mock(UserManager.class);
        mCallbacks = mock(RebootEscrowManager.Callbacks.class);
        mRebootEscrow = mock(IRebootEscrow.class);

        mStorage = new LockSettingsStorageTestable(mContext,
                new File(InstrumentationRegistry.getContext().getFilesDir(), "locksettings"));

        ArrayList<UserInfo> users = new ArrayList<>();
        users.add(new UserInfo(PRIMARY_USER_ID, "primary", FLAG_PRIMARY));
        users.add(new UserInfo(NONSECURE_USER_ID, "non-secure", FLAG_FULL));
        when(mUserManager.getUsers()).thenReturn(users);
        when(mCallbacks.isUserSecure(PRIMARY_USER_ID)).thenReturn(true);
        when(mCallbacks.isUserSecure(NONSECURE_USER_ID)).thenReturn(false);
        mService = new RebootEscrowManager(new MockInjector(mContext, mUserManager, mRebootEscrow),
                mCallbacks, mStorage);
    }

    @Test
    public void prepareRebootEscrow_Success() throws Exception {
        RebootEscrowListener mockListener = mock(RebootEscrowListener.class);
        mService.setRebootEscrowListener(mockListener);
        mService.prepareRebootEscrow();

        clearInvocations(mRebootEscrow);
        mService.callToRebootEscrowIfNeeded(PRIMARY_USER_ID, FAKE_SP_VERSION, FAKE_AUTH_TOKEN);
        verify(mockListener).onPreparedForReboot(eq(true));
        verify(mRebootEscrow, never()).storeKey(any());
    }

    @Test
    public void prepareRebootEscrow_ClearCredentials_Success() throws Exception {
        RebootEscrowListener mockListener = mock(RebootEscrowListener.class);
        mService.setRebootEscrowListener(mockListener);
        mService.prepareRebootEscrow();
        mService.callToRebootEscrowIfNeeded(PRIMARY_USER_ID, FAKE_SP_VERSION, FAKE_AUTH_TOKEN);
        verify(mockListener).onPreparedForReboot(eq(true));

        clearInvocations(mRebootEscrow);
        mService.clearRebootEscrow();
        verify(mockListener).onPreparedForReboot(eq(false));
        verify(mRebootEscrow).storeKey(eq(new byte[32]));
    }

    @Test
    public void armService_Success() throws Exception {
        RebootEscrowListener mockListener = mock(RebootEscrowListener.class);
        mService.setRebootEscrowListener(mockListener);
        mService.prepareRebootEscrow();

        clearInvocations(mRebootEscrow);
        mService.callToRebootEscrowIfNeeded(PRIMARY_USER_ID, FAKE_SP_VERSION, FAKE_AUTH_TOKEN);
        verify(mockListener).onPreparedForReboot(eq(true));
        verify(mRebootEscrow, never()).storeKey(any());

        assertTrue(mService.armRebootEscrowIfNeeded());
        verify(mRebootEscrow).storeKey(any());
    }

    @Test
    public void armService_NoInitialization_Failure() throws Exception {
        assertFalse(mService.armRebootEscrowIfNeeded());
        verifyNoMoreInteractions(mRebootEscrow);
    }

    @Test
    public void armService_RebootEscrowServiceException_Failure() throws Exception {
        doThrow(RemoteException.class).when(mRebootEscrow).storeKey(any());
        assertFalse(mService.armRebootEscrowIfNeeded());
        verifyNoMoreInteractions(mRebootEscrow);
    }
}
