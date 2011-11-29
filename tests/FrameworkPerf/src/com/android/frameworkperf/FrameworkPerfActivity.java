/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.frameworkperf;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * So you thought sync used up your battery life.
 */
public class FrameworkPerfActivity extends Activity
        implements AdapterView.OnItemSelectedListener {
    static final String TAG = "Perf";
    static final boolean DEBUG = false;

    Spinner mFgSpinner;
    Spinner mBgSpinner;
    Spinner mLimitSpinner;
    TextView mLimitLabel;
    TextView mTestTime;
    Button mStartButton;
    Button mStopButton;
    CheckBox mLocalCheckBox;
    TextView mLog;
    PowerManager.WakeLock mPartialWakeLock;

    long mMaxRunTime = 5000;
    boolean mLimitIsIterations;
    boolean mStarted;

    final String[] mAvailOpLabels;
    final String[] mAvailOpDescriptions;
    final String[] mLimitLabels = { "Time", "Iterations" };

    int mFgTestIndex = -1;
    int mBgTestIndex = -1;
    TestService.Op mFgTest;
    TestService.Op mBgTest;
    int mCurOpIndex = 0;
    TestConnection mCurConnection;
    boolean mConnectionBound;

    final ArrayList<RunResult> mResults = new ArrayList<RunResult>();

    Object mResultNotifier = new Object();

    class TestConnection implements ServiceConnection, IBinder.DeathRecipient {
        Messenger mService;
        boolean mLinked;

        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                if (!(service instanceof Binder)) {
                    // If remote, we'll be killing ye.
                    service.linkToDeath(this, 0);
                    mLinked = true;
                }
                mService = new Messenger(service);
                dispatchCurOp(this);
            } catch (RemoteException e) {
                // Whoops, service has disappeared...  try starting again.
                Log.w(TAG, "Test service died, starting again");
                startCurOp();
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
        }

        @Override public void binderDied() {
            cleanup();
            connectionDied(this);
        }

        void cleanup() {
            if (mLinked) {
                mLinked = false;
                mService.getBinder().unlinkToDeath(this, 0);
            }
        }
    }

    static final int MSG_DO_NEXT_TEST = 1000;

    final Handler mHandler = new Handler() {
        @Override public void handleMessage(Message msg) {
            switch (msg.what) {
                case TestService.RES_TEST_FINISHED: {
                    Bundle bundle = (Bundle)msg.obj;
                    bundle.setClassLoader(getClassLoader());
                    RunResult res = (RunResult)bundle.getParcelable("res");
                    completeCurOp(res);
                } break;
                case MSG_DO_NEXT_TEST: {
                    startCurOp();
                } break;
            }
        }
    };

    final Messenger mMessenger = new Messenger(mHandler);

    public FrameworkPerfActivity() {
        mAvailOpLabels = new String[TestService.mAvailOps.length];
        mAvailOpDescriptions = new String[TestService.mAvailOps.length];
        for (int i=0; i<TestService.mAvailOps.length; i++) {
            TestService.Op op = TestService.mAvailOps[i];
            if (op == null) {
                mAvailOpLabels[i] = "All";
                mAvailOpDescriptions[i] = "All tests";
            } else {
                mAvailOpLabels[i] = op.getName();
                if (mAvailOpLabels[i] == null) {
                    mAvailOpLabels[i] = "Nothing";
                }
                mAvailOpDescriptions[i] = op.getLongName();
            }
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Set the layout for this activity.  You can find it
        // in res/layout/hello_activity.xml
        setContentView(R.layout.main);

        mFgSpinner = (Spinner) findViewById(R.id.fgspinner);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, mAvailOpLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mFgSpinner.setAdapter(adapter);
        mFgSpinner.setOnItemSelectedListener(this);
        mBgSpinner = (Spinner) findViewById(R.id.bgspinner);
        adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, mAvailOpLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mBgSpinner.setAdapter(adapter);
        mBgSpinner.setOnItemSelectedListener(this);
        mLimitSpinner = (Spinner) findViewById(R.id.limitspinner);
        adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, mLimitLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mLimitSpinner.setAdapter(adapter);
        mLimitSpinner.setOnItemSelectedListener(this);

        mTestTime = (TextView)findViewById(R.id.testtime);
        mLimitLabel = (TextView)findViewById(R.id.limitlabel);

        mStartButton = (Button)findViewById(R.id.start);
        mStartButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startRunning();
            }
        });
        mStopButton = (Button)findViewById(R.id.stop);
        mStopButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                stopRunning();
            }
        });
        mStopButton.setEnabled(false);
        mLocalCheckBox = (CheckBox)findViewById(R.id.local);

        mLog = (TextView)findViewById(R.id.log);

        PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
        mPartialWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Scheduler");
        mPartialWakeLock.setReferenceCounted(false);
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (parent == mFgSpinner || parent == mBgSpinner || parent == mLimitSpinner) {
            TestService.Op op = TestService.mAvailOps[position];
            if (parent == mFgSpinner) {
                mFgTestIndex = position;
                mFgTest = op;
                ((TextView)findViewById(R.id.fgtext)).setText(mAvailOpDescriptions[position]);
            } else if (parent == mBgSpinner) {
                mBgTestIndex = position;
                mBgTest = op;
                ((TextView)findViewById(R.id.bgtext)).setText(mAvailOpDescriptions[position]);
            } else if (parent == mLimitSpinner) {
                mLimitIsIterations = (position != 0);
                if (mLimitIsIterations) {
                    mLimitLabel.setText("Iterations: ");
                } else {
                    mLimitLabel.setText("Test time (ms): ");
                }
            }
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopRunning();
        if (mPartialWakeLock.isHeld()) {
            mPartialWakeLock.release();
        }
    }

    void dispatchCurOp(TestConnection conn) {
        if (mCurConnection != conn) {
            Log.w(TAG, "Dispatching on invalid connection: " + conn);
            return;
        }
        TestArgs args = new TestArgs();
        if (mLimitIsIterations) {
            args.maxOps = mMaxRunTime;
        } else {
            args.maxTime = mMaxRunTime;
        }
        if (mFgTestIndex == 0 && mBgTestIndex == 0) {
            args.combOp = mCurOpIndex;
        } else if (mFgTestIndex != 0 && mBgTestIndex != 0) {
            args.fgOp = mFgTestIndex;
            args.bgOp = mBgTestIndex;
        } else {
            // Skip null test.
            if (mCurOpIndex == 0) {
                mCurOpIndex = 1;
            }
            if (mFgTestIndex != 0) {
                args.fgOp = mFgTestIndex;
                args.bgOp = mCurOpIndex;
            } else {
                args.fgOp = mCurOpIndex;
                args.bgOp = mFgTestIndex;
            }
        }
        Bundle bundle = new Bundle();
        bundle.putParcelable("args", args);
        Message msg = Message.obtain(null, TestService.CMD_START_TEST, bundle);
        msg.replyTo = mMessenger;
        try {
            conn.mService.send(msg);
        } catch (RemoteException e) {
            Log.w(TAG, "Failure communicating with service", e);
        }
    }

    void completeCurOp(RunResult result) {
        log(String.format("%s: fg=%d*%gms/op (%dms) / bg=%d*%gms/op (%dms)",
                result.name, result.fgOps, result.getFgMsPerOp(), result.fgTime,
                result.bgOps, result.getBgMsPerOp(), result.bgTime));
        synchronized (mResults) {
            mResults.add(result);
        }
        if (!mStarted) {
            log("Stop");
            stopRunning();
            return;
        }
        if (mFgTest != null && mBgTest != null) {
            log("Finished");
            stopRunning();
            return;
        }
        if (mFgTest == null && mBgTest == null) {
            mCurOpIndex+=2;
            if (mCurOpIndex >= TestService.mOpPairs.length) {
                log("Finished");
                stopRunning();
                return;
            }
        } else {
            mCurOpIndex++;
            if (mCurOpIndex >= TestService.mAvailOps.length) {
                log("Finished");
                stopRunning();
                return;
            }
        }
        startCurOp();
    }

    void disconnect() {
        final TestConnection conn = mCurConnection;
        if (conn != null) {
            if (DEBUG) {
                RuntimeException here = new RuntimeException("here");
                here.fillInStackTrace();
                Log.i(TAG, "Unbinding " + conn, here);
            }
            if (mConnectionBound) {
                unbindService(conn);
                mConnectionBound = false;
            }
            if (conn.mLinked) {
                Message msg = Message.obtain(null, TestService.CMD_TERMINATE);
                try {
                    conn.mService.send(msg);
                    return;
                } catch (RemoteException e) {
                    Log.w(TAG, "Test service aleady died when terminating");
                }
            }
            conn.cleanup();
        }
        connectionDied(conn);
    }

    void connectionDied(TestConnection conn) {
        if (mCurConnection == conn) {
            // Now that we know the test process has died, we can commence
            // the next test.  Just give a little delay to allow the activity
            // manager to know it has died as well (not a disaster if it hasn't
            // yet, though).
            if (mConnectionBound) {
                unbindService(conn);
            }
            mCurConnection = null;
            mHandler.sendMessageDelayed(Message.obtain(null, MSG_DO_NEXT_TEST), 100);
        }
    }

    void startCurOp() {
        if (DEBUG) Log.i(TAG, "startCurOp: mCurConnection=" + mCurConnection);
        if (mCurConnection != null) {
            disconnect();
            return;
        }
        if (mStarted) {
            mHandler.removeMessages(TestService.RES_TEST_FINISHED);
            mHandler.removeMessages(TestService.RES_TERMINATED);
            mHandler.removeMessages(MSG_DO_NEXT_TEST);
            mCurConnection = new TestConnection();
            Intent intent;
            if (mLocalCheckBox.isChecked()) {
                intent = new Intent(this, LocalTestService.class);
            } else {
                intent = new Intent(this, TestService.class);
            }
            if (DEBUG) {
                RuntimeException here = new RuntimeException("here");
                here.fillInStackTrace();
                Log.i(TAG, "Binding " + mCurConnection, here);
            }
            bindService(intent, mCurConnection, BIND_AUTO_CREATE|BIND_IMPORTANT);
            mConnectionBound = true;
        }
    }

    void startRunning() {
        if (!mStarted) {
            log("Start");
            mStarted = true;
            mStartButton.setEnabled(false);
            mStopButton.setEnabled(true);
            mLocalCheckBox.setEnabled(false);
            mTestTime.setEnabled(false);
            mFgSpinner.setEnabled(false);
            mBgSpinner.setEnabled(false);
            mLimitSpinner.setEnabled(false);
            updateWakeLock();
            startService(new Intent(this, SchedulerService.class));
            mCurOpIndex = 0;
            mMaxRunTime = Integer.parseInt(mTestTime.getText().toString());
            synchronized (mResults) {
                mResults.clear();
            }
            startCurOp();
        }
    }

    void stopRunning() {
        if (mStarted) {
            disconnect();
            mStarted = false;
            mStartButton.setEnabled(true);
            mStopButton.setEnabled(false);
            mLocalCheckBox.setEnabled(true);
            mTestTime.setEnabled(true);
            mFgSpinner.setEnabled(true);
            mBgSpinner.setEnabled(true);
            mLimitSpinner.setEnabled(true);
            updateWakeLock();
            stopService(new Intent(this, SchedulerService.class));
            synchronized (mResults) {
                for (int i=0; i<mResults.size(); i++) {
                    RunResult result = mResults.get(i);
                    float fgMsPerOp = result.getFgMsPerOp();
                    float bgMsPerOp = result.getBgMsPerOp();
                    String fgMsPerOpStr = fgMsPerOp != 0 ? Float.toString(fgMsPerOp) : "";
                    String bgMsPerOpStr = bgMsPerOp != 0 ? Float.toString(bgMsPerOp) : "";
                    Log.i("PerfRes", "\t" + result.name + "\t" + result.fgOps
                            + "\t" + result.getFgMsPerOp() + "\t" + result.fgTime
                            + "\t" + result.fgLongName + "\t" + result.bgOps
                            + "\t" + result.getBgMsPerOp() + "\t" + result.bgTime
                            + "\t" + result.bgLongName);
                }
            }
            synchronized (mResultNotifier) {
                mResultNotifier.notifyAll();
            }
        }
    }

    void updateWakeLock() {
        if (mStarted) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (!mPartialWakeLock.isHeld()) {
                mPartialWakeLock.acquire();
            }
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (mPartialWakeLock.isHeld()) {
                mPartialWakeLock.release();
            }
        }
    }

    void log(String s) {
        mLog.setText(mLog.getText() + "\n" + s);
        Log.i(TAG, s);
    }
}
