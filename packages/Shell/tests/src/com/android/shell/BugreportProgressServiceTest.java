/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.shell;

import static com.android.shell.BugreportProgressService.findSendToAccount;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;

import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.os.UserHandle;
import android.os.UserManager;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.test.mock.MockContext;
import android.util.Pair;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.List;

/**
 * Test for {@link BugreportProgressServiceTest}.
 *
 * Usage:
   adb shell am instrument -w \
     -e class com.android.shell.BugreportProgressServiceTest \
     com.android.shell.tests
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BugreportProgressServiceTest {
    private static class MyContext extends MockContext {
        @Mock
        public UserManager userManager;

        @Mock
        public AccountManager accountManager;

        public MyContext() {
            MockitoAnnotations.initMocks(this);
        }

        @Override
        public Object getSystemService(String name) {
            switch (name) {
                case Context.USER_SERVICE:
                    return userManager;
                case Context.ACCOUNT_SERVICE:
                    return accountManager;
                default:
                    return super.getSystemService(name);
            }
        }

        @Override
        public String getSystemServiceName(Class<?> serviceClass) {
            if (UserManager.class.equals(serviceClass)) {
                return Context.USER_SERVICE;
            }
            if (AccountManager.class.equals(serviceClass)) {
                return Context.ACCOUNT_SERVICE;
            }
            return super.getSystemServiceName(serviceClass);
        }
    }

    private final MyContext mTestContext = new MyContext();

    private static <T> List<T> list(T... array) {
        return Arrays.asList(array);
    }

    private static <T> T[] array(T... array) {
        return array;
    }

    private Account account(String email) {
        return new Account(email, "test.com");
    }

    private void checkFindSendToAccount(
            int expectedUserId, String expectedEmail, String preferredDomain) {
        final Pair<UserHandle, Account> actual = findSendToAccount(mTestContext, preferredDomain);
        assertEquals(UserHandle.of(expectedUserId), actual.first);
        assertEquals(account(expectedEmail), actual.second);
    }

    @Test
    public void findSendToAccount_noWorkProfile() {
        when(mTestContext.userManager.getUserProfiles()).thenReturn(
                list(UserHandle.of(UserHandle.USER_SYSTEM)));

        // No accounts.
        when(mTestContext.accountManager.getAccountsAsUser(eq(UserHandle.USER_SYSTEM))).thenReturn(
                array());

        assertNull(findSendToAccount(mTestContext, null));
        assertNull(findSendToAccount(mTestContext, ""));
        assertNull(findSendToAccount(mTestContext, "android.com"));
        assertNull(findSendToAccount(mTestContext, "@android.com"));

        // 1 account
        when(mTestContext.accountManager.getAccountsAsUser(eq(UserHandle.USER_SYSTEM))).thenReturn(
                array(account("abc@gmail.com")));

        checkFindSendToAccount(0, "abc@gmail.com", null);
        checkFindSendToAccount(0, "abc@gmail.com", "");
        checkFindSendToAccount(0, "abc@gmail.com", "android.com");
        checkFindSendToAccount(0, "abc@gmail.com", "@android.com");
        checkFindSendToAccount(0, "abc@gmail.com", "gmail.com");
        checkFindSendToAccount(0, "abc@gmail.com", "@gmail.com");

        // 2 accounts, same domain
        when(mTestContext.accountManager.getAccountsAsUser(eq(UserHandle.USER_SYSTEM))).thenReturn(
                array(account("abc@gmail.com"), account("def@gmail.com")));

        checkFindSendToAccount(0, "abc@gmail.com", null);
        checkFindSendToAccount(0, "abc@gmail.com", "");
        checkFindSendToAccount(0, "abc@gmail.com", "android.com");
        checkFindSendToAccount(0, "abc@gmail.com", "@android.com");
        checkFindSendToAccount(0, "abc@gmail.com", "gmail.com");
        checkFindSendToAccount(0, "abc@gmail.com", "@gmail.com");

        // 2 accounts, different domains
        when(mTestContext.accountManager.getAccountsAsUser(eq(UserHandle.USER_SYSTEM))).thenReturn(
                array(account("abc@gmail.com"), account("def@android.com")));

        checkFindSendToAccount(0, "abc@gmail.com", null);
        checkFindSendToAccount(0, "abc@gmail.com", "");
        checkFindSendToAccount(0, "def@android.com", "android.com");
        checkFindSendToAccount(0, "def@android.com", "@android.com");
        checkFindSendToAccount(0, "abc@gmail.com", "gmail.com");
        checkFindSendToAccount(0, "abc@gmail.com", "@gmail.com");

        // Plut an account that doesn't look like an email address.
        when(mTestContext.accountManager.getAccountsAsUser(eq(UserHandle.USER_SYSTEM))).thenReturn(
                array(account("notemail"), account("abc@gmail.com"), account("def@android.com")));

        checkFindSendToAccount(0, "abc@gmail.com", null);
        checkFindSendToAccount(0, "abc@gmail.com", "");
        checkFindSendToAccount(0, "def@android.com", "android.com");
        checkFindSendToAccount(0, "def@android.com", "@android.com");
        checkFindSendToAccount(0, "abc@gmail.com", "gmail.com");
        checkFindSendToAccount(0, "abc@gmail.com", "@gmail.com");
    }

    /**
     * Same as {@link #findSendToAccount_noWorkProfile()}, but with work profile, which has no
     * accounts.  The expected results are the same as the original.
     */
    @Test
    public void findSendToAccount_withWorkProfile_noAccounts() {
        when(mTestContext.userManager.getUserProfiles()).thenReturn(
                list(UserHandle.of(UserHandle.USER_SYSTEM), UserHandle.of(10)));

        // Work profile has no accounts
        when(mTestContext.accountManager.getAccountsAsUser(eq(10))).thenReturn(
                array());

        // No accounts.
        when(mTestContext.accountManager.getAccountsAsUser(eq(UserHandle.USER_SYSTEM))).thenReturn(
                array());

        assertNull(findSendToAccount(mTestContext, null));
        assertNull(findSendToAccount(mTestContext, ""));
        assertNull(findSendToAccount(mTestContext, "android.com"));
        assertNull(findSendToAccount(mTestContext, "@android.com"));

        // 1 account
        when(mTestContext.accountManager.getAccountsAsUser(eq(UserHandle.USER_SYSTEM))).thenReturn(
                array(account("abc@gmail.com")));

        checkFindSendToAccount(0, "abc@gmail.com", null);
        checkFindSendToAccount(0, "abc@gmail.com", "");
        checkFindSendToAccount(0, "abc@gmail.com", "android.com");
        checkFindSendToAccount(0, "abc@gmail.com", "@android.com");
        checkFindSendToAccount(0, "abc@gmail.com", "gmail.com");
        checkFindSendToAccount(0, "abc@gmail.com", "@gmail.com");

        // 2 accounts, same domain
        when(mTestContext.accountManager.getAccountsAsUser(eq(UserHandle.USER_SYSTEM))).thenReturn(
                array(account("abc@gmail.com"), account("def@gmail.com")));

        checkFindSendToAccount(0, "abc@gmail.com", null);
        checkFindSendToAccount(0, "abc@gmail.com", "");
        checkFindSendToAccount(0, "abc@gmail.com", "android.com");
        checkFindSendToAccount(0, "abc@gmail.com", "@android.com");
        checkFindSendToAccount(0, "abc@gmail.com", "gmail.com");
        checkFindSendToAccount(0, "abc@gmail.com", "@gmail.com");

        // 2 accounts, different domains
        when(mTestContext.accountManager.getAccountsAsUser(eq(UserHandle.USER_SYSTEM))).thenReturn(
                array(account("abc@gmail.com"), account("def@android.com")));

        checkFindSendToAccount(0, "abc@gmail.com", null);
        checkFindSendToAccount(0, "abc@gmail.com", "");
        checkFindSendToAccount(0, "def@android.com", "android.com");
        checkFindSendToAccount(0, "def@android.com", "@android.com");
        checkFindSendToAccount(0, "abc@gmail.com", "gmail.com");
        checkFindSendToAccount(0, "abc@gmail.com", "@gmail.com");

        // Plut an account that doesn't look like an email address.
        when(mTestContext.accountManager.getAccountsAsUser(eq(UserHandle.USER_SYSTEM))).thenReturn(
                array(account("notemail"), account("abc@gmail.com"), account("def@android.com")));

        checkFindSendToAccount(0, "abc@gmail.com", null);
        checkFindSendToAccount(0, "abc@gmail.com", "");
        checkFindSendToAccount(0, "def@android.com", "android.com");
        checkFindSendToAccount(0, "def@android.com", "@android.com");
        checkFindSendToAccount(0, "abc@gmail.com", "gmail.com");
        checkFindSendToAccount(0, "abc@gmail.com", "@gmail.com");
    }

    /**
     * Same as {@link #findSendToAccount_noWorkProfile()}, but with work profile, which has
     * 1 account.  The expected results are the same as the original, expect for the "no accounts
     * on the primary profile" case.
     */
    @Test
    public void findSendToAccount_withWorkProfile_1account() {
        when(mTestContext.userManager.getUserProfiles()).thenReturn(
                list(UserHandle.of(UserHandle.USER_SYSTEM), UserHandle.of(10)));

        // Work profile has no accounts
        when(mTestContext.accountManager.getAccountsAsUser(eq(10))).thenReturn(
                array(account("xyz@gmail.com")));

        // No accounts.
        when(mTestContext.accountManager.getAccountsAsUser(eq(UserHandle.USER_SYSTEM))).thenReturn(
                array());

        checkFindSendToAccount(10, "xyz@gmail.com", null);
        checkFindSendToAccount(10, "xyz@gmail.com", "");
        checkFindSendToAccount(10, "xyz@gmail.com", "android.com");
        checkFindSendToAccount(10, "xyz@gmail.com", "@android.com");

        // 1 account
        when(mTestContext.accountManager.getAccountsAsUser(eq(UserHandle.USER_SYSTEM))).thenReturn(
                array(account("abc@gmail.com")));

        checkFindSendToAccount(0, "abc@gmail.com", null);
        checkFindSendToAccount(0, "abc@gmail.com", "");
        checkFindSendToAccount(0, "abc@gmail.com", "android.com");
        checkFindSendToAccount(0, "abc@gmail.com", "@android.com");
        checkFindSendToAccount(0, "abc@gmail.com", "gmail.com");
        checkFindSendToAccount(0, "abc@gmail.com", "@gmail.com");

        // 2 accounts, same domain
        when(mTestContext.accountManager.getAccountsAsUser(eq(UserHandle.USER_SYSTEM))).thenReturn(
                array(account("abc@gmail.com"), account("def@gmail.com")));

        checkFindSendToAccount(0, "abc@gmail.com", null);
        checkFindSendToAccount(0, "abc@gmail.com", "");
        checkFindSendToAccount(0, "abc@gmail.com", "android.com");
        checkFindSendToAccount(0, "abc@gmail.com", "@android.com");
        checkFindSendToAccount(0, "abc@gmail.com", "gmail.com");
        checkFindSendToAccount(0, "abc@gmail.com", "@gmail.com");

        // 2 accounts, different domains
        when(mTestContext.accountManager.getAccountsAsUser(eq(UserHandle.USER_SYSTEM))).thenReturn(
                array(account("abc@gmail.com"), account("def@android.com")));

        checkFindSendToAccount(0, "abc@gmail.com", null);
        checkFindSendToAccount(0, "abc@gmail.com", "");
        checkFindSendToAccount(0, "def@android.com", "android.com");
        checkFindSendToAccount(0, "def@android.com", "@android.com");
        checkFindSendToAccount(0, "abc@gmail.com", "gmail.com");
        checkFindSendToAccount(0, "abc@gmail.com", "@gmail.com");

        // Plut an account that doesn't look like an email address.
        when(mTestContext.accountManager.getAccountsAsUser(eq(UserHandle.USER_SYSTEM))).thenReturn(
                array(account("notemail"), account("abc@gmail.com"), account("def@android.com")));

        checkFindSendToAccount(0, "abc@gmail.com", null);
        checkFindSendToAccount(0, "abc@gmail.com", "");
        checkFindSendToAccount(0, "def@android.com", "android.com");
        checkFindSendToAccount(0, "def@android.com", "@android.com");
        checkFindSendToAccount(0, "abc@gmail.com", "gmail.com");
        checkFindSendToAccount(0, "abc@gmail.com", "@gmail.com");
    }

    /**
     * Same as {@link #findSendToAccount_noWorkProfile()}, but with work profile, with mixed
     * domains.
     */
    @Test
    public void findSendToAccount_withWorkProfile_mixedDomains() {
        when(mTestContext.userManager.getUserProfiles()).thenReturn(
                list(UserHandle.of(UserHandle.USER_SYSTEM), UserHandle.of(10)));

        // Work profile has no accounts
        when(mTestContext.accountManager.getAccountsAsUser(eq(10))).thenReturn(
                array(account("xyz@android.com")));

        // No accounts.
        when(mTestContext.accountManager.getAccountsAsUser(eq(UserHandle.USER_SYSTEM))).thenReturn(
                array());

        checkFindSendToAccount(10, "xyz@android.com", null);
        checkFindSendToAccount(10, "xyz@android.com", "");
        checkFindSendToAccount(10, "xyz@android.com", "android.com");
        checkFindSendToAccount(10, "xyz@android.com", "@android.com");

        // 1 account
        when(mTestContext.accountManager.getAccountsAsUser(eq(UserHandle.USER_SYSTEM))).thenReturn(
                array(account("abc@gmail.com")));

        checkFindSendToAccount(0, "abc@gmail.com", null);
        checkFindSendToAccount(0, "abc@gmail.com", "");
        checkFindSendToAccount(10, "xyz@android.com", "android.com");
        checkFindSendToAccount(10, "xyz@android.com", "@android.com");
        checkFindSendToAccount(0, "abc@gmail.com", "gmail.com");
        checkFindSendToAccount(0, "abc@gmail.com", "@gmail.com");

        // more accounts.
        when(mTestContext.accountManager.getAccountsAsUser(eq(UserHandle.USER_SYSTEM))).thenReturn(
                array(account("abc@gmail.com"), account("def@gmail.com")));

        checkFindSendToAccount(0, "abc@gmail.com", null);
        checkFindSendToAccount(0, "abc@gmail.com", "");
        checkFindSendToAccount(10, "xyz@android.com", "android.com");
        checkFindSendToAccount(10, "xyz@android.com", "@android.com");
        checkFindSendToAccount(0, "abc@gmail.com", "gmail.com");
        checkFindSendToAccount(0, "abc@gmail.com", "@gmail.com");
    }
}
