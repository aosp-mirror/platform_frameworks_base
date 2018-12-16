/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm;

import static android.os.Process.FIRST_APPLICATION_UID;
import static android.os.Process.NFC_UID;
import static android.os.Process.SYSTEM_UID;
import static android.os.UserHandle.USER_ALL;
import static android.os.UserHandle.USER_SYSTEM;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.UserHandle;
import android.util.SparseBooleanArray;

import com.android.server.wm.LockTaskController.LockTaskToken;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;

public class KeyguardDisableHandlerTest {

    private KeyguardDisableHandler mKeyguardDisable;

    private boolean mKeyguardEnabled;
    private SparseBooleanArray mKeyguardSecure = new SparseBooleanArray();
    private SparseBooleanArray mDpmRequiresPassword = new SparseBooleanArray();

    @Before
    public void setUp() throws Exception {
        mKeyguardEnabled = true;

        mKeyguardDisable = new KeyguardDisableHandler(new KeyguardDisableHandler.Injector() {
            @Override
            public boolean dpmRequiresPassword(int userId) {
                return mDpmRequiresPassword.get(userId);
            }

            @Override
            public boolean isKeyguardSecure(int userId) {
                return mKeyguardSecure.get(userId);
            }

            @Override
            public int getProfileParentId(int userId) {
                return userId;
            }

            @Override
            public void enableKeyguard(boolean enabled) {
                mKeyguardEnabled = enabled;
            }
        }, mock(Handler.class)) {
            @Override
            public void disableKeyguard(IBinder token, String tag, int callingUid, int userId) {
                super.disableKeyguard(token, tag, callingUid, userId);
                // In the actual code, the update is posted to the handler thread. Eagerly update
                // here to simplify the test.
                updateKeyguardEnabled(userId);
            }

            @Override
            public void reenableKeyguard(IBinder token, int callingUid, int userId) {
                super.reenableKeyguard(token, callingUid, userId);
                // In the actual code, the update is posted to the handler thread. Eagerly update
                // here to simplify the test.
                updateKeyguardEnabled(userId);
            }
        };
    }

    @Test
    public void starts_enabled() {
        assertTrue(mKeyguardEnabled);
        mKeyguardDisable.updateKeyguardEnabled(USER_ALL);
        assertTrue(mKeyguardEnabled);
    }

    @Test
    public void disable_fromApp_disables() {
        mKeyguardDisable.disableKeyguard(new Binder(), "Tag", FIRST_APPLICATION_UID, USER_SYSTEM);
        assertFalse(mKeyguardEnabled);
    }

    @Test
    public void disable_fromApp_secondaryUser_disables() {
        mKeyguardDisable.setCurrentUser(1);
        mKeyguardDisable.disableKeyguard(new Binder(), "Tag",
                UserHandle.getUid(1, FIRST_APPLICATION_UID), 1);
        assertFalse(mKeyguardEnabled);
    }

    @Test
    public void disable_fromSystem_LockTask_disables() {
        mKeyguardDisable.disableKeyguard(createLockTaskToken(), "Tag", SYSTEM_UID, USER_SYSTEM);
        assertFalse(mKeyguardEnabled);
    }

    @Test
    public void disable_fromSystem_genericToken_fails() {
        try {
            mKeyguardDisable.disableKeyguard(new Binder(), "Tag", SYSTEM_UID, USER_SYSTEM);
            fail("Expected exception not thrown");
        } catch (UnsupportedOperationException e) {
            assertThat(e.getMessage(), containsString("Only apps can use the KeyguardLock API"));
        }
        assertTrue(mKeyguardEnabled);
    }

    @Test
    public void disable_fromNonApp_genericToken_fails() {
        try {
            mKeyguardDisable.disableKeyguard(new Binder(), "Tag", NFC_UID, USER_SYSTEM);
            fail("Expected exception not thrown");
        } catch (UnsupportedOperationException e) {
            assertThat(e.getMessage(), containsString("Only apps can use the KeyguardLock API"));
        }
        assertTrue(mKeyguardEnabled);
    }

    @Test
    public void disable_fromApp_secure_staysEnabled() {
        configureIsSecure(true, USER_SYSTEM);
        mKeyguardDisable.disableKeyguard(new Binder(), "Tag", FIRST_APPLICATION_UID, USER_SYSTEM);
        assertTrue(mKeyguardEnabled);
    }

    @Test
    public void disable_fromApp_dpmRequiresPassword_staysEnabled() {
        configureDpmRequiresPassword(true, USER_SYSTEM);
        mKeyguardDisable.disableKeyguard(new Binder(), "Tag", FIRST_APPLICATION_UID, USER_SYSTEM);
        assertTrue(mKeyguardEnabled);
    }

    @Test
    public void disable_fromSystem_LockTask_secure_disables() {
        configureIsSecure(true, USER_SYSTEM);
        mKeyguardDisable.disableKeyguard(createLockTaskToken(), "Tag", SYSTEM_UID, USER_SYSTEM);
        assertFalse(mKeyguardEnabled);
    }

    @Test
    public void disable_fromSystem_LockTask_requiresDpm_staysEnabled() {
        configureDpmRequiresPassword(true, USER_SYSTEM);
        mKeyguardDisable.disableKeyguard(createLockTaskToken(), "Tag", SYSTEM_UID, USER_SYSTEM);
        assertTrue(mKeyguardEnabled);
    }

    @Test
    public void disable_fromApp_thenSecure_reenables() {
        mKeyguardDisable.disableKeyguard(new Binder(), "Tag", FIRST_APPLICATION_UID, USER_SYSTEM);
        configureIsSecure(true, USER_SYSTEM);
        assertTrue(mKeyguardEnabled);
    }

    @Test
    public void disable_fromSystem_LockTask_thenRequiresDpm_reenables() {
        mKeyguardDisable.disableKeyguard(createLockTaskToken(), "Tag", SYSTEM_UID, USER_SYSTEM);
        configureDpmRequiresPassword(true, USER_SYSTEM);
        assertTrue(mKeyguardEnabled);
    }

    @Test
    public void user_switch_to_enabledUser_applies_enabled() {
        mKeyguardDisable.disableKeyguard(createLockTaskToken(), "Tag", SYSTEM_UID, USER_SYSTEM);
        assertFalse("test setup failed", mKeyguardEnabled);
        mKeyguardDisable.setCurrentUser(1);
        assertTrue(mKeyguardEnabled);
    }

    @Test
    public void user_switch_to_disabledUser_applies_disabled() {
        mKeyguardDisable.disableKeyguard(createLockTaskToken(), "Tag",
                SYSTEM_UID, 1);
        assertTrue("test setup failed", mKeyguardEnabled);
        mKeyguardDisable.setCurrentUser(1);
        assertFalse(mKeyguardEnabled);
    }

    private void configureIsSecure(boolean secure, int userId) {
        mKeyguardSecure.put(userId, secure);
        mKeyguardDisable.updateKeyguardEnabled(userId);
    }

    private void configureDpmRequiresPassword(boolean requiresPassword, int userId) {
        mDpmRequiresPassword.put(userId, requiresPassword);
        mKeyguardDisable.updateKeyguardEnabled(userId);
    }

    private LockTaskToken createLockTaskToken() {
        try {
            final Constructor<LockTaskToken> constructor =
                    LockTaskToken.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
