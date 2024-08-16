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

import android.platform.test.ravenwood.RavenwoodRule;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import com.android.server.wm.WindowManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
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
    private InputMethodManagerService mMockInputMethodManagerService;

    @Mock
    private WindowManagerInternal mMockWindowManagerInternal;

    @NonNull
    private IntFunction<InputMethodBindingController> mBindingControllerFactory;

    @NonNull
    private IntFunction<ImeVisibilityStateComputer> mVisibilityStateComputerFactory;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        SecureSettingsWrapper.startTestMode();

        mBindingControllerFactory = userId ->
                new InputMethodBindingController(userId, mMockInputMethodManagerService);

        mVisibilityStateComputerFactory = userId -> new ImeVisibilityStateComputer(
                mMockInputMethodManagerService,
                new ImeVisibilityStateComputer.Injector() {
                    @NonNull
                    @Override
                    public WindowManagerInternal getWmService() {
                        return mMockWindowManagerInternal;
                    }

                    @NonNull
                    @Override
                    public InputMethodManagerService.ImeDisplayValidator getImeValidator() {
                        return displayId -> WindowManager.DISPLAY_IME_POLICY_LOCAL;
                    }

                    @Override
                    public int getUserId() {
                        return userId;
                    }
                });
    }

    @After
    public void tearDown() {
        SecureSettingsWrapper.endTestMode();
    }

    // TODO(b/352615651): Move this to end-to-end test.
    @Test
    public void testUserDataRepository_removesUserInfoOnUserRemovedEvent() {
        // Create UserDataRepository
        final var repository = new UserDataRepository(
                userId -> new InputMethodBindingController(userId, mMockInputMethodManagerService),
                mVisibilityStateComputerFactory);

        // Add one UserData ...
        final var userData = repository.getOrCreate(ANY_USER_ID);
        assertThat(userData.mUserId).isEqualTo(ANY_USER_ID);

        // ... and then call onUserRemoved
        assertThat(collectUserData(repository)).hasSize(1);
        repository.remove(ANY_USER_ID);

        // Assert UserDataRepository is now empty
        assertThat(collectUserData(repository)).isEmpty();
    }

    @Test
    public void testGetOrCreate() {
        final var repository = new UserDataRepository(mBindingControllerFactory,
                mVisibilityStateComputerFactory);

        final var userData = repository.getOrCreate(ANY_USER_ID);
        assertThat(userData.mUserId).isEqualTo(ANY_USER_ID);

        final var allUserData = collectUserData(repository);
        assertThat(allUserData).hasSize(1);
        assertThat(allUserData.get(0).mUserId).isEqualTo(ANY_USER_ID);

        // Assert UserDataRepository called the InputMethodBindingController creator function.
        assertThat(allUserData.get(0).mBindingController.getUserId()).isEqualTo(ANY_USER_ID);
        assertThat(allUserData.get(0).mVisibilityStateComputer.getUserId()).isEqualTo(ANY_USER_ID);
    }

    private List<UserData> collectUserData(UserDataRepository repository) {
        final var collected = new ArrayList<UserData>();
        repository.forAllUserData(userData -> collected.add(userData));
        return collected;
    }

}
