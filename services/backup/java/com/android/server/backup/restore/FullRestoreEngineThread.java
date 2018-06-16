package com.android.server.backup.restore;

import android.os.ParcelFileDescriptor;

import libcore.io.IoUtils;

import java.io.FileInputStream;
import java.io.InputStream;

class FullRestoreEngineThread implements Runnable {

    FullRestoreEngine mEngine;
    InputStream mEngineStream;
    private final boolean mMustKillAgent;

    FullRestoreEngineThread(FullRestoreEngine engine, ParcelFileDescriptor engineSocket) {
        mEngine = engine;
        engine.setRunning(true);
        // We *do* want this FileInputStream to own the underlying fd, so that
        // when we are finished with it, it closes this end of the pipe in a way
        // that signals its other end.
        mEngineStream = new FileInputStream(engineSocket.getFileDescriptor(), true);
        // Tell it to be sure to leave the agent instance up after finishing
        mMustKillAgent = false;
    }

    //for adb restore
    FullRestoreEngineThread(FullRestoreEngine engine, InputStream inputStream) {
        mEngine = engine;
        engine.setRunning(true);
        mEngineStream = inputStream;
        // philippov: in adb agent is killed after restore.
        mMustKillAgent = true;
    }

    public boolean isRunning() {
        return mEngine.isRunning();
    }

    public int waitForResult() {
        return mEngine.waitForResult();
    }

    @Override
    public void run() {
        try {
            while (mEngine.isRunning()) {
                mEngine.restoreOneFile(mEngineStream, mMustKillAgent, mEngine.mBuffer,
                        mEngine.mOnlyPackage, mEngine.mAllowApks, mEngine.mEphemeralOpToken,
                        mEngine.mMonitor);
            }
        } finally {
            // Because mEngineStream adopted its underlying FD, this also
            // closes this end of the pipe.
            IoUtils.closeQuietly(mEngineStream);
        }
    }

    public void handleTimeout() {
        IoUtils.closeQuietly(mEngineStream);
        mEngine.handleTimeout();
    }
}
