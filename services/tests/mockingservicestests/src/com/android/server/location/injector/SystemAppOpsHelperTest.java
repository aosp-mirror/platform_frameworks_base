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
package com.android.server.location.injector;

import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_IGNORED;
import static android.app.AppOpsManager.OP_COARSE_LOCATION;
import static android.app.AppOpsManager.OP_FINE_LOCATION;
import static android.app.AppOpsManager.OP_MOCK_LOCATION;
import static android.app.AppOpsManager.OP_MONITOR_LOCATION;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;
import static org.testng.Assert.assertThrows;

import android.app.AppOpsManager;
import android.content.Context;
import android.location.util.identity.CallerIdentity;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SystemAppOpsHelperTest {

    private static final long TIMEOUT_MS = 5000;

    @Mock private Context mContext;
    @Mock private AppOpsManager mAppOps;

    private List<AppOpsManager.OnOpChangedListener> mListeners = new ArrayList<>();

    private SystemAppOpsHelper mHelper;

    @Before
    public void setUp() {
        initMocks(this);

        doReturn(mAppOps).when(mContext).getSystemService(AppOpsManager.class);
        doAnswer(invocation -> mListeners.add(invocation.getArgument(3))).when(mAppOps)
                .startWatchingMode(
                        eq(AppOpsManager.OP_COARSE_LOCATION),
                        isNull(),
                        eq(AppOpsManager.WATCH_FOREGROUND_CHANGES),
                        any(AppOpsManager.OnOpChangedListener.class));

        mHelper = new SystemAppOpsHelper(mContext);
        mHelper.onSystemReady();
    }

    private void sendAppOp(String packageName) {
        for (AppOpsManager.OnOpChangedListener listener : mListeners) {
            listener.onOpChanged(AppOpsManager.OPSTR_COARSE_LOCATION, packageName);
        }
    }

    @Test
    public void testListener() {
        AppOpsHelper.LocationAppOpListener listener = mock(
                AppOpsHelper.LocationAppOpListener.class);
        mHelper.addListener(listener);

        sendAppOp("mypackage1");
        verify(listener, timeout(TIMEOUT_MS)).onAppOpsChanged("mypackage1");

        sendAppOp("mypackage2");
        verify(listener, timeout(TIMEOUT_MS)).onAppOpsChanged("mypackage2");
    }

    @Test
    public void testCheckOp() {
        CallerIdentity identity = CallerIdentity.forTest(1000, 1000, "mypackage", "myfeature");

        doReturn(MODE_ALLOWED).when(
                mAppOps).checkOpNoThrow(eq(OP_FINE_LOCATION), eq(1000), eq("mypackage"));
        assertThat(mHelper.checkOpNoThrow(OP_FINE_LOCATION, identity)).isTrue();

        doReturn(MODE_IGNORED).when(
                mAppOps).checkOpNoThrow(eq(OP_FINE_LOCATION), eq(1000), eq("mypackage"));
        assertThat(mHelper.checkOpNoThrow(OP_FINE_LOCATION, identity)).isFalse();

        identity = CallerIdentity.forTest(1000, 1000, "mypackage", "myfeature");

        doReturn(MODE_ALLOWED).when(
                mAppOps).checkOpNoThrow(eq(OP_COARSE_LOCATION), eq(1000), eq("mypackage"));
        assertThat(mHelper.checkOpNoThrow(OP_COARSE_LOCATION, identity)).isTrue();

        doReturn(MODE_IGNORED).when(
                mAppOps).checkOpNoThrow(eq(OP_COARSE_LOCATION), eq(1000), eq("mypackage"));
        assertThat(mHelper.checkOpNoThrow(OP_COARSE_LOCATION, identity)).isFalse();
    }

    @Test
    public void testNoteOpNoThrow() {
        CallerIdentity identity = CallerIdentity.forTest(1000, 1000, "mypackage", "myfeature");

        doReturn(MODE_ALLOWED).when(
                mAppOps).noteOpNoThrow(eq(OP_FINE_LOCATION), eq(1000), eq("mypackage"),
                        eq("myfeature"), nullable(String.class));
        assertThat(mHelper.noteOpNoThrow(OP_FINE_LOCATION, identity)).isTrue();

        doReturn(MODE_IGNORED).when(
                mAppOps).noteOpNoThrow(eq(OP_FINE_LOCATION), eq(1000), eq("mypackage"),
                        eq("myfeature"), nullable(String.class));
        assertThat(mHelper.noteOpNoThrow(OP_FINE_LOCATION, identity)).isFalse();


        identity = CallerIdentity.forTest(1000, 1000, "mypackage", "myfeature");

        doReturn(MODE_ALLOWED).when(
                mAppOps).noteOpNoThrow(eq(OP_COARSE_LOCATION), eq(1000), eq("mypackage"),
                        eq("myfeature"), nullable(String.class));
        assertThat(mHelper.noteOpNoThrow(OP_COARSE_LOCATION, identity)).isTrue();

        doReturn(MODE_IGNORED).when(
                mAppOps).noteOpNoThrow(eq(OP_COARSE_LOCATION), eq(1000), eq("mypackage"),
                        eq("myfeature"), nullable(String.class));
        assertThat(mHelper.noteOpNoThrow(OP_COARSE_LOCATION, identity)).isFalse();
    }

    @Test
    public void testStartOp() {
        CallerIdentity identity = CallerIdentity.forTest(1000, 1000, "mypackage", "myfeature");

        doReturn(MODE_ALLOWED).when(
                mAppOps).startOpNoThrow(eq(OP_MONITOR_LOCATION), eq(1000), eq("mypackage"),
                        eq(false), eq("myfeature"), nullable(String.class));
        assertThat(mHelper.startOpNoThrow(OP_MONITOR_LOCATION, identity)).isTrue();

        doReturn(MODE_IGNORED).when(
                mAppOps).startOpNoThrow(eq(OP_MONITOR_LOCATION), eq(1000), eq("mypackage"),
                        eq(false), eq("myfeature"), nullable(String.class));
        assertThat(mHelper.startOpNoThrow(OP_MONITOR_LOCATION, identity)).isFalse();
    }

    @Test
    public void testFinishOp() {
        CallerIdentity identity = CallerIdentity.forTest(1000, 1000, "mypackage", "myfeature");

        mHelper.finishOp(OP_MONITOR_LOCATION, identity);
        verify(mAppOps).finishOp(OP_MONITOR_LOCATION, 1000, "mypackage", "myfeature");
    }

    @Test
    public void testNoteOp() {
        CallerIdentity identity = CallerIdentity.forTest(1000, 1000, "mypackage", "myfeature");

        doReturn(MODE_ALLOWED).when(
                mAppOps).noteOp(eq(OP_MOCK_LOCATION), eq(1000), eq("mypackage"), eq("myfeature"),
                        nullable(String.class));
        assertThat(mHelper.noteOp(OP_MOCK_LOCATION, identity)).isTrue();

        doReturn(MODE_IGNORED).when(
                mAppOps).noteOp(eq(OP_MOCK_LOCATION), eq(1000), eq("mypackage"), eq("myfeature"),
                        nullable(String.class));
        assertThat(mHelper.noteOp(OP_MOCK_LOCATION, identity)).isFalse();


        doThrow(new SecurityException()).when(
                mAppOps).noteOp(eq(OP_MOCK_LOCATION), eq(1000), eq("mypackage"), eq("myfeature"),
                nullable(String.class));
        assertThrows(SecurityException.class, () -> mHelper.noteOp(OP_MOCK_LOCATION, identity));
    }
}
