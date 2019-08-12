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

package com.android.systemui.model;


import static android.view.Display.DEFAULT_DISPLAY;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@SmallTest
@RunWith(MockitoJUnitRunner.class)
public class SysUiStateTest extends SysuiTestCase {
    private static final int FLAG_1 = 1;
    private static final int FLAG_2 = 1 << 1;
    private static final int FLAG_3 = 1 << 2;
    private static final int FLAG_4 = 1 << 3;
    private static final int DISPLAY_ID = DEFAULT_DISPLAY;

    private SysUiState.SysUiStateCallback mCallback;
    private SysUiState mFlagsContainer;

    @Before
    public void setup() {
        mFlagsContainer = new SysUiState();
        mCallback = mock(SysUiState.SysUiStateCallback.class);
        mFlagsContainer.addCallback(mCallback);
    }

    @Test
    public void addSingle_setFlag() {
        setFlags(FLAG_1);

        verify(mCallback, times(1)).onSystemUiStateChanged(FLAG_1);
    }

    @Test
    public void addMultiple_setFlag() {
        setFlags(FLAG_1);
        setFlags(FLAG_2);

        verify(mCallback, times(1)).onSystemUiStateChanged(FLAG_1);
        verify(mCallback, times(1))
                .onSystemUiStateChanged(FLAG_1 | FLAG_2);
    }

    @Test
    public void addMultipleRemoveOne_setFlag() {
        setFlags(FLAG_1);
        setFlags(FLAG_2);
        mFlagsContainer.setFlag(FLAG_1, false)
                .commitUpdate(DISPLAY_ID);

        verify(mCallback, times(1)).onSystemUiStateChanged(FLAG_1);
        verify(mCallback, times(1))
                .onSystemUiStateChanged(FLAG_1 | FLAG_2);
        verify(mCallback, times(1)).onSystemUiStateChanged(FLAG_2);
    }

    @Test
    public void addMultiple_setFlags() {
        setFlags(FLAG_1, FLAG_2, FLAG_3, FLAG_4);

        int expected = FLAG_1 | FLAG_2 | FLAG_3 | FLAG_4;
        verify(mCallback, times(1)).onSystemUiStateChanged(expected);
    }

    @Test
    public void addMultipleRemoveOne_setFlags() {
        setFlags(FLAG_1, FLAG_2, FLAG_3, FLAG_4);
        mFlagsContainer.setFlag(FLAG_2, false)
                .commitUpdate(DISPLAY_ID);

        int expected1 = FLAG_1 | FLAG_2 | FLAG_3 | FLAG_4;
        verify(mCallback, times(1)).onSystemUiStateChanged(expected1);
        int expected2 = FLAG_1 | FLAG_3 | FLAG_4;
        verify(mCallback, times(1)).onSystemUiStateChanged(expected2);
    }

    @Test
    public void removeCallback() {
        mFlagsContainer.removeCallback(mCallback);
        setFlags(FLAG_1, FLAG_2, FLAG_3, FLAG_4);

        int expected = FLAG_1 | FLAG_2 | FLAG_3 | FLAG_4;
        verify(mCallback, times(0)).onSystemUiStateChanged(expected);
    }

    private void setFlags(int... flags) {
        for (int i = 0; i < flags.length; i++) {
            mFlagsContainer.setFlag(flags[i], true);
        }
        mFlagsContainer.commitUpdate(DISPLAY_ID);
    }
}
