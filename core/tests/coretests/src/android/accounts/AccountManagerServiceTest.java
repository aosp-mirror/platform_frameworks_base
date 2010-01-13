/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.accounts;

import android.test.AndroidTestCase;
import android.test.RenamingDelegatingContext;
import android.test.IsolatedContext;
import android.test.mock.MockContext;
import android.test.mock.MockContentResolver;
import android.content.*;
import android.accounts.Account;
import android.accounts.AccountManagerService;
import android.os.Bundle;

import java.util.Arrays;
import java.util.Comparator;

public class AccountManagerServiceTest extends AndroidTestCase {
    @Override
    protected void setUp() throws Exception {
        final String filenamePrefix = "test.";
        MockContentResolver resolver = new MockContentResolver();
        RenamingDelegatingContext targetContextWrapper = new RenamingDelegatingContext(
                new MockContext(), // The context that most methods are delegated to
                getContext(), // The context that file methods are delegated to
                filenamePrefix);
        Context context = new IsolatedContext(resolver, targetContextWrapper);
        setContext(context);
    }

    public class AccountSorter implements Comparator<Account> {
        public int compare(Account object1, Account object2) {
            if (object1 == object2) return 0;
            if (object1 == null) return 1;
            if (object2 == null) return -1;
            int result = object1.type.compareTo(object2.type);
            if (result != 0) return result;
            return object1.name.compareTo(object2.name);
        }
    }

    public void testCheckAddAccount() throws Exception {
        AccountManagerService ams = new AccountManagerService(getContext());
        Account a11 = new Account("account1", "type1");
        Account a21 = new Account("account2", "type1");
        Account a31 = new Account("account3", "type1");
        Account a12 = new Account("account1", "type2");
        Account a22 = new Account("account2", "type2");
        Account a32 = new Account("account3", "type2");
        ams.addAccount(a11, "p11", null);
        ams.addAccount(a12, "p12", null);
        ams.addAccount(a21, "p21", null);
        ams.addAccount(a22, "p22", null);
        ams.addAccount(a31, "p31", null);
        ams.addAccount(a32, "p32", null);

        Account[] accounts = ams.getAccounts(null);
        Arrays.sort(accounts, new AccountSorter());
        assertEquals(6, accounts.length);
        assertEquals(a11, accounts[0]);
        assertEquals(a21, accounts[1]);
        assertEquals(a31, accounts[2]);
        assertEquals(a12, accounts[3]);
        assertEquals(a22, accounts[4]);
        assertEquals(a32, accounts[5]);

        accounts = ams.getAccountsByType("type1" );
        Arrays.sort(accounts, new AccountSorter());
        assertEquals(3, accounts.length);
        assertEquals(a11, accounts[0]);
        assertEquals(a21, accounts[1]);
        assertEquals(a31, accounts[2]);

        ams.removeAccount(null, a21);

        accounts = ams.getAccountsByType("type1" );
        Arrays.sort(accounts, new AccountSorter());
        assertEquals(2, accounts.length);
        assertEquals(a11, accounts[0]);
        assertEquals(a31, accounts[1]);
    }

    public void testPasswords() throws Exception {
        AccountManagerService ams = new AccountManagerService(getContext());
        Account a11 = new Account("account1", "type1");
        Account a12 = new Account("account1", "type2");
        ams.addAccount(a11, "p11", null);
        ams.addAccount(a12, "p12", null);

        assertEquals("p11", ams.getPassword(a11));
        assertEquals("p12", ams.getPassword(a12));

        ams.setPassword(a11, "p11b");

        assertEquals("p11b", ams.getPassword(a11));
        assertEquals("p12", ams.getPassword(a12));
    }

    public void testUserdata() throws Exception {
        AccountManagerService ams = new AccountManagerService(getContext());
        Account a11 = new Account("account1", "type1");
        Bundle u11 = new Bundle();
        u11.putString("a", "a_a11");
        u11.putString("b", "b_a11");
        u11.putString("c", "c_a11");
        Account a12 = new Account("account1", "type2");
        Bundle u12 = new Bundle();
        u12.putString("a", "a_a12");
        u12.putString("b", "b_a12");
        u12.putString("c", "c_a12");
        ams.addAccount(a11, "p11", u11);
        ams.addAccount(a12, "p12", u12);

        assertEquals("a_a11", ams.getUserData(a11, "a"));
        assertEquals("b_a11", ams.getUserData(a11, "b"));
        assertEquals("c_a11", ams.getUserData(a11, "c"));
        assertEquals("a_a12", ams.getUserData(a12, "a"));
        assertEquals("b_a12", ams.getUserData(a12, "b"));
        assertEquals("c_a12", ams.getUserData(a12, "c"));

        ams.setUserData(a11, "b", "b_a11b");

        assertEquals("a_a11", ams.getUserData(a11, "a"));
        assertEquals("b_a11b", ams.getUserData(a11, "b"));
        assertEquals("c_a11", ams.getUserData(a11, "c"));
        assertEquals("a_a12", ams.getUserData(a12, "a"));
        assertEquals("b_a12", ams.getUserData(a12, "b"));
        assertEquals("c_a12", ams.getUserData(a12, "c"));
    }

    public void testAuthtokens() throws Exception {
        AccountManagerService ams = new AccountManagerService(getContext());
        Account a11 = new Account("account1", "type1");
        Account a12 = new Account("account1", "type2");
        ams.addAccount(a11, "p11", null);
        ams.addAccount(a12, "p12", null);

        ams.setAuthToken(a11, "att1", "a11_att1");
        ams.setAuthToken(a11, "att2", "a11_att2");
        ams.setAuthToken(a11, "att3", "a11_att3");
        ams.setAuthToken(a12, "att1", "a12_att1");
        ams.setAuthToken(a12, "att2", "a12_att2");
        ams.setAuthToken(a12, "att3", "a12_att3");

        assertEquals("a11_att1", ams.peekAuthToken(a11, "att1"));
        assertEquals("a11_att2", ams.peekAuthToken(a11, "att2"));
        assertEquals("a11_att3", ams.peekAuthToken(a11, "att3"));
        assertEquals("a12_att1", ams.peekAuthToken(a12, "att1"));
        assertEquals("a12_att2", ams.peekAuthToken(a12, "att2"));
        assertEquals("a12_att3", ams.peekAuthToken(a12, "att3"));

        ams.setAuthToken(a11, "att3", "a11_att3b");
        ams.invalidateAuthToken(a12.type, "a12_att2");

        assertEquals("a11_att1", ams.peekAuthToken(a11, "att1"));
        assertEquals("a11_att2", ams.peekAuthToken(a11, "att2"));
        assertEquals("a11_att3b", ams.peekAuthToken(a11, "att3"));
        assertEquals("a12_att1", ams.peekAuthToken(a12, "att1"));
        assertNull(ams.peekAuthToken(a12, "att2"));
        assertEquals("a12_att3", ams.peekAuthToken(a12, "att3"));

        assertNull(ams.readAuthTokenFromDatabase(a12, "att2"));
    }
}
