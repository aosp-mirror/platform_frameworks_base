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
import static org.junit.Assume.assumeTrue;

import android.app.ActivityManager;
import android.app.activity.LocalProvider;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import com.android.compatibility.common.util.SystemUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@LargeTest
@RunWith(AndroidJUnit4.class)
abstract class AbstractCrossUserContentResolverTest {
    private static final int TIMEOUT_SERVICE_CONNECTION_SEC = 4;
    private static final int TIMEOUT_CONTENT_CHANGE_SEC = 4;
    private static final int TIMEOUT_USER_UNLOCK_SEC = 10;

    private Context mContext;
    protected UserManager mUm;
    private int mCrossUserId = -1;
    private CrossUserContentServiceConnection mServiceConnection;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getContext();
        final PackageManager pm = mContext.getPackageManager();
        assumeTrue("device doesn't have the " + PackageManager.FEATURE_MANAGED_USERS + " feature",
                pm.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS));
        mUm = UserManager.get(mContext);
        final UserInfo userInfo = createUser();
        mCrossUserId = userInfo.id;
        pm.installExistingPackageAsUser(mContext.getPackageName(), mCrossUserId);
        unlockUser();

        final CountDownLatch connectionLatch = new CountDownLatch(1);
        mServiceConnection = new CrossUserContentServiceConnection(connectionLatch);
        mContext.bindServiceAsUser(
                new Intent(mContext, CrossUserContentService.class),
                mServiceConnection,
                Context.BIND_AUTO_CREATE,
                UserHandle.of(mCrossUserId));
        if (!connectionLatch.await(TIMEOUT_SERVICE_CONNECTION_SEC, TimeUnit.SECONDS)) {
            fail("Timed out waiting for service connection to establish");
        }
    }

    protected abstract UserInfo createUser() throws RemoteException ;

    private void unlockUser() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL)
                        == mCrossUserId) {
                    latch.countDown();
                }
            }
        };
        mContext.registerReceiverAsUser(receiver, UserHandle.of(mCrossUserId),
                new IntentFilter(Intent.ACTION_USER_UNLOCKED), null, null);
        ActivityManager.getService().startUserInBackground(mCrossUserId);
        SystemUtil.runShellCommand("am wait-for-broadcast-barrier");

        try {
            if (!latch.await(TIMEOUT_USER_UNLOCK_SEC, TimeUnit.SECONDS)) {
                fail("Timed out waiting for the u" + mCrossUserId + " to unlock");
            }
        } finally {
            mContext.unregisterReceiver(receiver);
        }
    }

    @After
    public void tearDown() throws Exception {
        if (mCrossUserId != -1) {
            mUm.removeUser(mCrossUserId);
        }
        if (mServiceConnection != null) {
            mContext.unbindService(mServiceConnection);
        }
    }

    /**
     * Register an observer for an URI in another user and verify that it receives
     * onChange callback when data at the URI changes.
     */
    @Ignore("b/272733874")
    @Test
    public void testRegisterContentObserver() throws Exception {
        Context crossUserContext = null;
        String packageName = null;
        try {
            packageName = InstrumentationRegistry.getContext().getPackageName();
            crossUserContext =
                    InstrumentationRegistry.getContext().createPackageContextAsUser(
                            packageName, 0 /* flags */, UserHandle.of(mCrossUserId));
        } catch (NameNotFoundException e) {
            fail("Couldn't find package " + packageName + " in u" + mCrossUserId);
        }

        final CountDownLatch updateLatch = new CountDownLatch(1);
        final Uri uriToUpdate = LocalProvider.getTableDataUriForRow(2);
        final TestContentObserver observer = new TestContentObserver(updateLatch,
                uriToUpdate, mCrossUserId);
        crossUserContext.getContentResolver().registerContentObserver(
                LocalProvider.TABLE_DATA_URI, true, observer, mCrossUserId);
        mServiceConnection.getService().updateContent(uriToUpdate, "New Text", 42);
        if (!updateLatch.await(TIMEOUT_CONTENT_CHANGE_SEC, TimeUnit.SECONDS)) {
            fail("Timed out waiting for the content change callback");
        }
    }

    /**
     * Register an observer for an URI in the current user and verify that another user can
     * notify changes for this URI.
     */
    @Ignore("b/272733874")
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
        public void onChange(boolean selfChange, Collection<Uri> uris, int flags, int userId) {
            if (uris.contains(mExpectedUri) && mExpectedUserId == userId) {
                mLatch.countDown();
            }
        }
    }
}