/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.flags;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.content.Context;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * NOTE: This test is for the version of FeatureFlagManager in src-release, which should not allow
 * overriding, and should never return any value other than the one provided as the default.
 */
@SmallTest
public class FeatureFlagManagerTest extends SysuiTestCase {
    FeatureFlagManager mFeatureFlagManager;

    @Mock private SystemPropertiesHelper mProps;
    @Mock private Context mContext;
    @Mock private DumpManager mDumpManager;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mFeatureFlagManager = new FeatureFlagManager(mProps, mContext, mDumpManager);
    }

    @After
    public void onFinished() {
        // SystemPropertiesHelper and Context are provided for constructor consistency with the
        // debug version of the FeatureFlagManager, but should never be used.
        verifyZeroInteractions(mProps, mContext);
        // The dump manager should be registered with even for the release version, but that's it.
        verify(mDumpManager).registerDumpable(anyString(), any());
        verifyNoMoreInteractions(mDumpManager);
    }

    @Test
    public void testIsEnabled() {
        mFeatureFlagManager.setEnabled(1, true);
        // Again, nothing changes.
        assertThat(mFeatureFlagManager.isEnabled(1, false)).isFalse();
    }

    @Test
    public void testDump() {
        // Even if a flag is set before
        mFeatureFlagManager.setEnabled(1, true);

        // WHEN the flags have been accessed
        assertFalse(mFeatureFlagManager.isEnabled(1, false));
        assertTrue(mFeatureFlagManager.isEnabled(2, true));

        // Even if a flag is set after
        mFeatureFlagManager.setEnabled(2, false);

        // THEN the dump contains the flags and the default values
        String dump = dumpToString();
        assertThat(dump).contains(" sysui_flag_1: false\n");
        assertThat(dump).contains(" sysui_flag_2: true\n");
    }

    private String dumpToString() {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        mFeatureFlagManager.dump(mock(FileDescriptor.class), pw, new String[0]);
        pw.flush();
        String dump = sw.toString();
        return dump;
    }
}
