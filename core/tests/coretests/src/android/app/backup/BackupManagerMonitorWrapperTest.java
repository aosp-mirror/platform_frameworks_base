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

package android.app.backup;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.os.Bundle;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class BackupManagerMonitorWrapperTest {
    @Mock
    private BackupManagerMonitor mMonitor;
    private BackupManagerMonitorWrapper mMonitorWrapper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testOnEvent_propagatesToMonitor() throws Exception {
        mMonitorWrapper = new BackupManagerMonitorWrapper(mMonitor);
        Bundle eventBundle = new Bundle();

        mMonitorWrapper.onEvent(eventBundle);

        verify(mMonitor, times(/* wantedNumberOfInvocations */ 1)).onEvent(eq(eventBundle));
    }

    @Test
    public void testOnEvent_nullMonitor_eventIsIgnored() throws Exception {
        mMonitorWrapper = new BackupManagerMonitorWrapper(/* monitor */ null);

        mMonitorWrapper.onEvent(new Bundle());

        verify(mMonitor, never()).onEvent(any());
    }
}
