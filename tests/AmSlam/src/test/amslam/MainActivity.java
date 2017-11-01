/*
 * Copyright (C) 2016 The Android Open Source Project
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

package test.amslam;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MainActivity extends Activity implements PongReceiver.PingPongResponseListener {
    private static final String TAG = "AmSlam";

    private static final Class<?>[] sTargets;
    private static final BlockingQueue<Intent> sWorkQueue = new ArrayBlockingQueue<>(100);
    private static Context sAppContext;
    private static final int[] CONCURRENT_TESTS = {1, 2, 4, 6, 8, 10};

    private boolean mAutoRun;

    private TextView mOutput;

    private int mTestPhase;
    private long mBatchStartTime;
    private int mPendingResponses;

    private int mBatchRemaining;
    private int mCurrentTargetIndex;

    private int mTotalReceived;
    private long mTotalTime;
    private long mTotalPingTime;
    private long mTotalPongTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sAppContext = getApplicationContext();
        setContentView(R.layout.activity_main);
        mOutput = findViewById(R.id.output);
        PongReceiver.addListener(this);

        findViewById(R.id.run).setOnClickListener(view -> {
            view.setEnabled(false);
            mOutput.setText("");
            startBatch();
        });

        mAutoRun = getIntent().getBooleanExtra("autorun", false);
        if (mAutoRun) {
            findViewById(R.id.run).performClick();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        PongReceiver.removeListener(this);
    }

    private void startBatch() {
        if (mBatchRemaining > 0 || mPendingResponses > 0) {
            // Still sending, skip
            return;
        }
        mBatchStartTime = SystemClock.uptimeMillis();
        mBatchRemaining = 10 * CONCURRENT_TESTS[mTestPhase];
        mTotalReceived = 0;
        mTotalTime = mTotalPingTime = mTotalPongTime = 0;
        log("Starting test with " + CONCURRENT_TESTS[mTestPhase] + " concurrent requests...\n");
        continueSend();
    }

    private Class<?> nextTarget() {
        Class<?> ret = sTargets[mCurrentTargetIndex];
        mCurrentTargetIndex = (mCurrentTargetIndex + 1) % sTargets.length;
        return ret;
    }

    private void continueSend() {
        while (mPendingResponses < CONCURRENT_TESTS[mTestPhase] && mBatchRemaining > 0) {
            mPendingResponses++;
            mBatchRemaining--;
            Class<?> target = nextTarget();
            Intent intent = new Intent(getApplicationContext(), target);
            try {
                sWorkQueue.put(intent);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void onPingPongResponse(long send, long bounce, long recv, String remote) {
        if (send < mBatchStartTime || mPendingResponses == 0) {
            Log.e(TAG, "received outdated response??");
            Log.e(TAG, "send " + send + ", bounce " + bounce + ", recv " + recv
                    + ", batchStart " + mBatchStartTime + ", remote: " + remote);
        }
        mPendingResponses--;
        mTotalReceived++;
        continueSend();
        mTotalTime += (recv - send);
        mTotalPingTime += (bounce - send);
        mTotalPongTime += (recv - bounce);
        if (mPendingResponses == 0) {
            long now = SystemClock.uptimeMillis();
            log(String.format("Sent %d ping/pongs, %d concurrent.\n"
                    + "Total duration %dms (%dms eff. avg)\n"
                    + "Average message took %dms (%dms + %dms)\n",
                    mTotalReceived, CONCURRENT_TESTS[mTestPhase],
                    (now - mBatchStartTime), (now - mBatchStartTime) / mTotalReceived,
                    mTotalTime / mTotalReceived, mTotalPingTime / mTotalReceived,
                    mTotalPongTime / mTotalReceived));

            mTestPhase++;
            if (mTestPhase < CONCURRENT_TESTS.length) {
                startBatch();
            } else {
                mTestPhase = 0;
                log("Finished\n");
                findViewById(R.id.run).setEnabled(true);
                if (mAutoRun) {
                    finish();
                }
            }
        }
    }

    private void log(String text) {
        mOutput.append(text);
        Log.d(TAG, text);
    }

    static {
        sTargets = new Class<?>[100];
        for (int i = 0; i < sTargets.length; i++) {
            try {
                sTargets[i] = Class.forName(
                        String.format("test.amslam.subreceivers.PingReceiver%03d", i));
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }

        Runnable work = () -> {
            while (true) {
                try {
                    Intent intent = sWorkQueue.take();
                    intent.putExtra("start_time", SystemClock.uptimeMillis());
                    sAppContext.startService(intent);
                } catch (InterruptedException e) {}
            }
        };

        // How many worker threads should we spawn? ¯\_(ツ)_/¯
        for (int i = 0; i < 10; i++) {
            new Thread(work, "Slammer" + i).start();
        }
    }
}
