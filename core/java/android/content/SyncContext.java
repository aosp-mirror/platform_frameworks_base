/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.content;

import android.annotation.UnsupportedAppUsage;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.IBinder;

public class SyncContext {
    private ISyncContext mSyncContext;
    private long mLastHeartbeatSendTime;

    private static final long HEARTBEAT_SEND_INTERVAL_IN_MS = 1000;

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public SyncContext(ISyncContext syncContextInterface) {
        mSyncContext = syncContextInterface;
        mLastHeartbeatSendTime = 0;
    }

    /**
     * Call to update the status text for this sync. This internally invokes
     * {@link #updateHeartbeat}, so it also takes the place of a call to that.
     *
     * @param message the current status message for this sync
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void setStatusText(String message) {
        updateHeartbeat();
    }

    /**
     * Call to indicate that the SyncAdapter is making progress. E.g., if this SyncAdapter
     * downloads or sends records to/from the server, this may be called after each record
     * is downloaded or uploaded.
     */
    private void updateHeartbeat() {
        final long now = SystemClock.elapsedRealtime();
        if (now < mLastHeartbeatSendTime + HEARTBEAT_SEND_INTERVAL_IN_MS) return;
        try {
            mLastHeartbeatSendTime = now;
            if (mSyncContext != null) {
                mSyncContext.sendHeartbeat();
            }
        } catch (RemoteException e) {
            // this should never happen
        }
    }

    public void onFinished(SyncResult result) {
        try {
            if (mSyncContext != null) {
                mSyncContext.onFinished(result);
            }
        } catch (RemoteException e) {
            // this should never happen
        }
    }

    public IBinder getSyncContextBinder() {
        return (mSyncContext == null) ? null : mSyncContext.asBinder();
    }
}
