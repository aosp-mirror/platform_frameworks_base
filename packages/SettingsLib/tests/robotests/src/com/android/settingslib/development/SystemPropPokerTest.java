/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.development;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.os.IBinder;
import android.os.Parcel;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class SystemPropPokerTest {

    @Spy
    private SystemPropPoker mSystemPropPoker;
    @Spy
    private SystemPropPoker.PokerTask mPokerTask;
    @Mock
    private IBinder mMockBinder;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        doReturn(mPokerTask).when(mSystemPropPoker).createPokerTask();
        doReturn(new String[] {"testService"}).when(mPokerTask).listServices();
        doReturn(mMockBinder).when(mPokerTask).checkService("testService");
        doReturn(true).when(mMockBinder)
                .transact(anyInt(), any(Parcel.class), nullable(Parcel.class), anyInt());
    }

    @Test
    public void testPoke() throws Exception {
        mSystemPropPoker.poke();
        verify(mMockBinder, atLeastOnce())
                .transact(anyInt(), any(Parcel.class), nullable(Parcel.class), anyInt());
    }

    @Test
    public void testPokeBlocking() throws Exception {
        mSystemPropPoker.blockPokes();
        mSystemPropPoker.poke();
        verify(mMockBinder, never())
                .transact(anyInt(), any(Parcel.class), nullable(Parcel.class), anyInt());
        mSystemPropPoker.unblockPokes();
        mSystemPropPoker.poke();
        verify(mMockBinder, atLeastOnce())
                .transact(anyInt(), any(Parcel.class), nullable(Parcel.class), anyInt());
    }
}
