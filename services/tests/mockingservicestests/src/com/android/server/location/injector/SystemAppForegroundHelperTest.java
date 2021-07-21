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

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import android.app.ActivityManager;
import android.content.Context;
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
public class SystemAppForegroundHelperTest {

    private static final long TIMEOUT_MS = 5000;

    @Mock private Context mContext;
    @Mock private ActivityManager mActivityManager;

    private List<ActivityManager.OnUidImportanceListener> mListeners = new ArrayList<>();

    private SystemAppForegroundHelper mHelper;

    @Before
    public void setUp() {
        initMocks(this);

        doReturn(mActivityManager).when(mContext).getSystemService(ActivityManager.class);
        doAnswer(invocation -> {
            mListeners.add(invocation.getArgument(0));
            return null;
        }).when(mActivityManager).addOnUidImportanceListener(any(
                ActivityManager.OnUidImportanceListener.class), eq(IMPORTANCE_FOREGROUND_SERVICE));

        mHelper = new SystemAppForegroundHelper(mContext);
        mHelper.onSystemReady();
    }

    private void setImportance(int uid, int importance) {
        doReturn(importance).when(mActivityManager).getUidImportance(uid);
        for (ActivityManager.OnUidImportanceListener listener : mListeners) {
            listener.onUidImportance(uid, importance);
        }
    }

    @Test
    public void testListeners() {
        AppForegroundHelper.AppForegroundListener listener = mock(
                AppForegroundHelper.AppForegroundListener.class);
        mHelper.addListener(listener);

        setImportance(0, IMPORTANCE_FOREGROUND);
        verify(listener, timeout(TIMEOUT_MS)).onAppForegroundChanged(0, true);

        setImportance(1, IMPORTANCE_FOREGROUND_SERVICE);
        verify(listener, timeout(TIMEOUT_MS)).onAppForegroundChanged(1, true);

        setImportance(2, IMPORTANCE_VISIBLE);
        verify(listener, timeout(TIMEOUT_MS)).onAppForegroundChanged(2, false);
    }

    @Test
    public void testIsAppForeground() {
        setImportance(0, IMPORTANCE_FOREGROUND);
        assertThat(mHelper.isAppForeground(0)).isEqualTo(true);

        setImportance(0, IMPORTANCE_FOREGROUND_SERVICE);
        assertThat(mHelper.isAppForeground(0)).isEqualTo(true);

        setImportance(0, IMPORTANCE_VISIBLE);
        assertThat(mHelper.isAppForeground(0)).isEqualTo(false);
    }
}
