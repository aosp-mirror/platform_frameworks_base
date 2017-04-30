package com.android.server.backup.restore;

import android.app.PackageInstallObserver;
import android.os.Bundle;

import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Synchronous implementation of PackageInstallObserver.
 *
 * Allows the caller to synchronously wait for package install event.
 */
public class RestoreInstallObserver extends PackageInstallObserver {

    @GuardedBy("mDone")
    private final AtomicBoolean mDone = new AtomicBoolean();

    private String mPackageName;
    private int mResult;

    public RestoreInstallObserver() {
    }

    /**
     * Resets the observer to prepare for another installation.
     */
    public void reset() {
        synchronized (mDone) {
            mDone.set(false);
        }
    }

    /**
     * Synchronously waits for completion.
     */
    public void waitForCompletion() {
        synchronized (mDone) {
            while (mDone.get() == false) {
                try {
                    mDone.wait();
                } catch (InterruptedException e) {
                }
            }
        }
    }

    /**
     * Returns result code.
     */
    public int getResult() {
        return mResult;
    }

    /**
     * Returns installed package name.
     */
    public String getPackageName() {
        return mPackageName;
    }

    @Override
    public void onPackageInstalled(String packageName, int returnCode,
            String msg, Bundle extras) {
        synchronized (mDone) {
            mResult = returnCode;
            mPackageName = packageName;
            mDone.set(true);
            mDone.notifyAll();
        }
    }
}
