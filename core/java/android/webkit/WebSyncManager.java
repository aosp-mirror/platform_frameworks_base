/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.webkit;

import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;

/**
 * @deprecated The WebSyncManager no longer does anything.
 */
@Deprecated
abstract class WebSyncManager implements Runnable {
    protected static final java.lang.String LOGTAG = "websync";
    protected android.webkit.WebViewDatabase mDataBase;
    @UnsupportedAppUsage
    protected android.os.Handler mHandler;

    protected WebSyncManager(Context context, String name) {
    }

    protected Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException("doesn't implement Cloneable");
    }

    public void run() {
    }

    /**
     * sync() forces sync manager to sync now
     */
    public void sync() {
    }

    /**
     * resetSync() resets sync manager's timer
     */
    public void resetSync() {
    }

    /**
     * startSync() requests sync manager to start sync
     */
    public void startSync() {
    }

    /**
     * stopSync() requests sync manager to stop sync. remove any SYNC_MESSAGE in
     * the queue to break the sync loop
     */
    public void stopSync() {
    }

    protected void onSyncInit() {
    }

    @UnsupportedAppUsage
    abstract void syncFromRamToFlash();
}
