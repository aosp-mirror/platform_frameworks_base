/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.dreams;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.concurrent.Executor;

@SmallTest
@RunWith(AndroidJUnit4.class)
@android.platform.test.annotations.EnabledOnRavenwood
public class DreamOverlayStatusBarItemsProviderTest extends SysuiTestCase {
    @Mock
    DreamOverlayStatusBarItemsProvider.Callback mCallback;
    @Mock
    DreamOverlayStatusBarItemsProvider.StatusBarItem mStatusBarItem;

    private final Executor mMainExecutor = Runnable::run;

    DreamOverlayStatusBarItemsProvider mProvider;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mProvider = new DreamOverlayStatusBarItemsProvider(mMainExecutor);
    }

    @Test
    public void addingCallbackCallsOnStatusBarItemsChanged() {
        mProvider.addStatusBarItem(mStatusBarItem);
        mProvider.addCallback(mCallback);
        verify(mCallback).onStatusBarItemsChanged(List.of(mStatusBarItem));
    }

    @Test
    public void addingStatusBarItemCallsOnStatusBarItemsChanged() {
        mProvider.addCallback(mCallback);
        mProvider.addStatusBarItem(mStatusBarItem);
        verify(mCallback).onStatusBarItemsChanged(List.of(mStatusBarItem));
    }

    @Test
    public void addingDuplicateStatusBarItemDoesNotCallOnStatusBarItemsChanged() {
        mProvider.addCallback(mCallback);
        mProvider.addStatusBarItem(mStatusBarItem);
        mProvider.addStatusBarItem(mStatusBarItem);
        // Called only once for addStatusBarItem.
        verify(mCallback, times(1))
                .onStatusBarItemsChanged(List.of(mStatusBarItem));
    }

    @Test
    public void removingStatusBarItemCallsOnStatusBarItemsChanged() {
        mProvider.addCallback(mCallback);
        mProvider.addStatusBarItem(mStatusBarItem);
        mProvider.removeStatusBarItem(mStatusBarItem);
        // Called once for addStatusBarItem and once for removeStatusBarItem.
        verify(mCallback, times(2)).onStatusBarItemsChanged(any());
    }

    @Test
    public void removingNonexistentStatusBarItemDoesNotCallOnStatusBarItemsChanged() {
        mProvider.addCallback(mCallback);
        mProvider.removeStatusBarItem(mStatusBarItem);
        verify(mCallback, never()).onStatusBarItemsChanged(any());
    }
}
