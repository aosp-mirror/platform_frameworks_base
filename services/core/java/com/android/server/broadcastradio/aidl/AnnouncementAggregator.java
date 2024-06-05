/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.broadcastradio.aidl;

import android.annotation.Nullable;
import android.hardware.radio.Announcement;
import android.hardware.radio.IAnnouncementListener;
import android.hardware.radio.ICloseHandle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.server.utils.Slogf;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Announcement aggregator extending {@link ICloseHandle} to support broadcast radio announcement
 */
public final class AnnouncementAggregator extends ICloseHandle.Stub {
    private static final String TAG = "BcRadioAidlSrv.AnnAggr";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Object mLock;
    private final IAnnouncementListener mListener;
    private final IBinder.DeathRecipient mDeathRecipient = new DeathRecipient();

    @GuardedBy("mLock")
    private final List<ModuleWatcher> mModuleWatchers = new ArrayList<>();

    @GuardedBy("mLock")
    private boolean mIsClosed;

    /**
     * Constructs Announcement aggregator with AnnouncementListener of BroadcastRadio AIDL HAL.
     */
    public AnnouncementAggregator(IAnnouncementListener listener, Object lock) {
        mListener = Objects.requireNonNull(listener, "listener cannot be null");
        mLock = Objects.requireNonNull(lock, "lock cannot be null");
        try {
            listener.asBinder().linkToDeath(mDeathRecipient, /* flags= */ 0);
        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
        }
    }

    private final class ModuleWatcher extends IAnnouncementListener.Stub {

        @Nullable
        private ICloseHandle mCloseHandle;

        public List<Announcement> mCurrentList = new ArrayList<>();

        public void onListUpdated(List<Announcement> active) {
            if (DEBUG) {
                Slogf.d(TAG, "onListUpdate for %s", active);
            }
            mCurrentList = Objects.requireNonNull(active, "active cannot be null");
            AnnouncementAggregator.this.onListUpdated();
        }

        public void setCloseHandle(ICloseHandle closeHandle) {
            if (DEBUG) {
                Slogf.d(TAG, "Set close handle %s", closeHandle);
            }
            mCloseHandle = Objects.requireNonNull(closeHandle, "closeHandle cannot be null");
        }

        public void close() throws RemoteException {
            if (DEBUG) {
                Slogf.d(TAG, "Close module watcher.");
            }
            if (mCloseHandle != null) mCloseHandle.close();
        }

        public void dumpInfo(android.util.IndentingPrintWriter pw) {
            pw.printf("ModuleWatcher:\n");

            pw.increaseIndent();
            pw.printf("Close handle: %s\n", mCloseHandle);
            pw.printf("Current announcement list: %s\n", mCurrentList);
            pw.decreaseIndent();
        }
    }

    private class DeathRecipient implements IBinder.DeathRecipient {
        public void binderDied() {
            try {
                close();
            } catch (RemoteException ex) {
                Slogf.e(TAG, ex, "Cannot close Announcement aggregator for DeathRecipient");
            }
        }
    }

    private void onListUpdated() {
        if (DEBUG) {
            Slogf.d(TAG, "onListUpdated()");
        }
        synchronized (mLock) {
            if (mIsClosed) {
                Slogf.e(TAG, "Announcement aggregator is closed, it shouldn't receive callbacks");
                return;
            }
            List<Announcement> combined = new ArrayList<>(mModuleWatchers.size());
            for (int i = 0; i < mModuleWatchers.size(); i++) {
                combined.addAll(mModuleWatchers.get(i).mCurrentList);
            }
            try {
                mListener.onListUpdated(combined);
            } catch (RemoteException ex) {
                Slogf.e(TAG, ex, "mListener.onListUpdated() failed");
            }
        }
    }

    /**
     * Watches the given RadioModule by adding Announcement Listener to it
     */
    public void watchModule(RadioModule radioModule, int[] enabledTypes) {
        if (DEBUG) {
            Slogf.d(TAG, "Watch module for %s with enabled types %s",
                    radioModule, Arrays.toString(enabledTypes));
        }
        synchronized (mLock) {
            if (mIsClosed) {
                throw new IllegalStateException("Failed to watch module"
                        + "since announcement aggregator has already been closed");
            }

            ModuleWatcher watcher = new ModuleWatcher();
            ICloseHandle closeHandle;
            try {
                closeHandle = radioModule.addAnnouncementListener(watcher, enabledTypes);
            } catch (RemoteException ex) {
                Slogf.e(TAG, ex, "Failed to add announcement listener");
                return;
            }
            watcher.setCloseHandle(closeHandle);
            mModuleWatchers.add(watcher);
        }
    }

    @Override
    public void close() throws RemoteException {
        if (DEBUG) {
            Slogf.d(TAG, "Close watchModule");
        }
        synchronized (mLock) {
            if (mIsClosed) {
                Slogf.w(TAG, "Announcement aggregator has already been closed.");
                return;
            }

            mListener.asBinder().unlinkToDeath(mDeathRecipient, /* flags= */ 0);

            for (int i = 0; i < mModuleWatchers.size(); i++) {
                ModuleWatcher moduleWatcher = mModuleWatchers.get(i);
                try {
                    moduleWatcher.close();
                } catch (Exception e) {
                    Slogf.e(TAG, "Failed to close module watcher %s: %s",
                            moduleWatcher, e);
                }
            }
            mModuleWatchers.clear();

            mIsClosed = true;
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter printWriter, String[] args) {
        android.util.IndentingPrintWriter announcementPrintWriter =
                new android.util.IndentingPrintWriter(printWriter);
        announcementPrintWriter.printf("AnnouncementAggregator\n");

        announcementPrintWriter.increaseIndent();
        synchronized (mLock) {
            announcementPrintWriter.printf("Is session closed? %s\n", mIsClosed ? "Yes" : "No");
            announcementPrintWriter.printf("Module Watchers [%d]:\n", mModuleWatchers.size());

            announcementPrintWriter.increaseIndent();
            for (int i = 0; i < mModuleWatchers.size(); i++) {
                mModuleWatchers.get(i).dumpInfo(announcementPrintWriter);
            }
            announcementPrintWriter.decreaseIndent();

        }
        announcementPrintWriter.decreaseIndent();
    }

}
