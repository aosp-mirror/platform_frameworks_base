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
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.test.PerformanceTestCase;
import android.util.Log;

class MyBadParcelable implements Parcelable {
    public MyBadParcelable() {
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString("I am bad");
    }

    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<MyBadParcelable> CREATOR
            = new Parcelable.Creator<MyBadParcelable>() {
        public MyBadParcelable createFromParcel(Parcel in) {
            return new MyBadParcelable(in);
        }

        public MyBadParcelable[] newArray(int size) {
            return new MyBadParcelable[size];
        }
    };

    public MyBadParcelable(Parcel in) {
        String nm = in.readString();
    }
}

public class LaunchpadActivity extends Activity {
    public interface CallingTest extends PerformanceTestCase.Intermediates {
        public void startTiming(boolean realTime);
        public void addIntermediate(String name);
        public void addIntermediate(String name, long timeInNS);
        public void finishTiming(boolean realTime);
        public void activityFinished(int resultCode, Intent data,
                RuntimeException where);
    }

    // Also used as the Binder interface descriptor string in these tests
    public static final String LAUNCH = "com.android.frameworks.coretests.activity.LAUNCH";

    public static final String FORWARD_RESULT =
            "com.android.frameworks.coretests.activity.FORWARD_RESULT";
    public static final String RETURNED_RESULT =
            "com.android.frameworks.coretests.activity.RETURNED_RESULT";

    public static final String BAD_PARCELABLE =
            "comcom.android.frameworks.coretests.activity.BAD_PARCELABLE";

    public static final int LAUNCHED_RESULT = 1;
    public static final int FORWARDED_RESULT = 2;

    public static final String LIFECYCLE_BASIC =
            "com.android.frameworks.coretests.activity.LIFECYCLE_BASIC";
    public static final String LIFECYCLE_SCREEN =
            "com.android.frameworks.coretests.activity.LIFECYCLE_SCREEN";
    public static final String LIFECYCLE_DIALOG =
            "com.android.frameworks.coretests.activity.LIFECYCLE_DIALOG";
    public static final String LIFECYCLE_FINISH_CREATE =
            "com.android.frameworks.coretests.activity.LIFECYCLE_FINISH_CREATE";
    public static final String LIFECYCLE_FINISH_START =
            "com.android.frameworks.coretests.activity.LIFECYCLE_FINISH_START";

    public static final String BROADCAST_REGISTERED =
            "com.android.frameworks.coretests.activity.BROADCAST_REGISTERED";
    public static final String BROADCAST_LOCAL =
            "com.android.frameworks.coretests.activity.BROADCAST_LOCAL";
    public static final String BROADCAST_REMOTE =
            "com.android.frameworks.coretests.activity.BROADCAST_REMOTE";
    public static final String BROADCAST_ALL =
            "com.android.frameworks.coretests.activity.BROADCAST_ALL";
    public static final String BROADCAST_REPEAT =
        "com.android.frameworks.coretests.activity.BROADCAST_REPEAT";
    public static final String BROADCAST_MULTI =
            "com.android.frameworks.coretests.activity.BROADCAST_MULTI";
    public static final String BROADCAST_ABORT =
            "com.android.frameworks.coretests.activity.BROADCAST_ABORT";

    public static final String BROADCAST_STICKY1 =
            "com.android.frameworks.coretests.activity.BROADCAST_STICKY1";
    public static final String BROADCAST_STICKY2 =
            "com.android.frameworks.coretests.activity.BROADCAST_STICKY2";

    public static final String RECEIVER_REG = "receiver-reg";
    public static final String RECEIVER_LOCAL = "receiver-local";
    public static final String RECEIVER_REMOTE = "receiver-remote";
    public static final String RECEIVER_ABORT = "receiver-abort";

    public static final String DATA_1 = "one";
    public static final String DATA_2 = "two";

    public static final String ON_START = "onStart";
    public static final String ON_RESTART = "onRestart";
    public static final String ON_RESUME = "onResume";
    public static final String ON_FREEZE = "onSaveInstanceState";
    public static final String ON_PAUSE = "onPause";
    public static final String ON_STOP = "onStop";
    public static final String ON_DESTROY = "onDestroy";

