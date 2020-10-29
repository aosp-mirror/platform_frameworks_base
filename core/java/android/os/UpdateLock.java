/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.os;

import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.util.Log;

/**
 * Advisory wakelock-like mechanism by which processes that should not be interrupted for
 * OTA/update purposes can so advise the OS.  This is particularly relevant for headless
 * or kiosk-like operation.
 *
 * @hide
 */
public class UpdateLock {
    private static final boolean DEBUG = false;
    private static final String TAG = "UpdateLock";

    private static IUpdateLock sService;
    private static void checkService() {
        if (sService == null) {
            sService = IUpdateLock.Stub.asInterface(
                    ServiceManager.getService(Context.UPDATE_LOCK_SERVICE));
        }
    }

    IBinder mToken;
    int mCount = 0;
    boolean mRefCounted = true;
    boolean mHeld = false;
    final String mTag;

    /**
     * Broadcast Intent action sent when the global update lock state changes,
     * i.e. when the first locker acquires an update lock, or when the last
     * locker releases theirs.  The broadcast is sticky but is sent only to
     * registered receivers.
     */
    @UnsupportedAppUsage
    public static final String UPDATE_LOCK_CHANGED = "android.os.UpdateLock.UPDATE_LOCK_CHANGED";

    /**
     * Boolean Intent extra on the UPDATE_LOCK_CHANGED sticky broadcast, indicating
     * whether now is an appropriate time to interrupt device activity with an
     * update operation.  True means that updates are okay right now; false indicates
     * that perhaps later would be a better time.
     */
    @UnsupportedAppUsage
    public static final String NOW_IS_CONVENIENT = "nowisconvenient";

    /**
     * Long Intent extra on the UPDATE_LOCK_CHANGED sticky broadcast, marking the
     * wall-clock time [in UTC] at which the broadcast was sent.  Note that this is
     * in the System.currentTimeMillis() time base, which may be non-monotonic especially
     * around reboots.
     */
    @UnsupportedAppUsage
    public static final String TIMESTAMP = "timestamp";

    /**
     * Construct an UpdateLock instance.
     * @param tag An arbitrary string used to identify this lock instance in dump output.
     */
    public UpdateLock(String tag) {
        mTag = tag;
        mToken = new Binder();
    }

    /**
     * Change the refcount behavior of this update lock.
     */
    public void setReferenceCounted(boolean isRefCounted) {
        if (DEBUG) {
            Log.v(TAG, "setting refcounted=" + isRefCounted + " : " + this);
        }
        mRefCounted = isRefCounted;
    }

    /**
     * Is this lock currently held?
     */
    @UnsupportedAppUsage
    public boolean isHeld() {
        synchronized (mToken) {
            return mHeld;
        }
    }

    /**
     * Acquire an update lock.
     */
    @UnsupportedAppUsage
    public void acquire() {
        if (DEBUG) {
            Log.v(TAG, "acquire() : " + this, new RuntimeException("here"));
        }
        checkService();
        synchronized (mToken) {
            acquireLocked();
        }
    }

    private void acquireLocked() {
        if (!mRefCounted || mCount++ == 0) {
            if (sService != null) {
                try {
                    sService.acquireUpdateLock(mToken, mTag);
                } catch (RemoteException e) {
                    Log.e(TAG, "Unable to contact service to acquire");
                }
            }
            mHeld = true;
        }
    }

    /**
     * Release this update lock.
     */
    @UnsupportedAppUsage
    public void release() {
        if (DEBUG) Log.v(TAG, "release() : " + this, new RuntimeException("here"));
        checkService();
        synchronized (mToken) {
            releaseLocked();
        }
    }

    private void releaseLocked() {
        if (!mRefCounted || --mCount == 0) {
            if (sService != null) {
                try {
                    sService.releaseUpdateLock(mToken);
                } catch (RemoteException e) {
                    Log.e(TAG, "Unable to contact service to release");
                }
            }
            mHeld = false;
        }
        if (mCount < 0) {
            throw new RuntimeException("UpdateLock under-locked");
        }
    }

    @Override
    protected void finalize() throws Throwable {
        synchronized (mToken) {
            // if mHeld is true, sService must be non-null
            if (mHeld) {
                Log.wtf(TAG, "UpdateLock finalized while still held");
                try {
                    sService.releaseUpdateLock(mToken);
                } catch (RemoteException e) {
                    Log.e(TAG, "Unable to contact service to release");
                }
            }
        }
    }
}
