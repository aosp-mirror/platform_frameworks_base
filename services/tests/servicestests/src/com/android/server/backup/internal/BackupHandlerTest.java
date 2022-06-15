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

package com.android.server.backup.internal;

import static org.mockito.Mockito.when;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import android.os.HandlerThread;
import android.os.Message;
import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.backup.UserBackupManagerService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class BackupHandlerTest {
    private static final int MESSAGE_TIMEOUT_MINUTES = 1;

    @Mock private UserBackupManagerService mUserBackupManagerService;
    @Mock private BackupAgentTimeoutParameters mTimeoutParameters;

    private HandlerThread mHandlerThread;
    private CountDownLatch mCountDownLatch;
    private boolean mExceptionPropagated;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(/* testClass */ this);
        when(mUserBackupManagerService.getAgentTimeoutParameters()).thenReturn(mTimeoutParameters);

        mExceptionPropagated = false;
        mCountDownLatch = new CountDownLatch(/* count */ 1);
        mHandlerThread = new HandlerThread("BackupHandlerTestThread");
        mHandlerThread.start();
    }

    @After
    public void tearDown() {
        mHandlerThread.quit();
    }

    @Test
    public void testSendMessage_propagatesExceptions() throws Exception {
        BackupHandler handler = new TestBackupHandler(/* shouldStop */ false);
        handler.sendMessage(getMessage());
        mCountDownLatch.await(MESSAGE_TIMEOUT_MINUTES, TimeUnit.MINUTES);

        assertTrue(mExceptionPropagated);
    }

    @Test
    public void testPost_propagatesExceptions() throws Exception {
        BackupHandler handler = new TestBackupHandler(/* shouldStop */ false);
        handler.post(() -> {});
        mCountDownLatch.await(MESSAGE_TIMEOUT_MINUTES, TimeUnit.MINUTES);

        assertTrue(mExceptionPropagated);
    }

    @Test
    public void testSendMessage_stopping_doesntPropagateExceptions() throws Exception {
        BackupHandler handler = new TestBackupHandler(/* shouldStop */ true);
        handler.sendMessage(getMessage());
        mCountDownLatch.await(MESSAGE_TIMEOUT_MINUTES, TimeUnit.MINUTES);

        assertFalse(mExceptionPropagated);
    }

    @Test
    public void testPost_stopping_doesntPropagateExceptions() throws Exception {
        BackupHandler handler = new TestBackupHandler(/* shouldStop */ true);
        handler.post(() -> {});
        mCountDownLatch.await(MESSAGE_TIMEOUT_MINUTES, TimeUnit.MINUTES);

        assertFalse(mExceptionPropagated);
    }

    private static Message getMessage() {
        Message message = Message.obtain();
        message.what = -1;
        return message;
    }

    private class TestBackupHandler extends BackupHandler  {
        private final boolean mShouldStop;

        TestBackupHandler(boolean shouldStop) {
            super(mUserBackupManagerService, mHandlerThread);

            mShouldStop = shouldStop;
        }

        @Override
        public void dispatchMessage(Message msg) {
            try {
                super.dispatchMessage(msg);
            } catch (Exception e) {
                mExceptionPropagated = true;
            } finally {
                mCountDownLatch.countDown();
            }
        }

        @Override
        void dispatchMessageInternal(Message msg) {
            mIsStopping = mShouldStop;
            throw new RuntimeException();
        }
    }
}
