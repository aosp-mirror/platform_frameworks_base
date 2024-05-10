/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.wm;

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.IRotationWatcher;

import java.io.PrintWriter;
import java.util.ArrayList;

/** Manages the registration and event dispatch of {@link IRotationWatcher}. */
class RotationWatcherController {

    private final WindowManagerService mService;

    /** The watchers that monitor the applied rotation of display. */
    private final ArrayList<DisplayRotationWatcher> mDisplayRotationWatchers = new ArrayList<>();

    /** The listeners that monitor the proposed rotation of the corresponding window container. */
    private final ArrayList<ProposedRotationListener> mProposedRotationListeners =
            new ArrayList<>();

    /** A quick look up which can be checked without WM lock. */
    private volatile boolean mHasProposedRotationListeners;

    RotationWatcherController(WindowManagerService wms) {
        mService = wms;
    }

    void registerDisplayRotationWatcher(IRotationWatcher watcher, int displayId) {
        final IBinder watcherBinder = watcher.asBinder();
        for (int i = mDisplayRotationWatchers.size() - 1; i >= 0; i--) {
            if (watcherBinder == mDisplayRotationWatchers.get(i).mWatcher.asBinder()) {
                throw new IllegalArgumentException("Registering existed rotation watcher");
            }
        }
        register(watcherBinder, new DisplayRotationWatcher(mService, watcher, displayId),
                mDisplayRotationWatchers);
    }

    void registerProposedRotationListener(IRotationWatcher listener, IBinder contextToken) {
        final IBinder listenerBinder = listener.asBinder();
        for (int i = mProposedRotationListeners.size() - 1; i >= 0; i--) {
            final ProposedRotationListener watcher = mProposedRotationListeners.get(i);
            if (contextToken == watcher.mToken || listenerBinder == watcher.mWatcher.asBinder()) {
                Slog.w(TAG_WM, "Register rotation listener to a registered token, uid="
                        + Binder.getCallingUid());
                return;
            }
        }
        register(listenerBinder, new ProposedRotationListener(mService, listener, contextToken),
                mProposedRotationListeners);
        mHasProposedRotationListeners = !mProposedRotationListeners.isEmpty();
    }

    private static <T extends RotationWatcher> void register(IBinder watcherBinder, T watcher,
            ArrayList<T> watcherList) {
        try {
            watcherBinder.linkToDeath(watcher, 0);
            watcherList.add(watcher);
        } catch (RemoteException e) {
            // Client died, no cleanup needed.
        }
    }

    private static <T extends RotationWatcher> boolean unregister(IRotationWatcher watcher,
            ArrayList<T> watcherList) {
        final IBinder watcherBinder = watcher.asBinder();
        for (int i = watcherList.size() - 1; i >= 0; i--) {
            final RotationWatcher rotationWatcher = watcherList.get(i);
            if (watcherBinder != rotationWatcher.mWatcher.asBinder()) {
                continue;
            }
            watcherList.remove(i);
            rotationWatcher.unlinkToDeath();
            return true;
        }
        return false;
    }

    void removeRotationWatcher(IRotationWatcher watcher) {
        final boolean removed = unregister(watcher, mProposedRotationListeners);
        if (removed) {
            mHasProposedRotationListeners = !mProposedRotationListeners.isEmpty();
        } else {
            // The un-registration shares the same interface, so look up the watchers as well.
            unregister(watcher, mDisplayRotationWatchers);
        }
    }

    /** Called when the new rotation of display is applied. */
    void dispatchDisplayRotationChange(int displayId, int rotation) {
        for (int i = mDisplayRotationWatchers.size() - 1; i >= 0; i--) {
            final DisplayRotationWatcher rotationWatcher = mDisplayRotationWatchers.get(i);
            if (rotationWatcher.mDisplayId == displayId) {
                rotationWatcher.notifyRotation(rotation);
            }
        }
    }

    /** Called when the window orientation listener has a new proposed rotation. */
    void dispatchProposedRotation(DisplayContent dc, int rotation) {
        for (int i = mProposedRotationListeners.size() - 1; i >= 0; i--) {
            final ProposedRotationListener listener = mProposedRotationListeners.get(i);
            final WindowContainer<?> wc = getAssociatedWindowContainer(listener.mToken);
            if (wc != null) {
                if (wc.mDisplayContent == dc) {
                    listener.notifyRotation(rotation);
                }
            } else {
                // Unregister if the associated window container is gone.
                mProposedRotationListeners.remove(i);
                mHasProposedRotationListeners = !mProposedRotationListeners.isEmpty();
                listener.unlinkToDeath();
            }
        }
    }

    boolean hasProposedRotationListeners() {
        return mHasProposedRotationListeners;
    }

    WindowContainer<?> getAssociatedWindowContainer(IBinder contextToken) {
        final WindowContainer<?> wc = ActivityRecord.forTokenLocked(contextToken);
        if (wc != null) {
            return wc;
        }
        return mService.mWindowContextListenerController.getContainer(contextToken);
    }

    void dump(PrintWriter pw) {
        if (!mDisplayRotationWatchers.isEmpty()) {
            pw.print("  mDisplayRotationWatchers: [");
            for (int i = mDisplayRotationWatchers.size() - 1; i >= 0; i--) {
                pw.print(' ');
                final DisplayRotationWatcher watcher = mDisplayRotationWatchers.get(i);
                pw.print(watcher.mOwnerUid);
                pw.print("->");
                pw.print(watcher.mDisplayId);
            }
            pw.println(']');
        }
        if (!mProposedRotationListeners.isEmpty()) {
            pw.print("  mProposedRotationListeners: [");
            for (int i = mProposedRotationListeners.size() - 1; i >= 0; i--) {
                pw.print(' ');
                final ProposedRotationListener listener = mProposedRotationListeners.get(i);
                pw.print(listener.mOwnerUid);
                pw.print("->");
                pw.print(getAssociatedWindowContainer(listener.mToken));
            }
            pw.println(']');
        }
    }

    /** Reports new applied rotation of a display. */
    private static class DisplayRotationWatcher extends RotationWatcher {
        final int mDisplayId;

        DisplayRotationWatcher(WindowManagerService wms, IRotationWatcher watcher, int displayId) {
            super(wms, watcher);
            mDisplayId = displayId;
        }
    }

    /** Reports proposed rotation of a window token. */
    private static class ProposedRotationListener extends RotationWatcher {
        final IBinder mToken;

        ProposedRotationListener(WindowManagerService wms, IRotationWatcher watcher,
                IBinder token) {
            super(wms, watcher);
            mToken = token;
        }
    }

    private static class RotationWatcher implements IBinder.DeathRecipient {
        final WindowManagerService mWms;
        final IRotationWatcher mWatcher;
        final int mOwnerUid = Binder.getCallingUid();

        RotationWatcher(WindowManagerService wms, IRotationWatcher watcher) {
            mWms = wms;
            mWatcher = watcher;
        }

        void notifyRotation(int rotation) {
            try {
                mWatcher.onRotationChanged(rotation);
            } catch (RemoteException ignored) {
                // Let binderDied() remove this.
            }
        }

        void unlinkToDeath() {
            mWatcher.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            mWms.removeRotationWatcher(mWatcher);
        }
    }
}
