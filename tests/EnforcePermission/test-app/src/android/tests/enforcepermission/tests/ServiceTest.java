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

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.tests.enforcepermission.IProtected;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RunWith(AndroidJUnit4.class)
public class ServiceTest {

    private static final String TAG = "EnforcePermission.Tests";
    private static final String SERVICE_NAME = "android.tests.enforcepermission.service";
    private static final int SERVICE_TIMEOUT_SEC = 5;

    private Context mContext;
    private volatile ServiceConnection mServiceConnection;

    @Before
    public void bindTestService() throws Exception {
        Log.d(TAG, "bindTestService");
        mContext = InstrumentationRegistry.getTargetContext();
        mServiceConnection = new ServiceConnection();
        Intent intent = new Intent();
        intent.setClassName(SERVICE_NAME, SERVICE_NAME + ".TestService");
        assertTrue(mContext.bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE));
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
    public void testImmediatePermissionGranted_succeeds()
            throws RemoteException {
        mServiceConnection.get().ProtectedByInternet();
    }

    @Test
    public void testImmediatePermissionNotGranted_fails()
            throws RemoteException {
        final Exception ex = assertThrows(SecurityException.class,
                () -> mServiceConnection.get().ProtectedByVibrate());
        assertThat(ex.getMessage(), containsString("VIBRATE"));
    }

    @Test
    public void testImmediatePermissionGrantedButImplicitLocalNotGranted_fails()
            throws RemoteException {
        final Exception ex = assertThrows(SecurityException.class,
                () -> mServiceConnection.get().ProtectedByInternetAndVibrateImplicitly());
        assertThat(ex.getMessage(), containsString("VIBRATE"));
    }

    @Test
    public void testImmediatePermissionGrantedButImplicitNestedNotGranted_fails()
            throws RemoteException {
        final Exception ex = assertThrows(SecurityException.class,
                () -> mServiceConnection.get()
                      .ProtectedByInternetAndAccessNetworkStateImplicitly());
        assertThat(ex.getMessage(), containsString("ACCESS_NETWORK_STATE"));
    }

    @Test
    public void testImmediatePermissionGrantedAndImplicitNestedGranted_succeeds()
            throws RemoteException {
        mServiceConnection.get().ProtectedByInternetAndReadSyncSettingsImplicitly();
    }

    @Test
    public void testAppOpPermissionGranted_succeeds() throws RemoteException {
        AppOpsManager appOpsManager = mContext.getSystemService(AppOpsManager.class);
        appOpsManager.setUidMode(AppOpsManager.OP_TURN_SCREEN_ON,
                Process.myUid(), AppOpsManager.MODE_ALLOWED);

        mServiceConnection.get().ProtectedByTurnScreenOn();
    }

    @Test
    public void testAppOpPermissionDenied_fails() throws RemoteException {
        AppOpsManager appOpsManager = mContext.getSystemService(AppOpsManager.class);
        appOpsManager.setUidMode(AppOpsManager.OP_TURN_SCREEN_ON,
                Process.myUid(), AppOpsManager.MODE_ERRORED);

        final Exception ex = assertThrows(SecurityException.class,
                () -> mServiceConnection.get().ProtectedByTurnScreenOn());
        assertThat(ex.getMessage(), containsString("TURN_SCREEN_ON"));
    }

    @Test
    public void testRuntimePermissionGranted_succeeds() throws RemoteException {
        mServiceConnection.get().ProtectedByReadContacts();
    }

    @Test
    public void testRuntimePermissionDenied_fails() throws RemoteException {
        final Exception ex = assertThrows(SecurityException.class,
                () -> mServiceConnection.get().ProtectedByReadCalendar());
        assertThat(ex.getMessage(), containsString("READ_CALENDAR"));
    }

    @Test
    public void testAllOfPermissionGranted_succeeds() throws RemoteException {
        mServiceConnection.get().ProtectedByInternetAndReadSyncSettings();
    }

    @Test
    public void testAllOfPermissionDenied_fails() throws RemoteException {
        final Exception ex = assertThrows(SecurityException.class,
                () -> mServiceConnection.get().ProtectedByInternetAndVibrate());
        assertThat(ex.getMessage(), containsString("VIBRATE"));
    }

    @Test
    public void testAnyOfPermissionGranted_succeeds() throws RemoteException {
        mServiceConnection.get().ProtectedByInternetOrVibrate();
    }

    @Test
    public void testAnyOfPermissionDenied_fails() throws RemoteException {
        final Exception ex = assertThrows(SecurityException.class,
                () -> mServiceConnection.get().ProtectedByAccessWifiStateOrVibrate());
        assertThat(ex.getMessage(), containsString("VIBRATE"));
        assertThat(ex.getMessage(), containsString("ACCESS_WIFI_STATE"));
    }
}
