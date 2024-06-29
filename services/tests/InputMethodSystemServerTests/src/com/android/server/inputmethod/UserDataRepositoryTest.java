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

package com.android.server.inputmethod;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.pm.UserInfo;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.Looper;
import android.platform.test.ravenwood.RavenwoodRule;

import com.android.server.pm.UserManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

// This test is designed to run on both device and host (Ravenwood) side.
public final class UserDataRepositoryTest {

    private static final int ANY_USER_ID = 1;

    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true).build();

    @Mock
    private UserManagerInternal mMockUserManagerInternal;

    @Mock
    private InputMethodManagerService mMockInputMethodManagerService;

    private Handler mHandler;

    private IntFunction<InputMethodBindingController> mBindingControllerFactory;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        SecureSettingsWrapper.startTestMode();

        mHandler = new Handler(Looper.getMainLooper());
        mBindingControllerFactory = new IntFunction<InputMethodBindingController>() {

            @Override
            public InputMethodBindingController apply(int userId) {
                return new InputMethodBindingController(userId, mMockInputMethodManagerService);
            }
        };
    }

    @After
    public void tearDown() {
        SecureSettingsWrapper.endTestMode();
    }

    @Test
    public void testUserDataRepository_addsNewUserInfoOnUserCreatedEvent() {
        // Create UserDataRepository and capture the user lifecycle listener
        final var captor = ArgumentCaptor.forClass(UserManagerInternal.UserLifecycleListener.class);
        final var bindingControllerFactorySpy = spy(mBindingControllerFactory);
        final var repository = new UserDataRepository(mHandler,
                mMockUserManagerInternal, bindingControllerFactorySpy);

        verify(mMockUserManagerInternal, times(1)).addUserLifecycleListener(captor.capture());
        final var listener = captor.getValue();

        // Assert that UserDataRepository is empty and then call onUserCreated
        assertThat(collectUserData(repository)).isEmpty();
        final var userInfo = new UserInfo();
        userInfo.id = ANY_USER_ID;
        listener.onUserCreated(userInfo, /* unused token */ new Object());
        waitForIdle();

        // Assert UserDataRepository contains the expected UserData
        final var allUserData = collectUserData(repository);
        assertThat(allUserData).hasSize(1);
        assertThat(allUserData.get(0).mUserId).isEqualTo(ANY_USER_ID);

        // Assert UserDataRepository called the InputMethodBindingController creator function.
        verify(bindingControllerFactorySpy).apply(ANY_USER_ID);
        assertThat(allUserData.get(0).mBindingController.mUserId).isEqualTo(ANY_USER_ID);
    }

    @Test
    public void testUserDataRepository_removesUserInfoOnUserRemovedEvent() {
        // Create UserDataRepository and capture the user lifecycle listener
        final var captor = ArgumentCaptor.forClass(UserManagerInternal.UserLifecycleListener.class);
        final var repository = new UserDataRepository(mHandler,
                mMockUserManagerInternal,
                userId -> new InputMethodBindingController(userId, mMockInputMethodManagerService));

        verify(mMockUserManagerInternal, times(1)).addUserLifecycleListener(captor.capture());
        final var listener = captor.getValue();

        // Add one UserData ...
        final var userInfo = new UserInfo();
        userInfo.id = ANY_USER_ID;
        listener.onUserCreated(userInfo, /* unused token */ new Object());
        waitForIdle();
        // ... and then call onUserRemoved
        assertThat(collectUserData(repository)).hasSize(1);
        listener.onUserRemoved(userInfo);
        waitForIdle();

        // Assert UserDataRepository is now empty
        assertThat(collectUserData(repository)).isEmpty();
    }

    @Test
    public void testGetOrCreate() {
        final var repository = new UserDataRepository(mHandler,
                mMockUserManagerInternal, mBindingControllerFactory);

        synchronized (ImfLock.class) {
            final var userData = repository.getOrCreate(ANY_USER_ID);
            assertThat(userData.mUserId).isEqualTo(ANY_USER_ID);
        }

        final var allUserData = collectUserData(repository);
        assertThat(allUserData).hasSize(1);
        assertThat(allUserData.get(0).mUserId).isEqualTo(ANY_USER_ID);

        // Assert UserDataRepository called the InputMethodBindingController creator function.
        assertThat(allUserData.get(0).mBindingController.mUserId).isEqualTo(ANY_USER_ID);
    }

    private List<UserDataRepository.UserData> collectUserData(UserDataRepository repository) {
        final var collected = new ArrayList<UserDataRepository.UserData>();
        synchronized (ImfLock.class) {
            repository.forAllUserData(userData -> collected.add(userData));
        }
        return collected;
    }

    private void waitForIdle() {
        final var done = new ConditionVariable();
        mHandler.post(done::open);
        done.block();
    }
}
