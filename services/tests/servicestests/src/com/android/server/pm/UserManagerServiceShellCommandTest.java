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

package com.android.server.pm;

import static junit.framework.Assert.assertEquals;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import static java.io.FileDescriptor.err;
import static java.io.FileDescriptor.in;
import static java.io.FileDescriptor.out;

import android.app.PropertyInvalidatedCache;
import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.widget.LockPatternUtils;
import com.android.server.LocalServices;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;

/**
 * Test class for {@link UserManagerServiceShellCommand}.
 *
 * runtest atest UserManagerServiceShellCommandTest
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public class UserManagerServiceShellCommandTest {

    private UserManagerServiceShellCommand mCommand;
    private UserManagerService mUserManagerService;
    private @Mock LockPatternUtils mLockPatternUtils;
    private final Binder mBinder = new Binder();
    private final ShellCallback mShellCallback = new ShellCallback();
    private final ResultReceiver mResultReceiver = new ResultReceiver(
            new Handler(Looper.getMainLooper()));
    private ByteArrayOutputStream mOutStream;
    private PrintWriter mWriter;

    @Before
    public void setUp() throws Exception {
        mOutStream = new ByteArrayOutputStream();
        mWriter = new PrintWriter(new PrintStream(mOutStream));
        MockitoAnnotations.initMocks(this);

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        // Disable binder caches in this process.
        PropertyInvalidatedCache.disableForTestMode();

        LocalServices.removeServiceForTest(UserManagerInternal.class);
        final Context context = InstrumentationRegistry.getTargetContext();

        UserManagerService serviceInstance = new UserManagerService(context);
        mUserManagerService = spy(serviceInstance);

        ArrayMap<String, UserTypeDetails> userTypes = UserTypeFactory.getUserTypes();
        UserSystemPackageInstaller userSystemPackageInstaller =
                new UserSystemPackageInstaller(mUserManagerService, userTypes);
        UserManagerServiceShellCommand cmd = new UserManagerServiceShellCommand(mUserManagerService,
                userSystemPackageInstaller, mLockPatternUtils, context);
        mCommand = spy(cmd);
    }

    @Test
    public void testMainUser() {
        when(mUserManagerService.getMainUserId()).thenReturn(12);
        doReturn(mWriter).when(mCommand).getOutPrintWriter();

        assertEquals(0, mCommand.exec(mBinder, in, out, err,
                new String[]{"get-main-user"}, mShellCallback, mResultReceiver));

        mWriter.flush();
        assertEquals("12", mOutStream.toString().trim());
    }

    @Test
    public void testMainUserNull() {
        when(mUserManagerService.getMainUserId()).thenReturn(UserHandle.USER_NULL);
        doReturn(mWriter).when(mCommand).getOutPrintWriter();

        assertEquals(1, mCommand.exec(mBinder, in, out, err,
                new String[]{"get-main-user"}, mShellCallback, mResultReceiver));
        mWriter.flush();
        assertEquals("None", mOutStream.toString().trim());
    }

    @Test
    public void testCanSwitchToHeadlessSystemUser() {
        doReturn(true).when(mUserManagerService).canSwitchToHeadlessSystemUser();
        doReturn(mWriter).when(mCommand).getOutPrintWriter();

        assertEquals(0, mCommand.exec(mBinder, in, out, err,
                new String[]{"can-switch-to-headless-system-user"},
                mShellCallback, mResultReceiver));

        mWriter.flush();
        assertEquals("true", mOutStream.toString().trim());
    }


    @Test
    public void testIsMainUserPermanentAdmin() {
        doReturn(false).when(mUserManagerService).isMainUserPermanentAdmin();
        doReturn(mWriter).when(mCommand).getOutPrintWriter();

        assertEquals(0, mCommand.exec(mBinder, in, out, err,
                new String[]{"is-main-user-permanent-admin"}, mShellCallback, mResultReceiver));

        mWriter.flush();
        assertEquals("false", mOutStream.toString().trim());
    }


}
