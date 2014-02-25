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

package com.android.server.accounts;

import android.accounts.Account;
import android.accounts.AuthenticatorDescription;
import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.RegisteredServicesCache.ServiceInfo;
import android.content.pm.RegisteredServicesCacheListener;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.test.AndroidTestCase;
import android.test.IsolatedContext;
import android.test.RenamingDelegatingContext;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;
import android.test.mock.MockPackageManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;

public class AccountManagerServiceTest extends AndroidTestCase {
    private AccountManagerService mAms;

    @Override
    protected void setUp() throws Exception {
        final String filenamePrefix = "test.";
        MockContentResolver resolver = new MockContentResolver();
        RenamingDelegatingContext targetContextWrapper = new RenamingDelegatingContext(
                new MyMockContext(), // The context that most methods are delegated to
                getContext(), // The context that file methods are delegated to
                filenamePrefix);
        Context context = new IsolatedContext(resolver, targetContextWrapper);
        setContext(context);
        mAms = new MyAccountManagerService(getContext(),
                new MyMockPackageManager(), new MockAccountAuthenticatorCache());
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
        Account a11 = new Account("account1", "type1");
        Account a21 = new Account("account2", "type1");
        Account a31 = new Account("account3", "type1");
        Account a12 = new Account("account1", "type2");
        Account a22 = new Account("account2", "type2");
        Account a32 = new Account("account3", "type2");
        mAms.addAccountExplicitly(a11, "p11", null);
        mAms.addAccountExplicitly(a12, "p12", null);
        mAms.addAccountExplicitly(a21, "p21", null);
        mAms.addAccountExplicitly(a22, "p22", null);
        mAms.addAccountExplicitly(a31, "p31", null);
        mAms.addAccountExplicitly(a32, "p32", null);

        Account[] accounts = mAms.getAccounts(null);
        Arrays.sort(accounts, new AccountSorter());
        assertEquals(6, accounts.length);
        assertEquals(a11, accounts[0]);
        assertEquals(a21, accounts[1]);
        assertEquals(a31, accounts[2]);
        assertEquals(a12, accounts[3]);
        assertEquals(a22, accounts[4]);
        assertEquals(a32, accounts[5]);

        accounts = mAms.getAccounts("type1" );
        Arrays.sort(accounts, new AccountSorter());
        assertEquals(3, accounts.length);
        assertEquals(a11, accounts[0]);
        assertEquals(a21, accounts[1]);
        assertEquals(a31, accounts[2]);

        mAms.removeAccountInternal(a21);

        accounts = mAms.getAccounts("type1" );
        Arrays.sort(accounts, new AccountSorter());
        assertEquals(2, accounts.length);
        assertEquals(a11, accounts[0]);
        assertEquals(a31, accounts[1]);
    }

    public void testPasswords() throws Exception {
        Account a11 = new Account("account1", "type1");
        Account a12 = new Account("account1", "type2");
        mAms.addAccountExplicitly(a11, "p11", null);
        mAms.addAccountExplicitly(a12, "p12", null);

        assertEquals("p11", mAms.getPassword(a11));
        assertEquals("p12", mAms.getPassword(a12));

        mAms.setPassword(a11, "p11b");

        assertEquals("p11b", mAms.getPassword(a11));
        assertEquals("p12", mAms.getPassword(a12));
    }

    public void testUserdata() throws Exception {
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
        mAms.addAccountExplicitly(a11, "p11", u11);
        mAms.addAccountExplicitly(a12, "p12", u12);

        assertEquals("a_a11", mAms.getUserData(a11, "a"));
        assertEquals("b_a11", mAms.getUserData(a11, "b"));
        assertEquals("c_a11", mAms.getUserData(a11, "c"));
        assertEquals("a_a12", mAms.getUserData(a12, "a"));
        assertEquals("b_a12", mAms.getUserData(a12, "b"));
        assertEquals("c_a12", mAms.getUserData(a12, "c"));

        mAms.setUserData(a11, "b", "b_a11b");
        mAms.setUserData(a12, "c", null);

        assertEquals("a_a11", mAms.getUserData(a11, "a"));
        assertEquals("b_a11b", mAms.getUserData(a11, "b"));
        assertEquals("c_a11", mAms.getUserData(a11, "c"));
        assertEquals("a_a12", mAms.getUserData(a12, "a"));
        assertEquals("b_a12", mAms.getUserData(a12, "b"));
        assertNull(mAms.getUserData(a12, "c"));
    }

