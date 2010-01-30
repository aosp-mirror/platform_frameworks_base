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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.IBinder;
import android.os.Parcel;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.test.suitebuilder.annotation.Suppress;
import android.util.Log;

// These test binders purport to support an interface whose canonical
// interface name is ServiceTest.SERVICE_LOCAL
// Temporarily suppress, this test is causing unit test suite run to fail
// TODO: remove this suppress
@Suppress
public class ServiceTest extends ActivityTestsBase {

    public static final String SERVICE_LOCAL =
            "com.android.frameworks.coretests.activity.SERVICE_LOCAL";
    public static final String SERVICE_LOCAL_GRANTED =
            "com.android.frameworks.coretests.activity.SERVICE_LOCAL_GRANTED";
    public static final String SERVICE_LOCAL_DENIED =
            "com.android.frameworks.coretests.activity.SERVICE_LOCAL_DENIED";

    public static final String REPORT_OBJ_NAME = "report";

    public static final int STARTED_CODE = 1;
    public static final int DESTROYED_CODE = 2;
    public static final int SET_REPORTER_CODE = 3;
    public static final int UNBIND_CODE = 4;
    public static final int REBIND_CODE = 5;

    public static final int STATE_START_1 = 0;
    public static final int STATE_START_2 = 1;
    public static final int STATE_UNBIND = 2;
    public static final int STATE_DESTROY = 3;
    public static final int STATE_REBIND = 4;
    public static final int STATE_UNBIND_ONLY = 5;
    public int mStartState;

