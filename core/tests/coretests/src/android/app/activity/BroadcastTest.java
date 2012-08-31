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

import android.app.Activity;
import android.app.ActivityManagerNative;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.UserHandle;
import android.test.FlakyTest;
import android.test.suitebuilder.annotation.Suppress;
import android.util.Log;

import java.util.Arrays;

public class BroadcastTest extends ActivityTestsBase {
    public static final int BROADCAST_TIMEOUT = 5 * 1000;

    public static final String BROADCAST_REGISTERED =
            "com.android.frameworks.coretests.activity.BROADCAST_REGISTERED";
    public static final String BROADCAST_LOCAL =
            "com.android.frameworks.coretests.activity.BROADCAST_LOCAL";
    public static final String BROADCAST_LOCAL_GRANTED =
            "com.android.frameworks.coretests.activity.BROADCAST_LOCAL_GRANTED";
    public static final String BROADCAST_LOCAL_DENIED =
            "com.android.frameworks.coretests.activity.BROADCAST_LOCAL_DENIED";
    public static final String BROADCAST_REMOTE =
            "com.android.frameworks.coretests.activity.BROADCAST_REMOTE";
    public static final String BROADCAST_REMOTE_GRANTED =
            "com.android.frameworks.coretests.activity.BROADCAST_REMOTE_GRANTED";
    public static final String BROADCAST_REMOTE_DENIED =
            "com.android.frameworks.coretests.activity.BROADCAST_REMOTE_DENIED";
    public static final String BROADCAST_ALL =
            "com.android.frameworks.coretests.activity.BROADCAST_ALL";
    public static final String BROADCAST_MULTI =
            "com.android.frameworks.coretests.activity.BROADCAST_MULTI";
    public static final String BROADCAST_ABORT =
            "com.android.frameworks.coretests.activity.BROADCAST_ABORT";

    public static final String BROADCAST_STICKY1 =
            "com.android.frameworks.coretests.activity.BROADCAST_STICKY1";
    public static final String BROADCAST_STICKY2 =
            "com.android.frameworks.coretests.activity.BROADCAST_STICKY2";

    public static final String BROADCAST_FAIL_REGISTER =
            "com.android.frameworks.coretests.activity.BROADCAST_FAIL_REGISTER";
    public static final String BROADCAST_FAIL_BIND =
            "com.android.frameworks.coretests.activity.BROADCAST_FAIL_BIND";

    public static final String RECEIVER_REG = "receiver-reg";
    public static final String RECEIVER_LOCAL = "receiver-local";
    public static final String RECEIVER_REMOTE = "receiver-remote";
    public static final String RECEIVER_ABORT = "receiver-abort";
    public static final String RECEIVER_RESULTS = "receiver-results";

    public static final String DATA_1 = "one";
    public static final String DATA_2 = "two";

    public static final int GOT_RECEIVE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION;
    public static final int ERROR_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 1;

    private String[] mExpectedReceivers = null;
    private int mNextReceiver;

    private String[] mExpectedData = null;
    private boolean[] mReceivedData = null;

    boolean mReceiverRegistered = false;

    public void setExpectedReceivers(String[] receivers) {
        mExpectedReceivers = receivers;
        mNextReceiver = 0;
    }

    public void setExpectedData(String[] data) {
        mExpectedData = data;
        mReceivedData = new boolean[data.length];
    }

    public void onTimeout() {
        String msg = "Timeout";
        if (mExpectedReceivers != null && mNextReceiver < mExpectedReceivers.length) {
            msg = msg + " waiting for " + mExpectedReceivers[mNextReceiver];
        }
        finishBad(msg);
    }

    public Intent makeBroadcastIntent(String action) {
        Intent intent = new Intent(action, null);
        intent.putExtra("caller", mCallTarget);
        return intent;
    }

    public void finishWithResult(int resultCode, Intent data) {
        unregisterMyReceiver();
        super.finishWithResult(resultCode, data);
    }

