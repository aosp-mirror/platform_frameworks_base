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

package com.android.systemui.util.settings;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.database.ContentObserver;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collection;
import java.util.Map;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class FakeSettingsTest extends SysuiTestCase {
    @Mock
    ContentObserver mContentObserver;

    private FakeSettings mFakeSettings;


    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mFakeSettings = new FakeSettings();
    }

    /**
     * Test FakeExecutor that receives non-delayed items to execute.
     */
    @Test
    public void testPutAndGet() throws Settings.SettingNotFoundException {
        mFakeSettings.putInt("foobar", 1);
        assertThat(mFakeSettings.getInt("foobar")).isEqualTo(1);
    }

    @Test
    public void testInitialize() {
        mFakeSettings = new FakeSettings("abra", "cadabra");
        assertThat(mFakeSettings.getString("abra")).isEqualTo("cadabra");
    }

    @Test
    public void testInitializeWithMap() {
        mFakeSettings = new FakeSettings(Map.of("one fish", "two fish", "red fish", "blue fish"));
        assertThat(mFakeSettings.getString("red fish")).isEqualTo("blue fish");
        assertThat(mFakeSettings.getString("one fish")).isEqualTo("two fish");
    }

    @Test
    public void testRegisterContentObserver() {
        mFakeSettings.registerContentObserverSync("cat", mContentObserver);

        mFakeSettings.putString("cat", "hat");

        verify(mContentObserver).dispatchChange(anyBoolean(), any(Collection.class), anyInt(),
                anyInt());
    }

    @Test
    public void testRegisterContentObserverAllUsers() {
        mFakeSettings.registerContentObserverForUserSync(
                mFakeSettings.getUriFor("cat"), false, mContentObserver, UserHandle.USER_ALL);

        mFakeSettings.putString("cat", "hat");

        verify(mContentObserver).dispatchChange(anyBoolean(), any(Collection.class), anyInt(),
                anyInt());
    }

    @Test
    public void testUnregisterContentObserver() {
        mFakeSettings.registerContentObserverSync("cat", mContentObserver);
        mFakeSettings.unregisterContentObserverSync(mContentObserver);

        mFakeSettings.putString("cat", "hat");

        verify(mContentObserver, never()).dispatchChange(
                anyBoolean(), any(Collection.class), anyInt());
    }

    @Test
    public void testUnregisterContentObserverAllUsers() {
        mFakeSettings.registerContentObserverForUserSync(
                mFakeSettings.getUriFor("cat"), false, mContentObserver, UserHandle.USER_ALL);
        mFakeSettings.unregisterContentObserverSync(mContentObserver);

        mFakeSettings.putString("cat", "hat");

        verify(mContentObserver, never()).dispatchChange(
                anyBoolean(), any(Collection.class), anyInt(), anyInt());
    }

    @Test
    public void testContentObserverDispatchCorrectUser() {
        int user = 10;
        mFakeSettings.registerContentObserverForUserSync(
                mFakeSettings.getUriFor("cat"), false, mContentObserver, UserHandle.USER_ALL
        );

        mFakeSettings.putStringForUser("cat", "hat", user);
        verify(mContentObserver).dispatchChange(anyBoolean(), any(Collection.class), anyInt(),
                eq(user));
    }
}
