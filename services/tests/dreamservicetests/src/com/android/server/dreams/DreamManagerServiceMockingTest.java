/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.server.dreams;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.app.ActivityManagerInternal;
import android.content.ContextWrapper;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.os.PowerManagerInternal;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.FlakyTest;

import com.android.server.LocalServices;
import com.android.server.SystemService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

/**
 * Collection of tests for exercising the {@link DreamManagerService} lifecycle.
 */
public class DreamManagerServiceMockingTest {
    private ContextWrapper mContextSpy;
    private Resources mResourcesSpy;

    @Mock
    private ActivityManagerInternal mActivityManagerInternalMock;

    @Mock
    private PowerManagerInternal mPowerManagerInternalMock;

    @Mock
    private UserManager mUserManagerMock;

    private MockitoSession mMockitoSession;

    private static <T> void addLocalServiceMock(Class<T> clazz, T mock) {
        LocalServices.removeServiceForTest(clazz);
        LocalServices.addService(clazz, mock);
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));
        mResourcesSpy = spy(mContextSpy.getResources());
        when(mContextSpy.getResources()).thenReturn(mResourcesSpy);

        addLocalServiceMock(ActivityManagerInternal.class, mActivityManagerInternalMock);
        addLocalServiceMock(PowerManagerInternal.class, mPowerManagerInternalMock);

        when(mContextSpy.getSystemService(UserManager.class)).thenReturn(mUserManagerMock);
        mMockitoSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .mockStatic(Settings.Secure.class)
                .startMocking();
    }

    @After
    public void tearDown() throws Exception {
        mMockitoSession.finishMocking();
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        LocalServices.removeServiceForTest(PowerManagerInternal.class);
    }

    private DreamManagerService createService() {
        return new DreamManagerService(mContextSpy);
    }

    @Test
    @FlakyTest(bugId = 293443309)
    public void testSettingsQueryUserChange() {
        final DreamManagerService service = createService();

        final SystemService.TargetUser from =
                new SystemService.TargetUser(mock(UserInfo.class));
        final SystemService.TargetUser to =
                new SystemService.TargetUser(mock(UserInfo.class));

        service.onUserSwitching(from, to);

        verify(() -> Settings.Secure.getIntForUser(any(),
                eq(Settings.Secure.SCREENSAVER_ENABLED),
                anyInt(),
                eq(UserHandle.USER_CURRENT)));
    }
}
