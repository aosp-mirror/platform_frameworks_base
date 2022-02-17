/**
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.broadcastradio.hal2;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.radio.Announcement;
import android.hardware.radio.IAnnouncementListener;
import android.hardware.radio.ICloseHandle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public class AnnouncementAggregator extends ICloseHandle.Stub {
    private static final String TAG = "BcRadio2Srv.AnnAggr";

    private final Object mLock;
    @NonNull private final IAnnouncementListener mListener;
    private final IBinder.DeathRecipient mDeathRecipient = new DeathRecipient();

    @GuardedBy("mLock")
    private final Collection<ModuleWatcher> mModuleWatchers = new ArrayList<>();

    @GuardedBy("mLock")
    private boolean mIsClosed = false;

    public AnnouncementAggregator(@NonNull IAnnouncementListener listener, @NonNull Object lock) {
        mListener = Objects.requireNonNull(listener);
        mLock = Objects.requireNonNull(lock);
        try {
            listener.asBinder().linkToDeath(mDeathRecipient, 0);
        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
        }
    }

    private class ModuleWatcher extends IAnnouncementListener.Stub {
        private @Nullable ICloseHandle mCloseHandle;
        public @NonNull List<Announcement> currentList = new ArrayList<>();

        public void onListUpdated(List<Announcement> active) {
            currentList = Objects.requireNonNull(active);
            AnnouncementAggregator.this.onListUpdated();
        }

        public void setCloseHandle(@NonNull ICloseHandle closeHandle) {
            mCloseHandle = Objects.requireNonNull(closeHandle);
        }

        public void close() throws RemoteException {
            if (mCloseHandle != null) mCloseHandle.close();
        }
    }

    private class DeathRecipient implements IBinder.DeathRecipient {
        public void binderDied() {
            try {
                close();
            } catch (RemoteException ex) {}
        }
    }

    private void onListUpdated() {
        synchronized (mLock) {
            if (mIsClosed) {
                Slog.e(TAG, "Announcement aggregator is closed, it shouldn't receive callbacks");
                return;
            }
            List<Announcement> combined = new ArrayList<>();
            for (ModuleWatcher watcher : mModuleWatchers) {
                combined.addAll(watcher.currentList);
            }
            try {
                mListener.onListUpdated(combined);
            } catch (RemoteException ex) {
                Slog.e(TAG, "mListener.onListUpdated() failed: ", ex);
            }
        }
    }

    public void watchModule(@NonNull RadioModule module, @NonNull int[] enabledTypes) {
        synchronized (mLock) {
            if (mIsClosed) throw new IllegalStateException();

            ModuleWatcher watcher = new ModuleWatcher();
            ICloseHandle closeHandle;
            try {
                closeHandle = module.addAnnouncementListener(enabledTypes, watcher);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to add announcement listener", ex);
                return;
            }
            watcher.setCloseHandle(closeHandle);
            mModuleWatchers.add(watcher);
        }
    }

    @Override
    public void close() throws RemoteException {
        synchronized (mLock) {
            if (mIsClosed) return;
            mIsClosed = true;

            mListener.asBinder().unlinkToDeath(mDeathRecipient, 0);

            for (ModuleWatcher watcher : mModuleWatchers) {
                watcher.close();
            }
            mModuleWatchers.clear();
        }
    }
}
