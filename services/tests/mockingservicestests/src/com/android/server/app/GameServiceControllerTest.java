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

package com.android.server.app;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.ConcurrentUtils;
import com.android.server.SystemService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;


/** Unit tests for {@link GameServiceController}. */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public final class GameServiceControllerTest {
    private static final UserHandle USER_HANDLE_10 = new UserHandle(10);
    private static final UserHandle USER_HANDLE_11 = new UserHandle(11);
    private static final SystemService.TargetUser USER_10 = user(10);
    private static final SystemService.TargetUser USER_11 = user(11);
    private static final String PROVIDER_A_PACKAGE_NAME = "com.provider.a";
    private static final ComponentName PROVIDER_A_SERVICE_A =
            new ComponentName(PROVIDER_A_PACKAGE_NAME, "com.provider.a.ServiceA");
    private static final ComponentName PROVIDER_A_SERVICE_B =
            new ComponentName(PROVIDER_A_PACKAGE_NAME, "com.provider.a.ServiceB");

    private MockitoSession mMockingSession;
    private GameServiceController mGameServiceManager;
    @Mock
    private GameServiceProviderSelector mMockGameServiceProviderSelector;
    @Mock
    private GameServiceProviderInstanceFactory mMockGameServiceProviderInstanceFactory;

    @Before
    public void setUp() throws Exception {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .startMocking();

        mGameServiceManager = new GameServiceController(
                ConcurrentUtils.DIRECT_EXECUTOR,
                mMockGameServiceProviderSelector,
                mMockGameServiceProviderInstanceFactory);
    }

    @After
    public void tearDown() {
        mMockingSession.finishMocking();
    }

    @Test
    public void notifyUserStarted_hasNotCompletedBoot_doesNothing() {
        mGameServiceManager.notifyUserStarted(USER_10);

        verifyNoMoreInteractions(mMockGameServiceProviderInstanceFactory);
    }

    @Test
    public void notifyUserStarted_createsAndStartsNewInstance() {
        GameServiceProviderConfiguration configurationA =
                new GameServiceProviderConfiguration(USER_HANDLE_10, PROVIDER_A_SERVICE_A,
                        PROVIDER_A_SERVICE_B);
        FakeGameServiceProviderInstance instanceA =
                seedConfigurationForUser(USER_10, configurationA);

        mGameServiceManager.onBootComplete();
        mGameServiceManager.notifyUserStarted(USER_10);

        verify(mMockGameServiceProviderInstanceFactory).create(configurationA);
        verifyNoMoreInteractions(mMockGameServiceProviderInstanceFactory);
        assertThat(instanceA.getIsRunning()).isTrue();
    }

    @Test
    public void notifyUserStarted_sameUser_doesNotCreateNewInstance() {
        GameServiceProviderConfiguration configurationA =
                new GameServiceProviderConfiguration(USER_HANDLE_10, PROVIDER_A_SERVICE_A,
                        PROVIDER_A_SERVICE_B);
        FakeGameServiceProviderInstance instanceA =
                seedConfigurationForUser(USER_10, configurationA);

        mGameServiceManager.onBootComplete();
        mGameServiceManager.notifyUserStarted(USER_10);
        mGameServiceManager.notifyUserStarted(USER_10);

        verify(mMockGameServiceProviderInstanceFactory).create(configurationA);
        verifyNoMoreInteractions(mMockGameServiceProviderInstanceFactory);
        assertThat(instanceA.getIsRunning()).isTrue();
    }

    @Test
    public void notifyUserUnlocking_noForegroundUser_ignores() {
        GameServiceProviderConfiguration configurationA =
                new GameServiceProviderConfiguration(USER_HANDLE_10, PROVIDER_A_SERVICE_A,
                        PROVIDER_A_SERVICE_B);
        FakeGameServiceProviderInstance instanceA =
                seedConfigurationForUser(USER_10, configurationA);

        mGameServiceManager.onBootComplete();
        mGameServiceManager.notifyUserUnlocking(USER_10);

        verifyNoMoreInteractions(mMockGameServiceProviderInstanceFactory);
        assertThat(instanceA.getIsRunning()).isFalse();
    }

    @Test
    public void notifyUserUnlocking_sameAsForegroundUser_evaluatesProvider() {
        GameServiceProviderConfiguration configurationA =
                new GameServiceProviderConfiguration(USER_HANDLE_10, PROVIDER_A_SERVICE_A,
                        PROVIDER_A_SERVICE_B);
        seedNoConfigurationForUser(USER_10);

        mGameServiceManager.onBootComplete();
        mGameServiceManager.notifyUserStarted(USER_10);
        FakeGameServiceProviderInstance instanceA =
                seedConfigurationForUser(USER_10, configurationA);
        mGameServiceManager.notifyUserUnlocking(USER_10);

        verify(mMockGameServiceProviderInstanceFactory).create(configurationA);
        verifyNoMoreInteractions(mMockGameServiceProviderInstanceFactory);
        assertThat(instanceA.getIsRunning()).isTrue();
    }

    @Test
    public void notifyUserUnlocking_differentFromForegroundUser_ignores() {
        GameServiceProviderConfiguration configurationA =
                new GameServiceProviderConfiguration(USER_HANDLE_10, PROVIDER_A_SERVICE_A,
                        PROVIDER_A_SERVICE_B);
        seedNoConfigurationForUser(USER_10);

        mGameServiceManager.onBootComplete();
        mGameServiceManager.notifyUserStarted(USER_10);
        FakeGameServiceProviderInstance instanceA =
                seedConfigurationForUser(USER_11, configurationA);
        mGameServiceManager.notifyUserUnlocking(USER_11);

        verifyNoMoreInteractions(mMockGameServiceProviderInstanceFactory);
        assertThat(instanceA.getIsRunning()).isFalse();
    }

    @Test
    public void
            notifyNewForegroundUser_differentUser_stopsPreviousInstanceAndThenStartsNewInstance() {
        GameServiceProviderConfiguration configurationA =
                new GameServiceProviderConfiguration(USER_HANDLE_10, PROVIDER_A_SERVICE_A,
                        PROVIDER_A_SERVICE_B);
        FakeGameServiceProviderInstance instanceA =
                seedConfigurationForUser(USER_10, configurationA);
        GameServiceProviderConfiguration configurationB =
                new GameServiceProviderConfiguration(USER_HANDLE_11, PROVIDER_A_SERVICE_A,
                        PROVIDER_A_SERVICE_B);
        FakeGameServiceProviderInstance instanceB = seedConfigurationForUser(USER_11,
                configurationB);
        InOrder instancesInOrder = Mockito.inOrder(instanceA, instanceB);

        mGameServiceManager.onBootComplete();
        mGameServiceManager.notifyUserStarted(USER_10);
        mGameServiceManager.notifyNewForegroundUser(USER_11);

        verify(mMockGameServiceProviderInstanceFactory).create(configurationA);
        verify(mMockGameServiceProviderInstanceFactory).create(configurationB);
        instancesInOrder.verify(instanceA).start();
        instancesInOrder.verify(instanceA).stop();
        instancesInOrder.verify(instanceB).start();
        verifyNoMoreInteractions(mMockGameServiceProviderInstanceFactory);
        assertThat(instanceA.getIsRunning()).isFalse();
        assertThat(instanceB.getIsRunning()).isTrue();
    }

    private void seedNoConfigurationForUser(SystemService.TargetUser user) {
        when(mMockGameServiceProviderSelector.get(user)).thenReturn(null);
    }

    private FakeGameServiceProviderInstance seedConfigurationForUser(SystemService.TargetUser user,
            GameServiceProviderConfiguration configuration) {
        when(mMockGameServiceProviderSelector.get(user)).thenReturn(configuration);
        FakeGameServiceProviderInstance instanceForConfiguration =
                spy(new FakeGameServiceProviderInstance());
        when(mMockGameServiceProviderInstanceFactory.create(configuration))
                .thenReturn(instanceForConfiguration);

        return instanceForConfiguration;
    }

    private static SystemService.TargetUser user(int userId) {
        UserInfo userInfo = new UserInfo(userId, "", "", UserInfo.FLAG_FULL);
        return new SystemService.TargetUser(userInfo);
    }
}