    public static final String DO_FINISH = "finish";
    public static final String DO_LOCAL_SCREEN = "local-screen";
    public static final String DO_LOCAL_DIALOG = "local-dialog";

    private boolean mBadParcelable = false;

    private boolean mStarted = false;
    private long mStartTime;

    private int mResultCode = RESULT_CANCELED;
    private Intent mData = (new Intent()).setAction("No result received");
    private RuntimeException mResultStack = null;

    private String[] mExpectedLifecycle = null;
    private int mNextLifecycle;

    private String[] mExpectedReceivers = null;
    private int mNextReceiver;

    private String[] mExpectedData = null;
    private boolean[] mReceivedData = null;

    boolean mReceiverRegistered = false;

    private static CallingTest sCallingTest = null;

    public static void setCallingTest(CallingTest ct) {
        sCallingTest = ct;
    }

    public LaunchpadActivity() {
        mStartTime = System.currentTimeMillis();
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        String action = getIntent().getAction();
        if (ActivityTests.DEBUG_LIFECYCLE) Log.v("test", "CREATE lauchpad "
                + Integer.toHexString(System.identityHashCode(this)) + ": " + getIntent());
        if (LIFECYCLE_BASIC.equals(action)) {
            setExpectedLifecycle(new String[]{ON_START, ON_RESUME,
                    DO_FINISH, ON_PAUSE, ON_STOP, ON_DESTROY});
        } else if (LIFECYCLE_SCREEN.equals(action)) {
            setExpectedLifecycle(new String[]{ON_START, ON_RESUME,
                    DO_LOCAL_SCREEN, ON_FREEZE, ON_PAUSE, ON_STOP,
                    ON_RESTART, ON_START, ON_RESUME,
                    DO_FINISH, ON_PAUSE, ON_STOP, ON_DESTROY});
        } else if (LIFECYCLE_DIALOG.equals(action)) {
            setExpectedLifecycle(new String[]{ON_START, ON_RESUME,
                    DO_LOCAL_DIALOG, ON_FREEZE, ON_PAUSE, ON_RESUME,
                    DO_FINISH, ON_PAUSE, ON_STOP, ON_DESTROY});
        } else if (LIFECYCLE_FINISH_CREATE.equals(action)) {
            // This one behaves a little differently when running in a group.
            if (getParent() == null) {
                setExpectedLifecycle(new String[]{ON_DESTROY});
            } else {
                setExpectedLifecycle(new String[]{ON_START, ON_STOP, ON_DESTROY});
            }
            finish();
        } else if (LIFECYCLE_FINISH_START.equals(action)) {
            setExpectedLifecycle(new String[]{ON_START, DO_FINISH,
                    ON_STOP, ON_DESTROY});
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (ActivityTests.DEBUG_LIFECYCLE) Log.v("test", "START lauchpad "
                + Integer.toHexString(System.identityHashCode(this)) + ": " + getIntent());
        checkLifecycle(ON_START);
    }

    @Override
    protected void onRestart() {
        super.onStart();
        checkLifecycle(ON_RESTART);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (ActivityTests.DEBUG_LIFECYCLE) Log.v("test", "RESUME lauchpad "
                + Integer.toHexString(System.identityHashCode(this)) + ": " + getIntent());
        checkLifecycle(ON_RESUME);

        if (!mStarted) {
            mStarted = true;

            mHandler.postDelayed(mTimeout, 5 * 1000);

            String action = getIntent().getAction();

            sCallingTest.startTiming(true);

            if (LAUNCH.equals(action)) {
                Intent intent = getIntent();
                intent.setFlags(0);
                intent.setComponent((ComponentName)
                        intent.getParcelableExtra("component"));
                //System.out.println("*** Launchpad is starting: comp=" + intent.component);
                startActivityForResult(intent, LAUNCHED_RESULT);
            } else if (FORWARD_RESULT.equals(action)) {
                Intent intent = getIntent();
                intent.setFlags(0);
                intent.setClass(this, LocalScreen.class);
                startActivityForResult(intent, FORWARDED_RESULT);
            } else if (BAD_PARCELABLE.equals(action)) {
                mBadParcelable = true;
                Intent intent = getIntent();
                intent.setFlags(0);
                intent.setClass(this, LocalScreen.class);
                startActivityForResult(intent, LAUNCHED_RESULT);
            } else if (BROADCAST_REGISTERED.equals(action)) {
                setExpectedReceivers(new String[]{RECEIVER_REG});
                registerMyReceiver(new IntentFilter(BROADCAST_REGISTERED));
                sCallingTest.addIntermediate("after-register");
                sendBroadcast(makeBroadcastIntent(BROADCAST_REGISTERED));
            } else if (BROADCAST_LOCAL.equals(action)) {
                setExpectedReceivers(new String[]{RECEIVER_LOCAL});
                sendBroadcast(makeBroadcastIntent(BROADCAST_LOCAL));
            } else if (BROADCAST_REMOTE.equals(action)) {
                setExpectedReceivers(new String[]{RECEIVER_REMOTE});
                sendBroadcast(makeBroadcastIntent(BROADCAST_REMOTE));
            } else if (BROADCAST_ALL.equals(action)) {
                setExpectedReceivers(new String[]{
                        RECEIVER_REMOTE, RECEIVER_REG, RECEIVER_LOCAL});
                registerMyReceiver(new IntentFilter(BROADCAST_ALL));
                sCallingTest.addIntermediate("after-register");
                sendOrderedBroadcast(makeBroadcastIntent(BROADCAST_ALL), null);
            } else if (BROADCAST_MULTI.equals(action)) {
                setExpectedReceivers(new String[]{
                        RECEIVER_REMOTE, RECEIVER_REG, RECEIVER_LOCAL,
                        RECEIVER_REMOTE, RECEIVER_REG, RECEIVER_LOCAL,
                        RECEIVER_REMOTE, RECEIVER_REG, RECEIVER_LOCAL,
                        RECEIVER_LOCAL, RECEIVER_REMOTE,
                        RECEIVER_LOCAL, RECEIVER_REMOTE,
                        RECEIVER_REMOTE, RECEIVER_REG, RECEIVER_LOCAL,
                        RECEIVER_REMOTE, RECEIVER_REG, RECEIVER_LOCAL,
                        RECEIVER_REMOTE, RECEIVER_REG, RECEIVER_LOCAL,
                        RECEIVER_REMOTE, RECEIVER_LOCAL,
                        RECEIVER_REMOTE, RECEIVER_LOCAL});
                registerMyReceiver(new IntentFilter(BROADCAST_ALL));
                sCallingTest.addIntermediate("after-register");
                sendOrderedBroadcast(makeBroadcastIntent(BROADCAST_ALL), null);
                sendOrderedBroadcast(makeBroadcastIntent(BROADCAST_ALL), null);
                sendOrderedBroadcast(makeBroadcastIntent(BROADCAST_ALL), null);
                sendOrderedBroadcast(makeBroadcastIntent(BROADCAST_LOCAL), null);
                sendOrderedBroadcast(makeBroadcastIntent(BROADCAST_REMOTE), null);
                sendOrderedBroadcast(makeBroadcastIntent(BROADCAST_LOCAL), null);
                sendOrderedBroadcast(makeBroadcastIntent(BROADCAST_REMOTE), null);
                sendOrderedBroadcast(makeBroadcastIntent(BROADCAST_ALL), null);
                sendOrderedBroadcast(makeBroadcastIntent(BROADCAST_ALL), null);
                sendOrderedBroadcast(makeBroadcastIntent(BROADCAST_ALL), null);
                sendOrderedBroadcast(makeBroadcastIntent(BROADCAST_REPEAT), null);
            } else if (BROADCAST_ABORT.equals(action)) {
                setExpectedReceivers(new String[]{
                        RECEIVER_REMOTE, RECEIVER_ABORT});
                registerMyReceiver(new IntentFilter(BROADCAST_ABORT));
                sCallingTest.addIntermediate("after-register");
                sendOrderedBroadcast(makeBroadcastIntent(BROADCAST_ABORT), null);
            } else if (BROADCAST_STICKY1.equals(action)) {
                setExpectedReceivers(new String[]{RECEIVER_REG});
                setExpectedData(new String[]{DATA_1});
                registerMyReceiver(new IntentFilter(BROADCAST_STICKY1));
                sCallingTest.addIntermediate("after-register");
            } else if (BROADCAST_STICKY2.equals(action)) {
                setExpectedReceivers(new String[]{RECEIVER_REG, RECEIVER_REG});
                setExpectedData(new String[]{DATA_1, DATA_2});
                IntentFilter filter = new IntentFilter(BROADCAST_STICKY1);
                filter.addAction(BROADCAST_STICKY2);
                registerMyReceiver(filter);
                sCallingTest.addIntermediate("after-register");
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle icicle) {
        super.onSaveInstanceState(icicle);
        checkLifecycle(ON_FREEZE);
        if (mBadParcelable) {
            icicle.putParcelable("baddy", new MyBadParcelable());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (ActivityTests.DEBUG_LIFECYCLE) Log.v("test", "PAUSE lauchpad "
                + Integer.toHexString(System.identityHashCode(this)) + ": " + getIntent());
        checkLifecycle(ON_PAUSE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (ActivityTests.DEBUG_LIFECYCLE) Log.v("test", "STOP lauchpad "
                + Integer.toHexString(System.identityHashCode(this)) + ": " + getIntent());
        checkLifecycle(ON_STOP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
            Intent data) {
        switch (requestCode) {
            case LAUNCHED_RESULT:
                sCallingTest.finishTiming(true);
                finishWithResult(resultCode, data);
                break;
            case FORWARDED_RESULT:
                sCallingTest.finishTiming(true);
                if (RETURNED_RESULT.equals(data.getAction())) {
                    finishWithResult(resultCode, data);
                } else {
                    finishWithResult(RESULT_CANCELED, (new Intent()).setAction(
                            "Bad data returned: " + data));
                }
                break;
            default:
                sCallingTest.finishTiming(true);
                finishWithResult(RESULT_CANCELED, (new Intent()).setAction(
                        "Unexpected request code: " + requestCode));
                break;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ActivityTests.DEBUG_LIFECYCLE) Log.v("test", "DESTROY lauchpad "
                + Integer.toHexString(System.identityHashCode(this)) + ": " + getIntent());
        checkLifecycle(ON_DESTROY);
        sCallingTest.activityFinished(mResultCode, mData, mResultStack);
    }

    private void setExpectedLifecycle(String[] lifecycle) {
        mExpectedLifecycle = lifecycle;
        mNextLifecycle = 0;
    }

    private void checkLifecycle(String where) {
        if (mExpectedLifecycle == null) return;

        if (mNextLifecycle >= mExpectedLifecycle.length) {
            finishBad("Activity lifecycle incorrect: received " + where
                    + " but don't expect any more calls");
            mExpectedLifecycle = null;
            return;
        }
        if (!mExpectedLifecycle[mNextLifecycle].equals(where)) {
            finishBad("Activity lifecycle incorrect: received " + where
                    + " but expected " + mExpectedLifecycle[mNextLifecycle]
                    + " at " + mNextLifecycle);
            mExpectedLifecycle = null;
            return;
        }

        mNextLifecycle++;

        if (mNextLifecycle >= mExpectedLifecycle.length) {
            setTestResult(RESULT_OK, null);
            return;
        }

        String next = mExpectedLifecycle[mNextLifecycle];
        if (where.equals(ON_DESTROY)) {
            finishBad("Activity lifecycle incorrect: received " + where
                    + " but expected more actions (next is " + next + ")");
            mExpectedLifecycle = null;
            return;
        } else if (next.equals(DO_FINISH)) {
            mNextLifecycle++;
            if (mNextLifecycle >= mExpectedLifecycle.length) {
                setTestResult(RESULT_OK, null);
            }
            if (!isFinishing()) {
                finish();
            }
        } else if (next.equals(DO_LOCAL_SCREEN)) {
            mNextLifecycle++;
            Intent intent = new Intent(TestedScreen.WAIT_BEFORE_FINISH);
            intent.setClass(this, LocalScreen.class);
            startActivity(intent);
        } else if (next.equals(DO_LOCAL_DIALOG)) {
            mNextLifecycle++;
            Intent intent = new Intent(TestedScreen.WAIT_BEFORE_FINISH);
            intent.setClass(this, LocalDialog.class);
            startActivity(intent);
        }
    }

    private void setExpectedReceivers(String[] receivers) {
        mExpectedReceivers = receivers;
        mNextReceiver = 0;
    }

    private void setExpectedData(String[] data) {
        mExpectedData = data;
        mReceivedData = new boolean[data.length];
    }

    private Intent makeBroadcastIntent(String action) {
        Intent intent = new Intent(action, null);
        intent.putExtra("caller", mCallTarget);
        return intent;
    }

    private void finishGood() {
        finishWithResult(RESULT_OK, null);
    }

    private void finishBad(String error) {
        finishWithResult(RESULT_CANCELED, (new Intent()).setAction(error));
    }

    private void finishWithResult(int resultCode, Intent data) {
        setTestResult(resultCode, data);
        finish();
    }

    private void setTestResult(int resultCode, Intent data) {
        mHandler.removeCallbacks(mTimeout);
        unregisterMyReceiver();
        mResultCode = resultCode;
        mData = data;
        mResultStack = new RuntimeException("Original error was here");
        mResultStack.fillInStackTrace();
    }

    private void registerMyReceiver(IntentFilter filter) {
        mReceiverRegistered = true;
        //System.out.println("Registering: " + mReceiver);
        registerReceiver(mReceiver, filter);
    }

    private void unregisterMyReceiver() {
        if (mReceiverRegistered) {
            mReceiverRegistered = false;
            //System.out.println("Unregistering: " + mReceiver);
            unregisterReceiver(mReceiver);
        }
    }

    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
        }
    };

    static final int GOT_RECEIVE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION;
    static final int ERROR_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 1;

    private Binder mCallTarget = new Binder() {
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
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

    private final void gotReceive(String name, Intent intent) {
        synchronized (this) {

            //System.out.println("Got receive: " + name);
            //System.out.println(mNextReceiver + " in " + mExpectedReceivers);
            //new RuntimeException("stack").printStackTrace();

            sCallingTest.addIntermediate(mNextReceiver + "-" + name);

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
                    return;
                }
            }

            if (mNextReceiver >= mExpectedReceivers.length) {
                finishBad("Got too many onReceiveIntent() calls!");
//                System.out.println("Too many intents received: now at "
//                                   + mNextReceiver + ", expect list: "
//                                   + Arrays.toString(mExpectedReceivers));
            } else if (!mExpectedReceivers[mNextReceiver].equals(name)) {
                finishBad("Receive out of order: got " + name + " but expected "
                        + mExpectedReceivers[mNextReceiver] + " at "
                        + mNextReceiver);
            } else {
                mNextReceiver++;
                if (mNextReceiver == mExpectedReceivers.length) {
                    mHandler.post(mUnregister);
                }
            }

        }
    }

    private Runnable mUnregister = new Runnable() {
        public void run() {
            if (mReceiverRegistered) {
                sCallingTest.addIntermediate("before-unregister");
                unregisterMyReceiver();
            }
            sCallingTest.finishTiming(true);
            finishGood();
        }
    };

    private Runnable mTimeout = new Runnable() {
        public void run() {
            Log.i("foo", "**** TIMEOUT");
            String msg = "Timeout";
            if (mExpectedReceivers != null
                    && mNextReceiver < mExpectedReceivers.length) {
                msg = msg + " waiting for " + mExpectedReceivers[mNextReceiver];
            }
            finishBad(msg);
        }
    };

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            //System.out.println("Receive in: " + this + ": " + intent);
            gotReceive(RECEIVER_REG, intent);
        }
    };
}

