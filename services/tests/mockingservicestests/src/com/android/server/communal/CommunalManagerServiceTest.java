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

package com.android.server.communal;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;

import android.Manifest;
import android.app.communal.ICommunalManager;
import android.content.ContextWrapper;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoSession;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.quality.Strictness;

/**
 * Test class for {@link CommunalManagerService}.
 *
 * Build/Install/Run:
 *   atest FrameworksMockingServicesTests:CommunalManagerServiceTest
 */
@RunWith(MockitoJUnitRunner.class)
@SmallTest
@Presubmit
public class CommunalManagerServiceTest {
    private MockitoSession mMockingSession;
    private CommunalManagerService mService;

    private ICommunalManager mBinder;
    private ContextWrapper mContextSpy;

    @Before
    public final void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.WARN)
                .startMocking();

        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));

        doNothing().when(mContextSpy).enforceCallingPermission(
                eq(Manifest.permission.WRITE_COMMUNAL_STATE), anyString());
        doNothing().when(mContextSpy).enforceCallingPermission(
                eq(Manifest.permission.READ_COMMUNAL_STATE), anyString());

        mService = new CommunalManagerService(mContextSpy);
        mBinder = mService.getBinderServiceInstance();
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    @Test
    public void testIsCommunalMode_isTrue() throws RemoteException {
        mBinder.setCommunalViewShowing(true);
        assertThat(mBinder.isCommunalMode()).isTrue();
    }

    @Test
    public void testIsCommunalMode_isFalse() throws RemoteException {
        mBinder.setCommunalViewShowing(false);
        assertThat(mBinder.isCommunalMode()).isFalse();
    }
}
