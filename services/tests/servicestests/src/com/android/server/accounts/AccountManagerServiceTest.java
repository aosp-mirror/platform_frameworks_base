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

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.accounts.Account;
import android.accounts.AuthenticatorDescription;
import android.app.AppOpsManager;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.RegisteredServicesCache.ServiceInfo;
import android.content.pm.RegisteredServicesCacheListener;
import android.content.pm.UserInfo;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.test.AndroidTestCase;
import android.test.mock.MockContext;
import android.test.mock.MockPackageManager;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;

public class AccountManagerServiceTest extends AndroidTestCase {
    private static final String TAG = AccountManagerServiceTest.class.getSimpleName();

    static final String PREN_DB = "pren.db";
    static final String DE_DB = "de.db";
    static final String CE_DB = "ce.db";
    private AccountManagerService mAms;

    @Override
    protected void setUp() throws Exception {
        Context realTestContext = getContext();
        Context mockContext = new MyMockContext(realTestContext);
        setContext(mockContext);
        mAms = createAccountManagerService(mockContext, realTestContext);
    }

    @Override
    protected void tearDown() throws Exception {
        SQLiteDatabase.deleteDatabase(new File(mAms.getCeDatabaseName(UserHandle.USER_SYSTEM)));
        SQLiteDatabase.deleteDatabase(new File(mAms.getDeDatabaseName(UserHandle.USER_SYSTEM)));
        SQLiteDatabase.deleteDatabase(new File(mAms.getPreNDatabaseName(UserHandle.USER_SYSTEM)));
        super.tearDown();
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
        unlockSystemUser();
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

        Account[] accounts = mAms.getAccounts(null, mContext.getOpPackageName());
        Arrays.sort(accounts, new AccountSorter());
        assertEquals(6, accounts.length);
        assertEquals(a11, accounts[0]);
        assertEquals(a21, accounts[1]);
        assertEquals(a31, accounts[2]);
        assertEquals(a12, accounts[3]);
        assertEquals(a22, accounts[4]);
        assertEquals(a32, accounts[5]);

        accounts = mAms.getAccounts("type1", mContext.getOpPackageName());
        Arrays.sort(accounts, new AccountSorter());
        assertEquals(3, accounts.length);
        assertEquals(a11, accounts[0]);
        assertEquals(a21, accounts[1]);
        assertEquals(a31, accounts[2]);

        mAms.removeAccountInternal(a21);

        accounts = mAms.getAccounts("type1", mContext.getOpPackageName());
        Arrays.sort(accounts, new AccountSorter());
        assertEquals(2, accounts.length);
        assertEquals(a11, accounts[0]);
        assertEquals(a31, accounts[1]);
    }

