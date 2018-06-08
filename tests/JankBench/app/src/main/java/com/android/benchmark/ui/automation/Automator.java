/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the
 * License.
 *
 */

package com.android.benchmark.ui.automation;

import android.annotation.TargetApi;
import android.app.Instrumentation;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.view.FrameMetrics;
import android.view.MotionEvent;
import android.view.ViewTreeObserver;
import android.view.Window;

import com.android.benchmark.results.GlobalResultsStore;
import com.android.benchmark.results.UiBenchmarkResult;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@TargetApi(24)
public class Automator extends HandlerThread
        implements ViewTreeObserver.OnGlobalLayoutListener, CollectorThread.CollectorListener {
    public static final long FRAME_PERIOD_MILLIS = 16;

    private static final int PRE_READY_STATE_COUNT = 3;
    private static final String TAG = "Benchmark.Automator";
    private final AtomicInteger mReadyState;

    private AutomateCallback mCallback;
    private Window mWindow;
    private AutomatorHandler mHandler;
    private CollectorThread mCollectorThread;
    private int mRunId;
    private int mIteration;
    private String mTestName;

    public static class AutomateCallback {
        public void onAutomate() {}
        public void onPostInteraction(List<FrameMetrics> metrics) {}
        public void onPostAutomate() {}

        protected final void addInteraction(Interaction interaction) {
            if (mInteractions == null) {
                return;
            }

            mInteractions.add(interaction);
        }

        protected final void setInteractions(List<Interaction> interactions) {
            mInteractions = interactions;
        }

        private List<Interaction> mInteractions;
    }

    private static final class AutomatorHandler extends Handler {
        public static final int MSG_NEXT_INTERACTION = 0;
        public static final int MSG_ON_AUTOMATE = 1;
        public static final int MSG_ON_POST_INTERACTION = 2;
        private final String mTestName;
        private final int mRunId;
        private final int mIteration;

        private Instrumentation mInstrumentation;
        private volatile boolean mCancelled;
        private CollectorThread mCollectorThread;
        private AutomateCallback mCallback;
        private Window mWindow;

        LinkedList<Interaction> mInteractions;
        private UiBenchmarkResult mResults;

        AutomatorHandler(Looper looper, Window window, CollectorThread collectorThread,
                         AutomateCallback callback, String testName, int runId, int iteration) {
            super(looper);

            mInstrumentation = new Instrumentation();

            mCallback = callback;
            mWindow = window;
            mCollectorThread = collectorThread;
            mInteractions = new LinkedList<>();
            mTestName = testName;
            mRunId = runId;
            mIteration = iteration;
        }

        @Override
        public void handleMessage(Message msg) {
            if (mCancelled) {
                return;
            }

            switch (msg.what) {
                case MSG_NEXT_INTERACTION:
                    if (!nextInteraction()) {
                        stopCollector();
                        writeResults();
                        mCallback.onPostAutomate();
                    }
                    break;
                case MSG_ON_AUTOMATE:
                    mCollectorThread.attachToWindow(mWindow);
                    mCallback.setInteractions(mInteractions);
                    mCallback.onAutomate();
                    postNextInteraction();
                    break;
                case MSG_ON_POST_INTERACTION:
                    List<FrameMetrics> collectedStats = (List<FrameMetrics>)msg.obj;
                    persistResults(collectedStats);
                    mCallback.onPostInteraction(collectedStats);
                    postNextInteraction();
                    break;
            }
        }

        public void cancel() {
            mCancelled = true;
            stopCollector();
        }

        private void stopCollector() {
            mCollectorThread.quitCollector();
        }

        private boolean nextInteraction() {

            Interaction interaction = mInteractions.poll();
            if (interaction != null) {
                doInteraction(interaction);
                return true;
            }
            return false;
        }

        private void doInteraction(Interaction interaction) {
            if (mCancelled) {
                return;
            }

            mCollectorThread.markInteractionStart();

            if (interaction.getType() == Interaction.Type.KEY_EVENT) {
                for (int code : interaction.getKeyCodes()) {
                    if (!mCancelled) {
                        mInstrumentation.sendKeyDownUpSync(code);
                    } else {
                        break;
                    }
                }
            } else {
                for (MotionEvent event : interaction.getEvents()) {
                    if (!mCancelled) {
                        mInstrumentation.sendPointerSync(event);
                    } else {
                        break;
                    }
                }
            }
        }

        protected void postNextInteraction() {
            final Message msg = obtainMessage(AutomatorHandler.MSG_NEXT_INTERACTION);
            sendMessage(msg);
        }

        private void persistResults(List<FrameMetrics> stats) {
            if (stats.isEmpty()) {
                return;
            }

            if (mResults == null) {
                mResults = new UiBenchmarkResult(stats);
            } else {
                mResults.update(stats);
            }
        }

        private void writeResults() {
            GlobalResultsStore.getInstance(mWindow.getContext())
                    .storeRunResults(mTestName, mRunId, mIteration, mResults);
        }
    }

    private void initHandler() {
        mHandler = new AutomatorHandler(getLooper(), mWindow, mCollectorThread, mCallback,
                mTestName, mRunId, mIteration);
        mWindow = null;
        mCallback = null;
        mCollectorThread = null;
        mTestName = null;
        mRunId = 0;
        mIteration = 0;
    }

    @Override
    public final void onGlobalLayout() {
        if (!mCollectorThread.isAlive()) {
            mCollectorThread.start();
            mWindow.getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(this);
            mReadyState.decrementAndGet();
        }
    }

    @Override
    public void onCollectorThreadReady() {
        if (mReadyState.decrementAndGet() == 0) {
            initHandler();
            postOnAutomate();
        }
    }

    @Override
    protected void onLooperPrepared() {
        if (mReadyState.decrementAndGet() == 0) {
            initHandler();
            postOnAutomate();
        }
    }

    @Override
    public void onPostInteraction(List<FrameMetrics> stats) {
        Message m = mHandler.obtainMessage(AutomatorHandler.MSG_ON_POST_INTERACTION, stats);
        mHandler.sendMessage(m);
    }

    protected void postOnAutomate() {
        final Message msg = mHandler.obtainMessage(AutomatorHandler.MSG_ON_AUTOMATE);
        mHandler.sendMessage(msg);
    }

    public void cancel() {
        mHandler.removeMessages(AutomatorHandler.MSG_NEXT_INTERACTION);
        mHandler.cancel();
        mHandler = null;
    }

    public Automator(String testName, int runId, int iteration,
                     Window window, AutomateCallback callback) {
        super("AutomatorThread");

        mTestName = testName;
        mRunId = runId;
        mIteration = iteration;
        mCallback = callback;
        mWindow = window;
        mWindow.getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(this);
        mCollectorThread = new CollectorThread(this);
        mReadyState = new AtomicInteger(PRE_READY_STATE_COUNT);
    }
}
