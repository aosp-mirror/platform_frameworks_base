/*
 * Copyright (C) 2009, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.vpn;

import android.os.ConditionVariable;

import java.io.IOException;

/**
 * A proxy class that spawns a process to accomplish a certain task.
 */
public abstract class ProcessProxy {
    /**
     * Defines the interface to call back when the process is finished or an
     * error occurs during execution.
     */
    public static interface Callback {
        /**
         * Called when the process is finished.
         * @param proxy the proxy that hosts the process
         */
        void done(ProcessProxy proxy);

        /**
         * Called when some error occurs.
         * @param proxy the proxy that hosts the process
         */
        void error(ProcessProxy proxy, Throwable error);
    }

    protected enum ProcessState {
        STOPPED, STARTING, RUNNING, STOPPING, ERROR
    }

    private ProcessState mState = ProcessState.STOPPED;
    private ConditionVariable mLock = new ConditionVariable();
    private Thread mThread;

    /**
     * Returns the name of the process.
     */
    public abstract String getName();

    /**
     * Starts the process with a callback.
     * @param callback the callback to get notified when the process is finished
     *      or an error occurs during execution
     * @throws IOException when the process is already running or failed to
     *      start
     */
    public synchronized void start(final Callback callback) throws IOException {
        if (!isStopped()) {
            throw new IOException("Process is already running: " + this);
        }
        mLock.close();
        setState(ProcessState.STARTING);
        Thread thread = new Thread(new Runnable() {
            public void run() {
                try {
                    performTask();
                    setState(ProcessState.STOPPED);
                    mLock.open();
                    if (callback != null) callback.done(ProcessProxy.this);
                } catch (Throwable e) {
                    setState(ProcessState.ERROR);
                    if (callback != null) callback.error(ProcessProxy.this, e);
                } finally {
                    mThread = null;
                }
            }
        });
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
        mThread = thread;
        if (!waitUntilRunning()) {
            throw new IOException("Failed to start the process: " + this);
        }
    }

    /**
     * Starts the process.
     * @throws IOException when the process is already running or failed to
     *      start
     */
    public synchronized void start() throws IOException {
        start(null);
        if (!waitUntilDone()) {
            throw new IOException("Failed to complete the process: " + this);
        }
    }

    /**
     * Returns the thread that hosts the process.
     */
    public Thread getHostThread() {
        return mThread;
    }

    /**
     * Blocks the current thread until the hosted process is finished.
     *
     * @return true if the process is finished normally; false if an error
     *      occurs
     */
    public boolean waitUntilDone() {
        while (!mLock.block(1000)) {
            if (isStopped() || isInError()) break;
        }
        return isStopped();
    }

    /**
     * Blocks the current thread until the hosted process is running.
     *
     * @return true if the process is running normally; false if the process
     *      is in another state
     */
    private boolean waitUntilRunning() {
        for (;;) {
            if (!isStarting()) break;
        }
        return isRunning();
    }

    /**
     * Stops and destroys the process.
     */
    public abstract void stop();

    /**
     * Checks whether the process is finished.
     * @return true if the process is stopped
     */
    public boolean isStopped() {
        return (mState == ProcessState.STOPPED);
    }

    /**
     * Checks whether the process is being stopped.
     * @return true if the process is being stopped
     */
    public boolean isStopping() {
        return (mState == ProcessState.STOPPING);
    }

    /**
     * Checks whether the process is being started.
     * @return true if the process is being started
     */
    public boolean isStarting() {
        return (mState == ProcessState.STARTING);
    }

    /**
     * Checks whether the process is running.
     * @return true if the process is running
     */
    public boolean isRunning() {
        return (mState == ProcessState.RUNNING);
    }

    /**
     * Checks whether some error has occurred and the process is stopped.
     * @return true if some error has occurred and the process is stopped
     */
    public boolean isInError() {
        return (mState == ProcessState.ERROR);
    }

    /**
     * Performs the actual task. Subclasses must make sure that the method
     * is blocked until the task is done or an error occurs.
     */
    protected abstract void performTask()
            throws IOException, InterruptedException;

    /**
     * Sets the process state.
     * @param state the new state to be in
     */
    protected void setState(ProcessState state) {
        mState = state;
    }

    /**
     * Makes the current thread sleep for the specified time.
     * @param msec time to sleep in miliseconds
     */
    protected void sleep(int msec) {
        try {
            Thread.currentThread().sleep(msec);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