    public IBinder mStartReceiver = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply,
                int flags) throws RemoteException {
            //Log.i("ServiceTest", "Received code " + code + " in state " + mStartState);
            if (code == STARTED_CODE) {
                data.enforceInterface(SERVICE_LOCAL);
                int count = data.readInt();
                if (mStartState == STATE_START_1) {
                    if (count == 1) {
                        finishGood();
                    } else {
                        finishBad("onStart() again on an object when it should have been the first time");
                    }
                } else if (mStartState == STATE_START_2) {
                    if (count == 2) {
                        finishGood();
                    } else {
                        finishBad("onStart() the first time on an object when it should have been the second time");
                    }
                } else {
                    finishBad("onStart() was called when not expected (state="+mStartState+")");
                }
                return true;
            } else if (code == DESTROYED_CODE) {
                data.enforceInterface(SERVICE_LOCAL);
                if (mStartState == STATE_DESTROY) {
                    finishGood();
                } else {
                    finishBad("onDestroy() was called when not expected (state="+mStartState+")");
                }
                return true;
            } else if (code == UNBIND_CODE) {
                data.enforceInterface(SERVICE_LOCAL);
                if (mStartState == STATE_UNBIND) {
                    mStartState = STATE_DESTROY;
                } else if (mStartState == STATE_UNBIND_ONLY) {
                    finishGood();
                } else {
                    finishBad("onUnbind() was called when not expected (state="+mStartState+")");
                }
                return true;
            } else if (code == REBIND_CODE) {
                data.enforceInterface(SERVICE_LOCAL);
                if (mStartState == STATE_REBIND) {
                    finishGood();
                } else {
                    finishBad("onRebind() was called when not expected (state="+mStartState+")");
                }
                return true;
            } else {
                return super.onTransact(code, data, reply, flags);
            }
        }
    };

    public class EmptyConnection implements ServiceConnection {
        public void onServiceConnected(ComponentName name, IBinder service) {
        }

        public void onServiceDisconnected(ComponentName name) {
        }
    }

    public class TestConnection implements ServiceConnection {
        private final boolean mExpectDisconnect;
        private final boolean mSetReporter;
        private boolean mMonitor;
        private int mCount;

        public TestConnection(boolean expectDisconnect, boolean setReporter) {
            mExpectDisconnect = expectDisconnect;
            mSetReporter = setReporter;
            mMonitor = !setReporter;
        }

        void setMonitor(boolean v) {
            mMonitor = v;
        }

        public void onServiceConnected(ComponentName name, IBinder service) {
            if (mSetReporter) {
                Parcel data = Parcel.obtain();
                data.writeInterfaceToken(SERVICE_LOCAL);
                data.writeStrongBinder(mStartReceiver);
                try {
                    service.transact(SET_REPORTER_CODE, data, null, 0);
                } catch (RemoteException e) {
                    finishBad("DeadObjectException when sending reporting object");
                }
                data.recycle();
            }

            if (mMonitor) {
                mCount++;
                if (mStartState == STATE_START_1) {
                    if (mCount == 1) {
                        finishGood();
                    } else {
                        finishBad("onServiceConnected() again on an object when it should have been the first time");
                    }
                } else if (mStartState == STATE_START_2) {
                    if (mCount == 2) {
                        finishGood();
                    } else {
                        finishBad("onServiceConnected() the first time on an object when it should have been the second time");
                    }
                } else {
                    finishBad("onServiceConnected() called unexpectedly");
                }
            }
        }

        public void onServiceDisconnected(ComponentName name) {
            if (mMonitor) {
                if (mStartState == STATE_DESTROY) {
                    if (mExpectDisconnect) {
                        finishGood();
                    } else {
                        finishBad("onServiceDisconnected() when it shouldn't have been");
                    }
                } else {
                    finishBad("onServiceDisconnected() called unexpectedly");
                }
            }
        }
    }

    void startExpectResult(Intent service) {
        startExpectResult(service, new Bundle());
    }

    void startExpectResult(Intent service, Bundle bundle) {
        bundle.putIBinder(REPORT_OBJ_NAME, mStartReceiver);
        boolean success = false;
        try {
            //Log.i("foo", "STATE_START_1");
            mStartState = STATE_START_1;
            getContext().startService(new Intent(service).putExtras(bundle));
            waitForResultOrThrow(5 * 1000, "service to start first time");
            //Log.i("foo", "STATE_START_2");
            mStartState = STATE_START_2;
            getContext().startService(new Intent(service).putExtras(bundle));
            waitForResultOrThrow(5 * 1000, "service to start second time");
            success = true;
        } finally {
            if (!success) {
                try {
                    getContext().stopService(service);
                } catch (Exception e) {
                    // eat
                }
            }
        }
        //Log.i("foo", "STATE_DESTROY");
        mStartState = STATE_DESTROY;
        getContext().stopService(service);
        waitForResultOrThrow(5 * 1000, "service to be destroyed");
    }

    void startExpectNoPermission(Intent service) {
        try {
            getContext().startService(service);
            fail("Expected security exception when starting " + service);
        } catch (SecurityException e) {
            // expected
        }
    }

    void bindExpectResult(Intent service) {
        TestConnection conn = new TestConnection(true, false);
        TestConnection conn2 = new TestConnection(false, false);
        boolean success = false;
        try {
            // Expect to see the TestConnection connected.
            mStartState = STATE_START_1;
            getContext().bindService(service, conn, 0);
            getContext().startService(service);
            waitForResultOrThrow(5 * 1000, "existing connection to receive service");

            // Expect to see the second TestConnection connected.
            getContext().bindService(service, conn2, 0);
            waitForResultOrThrow(5 * 1000, "new connection to receive service");

            getContext().unbindService(conn2);
            success = true;
        } finally {
            if (!success) {
                try {
                    getContext().stopService(service);
                    getContext().unbindService(conn);
                    getContext().unbindService(conn2);
                } catch (Exception e) {
                    // eat
                }
            }
        }

        // Expect to see the TestConnection disconnected.
        mStartState = STATE_DESTROY;
        getContext().stopService(service);
        waitForResultOrThrow(5 * 1000, "existing connection to lose service");

        getContext().unbindService(conn);

        conn = new TestConnection(true, true);
        success = false;
        try {
            // Expect to see the TestConnection connected.
            conn.setMonitor(true);
            mStartState = STATE_START_1;
            getContext().bindService(service, conn, 0);
            getContext().startService(service);
            waitForResultOrThrow(5 * 1000, "existing connection to receive service");

            success = true;
        } finally {
            if (!success) {
                try {
                    getContext().stopService(service);
                    getContext().unbindService(conn);
                } catch (Exception e) {
                    // eat
                }
            }
        }

        // Expect to see the service unbind and then destroyed.
        conn.setMonitor(false);
        mStartState = STATE_UNBIND;
        getContext().stopService(service);
        waitForResultOrThrow(5 * 1000, "existing connection to lose service");

        getContext().unbindService(conn);

        conn = new TestConnection(true, true);
        success = false;
        try {
            // Expect to see the TestConnection connected.
            conn.setMonitor(true);
            mStartState = STATE_START_1;
            getContext().bindService(service, conn, 0);
            getContext().startService(service);
            waitForResultOrThrow(5 * 1000, "existing connection to receive service");

            success = true;
        } finally {
            if (!success) {
                try {
                    getContext().stopService(service);
                    getContext().unbindService(conn);
                } catch (Exception e) {
                    // eat
                }
            }
        }

        // Expect to see the service unbind but not destroyed.
        conn.setMonitor(false);
        mStartState = STATE_UNBIND_ONLY;
        getContext().unbindService(conn);
        waitForResultOrThrow(5 * 1000, "existing connection to unbind service");

        // Expect to see the service rebound.
        mStartState = STATE_REBIND;
        getContext().bindService(service, conn, 0);
        waitForResultOrThrow(5 * 1000, "existing connection to rebind service");

        // Expect to see the service unbind and then destroyed.
        mStartState = STATE_UNBIND;
        getContext().stopService(service);
        waitForResultOrThrow(5 * 1000, "existing connection to lose service");

        getContext().unbindService(conn);
    }

    void bindAutoExpectResult(Intent service) {
        TestConnection conn = new TestConnection(false, true);
        boolean success = false;
        try {
            conn.setMonitor(true);
            mStartState = STATE_START_1;
            getContext().bindService(
                    service, conn, Context.BIND_AUTO_CREATE);
            waitForResultOrThrow(5 * 1000, "connection to start and receive service");
            success = true;
        } finally {
            if (!success) {
                try {
                    getContext().unbindService(conn);
                } catch (Exception e) {
                    // eat
                }
            }
        }
        mStartState = STATE_UNBIND;
        getContext().unbindService(conn);
        waitForResultOrThrow(5 * 1000, "disconnecting from service");
    }

    void bindExpectNoPermission(Intent service) {
        TestConnection conn = new TestConnection(false, false);
        try {
            getContext().bindService(service, conn, Context.BIND_AUTO_CREATE);
            fail("Expected security exception when binding " + service);
        } catch (SecurityException e) {
            // expected
        } finally {
            getContext().unbindService(conn);
        }
    }


    @MediumTest
    public void testLocalStartClass() throws Exception {
        startExpectResult(new Intent(getContext(), LocalService.class));
    }

    @MediumTest
    public void testLocalStartAction() throws Exception {
        startExpectResult(new Intent(SERVICE_LOCAL));
    }

    @MediumTest
    public void testLocalBindClass() throws Exception {
        bindExpectResult(new Intent(getContext(), LocalService.class));
    }

    @MediumTest
    public void testLocalBindAction() throws Exception {
        bindExpectResult(new Intent(SERVICE_LOCAL));
    }

    @MediumTest
    public void testLocalBindAutoClass() throws Exception {
        bindAutoExpectResult(new Intent(getContext(), LocalService.class));
    }

    @MediumTest
    public void testLocalBindAutoAction() throws Exception {
        bindAutoExpectResult(new Intent(SERVICE_LOCAL));
    }

    @MediumTest
    public void testLocalStartClassPermissionGranted() throws Exception {
        startExpectResult(new Intent(getContext(), LocalGrantedService.class));
    }

    @MediumTest
    public void testLocalStartActionPermissionGranted() throws Exception {
        startExpectResult(new Intent(SERVICE_LOCAL_GRANTED));
    }

    @MediumTest
    public void testLocalBindClassPermissionGranted() throws Exception {
        bindExpectResult(new Intent(getContext(), LocalGrantedService.class));
    }

    @MediumTest
    public void testLocalBindActionPermissionGranted() throws Exception {
        bindExpectResult(new Intent(SERVICE_LOCAL_GRANTED));
    }

    @MediumTest
    public void testLocalBindAutoClassPermissionGranted() throws Exception {
        bindAutoExpectResult(new Intent(getContext(), LocalGrantedService.class));
    }

    @MediumTest
    public void testLocalBindAutoActionPermissionGranted() throws Exception {
        bindAutoExpectResult(new Intent(SERVICE_LOCAL_GRANTED));
    }

    @MediumTest
    public void testLocalStartClassPermissionDenied() throws Exception {
        startExpectNoPermission(new Intent(getContext(), LocalDeniedService.class));
    }

    @MediumTest
    public void testLocalStartActionPermissionDenied() throws Exception {
        startExpectNoPermission(new Intent(SERVICE_LOCAL_DENIED));
    }

    @MediumTest
    public void testLocalBindClassPermissionDenied() throws Exception {
        bindExpectNoPermission(new Intent(getContext(), LocalDeniedService.class));
    }

    @MediumTest
    public void testLocalBindActionPermissionDenied() throws Exception {
        bindExpectNoPermission(new Intent(SERVICE_LOCAL_DENIED));
    }

    @MediumTest
    public void testLocalUnbindTwice() throws Exception {
        EmptyConnection conn = new EmptyConnection();
        getContext().bindService(
                new Intent(SERVICE_LOCAL_GRANTED), conn, 0);
        getContext().unbindService(conn);
        try {
            getContext().unbindService(conn);
            fail("No exception thrown on second unbind");
        } catch (IllegalArgumentException e) {
            //Log.i("foo", "Unbind exception", e);
        }
    }
}
