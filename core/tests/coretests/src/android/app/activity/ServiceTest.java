/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.app.activity;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

import androidx.test.filters.MediumTest;

import junit.framework.TestCase;

import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Test for verifying the behavior of {@link Service}.
 * <p>
 * Tests related to internal behavior are usually placed here, e.g. the restart delay may be
 * different depending on the current amount of restarting services.
 * <p>
 * Build/Install/Run:
 *  atest FrameworksCoreTests:ServiceTest
 */
@MediumTest
public class ServiceTest extends TestCase {
    private static final String ACTION_SERVICE_STARTED = RemoteService.class.getName() + "_STARTED";
    private static final String EXTRA_START_CODE = "start_code";
    private static final String EXTRA_PID = "pid";

    private static final long TIMEOUT_SEC = 5;
    private static final int NOT_STARTED = -1;

    private final Context mContext = getInstrumentation().getContext();
    private final Intent mServiceIntent = new Intent(mContext, RemoteService.class);
    private TestConnection mCurrentConnection;

    @Override
    public void tearDown() {
        mContext.stopService(mServiceIntent);
        if (mCurrentConnection != null) {
            mContext.unbindService(mCurrentConnection);
            mCurrentConnection = null;
        }
    }

    @Test
    public void testRestart_stickyStartedService_restarted() {
        testRestartStartedService(Service.START_STICKY, true /* shouldRestart */);
    }

    @Test
    public void testRestart_redeliveryStartedService_restarted() {
        testRestartStartedService(Service.START_FLAG_REDELIVERY, true /* shouldRestart */);
    }

    @Test
    public void testRestart_notStickyStartedService_notRestarted() {
        testRestartStartedService(Service.START_NOT_STICKY, false /* shouldRestart */);
    }

    private void testRestartStartedService(int startFlag, boolean shouldRestart) {
        final int servicePid = startService(startFlag);
        assertThat(servicePid, not(NOT_STARTED));

        final int restartedServicePid = waitForServiceStarted(
                () -> Process.killProcess(servicePid));
        assertThat(restartedServicePid, shouldRestart ? not(NOT_STARTED) : is(NOT_STARTED));
    }

    @Test
    public void testRestart_boundService_restarted() {
        final int servicePid = bindService(Context.BIND_AUTO_CREATE);
        assertThat(servicePid, not(NOT_STARTED));

        Process.killProcess(servicePid);
        // The service should be restarted and the connection will receive onServiceConnected again.
        assertThat(mCurrentConnection.takePid(), not(NOT_STARTED));
    }

    @Test
    public void testRestart_boundNotStickyStartedService_restarted() {
        final ActivityManager am = mContext.getSystemService(ActivityManager.class);
        final Supplier<RunningServiceInfo> serviceInfoGetter = () -> {
            for (RunningServiceInfo rs : am.getRunningServices(Integer.MAX_VALUE)) {
                if (mServiceIntent.getComponent().equals(rs.service)) {
                    return rs;
                }
            }
            return null;
        };

        final int servicePid = bindService(Context.BIND_AUTO_CREATE);
        assertThat(servicePid, not(NOT_STARTED));
        assertThat(startService(Service.START_NOT_STICKY), is(servicePid));

        RunningServiceInfo info = serviceInfoGetter.get();
        assertThat(info, notNullValue());
        assertThat(info.started, is(true));

        Process.killProcess(servicePid);
        // The service will be restarted for connection but the started state should be gone.
        final int restartedServicePid = mCurrentConnection.takePid();
        assertThat(restartedServicePid, not(NOT_STARTED));

        info = serviceInfoGetter.get();
        assertThat(info, notNullValue());
        assertThat(info.started, is(false));
        assertThat(info.clientCount, is(1));
    }

    @Test
    public void testRestart_notStickyStartedNoAutoCreateBoundService_notRestarted() {
        final int servicePid = startService(Service.START_NOT_STICKY);
        assertThat(servicePid, not(NOT_STARTED));
        assertThat(bindService(0 /* flags */), is(servicePid));

        Process.killProcess(servicePid);
        assertThat(mCurrentConnection.takePid(), is(NOT_STARTED));
    }

    /** @return The pid of the started service. */
    private int startService(int code) {
        return waitForServiceStarted(
                () -> mContext.startService(mServiceIntent.putExtra(EXTRA_START_CODE, code)));
    }

    /** @return The pid of the started service. */
    private int waitForServiceStarted(Runnable serviceTrigger) {
        final CompletableFuture<Integer> pidResult = new CompletableFuture<>();
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                pidResult.complete(intent.getIntExtra(EXTRA_PID, NOT_STARTED));
                mContext.unregisterReceiver(this);
            }
        }, new IntentFilter(ACTION_SERVICE_STARTED));

        serviceTrigger.run();
        try {
            return pidResult.get(TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException ignored) {
        }
        return NOT_STARTED;
    }

    /** @return The pid of the bound service. */
    private int bindService(int flags) {
        mCurrentConnection = new TestConnection();
        assertThat(mContext.bindService(mServiceIntent, mCurrentConnection, flags), is(true));
        return mCurrentConnection.takePid();
    }

    private static class TestConnection implements ServiceConnection {
        private CompletableFuture<Integer> mServicePid = new CompletableFuture<>();

        /**
         * @return The pid of the connected service. It is only valid once after
         *         {@link #onServiceConnected} is called.
         */
        int takePid() {
            try {
                return mServicePid.get(TIMEOUT_SEC, TimeUnit.SECONDS);
            } catch (ExecutionException | InterruptedException | TimeoutException ignored) {
            } finally {
                mServicePid = new CompletableFuture<>();
            }
            return NOT_STARTED;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            final Parcel data = Parcel.obtain();
            final Parcel reply = Parcel.obtain();
            data.writeInterfaceToken(RemoteService.DESCRIPTOR);
            try {
                service.transact(RemoteService.TRANSACTION_GET_PID, data, reply, 0 /* flags */);
                reply.readException();
                mServicePid.complete(reply.readInt());
            } catch (RemoteException e) {
                mServicePid.complete(NOT_STARTED);
            } finally {
                data.recycle();
                reply.recycle();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    }

    public static class RemoteService extends Service {
        static final String DESCRIPTOR = RemoteService.class.getName();
        static final int TRANSACTION_GET_PID = Binder.FIRST_CALL_TRANSACTION;

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            new Handler().post(() -> {
                final Intent responseIntent = new Intent(ACTION_SERVICE_STARTED);
                responseIntent.putExtra(EXTRA_PID, Process.myPid());
                sendBroadcast(responseIntent);
            });
            if (intent != null && intent.hasExtra(EXTRA_START_CODE)) {
                return intent.getIntExtra(EXTRA_START_CODE, Service.START_NOT_STICKY);
            }
            return super.onStartCommand(intent, flags, startId);
        }

        @Override
        public IBinder onBind(Intent intent) {
            return new Binder() {
                @Override
                protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                        throws RemoteException {
                    if (code == TRANSACTION_GET_PID) {
                        data.enforceInterface(DESCRIPTOR);
                        reply.writeNoException();
                        reply.writeInt(Process.myPid());
                        return true;
                    }
                    return super.onTransact(code, data, reply, flags);
                }
            };
        }
    }
}
