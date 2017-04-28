package com.android.server.backup.restore;

import android.content.pm.IPackageDeleteObserver;
import android.os.RemoteException;

import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Synchronous implementation of IPackageDeleteObserver.Stub.
 *
 * Allows the caller to synchronously wait for package deleted event.
 */
public class RestoreDeleteObserver extends IPackageDeleteObserver.Stub {

    @GuardedBy("mDone")
    private final AtomicBoolean mDone = new AtomicBoolean();

    public RestoreDeleteObserver() {
    }

    /**
     * Resets the observer to prepare for another removal.
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

    @Override
    public void packageDeleted(String packageName, int returnCode) throws RemoteException {
        synchronized (mDone) {
            mDone.set(true);
            mDone.notifyAll();
        }
    }
}