    public final void gotReceive(String name, Intent intent) {
        synchronized (this) {

            //System.out.println("Got receive: " + name);
            //System.out.println(mNextReceiver + " in " + mExpectedReceivers);
            //new RuntimeException("stack").printStackTrace();

            addIntermediate(name);

            if (mExpectedData != null) {
                int n = mExpectedData.length;
                int i;
                boolean prev = false;
                for (i = 0; i < n; i++) {
                    if (mExpectedData[i].equals(intent.getStringExtra("test"))) {
                        if (mReceivedData[i]) {
                            prev = true;
                            continue;
                        }
                        mReceivedData[i] = true;
                        break;
                    }
                }
                if (i >= n) {
                    if (prev) {
                        finishBad("Receive got data too many times: "
                                + intent.getStringExtra("test"));
                    } else {
                        finishBad("Receive got unexpected data: "
                                + intent.getStringExtra("test"));
                    }
                    new RuntimeException("stack").printStackTrace();
                    return;
                }
            }

            if (mNextReceiver >= mExpectedReceivers.length) {
                finishBad("Got too many onReceiveIntent() calls!");
//                System.out.println("Too many intents received: now at "
//                        + mNextReceiver + ", expect list: "
//                        + Arrays.toString(mExpectedReceivers));
                fail("Got too many onReceiveIntent() calls!");
            } else if (!mExpectedReceivers[mNextReceiver].equals(name)) {
                finishBad("Receive out of order: got " + name
                        + " but expected "
                        + mExpectedReceivers[mNextReceiver]);
                fail("Receive out of order: got " + name
                        + " but expected "
                        + mExpectedReceivers[mNextReceiver]);
            } else {
                mNextReceiver++;
                if (mNextReceiver == mExpectedReceivers.length) {
                    finishTest();
                }
            }
        }
    }

    public void registerMyReceiver(IntentFilter filter, String permission) {
        mReceiverRegistered = true;
        //System.out.println("Registering: " + mReceiver);
        getContext().registerReceiver(mReceiver, filter, permission, null);
    }

    public void unregisterMyReceiver() {
        if (mReceiverRegistered) {
            unregisterMyReceiverNoCheck();
        }
    }

    public void unregisterMyReceiverNoCheck() {
        mReceiverRegistered = false;
        //System.out.println("Unregistering: " + mReceiver);
        getContext().unregisterReceiver(mReceiver);
    }

    public void onRegisteredReceiver(Intent intent) {
        gotReceive(RECEIVER_REG, intent);
    }

    private Binder mCallTarget = new Binder() {
        public boolean onTransact(int code, Parcel data, Parcel reply,
                int flags) {
            data.setDataPosition(0);
            data.enforceInterface(LaunchpadActivity.LAUNCH);
            if (code == GOT_RECEIVE_TRANSACTION) {
                String name = data.readString();
                gotReceive(name, null);
                return true;
            } else if (code == ERROR_TRANSACTION) {
                finishBad(data.readString());
                return true;
            }
            return false;
        }
    };

