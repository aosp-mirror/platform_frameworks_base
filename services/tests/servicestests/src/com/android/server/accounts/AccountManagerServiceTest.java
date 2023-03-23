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

import static android.database.sqlite.SQLiteDatabase.deleteDatabase;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerInternal;
import android.accounts.CantAddAccountActivity;
import android.accounts.IAccountManagerResponse;
import android.app.AppOpsManager;
import android.app.INotificationManager;
import android.app.PropertyInvalidatedCache;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.UserInfo;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.test.AndroidTestCase;
import android.test.mock.MockContext;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import com.android.server.LocalServices;

import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tests for {@link AccountManagerService}.
 * <p>Run with:<pre>
 * mmma -j40 frameworks/base/services/tests/servicestests
 * adb install -r ${OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk
 * adb shell am instrument -w -e package com.android.server.accounts \
 * com.android.frameworks.servicestests\
 * /androidx.test.runner.AndroidJUnitRunner
 * </pre>
 */
public class AccountManagerServiceTest extends AndroidTestCase {
    private static final String TAG = AccountManagerServiceTest.class.getSimpleName();
    private static final long ONE_DAY_IN_MILLISECOND = 86400000;

    @Mock private Context mMockContext;
    @Mock private AppOpsManager mMockAppOpsManager;
    @Mock private UserManager mMockUserManager;
    @Mock private PackageManager mMockPackageManager;
    @Mock private DevicePolicyManagerInternal mMockDevicePolicyManagerInternal;
    @Mock private DevicePolicyManager mMockDevicePolicyManager;
    @Mock private IAccountManagerResponse mMockAccountManagerResponse;
    @Mock private IBinder mMockBinder;
    @Mock private INotificationManager mMockNotificationManager;
    @Mock private PackageManagerInternal mMockPackageManagerInternal;

    @Captor private ArgumentCaptor<Intent> mIntentCaptor;
    @Captor private ArgumentCaptor<Bundle> mBundleCaptor;
    private int mVisibleAccountsChangedBroadcasts;
    private int mLoginAccountsChangedBroadcasts;
    private int mAccountRemovedBroadcasts;

    private static final int LATCH_TIMEOUT_MS = 500;
    private static final String PREN_DB = "pren.db";
    private static final String DE_DB = "de.db";
    private static final String CE_DB = "ce.db";
    private PackageInfo mPackageInfo;
    private AccountManagerService mAms;
    private TestInjector mTestInjector;

    @Override
    protected void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        PropertyInvalidatedCache.disableForTestMode();

        when(mMockPackageManager.checkSignatures(anyInt(), anyInt()))
                    .thenReturn(PackageManager.SIGNATURE_MATCH);
        final UserInfo ui = new UserInfo(UserHandle.USER_SYSTEM, "user0", 0);
        when(mMockUserManager.getUserInfo(eq(ui.id))).thenReturn(ui);
        when(mMockContext.createPackageContextAsUser(
                 anyString(), anyInt(), any(UserHandle.class))).thenReturn(mMockContext);
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);

        mPackageInfo = new PackageInfo();
        mPackageInfo.signatures = new Signature[1];
        mPackageInfo.signatures[0] = new Signature(new byte[] {'a', 'b', 'c', 'd'});
        mPackageInfo.applicationInfo = new ApplicationInfo();
        mPackageInfo.applicationInfo.privateFlags = ApplicationInfo.PRIVATE_FLAG_PRIVILEGED;
        when(mMockPackageManager.getPackageInfo(anyString(), anyInt())).thenReturn(mPackageInfo);
        when(mMockContext.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(mMockAppOpsManager);
        when(mMockContext.getSystemService(Context.USER_SERVICE)).thenReturn(mMockUserManager);
        when(mMockContext.getSystemServiceName(AppOpsManager.class)).thenReturn(
                Context.APP_OPS_SERVICE);
        when(mMockContext.checkCallingOrSelfPermission(anyString())).thenReturn(
                PackageManager.PERMISSION_GRANTED);
        Bundle bundle = new Bundle();
        when(mMockUserManager.getUserRestrictions(any(UserHandle.class))).thenReturn(bundle);
        when(mMockContext.getSystemService(Context.DEVICE_POLICY_SERVICE)).thenReturn(
                mMockDevicePolicyManager);
        when(mMockAccountManagerResponse.asBinder()).thenReturn(mMockBinder);
        when(mMockPackageManagerInternal.hasSignatureCapability(anyInt(), anyInt(), anyInt()))
                .thenReturn(true);
        LocalServices.addService(PackageManagerInternal.class, mMockPackageManagerInternal);

        Context realTestContext = getContext();
        MyMockContext mockContext = new MyMockContext(realTestContext, mMockContext);
        setContext(mockContext);
        mTestInjector = new TestInjector(realTestContext, mockContext, mMockNotificationManager);
        mAms = new AccountManagerService(mTestInjector);
    }

    @Override
    protected void tearDown() throws Exception {
        // Let async logging tasks finish, otherwise they may crash due to db being removed
        CountDownLatch cdl = new CountDownLatch(1);
        mAms.mHandler.post(() -> {
            deleteDatabase(new File(mTestInjector.getCeDatabaseName(UserHandle.USER_SYSTEM)));
            deleteDatabase(new File(mTestInjector.getDeDatabaseName(UserHandle.USER_SYSTEM)));
            deleteDatabase(new File(mTestInjector.getPreNDatabaseName(UserHandle.USER_SYSTEM)));
            cdl.countDown();
        });
        cdl.await(1, TimeUnit.SECONDS);
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        super.tearDown();
    }

    class AccountSorter implements Comparator<Account> {
        public int compare(Account object1, Account object2) {
            if (object1 == object2) return 0;
            if (object1 == null) return 1;
            if (object2 == null) return -1;
            int result = object1.type.compareTo(object2.type);
            if (result != 0) return result;
            return object1.name.compareTo(object2.name);
        }
    }

    @SmallTest
    public void testCheckAddAccount() throws Exception {
        unlockSystemUser();
        Account a11 = new Account("account1", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);
        Account a21 = new Account("account2", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);
        Account a31 = new Account("account3", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);
        Account a12 = new Account("account1", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_2);
        Account a22 = new Account("account2", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_2);
        Account a32 = new Account("account3", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_2);
        mAms.addAccountExplicitly(a11, "p11", null);
        mAms.addAccountExplicitly(a12, "p12", null);
        mAms.addAccountExplicitly(a21, "p21", null);
        mAms.addAccountExplicitly(a22, "p22", null);
        mAms.addAccountExplicitly(a31, "p31", null);
        mAms.addAccountExplicitly(a32, "p32", null);

        String[] list = new String[]{AccountManagerServiceTestFixtures.CALLER_PACKAGE};
        when(mMockPackageManager.getPackagesForUid(anyInt())).thenReturn(list);
        Account[] accounts = mAms.getAccountsAsUser(null,
                UserHandle.getCallingUserId(), mContext.getOpPackageName());
        Arrays.sort(accounts, new AccountSorter());
        assertEquals(6, accounts.length);
        assertEquals(a11, accounts[0]);
        assertEquals(a21, accounts[1]);
        assertEquals(a31, accounts[2]);
        assertEquals(a12, accounts[3]);
        assertEquals(a22, accounts[4]);
        assertEquals(a32, accounts[5]);

        accounts = mAms.getAccountsAsUser(AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1,
                UserHandle.getCallingUserId(), mContext.getOpPackageName());
        Arrays.sort(accounts, new AccountSorter());
        assertEquals(3, accounts.length);
        assertEquals(a11, accounts[0]);
        assertEquals(a21, accounts[1]);
        assertEquals(a31, accounts[2]);

        mAms.removeAccountInternal(a21);

        accounts = mAms.getAccountsAsUser(AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1,
                UserHandle.getCallingUserId(), mContext.getOpPackageName());
        Arrays.sort(accounts, new AccountSorter());
        assertEquals(2, accounts.length);
        assertEquals(a11, accounts[0]);
        assertEquals(a31, accounts[1]);
    }

    @SmallTest
    public void testCheckAddAccountLongName() throws Exception {
        unlockSystemUser();
        String longString = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                + "aaaaa";
        Account a11 = new Account(longString, AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);

        mAms.addAccountExplicitly(a11, /* password= */ "p11", /* extras= */ null);

        String[] list = new String[]{AccountManagerServiceTestFixtures.CALLER_PACKAGE};
        when(mMockPackageManager.getPackagesForUid(anyInt())).thenReturn(list);
        Account[] accounts = mAms.getAccountsAsUser(null,
                UserHandle.getCallingUserId(), mContext.getOpPackageName());
        assertEquals(0, accounts.length);
    }


    @SmallTest
    public void testPasswords() throws Exception {
        unlockSystemUser();
        Account a11 = new Account("account1", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);
        Account a12 = new Account("account1", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_2);
        mAms.addAccountExplicitly(a11, "p11", null);
        mAms.addAccountExplicitly(a12, "p12", null);

        assertEquals("p11", mAms.getPassword(a11));
        assertEquals("p12", mAms.getPassword(a12));

        mAms.setPassword(a11, "p11b");

        assertEquals("p11b", mAms.getPassword(a11));
        assertEquals("p12", mAms.getPassword(a12));
    }

    @SmallTest
    public void testUserdata() throws Exception {
        unlockSystemUser();
        Account a11 = new Account("account1", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);
        Bundle u11 = new Bundle();
        u11.putString("a", "a_a11");
        u11.putString("b", "b_a11");
        u11.putString("c", "c_a11");
        Account a12 = new Account("account1", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_2);
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

    @SmallTest
    public void testAuthtokens() throws Exception {
        unlockSystemUser();
        Account a11 = new Account("account1", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);
        Account a12 = new Account("account1", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_2);
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

    @SmallTest
    public void testRemovedAccountSync() throws Exception {
        String[] list = new String[]{AccountManagerServiceTestFixtures.CALLER_PACKAGE};
        when(mMockPackageManager.getPackagesForUid(anyInt())).thenReturn(list);
        unlockSystemUser();
        Account a1 = new Account("account1", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);
        Account a2 = new Account("account2", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_2);
        mAms.addAccountExplicitly(a1, "p1", null);
        mAms.addAccountExplicitly(a2, "p2", null);

        Context originalContext = ((MyMockContext)getContext()).mTestContext;
        // create a separate instance of AMS. It initially assumes that user0 is locked
        AccountManagerService ams2 = new AccountManagerService(mTestInjector);

        // Verify that account can be removed when user is locked
        ams2.removeAccountInternal(a1);
        Account[] accounts = ams2.getAccounts(UserHandle.USER_SYSTEM, mContext.getOpPackageName());
        assertEquals(1, accounts.length);
        assertEquals("Only a2 should be returned", a2, accounts[0]);

        // Verify that CE db file is unchanged and still has 2 accounts
        String ceDatabaseName = mTestInjector.getCeDatabaseName(UserHandle.USER_SYSTEM);
        int accountsNumber = readNumberOfAccountsFromDbFile(originalContext, ceDatabaseName);
        assertEquals("CE database should still have 2 accounts", 2, accountsNumber);

        // Unlock the user and verify that db has been updated
        ams2.onUserUnlocked(newIntentForUser(UserHandle.USER_SYSTEM));
        accounts = ams2.getAccounts(UserHandle.USER_SYSTEM, mContext.getOpPackageName());
        assertEquals(1, accounts.length);
        assertEquals("Only a2 should be returned", a2, accounts[0]);
        accountsNumber = readNumberOfAccountsFromDbFile(originalContext, ceDatabaseName);
        assertEquals("CE database should now have 1 account", 1, accountsNumber);
    }

    @SmallTest
    public void testPreNDatabaseMigration() throws Exception {
        String preNDatabaseName = mTestInjector.getPreNDatabaseName(UserHandle.USER_SYSTEM);
        Context originalContext = ((MyMockContext) getContext()).mTestContext;
        PreNTestDatabaseHelper.createV4Database(originalContext, preNDatabaseName);
        // Assert that database was created with 1 account
        int n = readNumberOfAccountsFromDbFile(originalContext, preNDatabaseName);
        assertEquals("pre-N database should have 1 account", 1, n);

        // Start testing
        unlockSystemUser();
        String[] list = new String[]{AccountManagerServiceTestFixtures.CALLER_PACKAGE};
        when(mMockPackageManager.getPackagesForUid(anyInt())).thenReturn(list);
        Account[] accounts = mAms.getAccountsAsUser(null, UserHandle.getCallingUserId(),
                mContext.getOpPackageName());
        assertEquals("1 account should be migrated", 1, accounts.length);
        assertEquals(PreNTestDatabaseHelper.ACCOUNT_NAME, accounts[0].name);
        assertEquals(PreNTestDatabaseHelper.ACCOUNT_PASSWORD, mAms.getPassword(accounts[0]));
        assertEquals("Authtoken should be migrated",
                PreNTestDatabaseHelper.TOKEN_STRING,
                mAms.peekAuthToken(accounts[0], PreNTestDatabaseHelper.TOKEN_TYPE));

        assertFalse("pre-N database file should be removed but was found at " + preNDatabaseName,
                new File(preNDatabaseName).exists());

        // Verify that ce/de files are present
        String deDatabaseName = mTestInjector.getDeDatabaseName(UserHandle.USER_SYSTEM);
        String ceDatabaseName = mTestInjector.getCeDatabaseName(UserHandle.USER_SYSTEM);
        assertTrue("DE database file should be created at " + deDatabaseName,
                new File(deDatabaseName).exists());
        assertTrue("CE database file should be created at " + ceDatabaseName,
                new File(ceDatabaseName).exists());
    }

    @SmallTest
    public void testStartAddAccountSessionWithNullResponse() throws Exception {
        unlockSystemUser();
        try {
            mAms.startAddAccountSession(
                null, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1,
                "authTokenType",
                null, // requiredFeatures
                true, // expectActivityLaunch
                null); // optionsIn
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testStartAddAccountSessionWithNullAccountType() throws Exception {
        unlockSystemUser();
        try {
            mAms.startAddAccountSession(
                    mMockAccountManagerResponse, // response
                    null, // accountType
                    "authTokenType",
                    null, // requiredFeatures
                    true, // expectActivityLaunch
                    null); // optionsIn
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testStartAddAccountSessionUserCannotModifyAccountNoDPM() throws Exception {
        unlockSystemUser();
        Bundle bundle = new Bundle();
        bundle.putBoolean(UserManager.DISALLOW_MODIFY_ACCOUNTS, true);
        when(mMockUserManager.getUserRestrictions(any(UserHandle.class))).thenReturn(bundle);
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);

        mAms.startAddAccountSession(
                mMockAccountManagerResponse, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                true, // expectActivityLaunch
                null); // optionsIn
        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_USER_RESTRICTED), anyString());
        verify(mMockContext).startActivityAsUser(mIntentCaptor.capture(), eq(UserHandle.SYSTEM));

        // verify the intent for default CantAddAccountActivity is sent.
        Intent intent = mIntentCaptor.getValue();
        assertEquals(intent.getComponent().getClassName(), CantAddAccountActivity.class.getName());
        assertEquals(intent.getIntExtra(CantAddAccountActivity.EXTRA_ERROR_CODE, 0),
                AccountManager.ERROR_CODE_USER_RESTRICTED);
    }

    @SmallTest
    public void testStartAddAccountSessionUserCannotModifyAccountWithDPM() throws Exception {
        unlockSystemUser();
        Bundle bundle = new Bundle();
        bundle.putBoolean(UserManager.DISALLOW_MODIFY_ACCOUNTS, true);
        when(mMockUserManager.getUserRestrictions(any(UserHandle.class))).thenReturn(bundle);
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
        LocalServices.addService(
                DevicePolicyManagerInternal.class, mMockDevicePolicyManagerInternal);
        when(mMockDevicePolicyManagerInternal.createUserRestrictionSupportIntent(
                anyInt(), anyString())).thenReturn(new Intent());
        when(mMockDevicePolicyManagerInternal.createShowAdminSupportIntent(
                anyInt(), anyBoolean())).thenReturn(new Intent());

        mAms.startAddAccountSession(
                mMockAccountManagerResponse, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                true, // expectActivityLaunch
                null); // optionsIn

        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_USER_RESTRICTED), anyString());
        verify(mMockContext).startActivityAsUser(any(Intent.class), eq(UserHandle.SYSTEM));
        verify(mMockDevicePolicyManagerInternal).createUserRestrictionSupportIntent(
                anyInt(), anyString());
    }

    @SmallTest
    public void testStartAddAccountSessionUserCannotModifyAccountForTypeNoDPM() throws Exception {
        unlockSystemUser();
        when(mMockDevicePolicyManager.getAccountTypesWithManagementDisabledAsUser(anyInt()))
                .thenReturn(new String[]{AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, "BBB"});
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);

        mAms.startAddAccountSession(
                mMockAccountManagerResponse, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                true, // expectActivityLaunch
                null); // optionsIn

        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE), anyString());
        verify(mMockContext).startActivityAsUser(mIntentCaptor.capture(), eq(UserHandle.SYSTEM));

        // verify the intent for default CantAddAccountActivity is sent.
        Intent intent = mIntentCaptor.getValue();
        assertEquals(intent.getComponent().getClassName(), CantAddAccountActivity.class.getName());
        assertEquals(intent.getIntExtra(CantAddAccountActivity.EXTRA_ERROR_CODE, 0),
                AccountManager.ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE);
    }

    @SmallTest
    public void testStartAddAccountSessionUserCannotModifyAccountForTypeWithDPM() throws Exception {
        unlockSystemUser();
        when(mMockContext.getSystemService(Context.DEVICE_POLICY_SERVICE)).thenReturn(
                mMockDevicePolicyManager);
        when(mMockDevicePolicyManager.getAccountTypesWithManagementDisabledAsUser(anyInt()))
                .thenReturn(new String[]{AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, "BBB"});

        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
        LocalServices.addService(
                DevicePolicyManagerInternal.class, mMockDevicePolicyManagerInternal);
        when(mMockDevicePolicyManagerInternal.createUserRestrictionSupportIntent(
                anyInt(), anyString())).thenReturn(new Intent());
        when(mMockDevicePolicyManagerInternal.createShowAdminSupportIntent(
                anyInt(), anyBoolean())).thenReturn(new Intent());

        mAms.startAddAccountSession(
                mMockAccountManagerResponse, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                true, // expectActivityLaunch
                null); // optionsIn

        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE), anyString());
        verify(mMockContext).startActivityAsUser(any(Intent.class), eq(UserHandle.SYSTEM));
        verify(mMockDevicePolicyManagerInternal).createShowAdminSupportIntent(
                anyInt(), anyBoolean());
    }

    @SmallTest
    public void testStartAddAccountSessionSuccessWithoutPasswordForwarding() throws Exception {
        unlockSystemUser();
        when(mMockContext.checkCallingOrSelfPermission(anyString())).thenReturn(
                PackageManager.PERMISSION_DENIED);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        Bundle options = createOptionsWithAccountName(
                AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS);
        mAms.startAddAccountSession(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                false, // expectActivityLaunch
                options); // optionsIn
        waitForLatch(latch);
        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        Bundle sessionBundle = result.getBundle(AccountManager.KEY_ACCOUNT_SESSION_BUNDLE);
        assertNotNull(sessionBundle);
        // Assert that session bundle is encrypted and hence data not visible.
        assertNull(sessionBundle.getString(AccountManagerServiceTestFixtures.SESSION_DATA_NAME_1));
        // Assert password is not returned
        assertNull(result.getString(AccountManager.KEY_PASSWORD));
        assertNull(result.getString(AccountManager.KEY_AUTHTOKEN, null));
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_STATUS_TOKEN,
                result.getString(AccountManager.KEY_ACCOUNT_STATUS_TOKEN));
    }

    @SmallTest
    public void testStartAddAccountSessionSuccessWithPasswordForwarding() throws Exception {
        unlockSystemUser();
        when(mMockContext.checkCallingOrSelfPermission(anyString())).thenReturn(
                PackageManager.PERMISSION_GRANTED);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        Bundle options = createOptionsWithAccountName(
                AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS);
        mAms.startAddAccountSession(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                false, // expectActivityLaunch
                options); // optionsIn

        waitForLatch(latch);
        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        Bundle sessionBundle = result.getBundle(AccountManager.KEY_ACCOUNT_SESSION_BUNDLE);
        assertNotNull(sessionBundle);
        // Assert that session bundle is encrypted and hence data not visible.
        assertNull(sessionBundle.getString(AccountManagerServiceTestFixtures.SESSION_DATA_NAME_1));
        // Assert password is returned
        assertEquals(result.getString(AccountManager.KEY_PASSWORD),
                AccountManagerServiceTestFixtures.ACCOUNT_PASSWORD);
        assertNull(result.getString(AccountManager.KEY_AUTHTOKEN));
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_STATUS_TOKEN,
                result.getString(AccountManager.KEY_ACCOUNT_STATUS_TOKEN));
    }

    @SmallTest
    public void testStartAddAccountSessionReturnWithInvalidIntent() throws Exception {
        unlockSystemUser();
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.applicationInfo = new ApplicationInfo();
        when(mMockPackageManager.resolveActivityAsUser(
                any(Intent.class), anyInt(), anyInt())).thenReturn(resolveInfo);
        when(mMockPackageManager.checkSignatures(
                anyInt(), anyInt())).thenReturn(PackageManager.SIGNATURE_NO_MATCH);
        when(mMockPackageManagerInternal.hasSignatureCapability(anyInt(), anyInt(), anyInt()))
                .thenReturn(false);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        Bundle options = createOptionsWithAccountName(
                AccountManagerServiceTestFixtures.ACCOUNT_NAME_INTERVENE);

        mAms.startAddAccountSession(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                true, // expectActivityLaunch
                options); // optionsIn
        waitForLatch(latch);
        verify(mMockAccountManagerResponse, never()).onResult(any(Bundle.class));
        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_INVALID_RESPONSE), anyString());
    }

    @SmallTest
    public void testStartAddAccountSessionReturnWithValidIntent() throws Exception {
        unlockSystemUser();
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.applicationInfo = new ApplicationInfo();
        when(mMockPackageManager.resolveActivityAsUser(
                any(Intent.class), anyInt(), anyInt())).thenReturn(resolveInfo);
        when(mMockPackageManager.checkSignatures(
                anyInt(), anyInt())).thenReturn(PackageManager.SIGNATURE_MATCH);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        Bundle options = createOptionsWithAccountName(
                AccountManagerServiceTestFixtures.ACCOUNT_NAME_INTERVENE);

        mAms.startAddAccountSession(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                true, // expectActivityLaunch
                options); // optionsIn
        waitForLatch(latch);

        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        Intent intent = result.getParcelable(AccountManager.KEY_INTENT);
        assertNotNull(intent);
        assertNotNull(intent.getParcelableExtra(AccountManagerServiceTestFixtures.KEY_RESULT));
        assertNotNull(intent.getParcelableExtra(AccountManagerServiceTestFixtures.KEY_CALLBACK));
    }

    @SmallTest
    public void testStartAddAccountSessionWhereAuthenticatorReturnsIntentWithProhibitedFlags()
            throws Exception {
        unlockSystemUser();
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.applicationInfo = new ApplicationInfo();
        when(mMockPackageManager.resolveActivityAsUser(
                any(Intent.class), anyInt(), anyInt())).thenReturn(resolveInfo);
        when(mMockPackageManager.checkSignatures(
                anyInt(), anyInt())).thenReturn(PackageManager.SIGNATURE_MATCH);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        Bundle options = createOptionsWithAccountName(
                AccountManagerServiceTestFixtures.ACCOUNT_NAME_INTERVENE);
        int prohibitedFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
        options.putInt(AccountManagerServiceTestFixtures.KEY_INTENT_FLAGS, prohibitedFlags);

        mAms.startAddAccountSession(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                true, // expectActivityLaunch
                options); // optionsIn
        waitForLatch(latch);

        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_INVALID_RESPONSE), contains("invalid intent"));
    }

    @SmallTest
    public void testStartAddAccountSessionError() throws Exception {
        unlockSystemUser();
        Bundle options = createOptionsWithAccountName(
                AccountManagerServiceTestFixtures.ACCOUNT_NAME_ERROR);
        options.putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_INVALID_RESPONSE);
        options.putString(AccountManager.KEY_ERROR_MESSAGE,
                AccountManagerServiceTestFixtures.ERROR_MESSAGE);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        mAms.startAddAccountSession(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                false, // expectActivityLaunch
                options); // optionsIn

        waitForLatch(latch);
        verify(mMockAccountManagerResponse).onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                AccountManagerServiceTestFixtures.ERROR_MESSAGE);
        verify(mMockAccountManagerResponse, never()).onResult(any(Bundle.class));
    }

    @SmallTest
    public void testStartUpdateCredentialsSessionWithNullResponse() throws Exception {
        unlockSystemUser();
        try {
            mAms.startUpdateCredentialsSession(
                null, // response
                AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS,
                "authTokenType",
                true, // expectActivityLaunch
                null); // optionsIn
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testStartUpdateCredentialsSessionWithNullAccount() throws Exception {
        unlockSystemUser();
        try {
            mAms.startUpdateCredentialsSession(
                mMockAccountManagerResponse, // response
                null,
                "authTokenType",
                true, // expectActivityLaunch
                null); // optionsIn
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testStartUpdateCredentialsSessionSuccessWithoutPasswordForwarding()
            throws Exception {
        unlockSystemUser();
        when(mMockContext.checkCallingOrSelfPermission(anyString())).thenReturn(
                PackageManager.PERMISSION_DENIED);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        Bundle options = createOptionsWithAccountName(
            AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS);
        mAms.startUpdateCredentialsSession(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS,
                "authTokenType",
                false, // expectActivityLaunch
                options); // optionsIn
        waitForLatch(latch);
        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        Bundle sessionBundle = result.getBundle(AccountManager.KEY_ACCOUNT_SESSION_BUNDLE);
        assertNotNull(sessionBundle);
        // Assert that session bundle is encrypted and hence data not visible.
        assertNull(sessionBundle.getString(AccountManagerServiceTestFixtures.SESSION_DATA_NAME_1));
        // Assert password is not returned
        assertNull(result.getString(AccountManager.KEY_PASSWORD));
        assertNull(result.getString(AccountManager.KEY_AUTHTOKEN, null));
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_STATUS_TOKEN,
                result.getString(AccountManager.KEY_ACCOUNT_STATUS_TOKEN));
    }

    @SmallTest
    public void testStartUpdateCredentialsSessionSuccessWithPasswordForwarding() throws Exception {
        unlockSystemUser();
        when(mMockContext.checkCallingOrSelfPermission(anyString())).thenReturn(
                PackageManager.PERMISSION_GRANTED);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        Bundle options = createOptionsWithAccountName(
            AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS);
        mAms.startUpdateCredentialsSession(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS,
                "authTokenType",
                false, // expectActivityLaunch
                options); // optionsIn

        waitForLatch(latch);
        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        Bundle sessionBundle = result.getBundle(AccountManager.KEY_ACCOUNT_SESSION_BUNDLE);
        assertNotNull(sessionBundle);
        // Assert that session bundle is encrypted and hence data not visible.
        assertNull(sessionBundle.getString(AccountManagerServiceTestFixtures.SESSION_DATA_NAME_1));
        // Assert password is returned
        assertEquals(result.getString(AccountManager.KEY_PASSWORD),
                AccountManagerServiceTestFixtures.ACCOUNT_PASSWORD);
        assertNull(result.getString(AccountManager.KEY_AUTHTOKEN));
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_STATUS_TOKEN,
                result.getString(AccountManager.KEY_ACCOUNT_STATUS_TOKEN));
    }

    @SmallTest
    public void testStartUpdateCredentialsSessionReturnWithInvalidIntent() throws Exception {
        unlockSystemUser();
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.applicationInfo = new ApplicationInfo();
        when(mMockPackageManager.resolveActivityAsUser(
                any(Intent.class), anyInt(), anyInt())).thenReturn(resolveInfo);
        when(mMockPackageManager.checkSignatures(
                anyInt(), anyInt())).thenReturn(PackageManager.SIGNATURE_NO_MATCH);
        when(mMockPackageManagerInternal.hasSignatureCapability(anyInt(), anyInt(), anyInt()))
                .thenReturn(false);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        Bundle options = createOptionsWithAccountName(
                AccountManagerServiceTestFixtures.ACCOUNT_NAME_INTERVENE);

        mAms.startUpdateCredentialsSession(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_INTERVENE,
                "authTokenType",
                true,  // expectActivityLaunch
                options); // optionsIn

        waitForLatch(latch);
        verify(mMockAccountManagerResponse, never()).onResult(any(Bundle.class));
        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_INVALID_RESPONSE), anyString());
    }

    @SmallTest
    public void testStartUpdateCredentialsSessionReturnWithValidIntent() throws Exception {
        unlockSystemUser();
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.applicationInfo = new ApplicationInfo();
        when(mMockPackageManager.resolveActivityAsUser(
                any(Intent.class), anyInt(), anyInt())).thenReturn(resolveInfo);
        when(mMockPackageManager.checkSignatures(
                anyInt(), anyInt())).thenReturn(PackageManager.SIGNATURE_MATCH);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        Bundle options = createOptionsWithAccountName(
                AccountManagerServiceTestFixtures.ACCOUNT_NAME_INTERVENE);

        mAms.startUpdateCredentialsSession(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_INTERVENE,
                "authTokenType",
                true,  // expectActivityLaunch
                options); // optionsIn

        waitForLatch(latch);

        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        Intent intent = result.getParcelable(AccountManager.KEY_INTENT);
        assertNotNull(intent);
        assertNotNull(intent.getParcelableExtra(AccountManagerServiceTestFixtures.KEY_RESULT));
        assertNotNull(intent.getParcelableExtra(AccountManagerServiceTestFixtures.KEY_CALLBACK));
    }

    @SmallTest
    public void testStartUpdateCredentialsSessionError() throws Exception {
        unlockSystemUser();
        Bundle options = createOptionsWithAccountName(
                AccountManagerServiceTestFixtures.ACCOUNT_NAME_ERROR);
        options.putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_INVALID_RESPONSE);
        options.putString(AccountManager.KEY_ERROR_MESSAGE,
                AccountManagerServiceTestFixtures.ERROR_MESSAGE);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);

        mAms.startUpdateCredentialsSession(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_ERROR,
                "authTokenType",
                true,  // expectActivityLaunch
                options); // optionsIn

        waitForLatch(latch);
        verify(mMockAccountManagerResponse).onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                AccountManagerServiceTestFixtures.ERROR_MESSAGE);
        verify(mMockAccountManagerResponse, never()).onResult(any(Bundle.class));
    }

    @SmallTest
    public void testFinishSessionAsUserWithNullResponse() throws Exception {
        unlockSystemUser();
        try {
            mAms.finishSessionAsUser(
                null, // response
                createEncryptedSessionBundle(
                        AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS),
                false, // expectActivityLaunch
                createAppBundle(), // appInfo
                UserHandle.USER_SYSTEM);
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testFinishSessionAsUserWithNullSessionBundle() throws Exception {
        unlockSystemUser();
        try {
            mAms.finishSessionAsUser(
                mMockAccountManagerResponse, // response
                null, // sessionBundle
                false, // expectActivityLaunch
                createAppBundle(), // appInfo
                UserHandle.USER_SYSTEM);
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testFinishSessionAsUserUserCannotModifyAccountNoDPM() throws Exception {
        unlockSystemUser();
        Bundle bundle = new Bundle();
        bundle.putBoolean(UserManager.DISALLOW_MODIFY_ACCOUNTS, true);
        when(mMockUserManager.getUserRestrictions(any(UserHandle.class))).thenReturn(bundle);
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);

        mAms.finishSessionAsUser(
            mMockAccountManagerResponse, // response
            createEncryptedSessionBundle(AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS),
            false, // expectActivityLaunch
            createAppBundle(), // appInfo
            2); // fake user id

        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_USER_RESTRICTED), anyString());
        verify(mMockContext).startActivityAsUser(mIntentCaptor.capture(), eq(UserHandle.of(2)));

        // verify the intent for default CantAddAccountActivity is sent.
        Intent intent = mIntentCaptor.getValue();
        assertEquals(intent.getComponent().getClassName(), CantAddAccountActivity.class.getName());
        assertEquals(intent.getIntExtra(CantAddAccountActivity.EXTRA_ERROR_CODE, 0),
                AccountManager.ERROR_CODE_USER_RESTRICTED);
    }

    @SmallTest
    public void testFinishSessionAsUserUserCannotModifyAccountWithDPM() throws Exception {
        unlockSystemUser();
        Bundle bundle = new Bundle();
        bundle.putBoolean(UserManager.DISALLOW_MODIFY_ACCOUNTS, true);
        when(mMockUserManager.getUserRestrictions(any(UserHandle.class))).thenReturn(bundle);
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
        LocalServices.addService(
                DevicePolicyManagerInternal.class, mMockDevicePolicyManagerInternal);
        when(mMockDevicePolicyManagerInternal.createUserRestrictionSupportIntent(
                anyInt(), anyString())).thenReturn(new Intent());
        when(mMockDevicePolicyManagerInternal.createShowAdminSupportIntent(
                anyInt(), anyBoolean())).thenReturn(new Intent());

        mAms.finishSessionAsUser(
            mMockAccountManagerResponse, // response
            createEncryptedSessionBundle(AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS),
            false, // expectActivityLaunch
            createAppBundle(), // appInfo
            2); // fake user id

        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_USER_RESTRICTED), anyString());
        verify(mMockContext).startActivityAsUser(any(Intent.class), eq(UserHandle.of(2)));
        verify(mMockDevicePolicyManagerInternal).createUserRestrictionSupportIntent(
                anyInt(), anyString());
    }

    @SmallTest
    public void testFinishSessionAsUserWithBadSessionBundle() throws Exception {
        unlockSystemUser();

        Bundle badSessionBundle = new Bundle();
        badSessionBundle.putString("any", "any");
        mAms.finishSessionAsUser(
            mMockAccountManagerResponse, // response
            badSessionBundle, // sessionBundle
            false, // expectActivityLaunch
            createAppBundle(), // appInfo
            2); // fake user id

        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_BAD_REQUEST), anyString());
    }

    @SmallTest
    public void testFinishSessionAsUserWithBadAccountType() throws Exception {
        unlockSystemUser();

        mAms.finishSessionAsUser(
            mMockAccountManagerResponse, // response
            createEncryptedSessionBundleWithNoAccountType(
                    AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS),
            false, // expectActivityLaunch
            createAppBundle(), // appInfo
            2); // fake user id

        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_BAD_ARGUMENTS), anyString());
    }

    @SmallTest
    public void testFinishSessionAsUserUserCannotModifyAccountForTypeNoDPM() throws Exception {
        unlockSystemUser();
        when(mMockDevicePolicyManager.getAccountTypesWithManagementDisabledAsUser(anyInt()))
                .thenReturn(new String[]{AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, "BBB"});
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);

        mAms.finishSessionAsUser(
            mMockAccountManagerResponse, // response
            createEncryptedSessionBundle(AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS),
            false, // expectActivityLaunch
            createAppBundle(), // appInfo
            2); // fake user id

        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE), anyString());
        verify(mMockContext).startActivityAsUser(mIntentCaptor.capture(), eq(UserHandle.of(2)));

        // verify the intent for default CantAddAccountActivity is sent.
        Intent intent = mIntentCaptor.getValue();
        assertEquals(intent.getComponent().getClassName(), CantAddAccountActivity.class.getName());
        assertEquals(intent.getIntExtra(CantAddAccountActivity.EXTRA_ERROR_CODE, 0),
                AccountManager.ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE);
    }

    @SmallTest
    public void testFinishSessionAsUserUserCannotModifyAccountForTypeWithDPM() throws Exception {
        unlockSystemUser();
        when(mMockContext.getSystemService(Context.DEVICE_POLICY_SERVICE)).thenReturn(
                mMockDevicePolicyManager);
        when(mMockDevicePolicyManager.getAccountTypesWithManagementDisabledAsUser(anyInt()))
                .thenReturn(new String[]{AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, "BBB"});

        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
        LocalServices.addService(
                DevicePolicyManagerInternal.class, mMockDevicePolicyManagerInternal);
        when(mMockDevicePolicyManagerInternal.createUserRestrictionSupportIntent(
                anyInt(), anyString())).thenReturn(new Intent());
        when(mMockDevicePolicyManagerInternal.createShowAdminSupportIntent(
                anyInt(), anyBoolean())).thenReturn(new Intent());

        mAms.finishSessionAsUser(
            mMockAccountManagerResponse, // response
            createEncryptedSessionBundle(AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS),
            false, // expectActivityLaunch
            createAppBundle(), // appInfo
            2); // fake user id

        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE), anyString());
        verify(mMockContext).startActivityAsUser(any(Intent.class), eq(UserHandle.of(2)));
        verify(mMockDevicePolicyManagerInternal).createShowAdminSupportIntent(
                anyInt(), anyBoolean());
    }

    @SmallTest
    public void testFinishSessionAsUserSuccess() throws Exception {
        unlockSystemUser();
        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        mAms.finishSessionAsUser(
            response, // response
            createEncryptedSessionBundle(AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS),
            false, // expectActivityLaunch
            createAppBundle(), // appInfo
            UserHandle.USER_SYSTEM);

        waitForLatch(latch);
        // Verify notification is cancelled
        verify(mMockNotificationManager).cancelNotificationWithTag(anyString(),
                anyString(), nullable(String.class), anyInt(), anyInt());

        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        Bundle sessionBundle = result.getBundle(AccountManager.KEY_ACCOUNT_SESSION_BUNDLE);
        assertNotNull(sessionBundle);
        // Assert that session bundle is decrypted and hence data is visible.
        assertEquals(AccountManagerServiceTestFixtures.SESSION_DATA_VALUE_1,
                sessionBundle.getString(AccountManagerServiceTestFixtures.SESSION_DATA_NAME_1));
        // Assert finishSessionAsUser added calling uid and pid into the sessionBundle
        assertTrue(sessionBundle.containsKey(AccountManager.KEY_CALLER_UID));
        assertTrue(sessionBundle.containsKey(AccountManager.KEY_CALLER_PID));
        assertEquals(sessionBundle.getString(
                AccountManager.KEY_ANDROID_PACKAGE_NAME), "APCT.package");

        // Verify response data
        assertNull(result.getString(AccountManager.KEY_AUTHTOKEN, null));
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_NAME,
                result.getString(AccountManager.KEY_ACCOUNT_NAME));
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1,
                result.getString(AccountManager.KEY_ACCOUNT_TYPE));
    }

    @SmallTest
    public void testFinishSessionAsUserReturnWithInvalidIntent() throws Exception {
        unlockSystemUser();
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.applicationInfo = new ApplicationInfo();
        when(mMockPackageManager.resolveActivityAsUser(
                any(Intent.class), anyInt(), anyInt())).thenReturn(resolveInfo);
        when(mMockPackageManager.checkSignatures(
                anyInt(), anyInt())).thenReturn(PackageManager.SIGNATURE_NO_MATCH);
        when(mMockPackageManagerInternal.hasSignatureCapability(anyInt(), anyInt(), anyInt()))
                .thenReturn(false);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);

        mAms.finishSessionAsUser(
            response, // response
            createEncryptedSessionBundle(AccountManagerServiceTestFixtures.ACCOUNT_NAME_INTERVENE),
            true, // expectActivityLaunch
            createAppBundle(), // appInfo
            UserHandle.USER_SYSTEM);

        waitForLatch(latch);
        verify(mMockAccountManagerResponse, never()).onResult(any(Bundle.class));
        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_INVALID_RESPONSE), anyString());
    }

    @SmallTest
    public void testFinishSessionAsUserReturnWithValidIntent() throws Exception {
        unlockSystemUser();
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.applicationInfo = new ApplicationInfo();
        when(mMockPackageManager.resolveActivityAsUser(
                any(Intent.class), anyInt(), anyInt())).thenReturn(resolveInfo);
        when(mMockPackageManager.checkSignatures(
                anyInt(), anyInt())).thenReturn(PackageManager.SIGNATURE_MATCH);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);

        mAms.finishSessionAsUser(
            response, // response
            createEncryptedSessionBundle(AccountManagerServiceTestFixtures.ACCOUNT_NAME_INTERVENE),
            true, // expectActivityLaunch
            createAppBundle(), // appInfo
            UserHandle.USER_SYSTEM);

        waitForLatch(latch);

        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        Intent intent = result.getParcelable(AccountManager.KEY_INTENT);
        assertNotNull(intent);
        assertNotNull(intent.getParcelableExtra(AccountManagerServiceTestFixtures.KEY_RESULT));
        assertNotNull(intent.getParcelableExtra(AccountManagerServiceTestFixtures.KEY_CALLBACK));
    }

    @SmallTest
    public void testFinishSessionAsUserError() throws Exception {
        unlockSystemUser();
        Bundle sessionBundle = createEncryptedSessionBundleWithError(
                AccountManagerServiceTestFixtures.ACCOUNT_NAME_ERROR);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);

        mAms.finishSessionAsUser(
            response, // response
            sessionBundle,
            false, // expectActivityLaunch
            createAppBundle(), // appInfo
            UserHandle.USER_SYSTEM);

        waitForLatch(latch);
        verify(mMockAccountManagerResponse).onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                AccountManagerServiceTestFixtures.ERROR_MESSAGE);
        verify(mMockAccountManagerResponse, never()).onResult(any(Bundle.class));
    }

    @SmallTest
    public void testIsCredentialsUpdatedSuggestedWithNullResponse() throws Exception {
        unlockSystemUser();
        try {
            mAms.isCredentialsUpdateSuggested(
                null, // response
                AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS,
                AccountManagerServiceTestFixtures.ACCOUNT_STATUS_TOKEN);
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testIsCredentialsUpdatedSuggestedWithNullAccount() throws Exception {
        unlockSystemUser();
        try {
            mAms.isCredentialsUpdateSuggested(
                mMockAccountManagerResponse,
                null, // account
                AccountManagerServiceTestFixtures.ACCOUNT_STATUS_TOKEN);
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testIsCredentialsUpdatedSuggestedWithEmptyStatusToken() throws Exception {
        unlockSystemUser();
        try {
            mAms.isCredentialsUpdateSuggested(
                mMockAccountManagerResponse,
                AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS,
                null);
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testIsCredentialsUpdatedSuggestedError() throws Exception {
        unlockSystemUser();
        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);

        mAms.isCredentialsUpdateSuggested(
            response,
            AccountManagerServiceTestFixtures.ACCOUNT_ERROR,
            AccountManagerServiceTestFixtures.ACCOUNT_STATUS_TOKEN);

        waitForLatch(latch);
        verify(mMockAccountManagerResponse).onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                AccountManagerServiceTestFixtures.ERROR_MESSAGE);
        verify(mMockAccountManagerResponse, never()).onResult(any(Bundle.class));
    }

    @SmallTest
    public void testIsCredentialsUpdatedSuggestedSuccess() throws Exception {
        unlockSystemUser();
        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);

        mAms.isCredentialsUpdateSuggested(
            response,
            AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS,
            AccountManagerServiceTestFixtures.ACCOUNT_STATUS_TOKEN);

        waitForLatch(latch);
        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        boolean needUpdate = result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT);
        assertTrue(needUpdate);
    }

    @SmallTest
    public void testHasFeaturesWithNullResponse() throws Exception {
        unlockSystemUser();
        try {
            mAms.hasFeatures(
                null, // response
                AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS,
                new String[] {"feature1", "feature2"}, // features
                "testPackage"); // opPackageName
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testHasFeaturesWithNullAccount() throws Exception {
        unlockSystemUser();
        try {
            mAms.hasFeatures(
                mMockAccountManagerResponse, // response
                null, // account
                new String[] {"feature1", "feature2"}, // features
                "testPackage"); // opPackageName
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testHasFeaturesWithNullFeature() throws Exception {
        unlockSystemUser();
        try {
            mAms.hasFeatures(
                    mMockAccountManagerResponse, // response
                    AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, // account
                    null, // features
                    "testPackage"); // opPackageName
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testHasFeaturesReturnNullResult() throws Exception {
        unlockSystemUser();
        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        mAms.hasFeatures(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_ERROR, // account
                AccountManagerServiceTestFixtures.ACCOUNT_FEATURES, // features
                "testPackage"); // opPackageName
        waitForLatch(latch);
        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_INVALID_RESPONSE), anyString());
        verify(mMockAccountManagerResponse, never()).onResult(any(Bundle.class));
    }

    @SmallTest
    public void testHasFeaturesSuccess() throws Exception {
        unlockSystemUser();
        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        mAms.hasFeatures(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, // account
                AccountManagerServiceTestFixtures.ACCOUNT_FEATURES, // features
                "testPackage"); // opPackageName
        waitForLatch(latch);
        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        boolean hasFeatures = result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT);
        assertTrue(hasFeatures);
    }

    @SmallTest
    public void testRemoveAccountAsUserWithNullResponse() throws Exception {
        unlockSystemUser();
        try {
            mAms.removeAccountAsUser(
                null, // response
                AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS,
                true, // expectActivityLaunch
                UserHandle.USER_SYSTEM);
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testRemoveAccountAsUserWithNullAccount() throws Exception {
        unlockSystemUser();
        try {
            mAms.removeAccountAsUser(
                mMockAccountManagerResponse, // response
                null, // account
                true, // expectActivityLaunch
                UserHandle.USER_SYSTEM);
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testRemoveAccountAsUserAccountNotManagedByCaller() throws Exception {
        unlockSystemUser();
        when(mMockPackageManager.checkSignatures(anyInt(), anyInt()))
                    .thenReturn(PackageManager.SIGNATURE_NO_MATCH);
        when(mMockPackageManagerInternal.hasSignatureCapability(anyInt(), anyInt(), anyInt()))
                .thenReturn(false);
        try {
            mAms.removeAccountAsUser(
                mMockAccountManagerResponse, // response
                AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS,
                true, // expectActivityLaunch
                UserHandle.USER_SYSTEM);
            fail("SecurityException expected. But no exception was thrown.");
        } catch (SecurityException e) {
            // SecurityException is expected.
        }
    }

    @SmallTest
    public void testRemoveAccountAsUserUserCannotModifyAccount() throws Exception {
        unlockSystemUser();
        Bundle bundle = new Bundle();
        bundle.putBoolean(UserManager.DISALLOW_MODIFY_ACCOUNTS, true);
        when(mMockUserManager.getUserRestrictions(any(UserHandle.class))).thenReturn(bundle);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);

        mAms.removeAccountAsUser(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS,
                true, // expectActivityLaunch
                UserHandle.USER_SYSTEM);
        waitForLatch(latch);
        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_USER_RESTRICTED), anyString());
        verify(mMockAccountManagerResponse, never()).onResult(any(Bundle.class));
    }

    @SmallTest
    public void testRemoveAccountAsUserUserCannotModifyAccountType() throws Exception {
        unlockSystemUser();
        when(mMockContext.getSystemService(Context.DEVICE_POLICY_SERVICE)).thenReturn(
                mMockDevicePolicyManager);
        when(mMockDevicePolicyManager.getAccountTypesWithManagementDisabledAsUser(anyInt()))
                .thenReturn(new String[]{AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, "BBB"});

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);

        mAms.removeAccountAsUser(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS,
                true, // expectActivityLaunch
                UserHandle.USER_SYSTEM);
        waitForLatch(latch);
        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE), anyString());
        verify(mMockAccountManagerResponse, never()).onResult(any(Bundle.class));
    }

    @SmallTest
    public void testRemoveAccountAsUserRemovalAllowed() throws Exception {
        String[] list = new String[]{AccountManagerServiceTestFixtures.CALLER_PACKAGE};
        when(mMockPackageManager.getPackagesForUid(anyInt())).thenReturn(list);

        unlockSystemUser();
        mAms.addAccountExplicitly(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, "p1", null);
        Account[] addedAccounts =
                mAms.getAccounts(UserHandle.USER_SYSTEM, mContext.getOpPackageName());
        assertEquals(1, addedAccounts.length);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);

        mAms.removeAccountAsUser(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS,
                true, // expectActivityLaunch
                UserHandle.USER_SYSTEM);
        waitForLatch(latch);

        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        boolean allowed = result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT);
        assertTrue(allowed);
        Account[] accounts = mAms.getAccounts(UserHandle.USER_SYSTEM, mContext.getOpPackageName());
        assertEquals(0, accounts.length);
    }

    @SmallTest
    public void testRemoveAccountAsUserRemovalNotAllowed() throws Exception {
        unlockSystemUser();

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);

        mAms.removeAccountAsUser(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_ERROR,
                true, // expectActivityLaunch
                UserHandle.USER_SYSTEM);
        waitForLatch(latch);

        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        boolean allowed = result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT);
        assertFalse(allowed);
    }

    @SmallTest
    public void testRemoveAccountAsUserReturnWithValidIntent() throws Exception {
        unlockSystemUser();
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.applicationInfo = new ApplicationInfo();
        when(mMockPackageManager.resolveActivityAsUser(
                any(Intent.class), anyInt(), anyInt())).thenReturn(resolveInfo);
        when(mMockPackageManager.checkSignatures(
                anyInt(), anyInt())).thenReturn(PackageManager.SIGNATURE_MATCH);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);

        mAms.removeAccountAsUser(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_INTERVENE,
                true, // expectActivityLaunch
                UserHandle.USER_SYSTEM);
        waitForLatch(latch);

        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        Intent intent = result.getParcelable(AccountManager.KEY_INTENT);
        assertNotNull(intent);
    }

    @SmallTest
    public void testGetAccountsByTypeForPackageWhenTypeIsNull() throws Exception {
        unlockSystemUser();
        HashMap<String, Integer> visibility1 = new HashMap<>();
        visibility1.put(AccountManagerServiceTestFixtures.CALLER_PACKAGE,
            AccountManager.VISIBILITY_USER_MANAGED_VISIBLE);

        HashMap<String, Integer> visibility2 = new HashMap<>();
        visibility2.put(AccountManagerServiceTestFixtures.CALLER_PACKAGE,
            AccountManager.VISIBILITY_USER_MANAGED_NOT_VISIBLE);

        mAms.addAccountExplicitlyWithVisibility(
            AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, "P11", null, visibility1);
        mAms.addAccountExplicitlyWithVisibility(
            AccountManagerServiceTestFixtures.ACCOUNT_INTERVENE, "P12", null, visibility2);

        Account[] accounts = mAms.getAccountsByTypeForPackage(
            null, "otherPackageName",
            AccountManagerServiceTestFixtures.CALLER_PACKAGE);
        // Only get the USER_MANAGED_NOT_VISIBLE account.
        assertEquals(1, accounts.length);
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS, accounts[0].name);
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, accounts[0].type);
    }

    @SmallTest
    public void testGetAuthTokenLabelWithNullAccountType() throws Exception {
        unlockSystemUser();
        try {
            mAms.getAuthTokenLabel(
                mMockAccountManagerResponse, // response
                null, // accountType
                "authTokenType");
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testGetAuthTokenLabelWithNullAuthTokenType() throws Exception {
        unlockSystemUser();
        try {
            mAms.getAuthTokenLabel(
                mMockAccountManagerResponse, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                null); // authTokenType
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testGetAuthTokenWithNullResponse() throws Exception {
        unlockSystemUser();
        try {
            mAms.getAuthToken(
                    null, // response
                    AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS,
                    "authTokenType", // authTokenType
                    true, // notifyOnAuthFailure
                    true, // expectActivityLaunch
                    createGetAuthTokenOptions());
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testGetAuthTokenWithNullAccount() throws Exception {
        unlockSystemUser();
        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        mAms.getAuthToken(
                    response, // response
                    null, // account
                    "authTokenType", // authTokenType
                    true, // notifyOnAuthFailure
                    true, // expectActivityLaunch
                    createGetAuthTokenOptions());
        waitForLatch(latch);

        verify(mMockAccountManagerResponse, never()).onResult(any(Bundle.class));
        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_BAD_ARGUMENTS), anyString());
    }

    @SmallTest
    public void testGetAuthTokenWithNullAuthTokenType() throws Exception {
        unlockSystemUser();
        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        mAms.getAuthToken(
                    response, // response
                    AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS,
                    null, // authTokenType
                    true, // notifyOnAuthFailure
                    true, // expectActivityLaunch
                    createGetAuthTokenOptions());
        waitForLatch(latch);

        verify(mMockAccountManagerResponse, never()).onResult(any(Bundle.class));
        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_BAD_ARGUMENTS), anyString());
    }

    @SmallTest
    public void testGetAuthTokenWithInvalidPackage() throws Exception {
        unlockSystemUser();
        String[] list = new String[]{"test"};
        when(mMockPackageManager.getPackagesForUid(anyInt())).thenReturn(list);
        try {
            mAms.getAuthToken(
                    mMockAccountManagerResponse, // response
                    AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS,
                    "authTokenType", // authTokenType
                    true, // notifyOnAuthFailure
                    true, // expectActivityLaunch
                    createGetAuthTokenOptions());
            fail("SecurityException expected. But no exception was thrown.");
        } catch (SecurityException e) {
            // SecurityException is expected.
        }
    }

    @SmallTest
    public void testGetAuthTokenFromInternal() throws Exception {
        unlockSystemUser();
        when(mMockContext.createPackageContextAsUser(
                 anyString(), anyInt(), any(UserHandle.class))).thenReturn(mMockContext);
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        String[] list = new String[]{AccountManagerServiceTestFixtures.CALLER_PACKAGE};
        when(mMockPackageManager.getPackagesForUid(anyInt())).thenReturn(list);
        mAms.addAccountExplicitly(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, "p11", null);

        mAms.setAuthToken(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS,
                "authTokenType", AccountManagerServiceTestFixtures.AUTH_TOKEN);
        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        mAms.getAuthToken(
                    response, // response
                    AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS,
                    "authTokenType", // authTokenType
                    true, // notifyOnAuthFailure
                    true, // expectActivityLaunch
                    createGetAuthTokenOptions());
        waitForLatch(latch);

        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        assertEquals(result.getString(AccountManager.KEY_AUTHTOKEN),
                AccountManagerServiceTestFixtures.AUTH_TOKEN);
        assertEquals(result.getString(AccountManager.KEY_ACCOUNT_NAME),
                AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS);
        assertEquals(result.getString(AccountManager.KEY_ACCOUNT_TYPE),
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);
    }

    @SmallTest
    public void testGetAuthTokenSuccess() throws Exception {
        unlockSystemUser();
        when(mMockContext.createPackageContextAsUser(
                 anyString(), anyInt(), any(UserHandle.class))).thenReturn(mMockContext);
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        String[] list = new String[]{AccountManagerServiceTestFixtures.CALLER_PACKAGE};
        when(mMockPackageManager.getPackagesForUid(anyInt())).thenReturn(list);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        mAms.getAuthToken(
                    response, // response
                    AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS,
                    "authTokenType", // authTokenType
                    true, // notifyOnAuthFailure
                    false, // expectActivityLaunch
                    createGetAuthTokenOptions());
        waitForLatch(latch);

        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        assertEquals(result.getString(AccountManager.KEY_AUTHTOKEN),
                AccountManagerServiceTestFixtures.AUTH_TOKEN);
        assertEquals(result.getString(AccountManager.KEY_ACCOUNT_NAME),
                AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS);
        assertEquals(result.getString(AccountManager.KEY_ACCOUNT_TYPE),
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);
    }

    @SmallTest
    public void testGetAuthTokenReturnWithInvalidIntent() throws Exception {
        unlockSystemUser();
        when(mMockContext.createPackageContextAsUser(
                 anyString(), anyInt(), any(UserHandle.class))).thenReturn(mMockContext);
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        String[] list = new String[]{AccountManagerServiceTestFixtures.CALLER_PACKAGE};
        when(mMockPackageManager.getPackagesForUid(anyInt())).thenReturn(list);
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.applicationInfo = new ApplicationInfo();
        when(mMockPackageManager.resolveActivityAsUser(
                any(Intent.class), anyInt(), anyInt())).thenReturn(resolveInfo);
        when(mMockPackageManager.checkSignatures(
                anyInt(), anyInt())).thenReturn(PackageManager.SIGNATURE_NO_MATCH);
        when(mMockPackageManagerInternal.hasSignatureCapability(anyInt(), anyInt(), anyInt()))
                .thenReturn(false);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        mAms.getAuthToken(
                    response, // response
                    AccountManagerServiceTestFixtures.ACCOUNT_INTERVENE,
                    "authTokenType", // authTokenType
                    true, // notifyOnAuthFailure
                    false, // expectActivityLaunch
                    createGetAuthTokenOptions());
        waitForLatch(latch);
        verify(mMockAccountManagerResponse, never()).onResult(any(Bundle.class));
        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_INVALID_RESPONSE), anyString());
    }

    @SmallTest
    public void testGetAuthTokenReturnWithValidIntent() throws Exception {
        unlockSystemUser();
        when(mMockContext.createPackageContextAsUser(
                 anyString(), anyInt(), any(UserHandle.class))).thenReturn(mMockContext);
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        String[] list = new String[]{AccountManagerServiceTestFixtures.CALLER_PACKAGE};
        when(mMockPackageManager.getPackagesForUid(anyInt())).thenReturn(list);

        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.applicationInfo = new ApplicationInfo();
        when(mMockPackageManager.resolveActivityAsUser(
                any(Intent.class), anyInt(), anyInt())).thenReturn(resolveInfo);
        when(mMockPackageManager.checkSignatures(
                anyInt(), anyInt())).thenReturn(PackageManager.SIGNATURE_MATCH);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        mAms.getAuthToken(
                    response, // response
                    AccountManagerServiceTestFixtures.ACCOUNT_INTERVENE,
                    "authTokenType", // authTokenType
                    false, // notifyOnAuthFailure
                    true, // expectActivityLaunch
                    createGetAuthTokenOptions());
        waitForLatch(latch);
        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        Intent intent = result.getParcelable(AccountManager.KEY_INTENT);
        assertNotNull(intent);
        assertNotNull(intent.getParcelableExtra(AccountManagerServiceTestFixtures.KEY_RESULT));
        assertNotNull(intent.getParcelableExtra(AccountManagerServiceTestFixtures.KEY_CALLBACK));
    }

    @SmallTest
    public void testGetAuthTokenError() throws Exception {
        unlockSystemUser();
        when(mMockContext.createPackageContextAsUser(
                 anyString(), anyInt(), any(UserHandle.class))).thenReturn(mMockContext);
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        String[] list = new String[]{AccountManagerServiceTestFixtures.CALLER_PACKAGE};
        when(mMockPackageManager.getPackagesForUid(anyInt())).thenReturn(list);
        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        mAms.getAuthToken(
                    response, // response
                    AccountManagerServiceTestFixtures.ACCOUNT_ERROR,
                    "authTokenType", // authTokenType
                    true, // notifyOnAuthFailure
                    false, // expectActivityLaunch
                    createGetAuthTokenOptions());
        waitForLatch(latch);
        verify(mMockAccountManagerResponse).onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                AccountManagerServiceTestFixtures.ERROR_MESSAGE);
        verify(mMockAccountManagerResponse, never()).onResult(any(Bundle.class));

    }

    @SmallTest
    public void testAddAccountAsUserWithNullResponse() throws Exception {
        unlockSystemUser();
        try {
            mAms.addAccountAsUser(
                null, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1,
                "authTokenType",
                null, // requiredFeatures
                true, // expectActivityLaunch
                null, // optionsIn
                UserHandle.USER_SYSTEM);
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testAddAccountAsUserWithNullAccountType() throws Exception {
        unlockSystemUser();
        try {
            mAms.addAccountAsUser(
                mMockAccountManagerResponse, // response
                null, // accountType
                "authTokenType",
                null, // requiredFeatures
                true, // expectActivityLaunch
                null, // optionsIn
                UserHandle.USER_SYSTEM);
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testAddAccountAsUserUserCannotModifyAccountNoDPM() throws Exception {
        unlockSystemUser();
        Bundle bundle = new Bundle();
        bundle.putBoolean(UserManager.DISALLOW_MODIFY_ACCOUNTS, true);
        when(mMockUserManager.getUserRestrictions(any(UserHandle.class))).thenReturn(bundle);
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);

        mAms.addAccountAsUser(
                mMockAccountManagerResponse, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                true, // expectActivityLaunch
                null, // optionsIn
                UserHandle.USER_SYSTEM);
        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_USER_RESTRICTED), anyString());
        verify(mMockContext).startActivityAsUser(mIntentCaptor.capture(), eq(UserHandle.SYSTEM));

        // verify the intent for default CantAddAccountActivity is sent.
        Intent intent = mIntentCaptor.getValue();
        assertEquals(intent.getComponent().getClassName(), CantAddAccountActivity.class.getName());
        assertEquals(intent.getIntExtra(CantAddAccountActivity.EXTRA_ERROR_CODE, 0),
                AccountManager.ERROR_CODE_USER_RESTRICTED);
    }

    @SmallTest
    public void testAddAccountAsUserUserCannotModifyAccountWithDPM() throws Exception {
        unlockSystemUser();
        Bundle bundle = new Bundle();
        bundle.putBoolean(UserManager.DISALLOW_MODIFY_ACCOUNTS, true);
        when(mMockUserManager.getUserRestrictions(any(UserHandle.class))).thenReturn(bundle);
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
        LocalServices.addService(
                DevicePolicyManagerInternal.class, mMockDevicePolicyManagerInternal);
        when(mMockDevicePolicyManagerInternal.createUserRestrictionSupportIntent(
                anyInt(), anyString())).thenReturn(new Intent());
        when(mMockDevicePolicyManagerInternal.createShowAdminSupportIntent(
                anyInt(), anyBoolean())).thenReturn(new Intent());

        mAms.addAccountAsUser(
                mMockAccountManagerResponse, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                true, // expectActivityLaunch
                null, // optionsIn
                UserHandle.USER_SYSTEM);

        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_USER_RESTRICTED), anyString());
        verify(mMockContext).startActivityAsUser(any(Intent.class), eq(UserHandle.SYSTEM));
        verify(mMockDevicePolicyManagerInternal).createUserRestrictionSupportIntent(
                anyInt(), anyString());
    }

    @SmallTest
    public void testAddAccountAsUserUserCannotModifyAccountForTypeNoDPM() throws Exception {
        unlockSystemUser();
        when(mMockDevicePolicyManager.getAccountTypesWithManagementDisabledAsUser(anyInt()))
                .thenReturn(new String[]{AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, "BBB"});
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);

        mAms.addAccountAsUser(
                mMockAccountManagerResponse, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                true, // expectActivityLaunch
                null, // optionsIn
                UserHandle.USER_SYSTEM);

        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE), anyString());
        verify(mMockContext).startActivityAsUser(mIntentCaptor.capture(), eq(UserHandle.SYSTEM));

        // verify the intent for default CantAddAccountActivity is sent.
        Intent intent = mIntentCaptor.getValue();
        assertEquals(intent.getComponent().getClassName(), CantAddAccountActivity.class.getName());
        assertEquals(intent.getIntExtra(CantAddAccountActivity.EXTRA_ERROR_CODE, 0),
                AccountManager.ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE);
    }

    @SmallTest
    public void testAddAccountAsUserUserCannotModifyAccountForTypeWithDPM() throws Exception {
        unlockSystemUser();
        when(mMockContext.getSystemService(Context.DEVICE_POLICY_SERVICE)).thenReturn(
                mMockDevicePolicyManager);
        when(mMockDevicePolicyManager.getAccountTypesWithManagementDisabledAsUser(anyInt()))
                .thenReturn(new String[]{AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, "BBB"});

        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
        LocalServices.addService(
                DevicePolicyManagerInternal.class, mMockDevicePolicyManagerInternal);
        when(mMockDevicePolicyManagerInternal.createUserRestrictionSupportIntent(
                anyInt(), anyString())).thenReturn(new Intent());
        when(mMockDevicePolicyManagerInternal.createShowAdminSupportIntent(
                anyInt(), anyBoolean())).thenReturn(new Intent());

        mAms.addAccountAsUser(
                mMockAccountManagerResponse, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                true, // expectActivityLaunch
                null, // optionsIn
                UserHandle.USER_SYSTEM);

        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE), anyString());
        verify(mMockContext).startActivityAsUser(any(Intent.class), eq(UserHandle.SYSTEM));
        verify(mMockDevicePolicyManagerInternal).createShowAdminSupportIntent(
                anyInt(), anyBoolean());
    }

    @SmallTest
    public void testAddAccountAsUserSuccess() throws Exception {
        unlockSystemUser();
        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        mAms.addAccountAsUser(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                true, // expectActivityLaunch
                createAddAccountOptions(AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS),
                UserHandle.USER_SYSTEM);
        waitForLatch(latch);
        // Verify notification is cancelled
        verify(mMockNotificationManager).cancelNotificationWithTag(anyString(),
                anyString(), nullable(String.class), anyInt(), anyInt());

        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        // Verify response data
        assertNull(result.getString(AccountManager.KEY_AUTHTOKEN, null));
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS,
                result.getString(AccountManager.KEY_ACCOUNT_NAME));
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1,
                result.getString(AccountManager.KEY_ACCOUNT_TYPE));

        Bundle optionBundle = result.getParcelable(
                AccountManagerServiceTestFixtures.KEY_OPTIONS_BUNDLE);
        // Assert addAccountAsUser added calling uid and pid into the option bundle
        assertTrue(optionBundle.containsKey(AccountManager.KEY_CALLER_UID));
        assertTrue(optionBundle.containsKey(AccountManager.KEY_CALLER_PID));
    }

    @SmallTest
    public void testAddAccountAsUserReturnWithInvalidIntent() throws Exception {
        unlockSystemUser();
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.applicationInfo = new ApplicationInfo();
        when(mMockPackageManager.resolveActivityAsUser(
                any(Intent.class), anyInt(), anyInt())).thenReturn(resolveInfo);
        when(mMockPackageManager.checkSignatures(
                anyInt(), anyInt())).thenReturn(PackageManager.SIGNATURE_NO_MATCH);
        when(mMockPackageManagerInternal.hasSignatureCapability(anyInt(), anyInt(), anyInt()))
                .thenReturn(false);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        mAms.addAccountAsUser(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                true, // expectActivityLaunch
                createAddAccountOptions(AccountManagerServiceTestFixtures.ACCOUNT_NAME_INTERVENE),
                UserHandle.USER_SYSTEM);

        waitForLatch(latch);
        verify(mMockAccountManagerResponse, never()).onResult(any(Bundle.class));
        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_INVALID_RESPONSE), anyString());
    }

    @SmallTest
    public void testAddAccountAsUserReturnWithValidIntent() throws Exception {
        unlockSystemUser();
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.applicationInfo = new ApplicationInfo();
        when(mMockPackageManager.resolveActivityAsUser(
                any(Intent.class), anyInt(), anyInt())).thenReturn(resolveInfo);
        when(mMockPackageManager.checkSignatures(
                anyInt(), anyInt())).thenReturn(PackageManager.SIGNATURE_MATCH);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);

        mAms.addAccountAsUser(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                true, // expectActivityLaunch
                createAddAccountOptions(AccountManagerServiceTestFixtures.ACCOUNT_NAME_INTERVENE),
                UserHandle.USER_SYSTEM);

        waitForLatch(latch);

        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        Intent intent = result.getParcelable(AccountManager.KEY_INTENT);
        assertNotNull(intent);
        assertNotNull(intent.getParcelableExtra(AccountManagerServiceTestFixtures.KEY_RESULT));
        assertNotNull(intent.getParcelableExtra(AccountManagerServiceTestFixtures.KEY_CALLBACK));
    }

    @SmallTest
    public void testAddAccountAsUserError() throws Exception {
        unlockSystemUser();

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);

        mAms.addAccountAsUser(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                true, // expectActivityLaunch
                createAddAccountOptions(AccountManagerServiceTestFixtures.ACCOUNT_NAME_ERROR),
                UserHandle.USER_SYSTEM);

        waitForLatch(latch);
        verify(mMockAccountManagerResponse).onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                AccountManagerServiceTestFixtures.ERROR_MESSAGE);
        verify(mMockAccountManagerResponse, never()).onResult(any(Bundle.class));
    }

    @SmallTest
    public void testConfirmCredentialsAsUserWithNullResponse() throws Exception {
        unlockSystemUser();
        try {
            mAms.confirmCredentialsAsUser(
                null, // response
                AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS,
                new Bundle(), // options
                false, // expectActivityLaunch
                UserHandle.USER_SYSTEM);
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testConfirmCredentialsAsUserWithNullAccount() throws Exception {
        unlockSystemUser();
        try {
            mAms.confirmCredentialsAsUser(
                mMockAccountManagerResponse, // response
                null, // account
                new Bundle(), // options
                false, // expectActivityLaunch
                UserHandle.USER_SYSTEM);
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testConfirmCredentialsAsUserSuccess() throws Exception {
        unlockSystemUser();
        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        mAms.confirmCredentialsAsUser(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS,
                new Bundle(), // options
                true, // expectActivityLaunch
                UserHandle.USER_SYSTEM);
        waitForLatch(latch);

        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        // Verify response data
        assertTrue(result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT));
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS,
                result.getString(AccountManager.KEY_ACCOUNT_NAME));
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1,
                result.getString(AccountManager.KEY_ACCOUNT_TYPE));
    }

    @SmallTest
    public void testConfirmCredentialsAsUserReturnWithInvalidIntent() throws Exception {
        unlockSystemUser();
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.applicationInfo = new ApplicationInfo();
        when(mMockPackageManager.resolveActivityAsUser(
                any(Intent.class), anyInt(), anyInt())).thenReturn(resolveInfo);
        when(mMockPackageManager.checkSignatures(
                anyInt(), anyInt())).thenReturn(PackageManager.SIGNATURE_NO_MATCH);
        when(mMockPackageManagerInternal.hasSignatureCapability(anyInt(), anyInt(), anyInt()))
                .thenReturn(false);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        mAms.confirmCredentialsAsUser(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_INTERVENE,
                new Bundle(), // options
                true, // expectActivityLaunch
                UserHandle.USER_SYSTEM);
        waitForLatch(latch);

        verify(mMockAccountManagerResponse, never()).onResult(any(Bundle.class));
        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_INVALID_RESPONSE), anyString());
    }

    @SmallTest
    public void testConfirmCredentialsAsUserReturnWithValidIntent() throws Exception {
        unlockSystemUser();
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.applicationInfo = new ApplicationInfo();
        when(mMockPackageManager.resolveActivityAsUser(
                any(Intent.class), anyInt(), anyInt())).thenReturn(resolveInfo);
        when(mMockPackageManager.checkSignatures(
                anyInt(), anyInt())).thenReturn(PackageManager.SIGNATURE_MATCH);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);

        mAms.confirmCredentialsAsUser(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_INTERVENE,
                new Bundle(), // options
                true, // expectActivityLaunch
                UserHandle.USER_SYSTEM);

        waitForLatch(latch);

        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        Intent intent = result.getParcelable(AccountManager.KEY_INTENT);
        assertNotNull(intent);
        assertNotNull(intent.getParcelableExtra(AccountManagerServiceTestFixtures.KEY_RESULT));
        assertNotNull(intent.getParcelableExtra(AccountManagerServiceTestFixtures.KEY_CALLBACK));
    }

    @SmallTest
    public void testConfirmCredentialsAsUserError() throws Exception {
        unlockSystemUser();

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);

        mAms.confirmCredentialsAsUser(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_ERROR,
                new Bundle(), // options
                true, // expectActivityLaunch
                UserHandle.USER_SYSTEM);

        waitForLatch(latch);
        verify(mMockAccountManagerResponse).onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                AccountManagerServiceTestFixtures.ERROR_MESSAGE);
        verify(mMockAccountManagerResponse, never()).onResult(any(Bundle.class));
    }

    @SmallTest
    public void testUpdateCredentialsWithNullResponse() throws Exception {
        unlockSystemUser();
        try {
            mAms.updateCredentials(
                null, // response
                AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS,
                "authTokenType",
                false, // expectActivityLaunch
                new Bundle()); // options
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testUpdateCredentialsWithNullAccount() throws Exception {
        unlockSystemUser();
        try {
            mAms.updateCredentials(
                mMockAccountManagerResponse, // response
                null, // account
                "authTokenType",
                false, // expectActivityLaunch
                new Bundle()); // options
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testUpdateCredentialsSuccess() throws Exception {
        unlockSystemUser();
        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);

        mAms.updateCredentials(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS,
                "authTokenType",
                false, // expectActivityLaunch
                new Bundle()); // options

        waitForLatch(latch);

        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        // Verify response data
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS,
                result.getString(AccountManager.KEY_ACCOUNT_NAME));
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1,
                result.getString(AccountManager.KEY_ACCOUNT_TYPE));
    }

    @SmallTest
    public void testUpdateCredentialsReturnWithInvalidIntent() throws Exception {
        unlockSystemUser();
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.applicationInfo = new ApplicationInfo();
        when(mMockPackageManager.resolveActivityAsUser(
                any(Intent.class), anyInt(), anyInt())).thenReturn(resolveInfo);
        when(mMockPackageManager.checkSignatures(
                anyInt(), anyInt())).thenReturn(PackageManager.SIGNATURE_NO_MATCH);
        when(mMockPackageManagerInternal.hasSignatureCapability(anyInt(), anyInt(), anyInt()))
                .thenReturn(false);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);

        mAms.updateCredentials(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_INTERVENE,
                "authTokenType",
                true, // expectActivityLaunch
                new Bundle()); // options

        waitForLatch(latch);

        verify(mMockAccountManagerResponse, never()).onResult(any(Bundle.class));
        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_INVALID_RESPONSE), anyString());
    }

    @SmallTest
    public void testUpdateCredentialsReturnWithValidIntent() throws Exception {
        unlockSystemUser();
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.applicationInfo = new ApplicationInfo();
        when(mMockPackageManager.resolveActivityAsUser(
                any(Intent.class), anyInt(), anyInt())).thenReturn(resolveInfo);
        when(mMockPackageManager.checkSignatures(
                anyInt(), anyInt())).thenReturn(PackageManager.SIGNATURE_MATCH);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);

        mAms.updateCredentials(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_INTERVENE,
                "authTokenType",
                true, // expectActivityLaunch
                new Bundle()); // options

        waitForLatch(latch);

        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        Intent intent = result.getParcelable(AccountManager.KEY_INTENT);
        assertNotNull(intent);
        assertNotNull(intent.getParcelableExtra(AccountManagerServiceTestFixtures.KEY_RESULT));
        assertNotNull(intent.getParcelableExtra(AccountManagerServiceTestFixtures.KEY_CALLBACK));
    }

    @SmallTest
    public void testUpdateCredentialsError() throws Exception {
        unlockSystemUser();

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);

        mAms.updateCredentials(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_ERROR,
                "authTokenType",
                false, // expectActivityLaunch
                new Bundle()); // options

        waitForLatch(latch);
        verify(mMockAccountManagerResponse).onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                AccountManagerServiceTestFixtures.ERROR_MESSAGE);
        verify(mMockAccountManagerResponse, never()).onResult(any(Bundle.class));
    }

    @SmallTest
    public void testEditPropertiesWithNullResponse() throws Exception {
        unlockSystemUser();
        try {
            mAms.editProperties(
                null, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1,
                false); // expectActivityLaunch
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testEditPropertiesWithNullAccountType() throws Exception {
        unlockSystemUser();
        try {
            mAms.editProperties(
                mMockAccountManagerResponse, // response
                null, // accountType
                false); // expectActivityLaunch
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testEditPropertiesAccountNotManagedByCaller() throws Exception {
        unlockSystemUser();
        when(mMockPackageManager.checkSignatures(anyInt(), anyInt()))
                    .thenReturn(PackageManager.SIGNATURE_NO_MATCH);
        when(mMockPackageManagerInternal.hasSignatureCapability(anyInt(), anyInt(), anyInt()))
                .thenReturn(false);
        try {
            mAms.editProperties(
                mMockAccountManagerResponse, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1,
                false); // expectActivityLaunch
            fail("SecurityException expected. But no exception was thrown.");
        } catch (SecurityException e) {
            // SecurityException is expected.
        }
    }

    @SmallTest
    public void testEditPropertiesSuccess() throws Exception {
        unlockSystemUser();
        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);

        mAms.editProperties(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1,
                false); // expectActivityLaunch

        waitForLatch(latch);

        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        // Verify response data
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS,
                result.getString(AccountManager.KEY_ACCOUNT_NAME));
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1,
                result.getString(AccountManager.KEY_ACCOUNT_TYPE));
    }

    @SmallTest
    public void testGetAccountByTypeAndFeaturesWithNullResponse() throws Exception {
        unlockSystemUser();
        try {
            mAms.getAccountByTypeAndFeatures(
                null, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1,
                AccountManagerServiceTestFixtures.ACCOUNT_FEATURES,
                "testpackage"); // opPackageName
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testGetAccountByTypeAndFeaturesWithNullAccountType() throws Exception {
        unlockSystemUser();
        try {
            mAms.getAccountByTypeAndFeatures(
                mMockAccountManagerResponse, // response
                null, // accountType
                AccountManagerServiceTestFixtures.ACCOUNT_FEATURES,
                "testpackage"); // opPackageName
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testGetAccountByTypeAndFeaturesWithNoFeaturesAndNoAccount() throws Exception {
        unlockSystemUser();
        mAms.getAccountByTypeAndFeatures(
            mMockAccountManagerResponse,
            AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1,
            null,
            "testpackage");
        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        String accountName = result.getString(AccountManager.KEY_ACCOUNT_NAME);
        String accountType = result.getString(AccountManager.KEY_ACCOUNT_TYPE);
        assertEquals(null, accountName);
        assertEquals(null, accountType);
    }

    @SmallTest
    public void testGetAccountByTypeAndFeaturesWithNoFeaturesAndOneVisibleAccount()
        throws Exception {
        unlockSystemUser();
        mAms.addAccountExplicitly(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, "p11", null);
        mAms.getAccountByTypeAndFeatures(
            mMockAccountManagerResponse,
            AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1,
            null,
            "testpackage");
        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        String accountName = result.getString(AccountManager.KEY_ACCOUNT_NAME);
        String accountType = result.getString(AccountManager.KEY_ACCOUNT_TYPE);
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS, accountName);
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, accountType);
    }

    @SmallTest
    public void testGetAccountByTypeAndFeaturesWithNoFeaturesAndOneNotVisibleAccount()
        throws Exception {
        unlockSystemUser();
        HashMap<String, Integer> visibility = new HashMap<>();
        visibility.put(AccountManagerServiceTestFixtures.CALLER_PACKAGE,
            AccountManager.VISIBILITY_USER_MANAGED_NOT_VISIBLE);
        mAms.addAccountExplicitlyWithVisibility(
            AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, "p11", null, visibility);
        mAms.getAccountByTypeAndFeatures(
            mMockAccountManagerResponse,
            AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1,
            null,
            AccountManagerServiceTestFixtures.CALLER_PACKAGE);
        verify(mMockContext).startActivityAsUser(mIntentCaptor.capture(), eq(UserHandle.SYSTEM));
        Intent intent = mIntentCaptor.getValue();
        Account[] accounts = (Account[]) intent.getExtra(AccountManager.KEY_ACCOUNTS);
        assertEquals(1, accounts.length);
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, accounts[0]);
    }

    @SmallTest
    public void testGetAccountByTypeAndFeaturesWithNoFeaturesAndTwoAccounts() throws Exception {
        unlockSystemUser();
        mAms.addAccountExplicitly(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, "p11", null);
        mAms.addAccountExplicitly(AccountManagerServiceTestFixtures.ACCOUNT_INTERVENE, "p12", null);

        mAms.getAccountByTypeAndFeatures(
            mMockAccountManagerResponse,
            AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1,
            null,
            "testpackage");
        verify(mMockContext).startActivityAsUser(mIntentCaptor.capture(), eq(UserHandle.SYSTEM));
        Intent intent = mIntentCaptor.getValue();
        Account[] accounts = (Account[]) intent.getExtra(AccountManager.KEY_ACCOUNTS);
        assertEquals(2, accounts.length);
        if (accounts[0].equals(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS)) {
            assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, accounts[0]);
            assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_INTERVENE, accounts[1]);
        } else {
            assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_INTERVENE, accounts[0]);
            assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, accounts[1]);
        }
    }

    @SmallTest
    public void testGetAccountByTypeAndFeaturesWithFeaturesAndNoAccount() throws Exception {
        unlockSystemUser();
        final CountDownLatch latch = new CountDownLatch(1);
        mAms.getAccountByTypeAndFeatures(
            mMockAccountManagerResponse,
            AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1,
            AccountManagerServiceTestFixtures.ACCOUNT_FEATURES,
            "testpackage");
        waitForLatch(latch);
        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        String accountName = result.getString(AccountManager.KEY_ACCOUNT_NAME);
        String accountType = result.getString(AccountManager.KEY_ACCOUNT_TYPE);
        assertEquals(null, accountName);
        assertEquals(null, accountType);
    }

    @SmallTest
    public void testGetAccountByTypeAndFeaturesWithFeaturesAndNoQualifiedAccount()
        throws Exception {
        unlockSystemUser();
        mAms.addAccountExplicitly(AccountManagerServiceTestFixtures.ACCOUNT_INTERVENE, "p12", null);
        final CountDownLatch latch = new CountDownLatch(1);
        mAms.getAccountByTypeAndFeatures(
            mMockAccountManagerResponse,
            AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1,
            AccountManagerServiceTestFixtures.ACCOUNT_FEATURES,
            "testpackage");
        waitForLatch(latch);
        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        String accountName = result.getString(AccountManager.KEY_ACCOUNT_NAME);
        String accountType = result.getString(AccountManager.KEY_ACCOUNT_TYPE);
        assertEquals(null, accountName);
        assertEquals(null, accountType);
    }

    @SmallTest
    public void testGetAccountByTypeAndFeaturesWithFeaturesAndOneQualifiedAccount()
        throws Exception {
        unlockSystemUser();
        mAms.addAccountExplicitly(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, "p11", null);
        mAms.addAccountExplicitly(AccountManagerServiceTestFixtures.ACCOUNT_INTERVENE, "p12", null);
        final CountDownLatch latch = new CountDownLatch(1);
        mAms.getAccountByTypeAndFeatures(
            mMockAccountManagerResponse,
            AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1,
            AccountManagerServiceTestFixtures.ACCOUNT_FEATURES,
            "testpackage");
        waitForLatch(latch);
        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        String accountName = result.getString(AccountManager.KEY_ACCOUNT_NAME);
        String accountType = result.getString(AccountManager.KEY_ACCOUNT_TYPE);
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS, accountName);
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, accountType);
    }

    @SmallTest
    public void testGetAccountByTypeAndFeaturesWithFeaturesAndOneQualifiedNotVisibleAccount()
        throws Exception {
        unlockSystemUser();
        HashMap<String, Integer> visibility = new HashMap<>();
        visibility.put(AccountManagerServiceTestFixtures.CALLER_PACKAGE,
            AccountManager.VISIBILITY_USER_MANAGED_NOT_VISIBLE);
        mAms.addAccountExplicitlyWithVisibility(
            AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, "p11", null, visibility);
        final CountDownLatch latch = new CountDownLatch(1);
        mAms.getAccountByTypeAndFeatures(
            mMockAccountManagerResponse,
            AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1,
            AccountManagerServiceTestFixtures.ACCOUNT_FEATURES,
            AccountManagerServiceTestFixtures.CALLER_PACKAGE);
        waitForLatch(latch);
        verify(mMockContext).startActivityAsUser(mIntentCaptor.capture(), eq(UserHandle.SYSTEM));
        Intent intent = mIntentCaptor.getValue();
        Account[] accounts = (Account[]) intent.getExtra(AccountManager.KEY_ACCOUNTS);
        assertEquals(1, accounts.length);
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, accounts[0]);
    }

    @SmallTest
    public void testGetAccountByTypeAndFeaturesWithFeaturesAndTwoQualifiedAccount()
        throws Exception {
        unlockSystemUser();
        mAms.addAccountExplicitly(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, "p11", null);
        mAms.addAccountExplicitly(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS_2, "p12", null);
        mAms.addAccountExplicitly(AccountManagerServiceTestFixtures.ACCOUNT_INTERVENE, "p13", null);
        final CountDownLatch latch = new CountDownLatch(1);
        mAms.getAccountByTypeAndFeatures(
            mMockAccountManagerResponse,
            AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1,
            AccountManagerServiceTestFixtures.ACCOUNT_FEATURES,
            "testpackage");
        waitForLatch(latch);
        verify(mMockContext).startActivityAsUser(mIntentCaptor.capture(), eq(UserHandle.SYSTEM));
        Intent intent = mIntentCaptor.getValue();
        Account[] accounts = (Account[]) intent.getExtra(AccountManager.KEY_ACCOUNTS);
        assertEquals(2, accounts.length);
        if (accounts[0].equals(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS)) {
            assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, accounts[0]);
            assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS_2, accounts[1]);
        } else {
            assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS_2, accounts[0]);
            assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, accounts[1]);
        }
    }

    @SmallTest
    public void testGetAccountsByFeaturesWithNullResponse() throws Exception {
        unlockSystemUser();
        try {
            mAms.getAccountsByFeatures(
                null, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1,
                AccountManagerServiceTestFixtures.ACCOUNT_FEATURES,
                "testpackage"); // opPackageName
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testGetAccountsByFeaturesWithNullAccountType() throws Exception {
        unlockSystemUser();
        try {
            mAms.getAccountsByFeatures(
                mMockAccountManagerResponse, // response
                null, // accountType
                AccountManagerServiceTestFixtures.ACCOUNT_FEATURES,
                "testpackage"); // opPackageName
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    @SmallTest
    public void testGetAccountsByFeaturesAccountNotVisible() throws Exception {
        unlockSystemUser();

        when(mMockContext.checkCallingOrSelfPermission(anyString())).thenReturn(
                PackageManager.PERMISSION_DENIED);
        when(mMockPackageManager.checkSignatures(anyInt(), anyInt()))
                    .thenReturn(PackageManager.SIGNATURE_NO_MATCH);
        when(mMockPackageManagerInternal.hasSignatureCapability(anyInt(), anyInt(), anyInt()))
                .thenReturn(false);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        mAms.getAccountsByFeatures(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                AccountManagerServiceTestFixtures.ACCOUNT_FEATURES,
                "testpackage"); // opPackageName
        waitForLatch(latch);

        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        Account[] accounts = (Account[]) result.getParcelableArray(AccountManager.KEY_ACCOUNTS);
        assertTrue(accounts.length == 0);
    }

    @SmallTest
    public void testGetAccountsByFeaturesNullFeatureReturnsAllAccounts() throws Exception {
        unlockSystemUser();

        mAms.addAccountExplicitly(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, "p11", null);
        mAms.addAccountExplicitly(AccountManagerServiceTestFixtures.ACCOUNT_INTERVENE, "p12", null);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        mAms.getAccountsByFeatures(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                null, // features
                "testpackage"); // opPackageName
        waitForLatch(latch);

        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        Account[] accounts = (Account[]) result.getParcelableArray(AccountManager.KEY_ACCOUNTS);
        Arrays.sort(accounts, new AccountSorter());
        assertEquals(2, accounts.length);
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_INTERVENE, accounts[0]);
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, accounts[1]);
    }

    @SmallTest
    public void testGetAccountsByFeaturesReturnsAccountsWithFeaturesOnly() throws Exception {
        unlockSystemUser();

        mAms.addAccountExplicitly(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, "p11", null);
        mAms.addAccountExplicitly(AccountManagerServiceTestFixtures.ACCOUNT_INTERVENE, "p12", null);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        mAms.getAccountsByFeatures(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                AccountManagerServiceTestFixtures.ACCOUNT_FEATURES,
                "testpackage"); // opPackageName
        waitForLatch(latch);

        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        Account[] accounts = (Account[]) result.getParcelableArray(AccountManager.KEY_ACCOUNTS);
        assertEquals(1, accounts.length);
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, accounts[0]);
    }

    @SmallTest
    public void testGetAccountsByFeaturesError() throws Exception {
        unlockSystemUser();
        mAms.addAccountExplicitly(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, "p11", null);
        mAms.addAccountExplicitly(AccountManagerServiceTestFixtures.ACCOUNT_ERROR, "p12", null);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        mAms.getAccountsByFeatures(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                AccountManagerServiceTestFixtures.ACCOUNT_FEATURES,
                "testpackage"); // opPackageName
        waitForLatch(latch);

        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_INVALID_RESPONSE), anyString());
        verify(mMockAccountManagerResponse, never()).onResult(any(Bundle.class));
    }

    @SmallTest
    public void testRegisterAccountListener() throws Exception {
        unlockSystemUser();
        mAms.registerAccountListener(
            new String [] {AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1},
            "testpackage"); // opPackageName

        mAms.registerAccountListener(
            null, //accountTypes
            "testpackage"); // opPackageName

        // Check that two previously registered receivers can be unregistered successfully.
        mAms.unregisterAccountListener(
            new String [] {AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1},
            "testpackage"); // opPackageName

        mAms.unregisterAccountListener(
             null, //accountTypes
            "testpackage"); // opPackageName
    }

    @SmallTest
    public void testRegisterAccountListenerAndAddAccount() throws Exception {
        unlockSystemUser();
        mAms.registerAccountListener(
            new String [] {AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1},
            "testpackage"); // opPackageName

        mAms.addAccountExplicitly(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, "p11", null);
        // Notification about new account
        updateBroadcastCounters(2);
        assertEquals(mVisibleAccountsChangedBroadcasts, 1);
        assertEquals(mLoginAccountsChangedBroadcasts, 1);
    }

    @SmallTest
    public void testRegisterAccountListenerAndAddAccountOfDifferentType() throws Exception {
        unlockSystemUser();
        mAms.registerAccountListener(
            new String [] {AccountManagerServiceTestFixtures.ACCOUNT_TYPE_2},
            "testpackage"); // opPackageName

        mAms.addAccountExplicitly(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, "p11", null);
        mAms.addAccountExplicitly(
            AccountManagerServiceTestFixtures.ACCOUNT_INTERVENE, "p11", null);
        // Notification about new account

        updateBroadcastCounters(2);
        assertEquals(mVisibleAccountsChangedBroadcasts, 0); // broadcast was not sent
        assertEquals(mLoginAccountsChangedBroadcasts, 2);
    }

    @SmallTest
    public void testRegisterAccountListenerWithAddingTwoAccounts() throws Exception {
        unlockSystemUser();

        HashMap<String, Integer> visibility = new HashMap<>();
        visibility.put(AccountManagerServiceTestFixtures.CALLER_PACKAGE,
            AccountManager.VISIBILITY_USER_MANAGED_VISIBLE);

        mAms.registerAccountListener(
            new String [] {AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1},
            AccountManagerServiceTestFixtures.CALLER_PACKAGE);
        mAms.addAccountExplicitlyWithVisibility(
            AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, "p11", null, visibility);
        mAms.unregisterAccountListener(
            new String [] {AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1},
            AccountManagerServiceTestFixtures.CALLER_PACKAGE);

        addAccountRemovedReceiver(AccountManagerServiceTestFixtures.CALLER_PACKAGE);
        mAms.addAccountExplicitlyWithVisibility(
            AccountManagerServiceTestFixtures.ACCOUNT_INTERVENE, "p11", null, visibility);

        updateBroadcastCounters(3);
        assertEquals(mVisibleAccountsChangedBroadcasts, 1);
        assertEquals(mLoginAccountsChangedBroadcasts, 2);
        assertEquals(mAccountRemovedBroadcasts, 0);

        mAms.removeAccountInternal(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS);
        mAms.registerAccountListener( null /* accountTypes */,
            AccountManagerServiceTestFixtures.CALLER_PACKAGE);
        mAms.removeAccountInternal(AccountManagerServiceTestFixtures.ACCOUNT_INTERVENE);

        updateBroadcastCounters(8);
        assertEquals(mVisibleAccountsChangedBroadcasts, 2);
        assertEquals(mLoginAccountsChangedBroadcasts, 4);
        assertEquals(mAccountRemovedBroadcasts, 2);
    }

    @SmallTest
    public void testRegisterAccountListenerForThreePackages() throws Exception {
        unlockSystemUser();

        addAccountRemovedReceiver("testpackage1");
        HashMap<String, Integer> visibility = new HashMap<>();
        visibility.put("testpackage1", AccountManager.VISIBILITY_USER_MANAGED_VISIBLE);
        visibility.put("testpackage2", AccountManager.VISIBILITY_USER_MANAGED_VISIBLE);
        visibility.put("testpackage3", AccountManager.VISIBILITY_USER_MANAGED_VISIBLE);

        mAms.registerAccountListener(
            new String [] {AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1},
            "testpackage1"); // opPackageName
        mAms.registerAccountListener(
            new String [] {AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1},
            "testpackage2"); // opPackageName
        mAms.registerAccountListener(
            new String [] {AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1},
            "testpackage3"); // opPackageName
        mAms.addAccountExplicitlyWithVisibility(
            AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, "p11", null, visibility);
        updateBroadcastCounters(4);
        assertEquals(mVisibleAccountsChangedBroadcasts, 3);
        assertEquals(mLoginAccountsChangedBroadcasts, 1);

        mAms.unregisterAccountListener(
            new String [] {AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1},
            "testpackage3"); // opPackageName
        // Remove account with 2 active listeners.
        mAms.removeAccountInternal(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS);
        updateBroadcastCounters(8);
        assertEquals(mVisibleAccountsChangedBroadcasts, 5);
        assertEquals(mLoginAccountsChangedBroadcasts, 2); // 3 add, 2 remove
        assertEquals(mAccountRemovedBroadcasts, 1);

        // Add account of another type.
        mAms.addAccountExplicitly(
            AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS_TYPE_2, "p11", null);

        updateBroadcastCounters(8);
        assertEquals(mVisibleAccountsChangedBroadcasts, 5);
        assertEquals(mLoginAccountsChangedBroadcasts, 3);
        assertEquals(mAccountRemovedBroadcasts, 1);
    }

    @SmallTest
    public void testRegisterAccountListenerForAddingAccountWithVisibility() throws Exception {
        unlockSystemUser();

        HashMap<String, Integer> visibility = new HashMap<>();
        visibility.put("testpackage1", AccountManager.VISIBILITY_NOT_VISIBLE);
        visibility.put("testpackage2", AccountManager.VISIBILITY_USER_MANAGED_NOT_VISIBLE);
        visibility.put("testpackage3", AccountManager.VISIBILITY_USER_MANAGED_VISIBLE);

        addAccountRemovedReceiver("testpackage1");
        mAms.registerAccountListener(
            new String [] {AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1},
            "testpackage1"); // opPackageName
        mAms.registerAccountListener(
            new String [] {AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1},
            "testpackage2"); // opPackageName
        mAms.registerAccountListener(
            new String [] {AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1},
            "testpackage3"); // opPackageName
        mAms.addAccountExplicitlyWithVisibility(
            AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, "p11", null, visibility);

        updateBroadcastCounters(2);
        assertEquals(mVisibleAccountsChangedBroadcasts, 1);
        assertEquals(mLoginAccountsChangedBroadcasts, 1);

        mAms.removeAccountInternal(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS);

        updateBroadcastCounters(4);
        assertEquals(mVisibleAccountsChangedBroadcasts, 2);
        assertEquals(mLoginAccountsChangedBroadcasts, 2);
        assertEquals(mAccountRemovedBroadcasts, 0); // account was never visible.
    }

    @SmallTest
    public void testRegisterAccountListenerCredentialsUpdate() throws Exception {
        unlockSystemUser();
        mAms.registerAccountListener(
            new String [] {AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1},
            "testpackage"); // opPackageName
        mAms.addAccountExplicitly(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, "p11", null);
        mAms.setPassword(AccountManagerServiceTestFixtures.ACCOUNT_SUCCESS, "pwd");
        updateBroadcastCounters(4);
        assertEquals(mVisibleAccountsChangedBroadcasts, 2);
        assertEquals(mLoginAccountsChangedBroadcasts, 2);
    }

    @SmallTest
    public void testUnregisterAccountListenerNotRegistered() throws Exception {
        unlockSystemUser();
        try {
            mAms.unregisterAccountListener(
                new String [] {AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1},
                "testpackage"); // opPackageName
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is expected.
        }
    }

    private void updateBroadcastCounters (int expectedBroadcasts){
        mVisibleAccountsChangedBroadcasts = 0;
        mLoginAccountsChangedBroadcasts = 0;
        mAccountRemovedBroadcasts = 0;
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mMockContext, atLeast(expectedBroadcasts)).sendBroadcastAsUser(captor.capture(),
            any(UserHandle.class));
        for (Intent intent : captor.getAllValues()) {
            if (AccountManager.ACTION_VISIBLE_ACCOUNTS_CHANGED.equals(intent.getAction())) {
                mVisibleAccountsChangedBroadcasts++;
            } else if (AccountManager.LOGIN_ACCOUNTS_CHANGED_ACTION.equals(intent.getAction())) {
                mLoginAccountsChangedBroadcasts++;
            } else if (AccountManager.ACTION_ACCOUNT_REMOVED.equals(intent.getAction())) {
                mAccountRemovedBroadcasts++;
            }
        }
    }

    private void addAccountRemovedReceiver(String packageName) {
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.applicationInfo =  new ApplicationInfo();
        resolveInfo.activityInfo.applicationInfo.packageName = packageName;

        List<ResolveInfo> accountRemovedReceivers = new ArrayList<>();
        accountRemovedReceivers.add(resolveInfo);
        when(mMockPackageManager.queryBroadcastReceiversAsUser(any(Intent.class), anyInt(),
            anyInt())).thenReturn(accountRemovedReceivers);
    }

    @SmallTest
    public void testConcurrencyReadWrite() throws Exception {
        // Test 2 threads calling getAccounts and 1 thread setAuthToken
        unlockSystemUser();
        String[] list = new String[]{AccountManagerServiceTestFixtures.CALLER_PACKAGE};
        when(mMockPackageManager.getPackagesForUid(anyInt())).thenReturn(list);

        Account a1 = new Account("account1",
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);
        mAms.addAccountExplicitly(a1, "p1", null);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        int readerCount = 2;
        ExecutorService es = Executors.newFixedThreadPool(readerCount + 1);
        AtomicLong readTotalTime = new AtomicLong(0);
        AtomicLong writeTotalTime = new AtomicLong(0);
        final CyclicBarrier cyclicBarrier = new CyclicBarrier(readerCount + 1);

        final int loopSize = 20;
        for (int t = 0; t < readerCount; t++) {
            es.submit(() -> {
                for (int i = 0; i < loopSize; i++) {
                    String logPrefix = Thread.currentThread().getName() + " " + i;
                    waitForCyclicBarrier(cyclicBarrier);
                    cyclicBarrier.reset();
                    SystemClock.sleep(1); // Ensure that writer wins
                    Log.d(TAG, logPrefix + " getAccounts started");
                    long ti = System.currentTimeMillis();
                    try {
                        Account[] accounts = mAms.getAccountsAsUser(null,
                                UserHandle.getCallingUserId(), mContext.getOpPackageName());
                        if (accounts == null || accounts.length != 1
                                || !AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1.equals(
                                accounts[0].type)) {
                            String msg = logPrefix + ": Unexpected accounts: " + Arrays
                                    .toString(accounts);
                            Log.e(TAG, "    " + msg);
                            errors.add(msg);
                        }
                        Log.d(TAG, logPrefix + " getAccounts done");
                    } catch (Exception e) {
                        String msg = logPrefix + ": getAccounts failed " + e;
                        Log.e(TAG, msg, e);
                        errors.add(msg);
                    }
                    ti = System.currentTimeMillis() - ti;
                    readTotalTime.addAndGet(ti);
                }
            });
        }

        es.submit(() -> {
            for (int i = 0; i < loopSize; i++) {
                String logPrefix = Thread.currentThread().getName() + " " + i;
                waitForCyclicBarrier(cyclicBarrier);
                long ti = System.currentTimeMillis();
                Log.d(TAG, logPrefix + " setAuthToken started");
                try {
                    mAms.setAuthToken(a1, "t1", "v" + i);
                    Log.d(TAG, logPrefix + " setAuthToken done");
                } catch (Exception e) {
                    errors.add(logPrefix + ": setAuthToken failed: " + e);
                }
                ti = System.currentTimeMillis() - ti;
                writeTotalTime.addAndGet(ti);
            }
        });
        es.shutdown();
        assertTrue("Time-out waiting for jobs to finish",
                es.awaitTermination(10, TimeUnit.SECONDS));
        es.shutdownNow();
        assertTrue("Errors: " + errors, errors.isEmpty());
        Log.i(TAG, "testConcurrencyReadWrite: readTotalTime=" + readTotalTime + " avg="
                + (readTotalTime.doubleValue() / readerCount / loopSize));
        Log.i(TAG, "testConcurrencyReadWrite: writeTotalTime=" + writeTotalTime + " avg="
                + (writeTotalTime.doubleValue() / loopSize));
    }

    @SmallTest
    public void testConcurrencyRead() throws Exception {
        // Test 2 threads calling getAccounts
        unlockSystemUser();
        String[] list = new String[]{AccountManagerServiceTestFixtures.CALLER_PACKAGE};
        when(mMockPackageManager.getPackagesForUid(anyInt())).thenReturn(list);

        Account a1 = new Account("account1",
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);
        mAms.addAccountExplicitly(a1, "p1", null);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        int readerCount = 2;
        ExecutorService es = Executors.newFixedThreadPool(readerCount + 1);
        AtomicLong readTotalTime = new AtomicLong(0);

        final int loopSize = 20;
        for (int t = 0; t < readerCount; t++) {
            es.submit(() -> {
                for (int i = 0; i < loopSize; i++) {
                    String logPrefix = Thread.currentThread().getName() + " " + i;
                    Log.d(TAG, logPrefix + " getAccounts started");
                    long ti = System.currentTimeMillis();
                    try {
                        Account[] accounts = mAms.getAccountsAsUser(null,
                                UserHandle.getCallingUserId(), mContext.getOpPackageName());
                        if (accounts == null || accounts.length != 1
                                || !AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1.equals(
                                accounts[0].type)) {
                            String msg = logPrefix + ": Unexpected accounts: " + Arrays
                                    .toString(accounts);
                            Log.e(TAG, "    " + msg);
                            errors.add(msg);
                        }
                        Log.d(TAG, logPrefix + " getAccounts done");
                    } catch (Exception e) {
                        String msg = logPrefix + ": getAccounts failed " + e;
                        Log.e(TAG, msg, e);
                        errors.add(msg);
                    }
                    ti = System.currentTimeMillis() - ti;
                    readTotalTime.addAndGet(ti);
                }
            });
        }
        es.shutdown();
        assertTrue("Time-out waiting for jobs to finish",
                es.awaitTermination(10, TimeUnit.SECONDS));
        es.shutdownNow();
        assertTrue("Errors: " + errors, errors.isEmpty());
        Log.i(TAG, "testConcurrencyRead: readTotalTime=" + readTotalTime + " avg="
                + (readTotalTime.doubleValue() / readerCount / loopSize));
    }

    private void waitForCyclicBarrier(CyclicBarrier cyclicBarrier) {
        try {
            cyclicBarrier.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Should not throw " + e, e);
        }
    }

    private void waitForLatch(CountDownLatch latch) {
        try {
            latch.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new IllegalStateException("Should not throw an InterruptedException", e);
        }
    }

    private Bundle createAddAccountOptions(String accountName) {
        Bundle options = new Bundle();
        options.putString(AccountManagerServiceTestFixtures.KEY_ACCOUNT_NAME, accountName);
        return options;
    }

    private Bundle createGetAuthTokenOptions() {
        Bundle options = new Bundle();
        options.putString(AccountManager.KEY_ANDROID_PACKAGE_NAME,
                AccountManagerServiceTestFixtures.CALLER_PACKAGE);
        options.putLong(AccountManagerServiceTestFixtures.KEY_TOKEN_EXPIRY,
                System.currentTimeMillis() + ONE_DAY_IN_MILLISECOND);
        return options;
    }

    private Bundle encryptBundleWithCryptoHelper(Bundle sessionBundle) {
        Bundle encryptedBundle = null;
        try {
            CryptoHelper cryptoHelper = CryptoHelper.getInstance();
            encryptedBundle = cryptoHelper.encryptBundle(sessionBundle);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt session bundle.", e);
        }
        return encryptedBundle;
    }

    private Bundle createEncryptedSessionBundle(final String accountName) {
        Bundle sessionBundle = new Bundle();
        sessionBundle.putString(AccountManagerServiceTestFixtures.KEY_ACCOUNT_NAME, accountName);
        sessionBundle.putString(
                AccountManagerServiceTestFixtures.SESSION_DATA_NAME_1,
                AccountManagerServiceTestFixtures.SESSION_DATA_VALUE_1);
        sessionBundle.putString(AccountManager.KEY_ACCOUNT_TYPE,
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);
        sessionBundle.putString(AccountManager.KEY_ANDROID_PACKAGE_NAME, "APCT.session.package");
        return encryptBundleWithCryptoHelper(sessionBundle);
    }

    private Bundle createEncryptedSessionBundleWithError(final String accountName) {
        Bundle sessionBundle = new Bundle();
        sessionBundle.putString(AccountManagerServiceTestFixtures.KEY_ACCOUNT_NAME, accountName);
        sessionBundle.putString(
                AccountManagerServiceTestFixtures.SESSION_DATA_NAME_1,
                AccountManagerServiceTestFixtures.SESSION_DATA_VALUE_1);
        sessionBundle.putString(AccountManager.KEY_ACCOUNT_TYPE,
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);
        sessionBundle.putString(AccountManager.KEY_ANDROID_PACKAGE_NAME, "APCT.session.package");
        sessionBundle.putInt(
                AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_INVALID_RESPONSE);
        sessionBundle.putString(AccountManager.KEY_ERROR_MESSAGE,
                AccountManagerServiceTestFixtures.ERROR_MESSAGE);
        return encryptBundleWithCryptoHelper(sessionBundle);
    }

    private Bundle createEncryptedSessionBundleWithNoAccountType(final String accountName) {
        Bundle sessionBundle = new Bundle();
        sessionBundle.putString(AccountManagerServiceTestFixtures.KEY_ACCOUNT_NAME, accountName);
        sessionBundle.putString(
                AccountManagerServiceTestFixtures.SESSION_DATA_NAME_1,
                AccountManagerServiceTestFixtures.SESSION_DATA_VALUE_1);
        sessionBundle.putString(AccountManager.KEY_ANDROID_PACKAGE_NAME, "APCT.session.package");
        return encryptBundleWithCryptoHelper(sessionBundle);
    }

    private Bundle createAppBundle() {
        Bundle appBundle = new Bundle();
        appBundle.putString(AccountManager.KEY_ANDROID_PACKAGE_NAME, "APCT.package");
        return appBundle;
    }

    private Bundle createOptionsWithAccountName(final String accountName) {
        Bundle sessionBundle = new Bundle();
        sessionBundle.putString(
                AccountManagerServiceTestFixtures.SESSION_DATA_NAME_1,
                AccountManagerServiceTestFixtures.SESSION_DATA_VALUE_1);
        sessionBundle.putString(AccountManager.KEY_ACCOUNT_TYPE,
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);
        Bundle options = new Bundle();
        options.putString(AccountManagerServiceTestFixtures.KEY_ACCOUNT_NAME, accountName);
        options.putBundle(AccountManagerServiceTestFixtures.KEY_ACCOUNT_SESSION_BUNDLE,
                sessionBundle);
        options.putString(AccountManagerServiceTestFixtures.KEY_ACCOUNT_PASSWORD,
                AccountManagerServiceTestFixtures.ACCOUNT_PASSWORD);
        return options;
    }

    private int readNumberOfAccountsFromDbFile(Context context, String dbName) {
        SQLiteDatabase ceDb = context.openOrCreateDatabase(dbName, 0, null);
        try (Cursor cursor = ceDb.rawQuery("SELECT count(*) FROM accounts", null)) {
            assertTrue(cursor.moveToNext());
            return cursor.getInt(0);
        }
    }

    private void unlockSystemUser() {
        mAms.onUserUnlocked(newIntentForUser(UserHandle.USER_SYSTEM));
    }

    private static Intent newIntentForUser(int userId) {
        Intent intent = new Intent();
        intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        return intent;
    }

    static class MyMockContext extends MockContext {
        private Context mTestContext;
        private Context mMockContext;

        MyMockContext(Context testContext, Context mockContext) {
            this.mTestContext = testContext;
            this.mMockContext = mockContext;
        }

        @Override
        public int checkCallingOrSelfPermission(final String permission) {
            return mMockContext.checkCallingOrSelfPermission(permission);
        }

        @Override
        public boolean bindServiceAsUser(Intent service, ServiceConnection conn, int flags,
                UserHandle user) {
            return mTestContext.bindServiceAsUser(service, conn, flags, user);
        }

        @Override
        public void unbindService(ServiceConnection conn) {
            mTestContext.unbindService(conn);
        }

        @Override
        public PackageManager getPackageManager() {
            return mMockContext.getPackageManager();
        }

        @Override
        public String getPackageName() {
            return mTestContext.getPackageName();
        }

        @Override
        public Object getSystemService(String name) {
            return mMockContext.getSystemService(name);
        }

        @Override
        public String getSystemServiceName(Class<?> serviceClass) {
            return mMockContext.getSystemServiceName(serviceClass);
        }

        @Override
        public void startActivityAsUser(Intent intent, UserHandle user) {
            mMockContext.startActivityAsUser(intent, user);
        }

        @Override
        public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
            return mMockContext.registerReceiver(receiver, filter);
        }

        @Override
        public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
                IntentFilter filter, String broadcastPermission, Handler scheduler) {
            return mMockContext.registerReceiverAsUser(
                    receiver, user, filter, broadcastPermission, scheduler);
        }

        @Override
        public SQLiteDatabase openOrCreateDatabase(String file, int mode,
                SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler errorHandler) {
            return mTestContext.openOrCreateDatabase(file, mode, factory,errorHandler);
        }

        @Override
        public File getDatabasePath(String name) {
            return mTestContext.getDatabasePath(name);
        }

        @Override
        public void sendBroadcastAsUser(Intent intent, UserHandle user) {
            mMockContext.sendBroadcastAsUser(intent, user);
        }

        @Override
        public String getOpPackageName() {
            return mMockContext.getOpPackageName();
        }

        @Override
        public Context createPackageContextAsUser(String packageName, int flags, UserHandle user)
                throws PackageManager.NameNotFoundException {
            return mMockContext.createPackageContextAsUser(packageName, flags, user);
        }
    }

    static class TestAccountAuthenticatorCache extends AccountAuthenticatorCache {
        public TestAccountAuthenticatorCache(Context realContext) {
            super(realContext);
        }

        @Override
        protected File getUserSystemDirectory(int userId) {
            return new File(mContext.getCacheDir(), "authenticator");
        }
    }

    static class TestInjector extends AccountManagerService.Injector {
        private Context mRealContext;
        private INotificationManager mMockNotificationManager;
        TestInjector(Context realContext,
                Context mockContext,
                INotificationManager mockNotificationManager) {
            super(mockContext);
            mRealContext = realContext;
            mMockNotificationManager = mockNotificationManager;
        }

        @Override
        Looper getMessageHandlerLooper() {
            return Looper.getMainLooper();
        }

        @Override
        void addLocalService(AccountManagerInternal service) {
        }

        @Override
        IAccountAuthenticatorCache getAccountAuthenticatorCache() {
            return new TestAccountAuthenticatorCache(mRealContext);
        }

        @Override
        protected String getCeDatabaseName(int userId) {
            return new File(mRealContext.getCacheDir(), CE_DB).getPath();
        }

        @Override
        protected String getDeDatabaseName(int userId) {
            return new File(mRealContext.getCacheDir(), DE_DB).getPath();
        }

        @Override
        String getPreNDatabaseName(int userId) {
            return new File(mRealContext.getCacheDir(), PREN_DB).getPath();
        }

        @Override
        INotificationManager getNotificationManager() {
            return mMockNotificationManager;
        }
    }

    class Response extends IAccountManagerResponse.Stub {
        private CountDownLatch mLatch;
        private IAccountManagerResponse mMockResponse;
        public Response(CountDownLatch latch, IAccountManagerResponse mockResponse) {
            mLatch = latch;
            mMockResponse = mockResponse;
        }

        @Override
        public void onResult(Bundle bundle) {
            try {
                mMockResponse.onResult(bundle);
            } catch (RemoteException e) {
            }
            mLatch.countDown();
        }

        @Override
        public void onError(int code, String message) {
            try {
                mMockResponse.onError(code, message);
            } catch (RemoteException e) {
            }
            mLatch.countDown();
        }
    }
}
