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

package com.android.server.wm;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.internal.protolog.ProtoLogGroup;
import com.android.internal.protolog.ProtoLogImpl;
import com.android.internal.protolog.common.IProtoLog;
import com.android.internal.protolog.common.LogLevel;
import com.android.internal.protolog.ProtoLog;

import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Check if the ProtoLogTools is used to process the WindowManager source code.
 */
@SmallTest
@Presubmit
public class ProtoLogIntegrationTest {
    @After
    public void tearDown() {
        ProtoLogImpl.setSingleInstance(null);
    }

    @Ignore("b/163095037")
    @Test
    public void testProtoLogToolIntegration() {
        IProtoLog mockedProtoLog = mock(IProtoLog.class);
        runWith(mockedProtoLog, this::testProtoLog);
        verify(mockedProtoLog).log(eq(LogLevel.ERROR), eq(ProtoLogGroup.TEST_GROUP),
                anyInt(), eq(0b0010010111),
                eq(com.android.internal.protolog.ProtoLogGroup.TEST_GROUP.isLogToLogcat()
                        ? "Test completed successfully: %b %d %x %f %% %s"
                        : null),
                eq(new Object[]{true, 1L, 2L, 0.3, "ok"}));
    }

    private void testProtoLog() {
        ProtoLog.e(ProtoLogGroup.TEST_GROUP,
                "Test completed successfully: %b %d %x %f %% %s",
                true, 1, 2, 0.3, "ok");
    }

    /**
     * Starts protolog for the duration of {@code runnable}, with a ProtoLogImpl instance installed.
     */
    private void runWith(IProtoLog mockInstance, Runnable runnable) {
        IProtoLog original = ProtoLog.getSingleInstance();
        ProtoLogImpl.setSingleInstance(mockInstance);
        try {
            runnable.run();
        } finally {
            ProtoLogImpl.setSingleInstance(original);
        }
    }
}