    public void testPasswords() throws Exception {
        unlockSystemUser();
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
        unlockSystemUser();
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
        unlockSystemUser();
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

    public void testRemovedAccountSync() throws Exception {
        unlockSystemUser();
        Account a1 = new Account("account1", "type1");
        Account a2 = new Account("account2", "type2");
        mAms.addAccountExplicitly(a1, "p1", null);
        mAms.addAccountExplicitly(a2, "p2", null);

        Context originalContext = ((MyMockContext)getContext()).mTestContext;
        // create a separate instance of AMS. It initially assumes that user0 is locked
        AccountManagerService ams2 = createAccountManagerService(getContext(), originalContext);

        // Verify that account can be removed when user is locked
        ams2.removeAccountInternal(a1);
        Account[] accounts = ams2.getAccounts(UserHandle.USER_SYSTEM, mContext.getOpPackageName());
        assertEquals(1, accounts.length);
        assertEquals("Only a2 should be returned", a2, accounts[0]);

        // Verify that CE db file is unchanged and still has 2 accounts
        String ceDatabaseName = mAms.getCeDatabaseName(UserHandle.USER_SYSTEM);
        int accountsNumber = readNumberOfAccountsFromDbFile(originalContext, ceDatabaseName);
        assertEquals("CE database should still have 2 accounts", 2, accountsNumber);

        // Unlock the user and verify that db has been updated
        ams2.onUserUnlocked(newIntentForUser(UserHandle.USER_SYSTEM));
        accountsNumber = readNumberOfAccountsFromDbFile(originalContext, ceDatabaseName);
        assertEquals("CE database should now have 1 account", 2, accountsNumber);
        accounts = ams2.getAccounts(UserHandle.USER_SYSTEM, mContext.getOpPackageName());
        assertEquals(1, accounts.length);
        assertEquals("Only a2 should be returned", a2, accounts[0]);
    }

    public void testPreNDatabaseMigration() throws Exception {
        String preNDatabaseName = mAms.getPreNDatabaseName(UserHandle.USER_SYSTEM);
        Context originalContext = ((MyMockContext) getContext()).mTestContext;
        PreNTestDatabaseHelper.createV4Database(originalContext, preNDatabaseName);
        // Assert that database was created with 1 account
        int n = readNumberOfAccountsFromDbFile(originalContext, preNDatabaseName);
        assertEquals("pre-N database should have 1 account", 1, n);

        // Start testing
        unlockSystemUser();
        Account[] accounts = mAms.getAccounts(null, mContext.getOpPackageName());
        assertEquals("1 account should be migrated", 1, accounts.length);
        assertEquals(PreNTestDatabaseHelper.ACCOUNT_NAME, accounts[0].name);
        assertEquals(PreNTestDatabaseHelper.ACCOUNT_PASSWORD, mAms.getPassword(accounts[0]));
        assertEquals("Authtoken should be migrated",
                PreNTestDatabaseHelper.TOKEN_STRING,
                mAms.peekAuthToken(accounts[0], PreNTestDatabaseHelper.TOKEN_TYPE));

        assertFalse("pre-N database file should be removed but was found at " + preNDatabaseName,
                new File(preNDatabaseName).exists());

        // Verify that ce/de files are present
        String deDatabaseName = mAms.getDeDatabaseName(UserHandle.USER_SYSTEM);
        String ceDatabaseName = mAms.getCeDatabaseName(UserHandle.USER_SYSTEM);
        assertTrue("DE database file should be created at " + deDatabaseName,
                new File(deDatabaseName).exists());
        assertTrue("CE database file should be created at " + ceDatabaseName,
                new File(ceDatabaseName).exists());
    }

    private int readNumberOfAccountsFromDbFile(Context context, String dbName) {
        SQLiteDatabase ceDb = context.openOrCreateDatabase(dbName, 0, null);
        try (Cursor cursor = ceDb.rawQuery("SELECT count(*) FROM accounts", null)) {
            assertTrue(cursor.moveToNext());
            return cursor.getInt(0);
        }
    }

    private AccountManagerService createAccountManagerService(Context mockContext,
            Context realContext) {
        return new MyAccountManagerService(mockContext,
                new MyMockPackageManager(), new MockAccountAuthenticatorCache(), realContext);
    }

    private void unlockSystemUser() {
        mAms.onUserUnlocked(newIntentForUser(UserHandle.USER_SYSTEM));
    }

    private static Intent newIntentForUser(int userId) {
        Intent intent = new Intent();
        intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        return intent;
    }

    static public class MockAccountAuthenticatorCache implements IAccountAuthenticatorCache {
        private ArrayList<ServiceInfo<AuthenticatorDescription>> mServices;

        public MockAccountAuthenticatorCache() {
            mServices = new ArrayList<>();
            AuthenticatorDescription d1 = new AuthenticatorDescription("type1", "p1", 0, 0, 0, 0);
            AuthenticatorDescription d2 = new AuthenticatorDescription("type2", "p2", 0, 0, 0, 0);
            mServices.add(new ServiceInfo<>(d1, null, null));
            mServices.add(new ServiceInfo<>(d2, null, null));
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

        @Override
        public void updateServices(int userId) {
        }
    }

    static public class MyMockContext extends MockContext {
        private Context mTestContext;
        private AppOpsManager mAppOpsManager;
        private UserManager mUserManager;
        private PackageManager mPackageManager;

        public MyMockContext(Context testContext) {
            this.mTestContext = testContext;
            this.mAppOpsManager = mock(AppOpsManager.class);
            this.mUserManager = mock(UserManager.class);
            this.mPackageManager = mock(PackageManager.class);
            final UserInfo ui = new UserInfo(UserHandle.USER_SYSTEM, "user0", 0);
            when(mUserManager.getUserInfo(eq(ui.id))).thenReturn(ui);
        }

        @Override
        public int checkCallingOrSelfPermission(final String permission) {
            return PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public PackageManager getPackageManager() {
            return mPackageManager;
        }

        @Override
        public Object getSystemService(String name) {
            if (Context.APP_OPS_SERVICE.equals(name)) {
                return mAppOpsManager;
            } else if( Context.USER_SERVICE.equals(name)) {
                return mUserManager;
            }
            return null;
        }

        @Override
        public String getSystemServiceName(Class<?> serviceClass) {
            if (AppOpsManager.class.equals(serviceClass)) {
                return Context.APP_OPS_SERVICE;
            }
            return null;
        }

        @Override
        public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
            return null;
        }

        @Override
        public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
                IntentFilter filter, String broadcastPermission, Handler scheduler) {
            return null;
        }

        @Override
        public SQLiteDatabase openOrCreateDatabase(String file, int mode,
                SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler errorHandler) {
            Log.i(TAG, "openOrCreateDatabase " + file + " mode " + mode);
            return mTestContext.openOrCreateDatabase(file, mode, factory,errorHandler);
        }

        @Override
        public void sendBroadcastAsUser(Intent intent, UserHandle user) {
            Log.i(TAG, "sendBroadcastAsUser " + intent + " " + user);
        }

        @Override
        public String getOpPackageName() {
            return null;
        }
    }

    static public class MyMockPackageManager extends MockPackageManager {
        @Override
        public int checkSignatures(final int uid1, final int uid2) {
            return PackageManager.SIGNATURE_MATCH;
        }
    }

    static public class MyAccountManagerService extends AccountManagerService {
        private Context mRealTestContext;
        public MyAccountManagerService(Context context, PackageManager packageManager,
                IAccountAuthenticatorCache authenticatorCache, Context realTestContext) {
            super(context, packageManager, authenticatorCache);
            this.mRealTestContext = realTestContext;
        }

        @Override
        protected void installNotification(final int notificationId, final Notification n, UserHandle user) {
        }

        @Override
        protected void cancelNotification(final int id, UserHandle user) {
        }

        @Override
        protected String getCeDatabaseName(int userId) {
            return new File(mRealTestContext.getCacheDir(), CE_DB).getPath();
        }

        @Override
        protected String getDeDatabaseName(int userId) {
            return new File(mRealTestContext.getCacheDir(), DE_DB).getPath();
        }

        @Override
        String getPreNDatabaseName(int userId) {
            return new File(mRealTestContext.getCacheDir(), PREN_DB).getPath();
        }
    }
}