    public void testAuthtokens() throws Exception {
        Account a11 = new Account("account1", "type1");
        Account a12 = new Account("account1", "type2");
        mAms.addAccountExplicitly(a11, "p11", null);
        mAms.addAccountExplicitly(a12, "p12", null);

        mAms.setAuthToken(a11, "att1", "a11_att1");
        mAms.setAuthToken(a11, "att2", "a11_att2");
        mAms.setAuthToken(a11, "att3", "a11_att3");
        mAms.setAuthToken(a12, "att1", "a12_att1");
        mAms.setAuthToken(a12, "att2", "a12_att2");
        mAms.setAuthToken(a12, "att3", "a12_att3");

        assertEquals("a11_att1", mAms.peekAuthToken(a11, "att1"));
        assertEquals("a11_att2", mAms.peekAuthToken(a11, "att2"));
        assertEquals("a11_att3", mAms.peekAuthToken(a11, "att3"));
        assertEquals("a12_att1", mAms.peekAuthToken(a12, "att1"));
        assertEquals("a12_att2", mAms.peekAuthToken(a12, "att2"));
        assertEquals("a12_att3", mAms.peekAuthToken(a12, "att3"));

        mAms.setAuthToken(a11, "att3", "a11_att3b");
        mAms.invalidateAuthToken(a12.type, "a12_att2");

        assertEquals("a11_att1", mAms.peekAuthToken(a11, "att1"));
        assertEquals("a11_att2", mAms.peekAuthToken(a11, "att2"));
        assertEquals("a11_att3b", mAms.peekAuthToken(a11, "att3"));
        assertEquals("a12_att1", mAms.peekAuthToken(a12, "att1"));
        assertNull(mAms.peekAuthToken(a12, "att2"));
        assertEquals("a12_att3", mAms.peekAuthToken(a12, "att3"));

        assertNull(mAms.peekAuthToken(a12, "att2"));
    }

    static public class MockAccountAuthenticatorCache implements IAccountAuthenticatorCache {
        private ArrayList<ServiceInfo<AuthenticatorDescription>> mServices;

        public MockAccountAuthenticatorCache() {
            mServices = new ArrayList<ServiceInfo<AuthenticatorDescription>>();
            AuthenticatorDescription d1 = new AuthenticatorDescription("type1", "p1", 0, 0, 0, 0);
            AuthenticatorDescription d2 = new AuthenticatorDescription("type2", "p2", 0, 0, 0, 0);
            mServices.add(new ServiceInfo<AuthenticatorDescription>(d1, null, 0));
            mServices.add(new ServiceInfo<AuthenticatorDescription>(d2, null, 0));
        }

        @Override
        public ServiceInfo<AuthenticatorDescription> getServiceInfo(
                AuthenticatorDescription type, int userId) {
            for (ServiceInfo<AuthenticatorDescription> service : mServices) {
                if (service.type.equals(type)) {
                    return service;
                }
            }
            return null;
        }

        @Override
        public Collection<ServiceInfo<AuthenticatorDescription>> getAllServices(int userId) {
            return mServices;
        }

        @Override
        public void dump(
                final FileDescriptor fd, final PrintWriter fout, final String[] args, int userId) {
        }

        @Override
        public void setListener(
                final RegisteredServicesCacheListener<AuthenticatorDescription> listener,
                final Handler handler) {
        }

        @Override
        public void invalidateCache(int userId) {
        }
    }

    static public class MyMockContext extends MockContext {
        @Override
        public int checkCallingOrSelfPermission(final String permission) {
            return PackageManager.PERMISSION_GRANTED;
        }
    }

    static public class MyMockPackageManager extends MockPackageManager {
        @Override
        public int checkSignatures(final int uid1, final int uid2) {
            return PackageManager.SIGNATURE_MATCH;
        }
    }

    static public class MyAccountManagerService extends AccountManagerService {
        public MyAccountManagerService(Context context, PackageManager packageManager,
                IAccountAuthenticatorCache authenticatorCache) {
            super(context, packageManager, authenticatorCache);
        }

        @Override
        protected void installNotification(final int notificationId, final Notification n, UserHandle user) {
        }

        @Override
        protected void cancelNotification(final int id, UserHandle user) {
        }
    }
}
