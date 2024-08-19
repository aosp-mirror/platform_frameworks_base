/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.policy;

import static com.android.internal.policy.FoldLockSettingsObserver.SETTING_VALUE_DEFAULT;
import static com.android.internal.policy.FoldLockSettingsObserver.SETTING_VALUE_SLEEP_ON_FOLD;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link FoldLockSettingsObserver}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public final class FoldLockSettingsObserverTest {
    @Mock
    private Context mContext;
    @Mock
    private Handler mHandler;
    @Mock
    private ContentResolver mContentResolver;

    private FoldLockSettingsObserver mFoldLockSettingsObserver;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mFoldLockSettingsObserver =
                spy(new FoldLockSettingsObserver(mHandler, mContext));

        doReturn(mContentResolver).when(mContext).getContentResolver();
        doReturn(SETTING_VALUE_DEFAULT).when(mFoldLockSettingsObserver).request();

        mFoldLockSettingsObserver.register();
    }

    @Test
    public void shouldRegister() {
        doReturn(mContentResolver).when(mContext).getContentResolver();

        mFoldLockSettingsObserver.register();

        verify(mContentResolver).registerContentObserver(
                Settings.System.getUriFor(Settings.System.FOLD_LOCK_BEHAVIOR),
                false /*notifyForDescendants */,
                mFoldLockSettingsObserver,
                UserHandle.USER_ALL
        );
    }

    @Test
    public void shouldUnregister() {
        mFoldLockSettingsObserver.unregister();

        verify(mContentResolver).unregisterContentObserver(mFoldLockSettingsObserver);
    }

    @Test
    public void shouldCacheNewValue() {
        // Reset the mock's behavior and call count to zero.
        reset(mFoldLockSettingsObserver);
        doReturn(SETTING_VALUE_SLEEP_ON_FOLD).when(mFoldLockSettingsObserver).request();

        // Setting is DEFAULT at first.
        assertEquals(SETTING_VALUE_DEFAULT, mFoldLockSettingsObserver.mFoldLockSetting);

        // Cache new setting.
        mFoldLockSettingsObserver.requestAndCacheFoldLockSetting();

        // Check that setter was called once and change went through properly.
        verify(mFoldLockSettingsObserver).setCurrentFoldSetting(anyString());
        assertTrue(mFoldLockSettingsObserver.isSleepOnFold());
    }
}
