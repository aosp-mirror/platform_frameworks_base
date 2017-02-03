/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.content;


import static org.junit.Assert.fail;

import android.app.ActivityManager;
import android.app.activity.LocalProvider;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.IBinder;
import android.os.UserHandle;
import android.os.UserManager;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class CrossUserContentResolverTest {
    private final static int TIMEOUT_SERVICE_CONNECTION_SEC = 4;
    private final static int TIMEOUT_CONTENT_CHANGE_SEC = 4;

    private Context mContext;
    private UserManager mUm;
    private int mSecondaryUserId = -1;
    private CrossUserContentServiceConnection mServiceConnection;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getContext();
        mUm = UserManager.get(mContext);
        final UserInfo userInfo = mUm.createUser("Test user", 0);
        mSecondaryUserId = userInfo.id;
        final PackageManager pm = mContext.getPackageManager();
        pm.installExistingPackageAsUser(mContext.getPackageName(), mSecondaryUserId);
        ActivityManager.getService().startUserInBackground(mSecondaryUserId);

        final CountDownLatch connectionLatch = new CountDownLatch(1);
        mServiceConnection = new CrossUserContentServiceConnection(connectionLatch);
        mContext.bindServiceAsUser(
                new Intent(mContext, CrossUserContentService.class),
                mServiceConnection,
                Context.BIND_AUTO_CREATE,
                UserHandle.of(mSecondaryUserId));
        if (!connectionLatch.await(TIMEOUT_SERVICE_CONNECTION_SEC, TimeUnit.SECONDS)) {
            fail("Timed out waiting for service connection to establish");
        }
    }

    @After
    public void tearDown() throws Exception {
        if (mSecondaryUserId != -1) {
            mUm.removeUser(mSecondaryUserId);
        }
        if (mServiceConnection != null) {
            mContext.unbindService(mServiceConnection);
        }
    }

    /**
     * Register an observer for an URI in the secondary user and verify that it receives
     * onChange callback when data at the URI changes.
     */
    @Test
    public void testRegisterContentObserver() throws Exception {
        Context secondaryUserContext = null;
        String packageName = null;
        try {
            packageName = InstrumentationRegistry.getContext().getPackageName();
            secondaryUserContext =
                    InstrumentationRegistry.getContext().createPackageContextAsUser(
                            packageName, 0 /* flags */, UserHandle.of(mSecondaryUserId));
        } catch (NameNotFoundException e) {
            fail("Couldn't find package " + packageName + " in u" + mSecondaryUserId);
        }

        final CountDownLatch updateLatch = new CountDownLatch(1);
        final Uri uriToUpdate = LocalProvider.getTableDataUriForRow(2);
        final TestContentObserver observer = new TestContentObserver(updateLatch,
                uriToUpdate, mSecondaryUserId);
        secondaryUserContext.getContentResolver().registerContentObserver(
                LocalProvider.TABLE_DATA_URI, true, observer, mSecondaryUserId);
        mServiceConnection.getService().updateContent(uriToUpdate, "New Text", 42);
        if (!updateLatch.await(TIMEOUT_CONTENT_CHANGE_SEC, TimeUnit.SECONDS)) {
            fail("Timed out waiting for the content change callback");
        }
    }

    /**
     * Register an observer for an URI in the current user and verify that secondary user can
     * notify changes for this URI.
     */
    @Test
    public void testNotifyChange() throws Exception {
        final CountDownLatch notifyLatch = new CountDownLatch(1);
        final Uri notifyUri = LocalProvider.TABLE_DATA_URI;
        final TestContentObserver observer = new TestContentObserver(notifyLatch,
                notifyUri, UserHandle.myUserId());
        mContext.getContentResolver().registerContentObserver(notifyUri, true, observer);
        mServiceConnection.getService().notifyForUriAsUser(notifyUri, UserHandle.myUserId());
        if (!notifyLatch.await(TIMEOUT_CONTENT_CHANGE_SEC, TimeUnit.SECONDS)) {
            fail("Timed out waiting for the notify callback");
        }
    }

    private static final class CrossUserContentServiceConnection implements ServiceConnection {
        private ICrossUserContentService mService;
        private final CountDownLatch mLatch;

        public CrossUserContentServiceConnection(CountDownLatch latch) {
            mLatch = latch;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mService = ICrossUserContentService.Stub.asInterface(service);
            mLatch.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }

        public ICrossUserContentService getService() {
            return mService;
        }
    }

    private static final class TestContentObserver extends ContentObserver {
        private final CountDownLatch mLatch;
        private final Uri mExpectedUri;
        private final int mExpectedUserId;

        public TestContentObserver(CountDownLatch latch, Uri exptectedUri, int expectedUserId) {
            super(null);
            mLatch = latch;
            mExpectedUri = exptectedUri;
            mExpectedUserId = expectedUserId;
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (mExpectedUri.equals(uri) && mExpectedUserId == userId) {
                mLatch.countDown();
            }
        }
    }
}