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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.protolog.ProtoLogImpl;

import org.junit.After;
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

    @Test
    public void testProtoLogToolIntegration() {
        ProtoLogImpl mockedProtoLog = mock(ProtoLogImpl.class);
        ProtoLogImpl.setSingleInstance(mockedProtoLog);
        ProtoLogGroup.testProtoLog();
        verify(mockedProtoLog).log(eq(ProtoLogImpl.LogLevel.ERROR), eq(
                ProtoLogGroup.TEST_GROUP),
                eq(485522692), eq(0b0010101001010111),
                eq(ProtoLogGroup.TEST_GROUP.isLogToLogcat()
                        ? "Test completed successfully: %b %d %o %x %e %g %f %% %s"
                        : null),
                eq(new Object[]{true, 1L, 2L, 3L, 0.4, 0.5, 0.6, "ok"}));
    }
}
