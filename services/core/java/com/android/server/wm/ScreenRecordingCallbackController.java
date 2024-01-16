/*
 * Copyright 2024 The Android Open Source Project
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

import static android.content.Context.MEDIA_PROJECTION_SERVICE;

import static com.android.internal.protolog.ProtoLogGroup.WM_ERROR;

import android.media.projection.IMediaProjectionManager;
import android.media.projection.IMediaProjectionWatcherCallback;
import android.media.projection.MediaProjectionInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.view.ContentRecordingSession;
import android.window.IScreenRecordingCallback;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.protolog.common.ProtoLog;

import java.io.PrintWriter;
import java.util.Map;
import java.util.Set;

public class ScreenRecordingCallbackController {

    private final class Callback implements IBinder.DeathRecipient {

        IScreenRecordingCallback mCallback;
        int mUid;

        Callback(IScreenRecordingCallback callback, int uid) {
            this.mCallback = callback;
            this.mUid = uid;
        }

        public void binderDied() {
            unregister(mCallback);
        }
    }

    @GuardedBy("WindowManagerService.mGlobalLock")
    private final Map<IBinder, Callback> mCallbacks = new ArrayMap<>();

    @GuardedBy("WindowManagerService.mGlobalLock")
    private final Map<Integer /*UID*/, Boolean> mLastInvokedStateByUid = new ArrayMap<>();

    private final WindowManagerService mWms;

    @GuardedBy("WindowManagerService.mGlobalLock")
    private WindowContainer<WindowContainer> mRecordedWC;

    private boolean mWatcherCallbackRegistered = false;

    private final class MediaProjectionWatcherCallback extends
            IMediaProjectionWatcherCallback.Stub {
        @Override
        public void onStart(MediaProjectionInfo mediaProjectionInfo) {
            onScreenRecordingStart(mediaProjectionInfo);
        }

        @Override
        public void onStop(MediaProjectionInfo mediaProjectionInfo) {
            onScreenRecordingStop();
        }

        @Override
        public void onRecordingSessionSet(MediaProjectionInfo mediaProjectionInfo,
                ContentRecordingSession contentRecordingSession) {
        }
    }

    ScreenRecordingCallbackController(WindowManagerService wms) {
        mWms = wms;
    }

    @GuardedBy("WindowManagerService.mGlobalLock")
    private void setRecordedWindowContainer(MediaProjectionInfo mediaProjectionInfo) {
        if (mediaProjectionInfo.getLaunchCookie() == null) {
            mRecordedWC = (WindowContainer) mWms.mRoot.getDefaultDisplay();
        } else {
            mRecordedWC = mWms.mRoot.getActivity(activity -> activity.mLaunchCookie
                    == mediaProjectionInfo.getLaunchCookie()).getTask();
        }
    }

    @GuardedBy("WindowManagerService.mGlobalLock")
    private void ensureMediaProjectionWatcherCallbackRegistered() {
        if (mWatcherCallbackRegistered) {
            return;
        }

        IBinder binder = ServiceManager.getService(MEDIA_PROJECTION_SERVICE);
        IMediaProjectionManager mediaProjectionManager =
                IMediaProjectionManager.Stub.asInterface(binder);

        long identityToken = Binder.clearCallingIdentity();
        MediaProjectionInfo mediaProjectionInfo = null;
        try {
            mediaProjectionInfo = mediaProjectionManager.addCallback(
                    new MediaProjectionWatcherCallback());
            mWatcherCallbackRegistered = true;
        } catch (RemoteException e) {
            ProtoLog.e(WM_ERROR, "Failed to register MediaProjectionWatcherCallback");
        } finally {
            Binder.restoreCallingIdentity(identityToken);
        }

        if (mediaProjectionInfo != null) {
            setRecordedWindowContainer(mediaProjectionInfo);
        }
    }

    boolean register(IScreenRecordingCallback callback) {
        synchronized (mWms.mGlobalLock) {
            ensureMediaProjectionWatcherCallbackRegistered();

            IBinder binder = callback.asBinder();
            int uid = Binder.getCallingUid();

            if (mCallbacks.containsKey(binder)) {
                return mLastInvokedStateByUid.get(uid);
            }

            Callback callbackInfo = new Callback(callback, uid);
            try {
                binder.linkToDeath(callbackInfo, 0);
            } catch (RemoteException e) {
                return false;
            }

            boolean uidInRecording = uidHasRecordedActivity(callbackInfo.mUid);
            mLastInvokedStateByUid.put(callbackInfo.mUid, uidInRecording);
            mCallbacks.put(binder, callbackInfo);
            return uidInRecording;
        }
    }

    void unregister(IScreenRecordingCallback callback) {
        synchronized (mWms.mGlobalLock) {
            IBinder binder = callback.asBinder();
            Callback callbackInfo = mCallbacks.remove(binder);
            binder.unlinkToDeath(callbackInfo, 0);

            boolean uidHasCallback = false;
            for (Callback cb : mCallbacks.values()) {
                if (cb.mUid == callbackInfo.mUid) {
                    uidHasCallback = true;
                    break;
                }
            }
            if (!uidHasCallback) {
                mLastInvokedStateByUid.remove(callbackInfo.mUid);
            }
        }
    }

    private void onScreenRecordingStart(MediaProjectionInfo mediaProjectionInfo) {
        synchronized (mWms.mGlobalLock) {
            setRecordedWindowContainer(mediaProjectionInfo);
            dispatchCallbacks(getRecordedUids(), true /* visibleInScreenRecording*/);
        }
    }

    private void onScreenRecordingStop() {
        synchronized (mWms.mGlobalLock) {
            dispatchCallbacks(getRecordedUids(), false /*visibleInScreenRecording*/);
            mRecordedWC = null;
        }
    }

    @GuardedBy("WindowManagerService.mGlobalLock")
    void onProcessActivityVisibilityChanged(int uid, boolean processVisible) {
        // If recording isn't active or there's no registered callback for the uid, there's nothing
        // to do on this visibility change.
        if (mRecordedWC == null || !mLastInvokedStateByUid.containsKey(uid)) {
            return;
        }

        // If the callbacks are already in the correct state, avoid making duplicate callbacks for
        // the same state. This can happen when:
        // * a process becomes visible but its UID already has a recorded activity from another
        //   process.
        // * a process becomes invisible but its UID already doesn't have any recorded activities.
        if (processVisible == mLastInvokedStateByUid.get(uid)) {
            return;
        }

        // If the process visibility change doesn't change the visibility of the UID, avoid making
        // duplicate callbacks for the same state. This can happen when:
        // * a process becomes visible but the newly visible activity isn't in the recorded window
        //   container.
        // * a process becomes invisible but there are still activities being recorded for the UID.
        boolean uidInRecording = uidHasRecordedActivity(uid);
        if ((processVisible && !uidInRecording) || (!processVisible && uidInRecording)) {
            return;
        }

        dispatchCallbacks(Set.of(uid), processVisible);
    }

    @GuardedBy("WindowManagerService.mGlobalLock")
    private boolean uidHasRecordedActivity(int uid) {
        if (mRecordedWC == null) {
            return false;
        }
        boolean[] hasRecordedActivity = {false};
        mRecordedWC.forAllActivities(activityRecord -> {
            if (activityRecord.getUid() == uid && activityRecord.isVisibleRequested()) {
                hasRecordedActivity[0] = true;
                return true;
            }
            return false;
        }, true /*traverseTopToBottom*/);
        return hasRecordedActivity[0];
    }

    @GuardedBy("WindowManagerService.mGlobalLock")
    private Set<Integer> getRecordedUids() {
        Set<Integer> result = new ArraySet<>();
        if (mRecordedWC == null) {
            return result;
        }
        mRecordedWC.forAllActivities(activityRecord -> {
            if (activityRecord.isVisibleRequested() && mLastInvokedStateByUid.containsKey(
                    activityRecord.getUid())) {
                result.add(activityRecord.getUid());
            }
        }, true /*traverseTopToBottom*/);
        return result;
    }

    @GuardedBy("WindowManagerService.mGlobalLock")
    private void dispatchCallbacks(Set<Integer> uids, boolean visibleInScreenRecording) {
        if (uids.isEmpty()) {
            return;
        }

        for (Integer uid : uids) {
            mLastInvokedStateByUid.put(uid, visibleInScreenRecording);
        }

        for (Callback callback : mCallbacks.values()) {
            if (!uids.contains(callback.mUid)) {
                continue;
            }
            try {
                callback.mCallback.onScreenRecordingStateChanged(visibleInScreenRecording);
            } catch (RemoteException e) {
                // Client has died. Cleanup is handled via DeathRecipient.
            }
        }
    }

    void dump(PrintWriter pw) {
        pw.format("ScreenRecordingCallbackController:\n");
        pw.format("  Registered callbacks:\n");
        for (Map.Entry<IBinder, Callback> entry : mCallbacks.entrySet()) {
            pw.format("    callback=%s uid=%s\n", entry.getKey(), entry.getValue().mUid);
        }
        pw.format("  Last invoked states:\n");
        for (Map.Entry<Integer, Boolean> entry : mLastInvokedStateByUid.entrySet()) {
            pw.format("    uid=%s isVisibleInScreenRecording=%s\n", entry.getKey(),
                    entry.getValue());
        }
    }
}
