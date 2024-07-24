/**
 * Copyright (C) 2023 The Android Open Source Project
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

package android.tests.enforcepermission.tests;

import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.tests.enforcepermission.IProtected;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Performance tests for EnforcePermission annotation.
 *
 * Permission check results are cached on the service side as it relies on
 * PermissionManager. It means that only the first request will trigger a
 * lookup to system_server. Subsequent requests will use the cached result. As
 * this timing is similar to a permission check for a service hosted in
 * system_server, we keep this cache active for the tests. The BenchmarkState
 * used by PerfStatusReporter includes a warm-up stage. It means that the extra
 * time taken by the first request will not be reflected in the outcome of the
 * test.
 */
@RunWith(AndroidJUnit4.class)
public class ServicePerfTest {

    private static final String TAG = "EnforcePermission.PerfTests";
    private static final String SERVICE_PACKAGE = "android.tests.enforcepermission.service";
    private static final String LOCAL_SERVICE_PACKAGE = "android.tests.enforcepermission.tests";
    private static final int SERVICE_TIMEOUT_SEC = 5;

    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private Context mContext;
    private volatile ServiceConnection mServiceConnection;

    private void bindService(Intent intent) throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        mServiceConnection = new ServiceConnection();
        assertTrue(mContext.bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE));
    }

    public void bindRemoteService() throws Exception {
        Log.d(TAG, "bindRemoteService");
        Intent intent = new Intent();
        intent.setClassName(SERVICE_PACKAGE, SERVICE_PACKAGE + ".TestService");
        bindService(intent);
    }

    public void bindLocalService() throws Exception {
        Log.d(TAG, "bindLocalService");
        Intent intent = new Intent();
        intent.setClassName(LOCAL_SERVICE_PACKAGE, SERVICE_PACKAGE + ".TestService");
        bindService(intent);
    }

    @After
    public void unbindTestService() throws Exception {
        mContext.unbindService(mServiceConnection);
    }

    private static final class ServiceConnection implements android.content.ServiceConnection {
        private volatile CompletableFuture<IProtected> mFuture = new CompletableFuture<>();

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            mFuture.complete(IProtected.Stub.asInterface(service));
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            mFuture = new CompletableFuture<>();
        }

        public IProtected get() {
            try {
                return mFuture.get(SERVICE_TIMEOUT_SEC, TimeUnit.SECONDS);
            } catch (ExecutionException | InterruptedException | TimeoutException e) {
                throw new RuntimeException("Unable to reach TestService: " + e.toString());
            }
        }
    }

    @Test
    public void testAnnotatedPermission() throws Exception {
        bindRemoteService();
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mServiceConnection.get().ProtectedByInternet();
        }
    }

    @Test
    public void testNoPermission() throws Exception {
        bindRemoteService();
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mServiceConnection.get().NotProtected();
        }
    }

    @Test
    public void testManuallyProtected() throws Exception {
        bindRemoteService();
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mServiceConnection.get().ManuallyProtected();
        }
    }

    @Test
    public void testAnnotatedPermissionLocal()
            throws Exception {
        bindLocalService();
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mServiceConnection.get().ProtectedByInternet();
        }
    }

    @Test
    public void testNoPermissionLocal() throws Exception {
        bindLocalService();
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mServiceConnection.get().NotProtected();
        }
    }

    @Test
    public void testManuallyProtectedLocal() throws Exception {
        bindLocalService();
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mServiceConnection.get().ManuallyProtected();
        }
    }
}