    private void finishTest() {
        if (mReceiverRegistered) {
            addIntermediate("before-unregister");
            unregisterMyReceiver();
        }
        finishTiming(true);
        finishGood();
    }

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            //System.out.println("Receive in: " + this + ": " + intent);
            onRegisteredReceiver(intent);
        }
    };

    // Mark flaky until http://b/issue?id=1191607 is resolved.
    @FlakyTest(tolerance=2)
    public void testRegistered() throws Exception {
        runLaunchpad(LaunchpadActivity.BROADCAST_REGISTERED);
    }

    public void testLocal() throws Exception {
        runLaunchpad(LaunchpadActivity.BROADCAST_LOCAL);
    }

    public void testRemote() throws Exception {
        runLaunchpad(LaunchpadActivity.BROADCAST_REMOTE);
    }

    public void testAbort() throws Exception {
        runLaunchpad(LaunchpadActivity.BROADCAST_ABORT);
    }

    @FlakyTest(tolerance=2)
    public void testAll() throws Exception {
        runLaunchpad(LaunchpadActivity.BROADCAST_ALL);
    }

    @FlakyTest(tolerance=2)
    public void testMulti() throws Exception {
        runLaunchpad(LaunchpadActivity.BROADCAST_MULTI);
    }

    private class TestBroadcastReceiver extends BroadcastReceiver {
        public boolean mHaveResult = false;

        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (BroadcastTest.this) {
                mHaveResult = true;
                BroadcastTest.this.notifyAll();
            }
        }
    }

    public void testResult() throws Exception {
        TestBroadcastReceiver broadcastReceiver = new TestBroadcastReceiver();

        synchronized (this) {
            Bundle map = new Bundle();
            map.putString("foo", "you");
            map.putString("remove", "me");
            getContext().sendOrderedBroadcast(
                    new Intent("com.android.frameworks.coretests.activity.BROADCAST_RESULT"),
                    null, broadcastReceiver, null, 1, "foo", map);
            while (!broadcastReceiver.mHaveResult) {
                try {
                    wait();
                } catch (InterruptedException e) {
                }
            }

            //System.out.println("Code: " + mResultCode + ", data: " + mResultData);
            //System.out.println("Extras: " + mResultExtras);

            assertEquals("Incorrect code: " + broadcastReceiver.getResultCode(),
                    3, broadcastReceiver.getResultCode());

            assertEquals("bar", broadcastReceiver.getResultData());

            Bundle resultExtras = broadcastReceiver.getResultExtras(false);
            assertEquals("them", resultExtras.getString("bar"));
            assertEquals("you", resultExtras.getString("foo"));
            assertNull(resultExtras.getString("remove"));
        }
    }

    public void testSetSticky() throws Exception {
        Intent intent = new Intent(LaunchpadActivity.BROADCAST_STICKY1, null);
        intent.putExtra("test", LaunchpadActivity.DATA_1);
        ActivityManagerNative.getDefault().unbroadcastIntent(null, intent,
                UserHandle.myUserId());

        ActivityManagerNative.broadcastStickyIntent(intent, null, UserHandle.myUserId());
        addIntermediate("finished-broadcast");

        IntentFilter filter = new IntentFilter(LaunchpadActivity.BROADCAST_STICKY1);
        Intent sticky = getContext().registerReceiver(null, filter);
        assertNotNull("Sticky not found", sticky);
        assertEquals(LaunchpadActivity.DATA_1, sticky.getStringExtra("test"));
    }

    public void testClearSticky() throws Exception {
        Intent intent = new Intent(LaunchpadActivity.BROADCAST_STICKY1, null);
        intent.putExtra("test", LaunchpadActivity.DATA_1);
        ActivityManagerNative.broadcastStickyIntent(intent, null, UserHandle.myUserId());

        ActivityManagerNative.getDefault().unbroadcastIntent(
                null, new Intent(LaunchpadActivity.BROADCAST_STICKY1, null),
                UserHandle.myUserId());
        addIntermediate("finished-unbroadcast");

        IntentFilter filter = new IntentFilter(LaunchpadActivity.BROADCAST_STICKY1);
        Intent sticky = getContext().registerReceiver(null, filter);
        assertNull("Sticky not found", sticky);
    }

    public void testReplaceSticky() throws Exception {
        Intent intent = new Intent(LaunchpadActivity.BROADCAST_STICKY1, null);
        intent.putExtra("test", LaunchpadActivity.DATA_1);
        ActivityManagerNative.broadcastStickyIntent(intent, null, UserHandle.myUserId());
        intent.putExtra("test", LaunchpadActivity.DATA_2);

        ActivityManagerNative.broadcastStickyIntent(intent, null, UserHandle.myUserId());
        addIntermediate("finished-broadcast");

        IntentFilter filter = new IntentFilter(LaunchpadActivity.BROADCAST_STICKY1);
        Intent sticky = getContext().registerReceiver(null, filter);
        assertNotNull("Sticky not found", sticky);
        assertEquals(LaunchpadActivity.DATA_2, sticky.getStringExtra("test"));
    }

    // Marking flaky until http://b/issue?id=1191337 is resolved
    @FlakyTest(tolerance=2)
    public void testReceiveSticky() throws Exception {
        Intent intent = new Intent(LaunchpadActivity.BROADCAST_STICKY1, null);
        intent.putExtra("test", LaunchpadActivity.DATA_1);
        ActivityManagerNative.broadcastStickyIntent(intent, null, UserHandle.myUserId());

        runLaunchpad(LaunchpadActivity.BROADCAST_STICKY1);
    }

    // Marking flaky until http://b/issue?id=1191337 is resolved
    @FlakyTest(tolerance=2)
    public void testReceive2Sticky() throws Exception {
        Intent intent = new Intent(LaunchpadActivity.BROADCAST_STICKY1, null);
        intent.putExtra("test", LaunchpadActivity.DATA_1);
        ActivityManagerNative.broadcastStickyIntent(intent, null, UserHandle.myUserId());
        intent = new Intent(LaunchpadActivity.BROADCAST_STICKY2, null);
        intent.putExtra("test", LaunchpadActivity.DATA_2);
        ActivityManagerNative.broadcastStickyIntent(intent, null, UserHandle.myUserId());

        runLaunchpad(LaunchpadActivity.BROADCAST_STICKY2);
    }

    public void testRegisteredReceivePermissionGranted() throws Exception {
        setExpectedReceivers(new String[]{RECEIVER_REG});
        registerMyReceiver(new IntentFilter(BROADCAST_REGISTERED), PERMISSION_GRANTED);
        addIntermediate("after-register");
        getContext().sendBroadcast(makeBroadcastIntent(BROADCAST_REGISTERED));
        waitForResultOrThrow(BROADCAST_TIMEOUT);
    }

    public void testRegisteredReceivePermissionDenied() throws Exception {
        setExpectedReceivers(new String[]{RECEIVER_RESULTS});
        registerMyReceiver(new IntentFilter(BROADCAST_REGISTERED), PERMISSION_DENIED);
        addIntermediate("after-register");

        BroadcastReceiver finish = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                gotReceive(RECEIVER_RESULTS, intent);
            }
        };

        getContext().sendOrderedBroadcast(
                makeBroadcastIntent(BROADCAST_REGISTERED),
                null, finish, null, Activity.RESULT_CANCELED, null, null);
        waitForResultOrThrow(BROADCAST_TIMEOUT);
    }

    public void testRegisteredBroadcastPermissionGranted() throws Exception {
        setExpectedReceivers(new String[]{RECEIVER_REG});
        registerMyReceiver(new IntentFilter(BROADCAST_REGISTERED), null);
        addIntermediate("after-register");
        getContext().sendBroadcast(
                makeBroadcastIntent(BROADCAST_REGISTERED),
                PERMISSION_GRANTED);
        waitForResultOrThrow(BROADCAST_TIMEOUT);
    }

    public void testRegisteredBroadcastPermissionDenied() throws Exception {
        setExpectedReceivers(new String[]{RECEIVER_RESULTS});
        registerMyReceiver(new IntentFilter(BROADCAST_REGISTERED), null);
        addIntermediate("after-register");

        BroadcastReceiver finish = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                gotReceive(RECEIVER_RESULTS, intent);
            }
        };

        getContext().sendOrderedBroadcast(
                makeBroadcastIntent(BROADCAST_REGISTERED),
                PERMISSION_DENIED, finish, null, Activity.RESULT_CANCELED,
                null, null);
        waitForResultOrThrow(BROADCAST_TIMEOUT);
    }

    public void testLocalReceivePermissionGranted() throws Exception {
        setExpectedReceivers(new String[]{RECEIVER_LOCAL});
        getContext().sendBroadcast(makeBroadcastIntent(BROADCAST_LOCAL_GRANTED));
        waitForResultOrThrow(BROADCAST_TIMEOUT);
    }

    public void testLocalReceivePermissionDenied() throws Exception {
        setExpectedReceivers(new String[]{RECEIVER_RESULTS});

        BroadcastReceiver finish = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                gotReceive(RECEIVER_RESULTS, intent);
            }
        };

        getContext().sendOrderedBroadcast(
                makeBroadcastIntent(BROADCAST_LOCAL_DENIED),
                null, finish, null, Activity.RESULT_CANCELED,
                null, null);
        waitForResultOrThrow(BROADCAST_TIMEOUT);
    }

    public void testLocalBroadcastPermissionGranted() throws Exception {
        setExpectedReceivers(new String[]{RECEIVER_LOCAL});
        getContext().sendBroadcast(
                makeBroadcastIntent(BROADCAST_LOCAL),
                PERMISSION_GRANTED);
        waitForResultOrThrow(BROADCAST_TIMEOUT);
    }

    public void testLocalBroadcastPermissionDenied() throws Exception {
        setExpectedReceivers(new String[]{RECEIVER_RESULTS});

        BroadcastReceiver finish = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                gotReceive(RECEIVER_RESULTS, intent);
            }
        };

        getContext().sendOrderedBroadcast(
                makeBroadcastIntent(BROADCAST_LOCAL),
                PERMISSION_DENIED, finish, null, Activity.RESULT_CANCELED,
                null, null);
        waitForResultOrThrow(BROADCAST_TIMEOUT);
    }

    public void testRemoteReceivePermissionGranted() throws Exception {
        setExpectedReceivers(new String[]{RECEIVER_REMOTE});
        getContext().sendBroadcast(makeBroadcastIntent(BROADCAST_REMOTE_GRANTED));
        waitForResultOrThrow(BROADCAST_TIMEOUT);
    }

    public void testRemoteReceivePermissionDenied() throws Exception {
        setExpectedReceivers(new String[]{RECEIVER_RESULTS});

        BroadcastReceiver finish = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                gotReceive(RECEIVER_RESULTS, intent);
            }
        };

        getContext().sendOrderedBroadcast(
                makeBroadcastIntent(BROADCAST_REMOTE_DENIED),
                null, finish, null, Activity.RESULT_CANCELED,
                null, null);
        waitForResultOrThrow(BROADCAST_TIMEOUT);
    }

    public void testRemoteBroadcastPermissionGranted() throws Exception {
        setExpectedReceivers(new String[]{RECEIVER_REMOTE});
        getContext().sendBroadcast(
                makeBroadcastIntent(BROADCAST_REMOTE),
                PERMISSION_GRANTED);
        waitForResultOrThrow(BROADCAST_TIMEOUT);
    }

    public void testRemoteBroadcastPermissionDenied() throws Exception {
        setExpectedReceivers(new String[]{RECEIVER_RESULTS});

        BroadcastReceiver finish = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                gotReceive(RECEIVER_RESULTS, intent);
            }
        };

        getContext().sendOrderedBroadcast(
                makeBroadcastIntent(BROADCAST_REMOTE),
                PERMISSION_DENIED, finish, null, Activity.RESULT_CANCELED,
                null, null);
        waitForResultOrThrow(BROADCAST_TIMEOUT);
    }

    public void testReceiverCanNotRegister() throws Exception {
        setExpectedReceivers(new String[]{RECEIVER_LOCAL});
        getContext().sendBroadcast(makeBroadcastIntent(BROADCAST_FAIL_REGISTER));
        waitForResultOrThrow(BROADCAST_TIMEOUT);
    }

    public void testReceiverCanNotBind() throws Exception {
        setExpectedReceivers(new String[]{RECEIVER_LOCAL});
        getContext().sendBroadcast(makeBroadcastIntent(BROADCAST_FAIL_BIND));
        waitForResultOrThrow(BROADCAST_TIMEOUT);
    }

    public void testLocalUnregisterTwice() throws Exception {
        registerMyReceiver(new IntentFilter(BROADCAST_REGISTERED), null);
        unregisterMyReceiverNoCheck();
        try {
            unregisterMyReceiverNoCheck();
            fail("No exception thrown on second unregister");
        } catch (IllegalArgumentException e) {
            Log.i("foo", "Unregister exception", e);
        }
    }
}
