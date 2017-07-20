/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.media.projection;

import com.android.server.Watchdog;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.media.MediaRouter;
import android.media.projection.IMediaProjectionManager;
import android.media.projection.IMediaProjection;
import android.media.projection.IMediaProjectionCallback;
import android.media.projection.IMediaProjectionWatcherCallback;
import android.media.projection.MediaProjectionInfo;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.util.DumpUtils;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Map;

/**
 * Manages MediaProjection sessions.
 *
 * The {@link MediaProjectionManagerService} manages the creation and lifetime of MediaProjections,
 * as well as the capabilities they grant. Any service using MediaProjection tokens as permission
 * grants <b>must</b> validate the token before use by calling {@link
 * IMediaProjectionService#isValidMediaProjection}.
 */
public final class MediaProjectionManagerService extends SystemService
        implements Watchdog.Monitor {
    private static final String TAG = "MediaProjectionManagerService";

    private final Object mLock = new Object(); // Protects the list of media projections
    private final Map<IBinder, IBinder.DeathRecipient> mDeathEaters;
    private final CallbackDelegate mCallbackDelegate;

    private final Context mContext;
    private final AppOpsManager mAppOps;

    private final MediaRouter mMediaRouter;
    private final MediaRouterCallback mMediaRouterCallback;
    private MediaRouter.RouteInfo mMediaRouteInfo;

    private IBinder mProjectionToken;
    private MediaProjection mProjectionGrant;

    public MediaProjectionManagerService(Context context) {
        super(context);
        mContext = context;
        mDeathEaters = new ArrayMap<IBinder, IBinder.DeathRecipient>();
        mCallbackDelegate = new CallbackDelegate();
        mAppOps = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        mMediaRouter = (MediaRouter) mContext.getSystemService(Context.MEDIA_ROUTER_SERVICE);
        mMediaRouterCallback = new MediaRouterCallback();
        Watchdog.getInstance().addMonitor(this);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.MEDIA_PROJECTION_SERVICE, new BinderService(),
                false /*allowIsolated*/);
        mMediaRouter.addCallback(MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY, mMediaRouterCallback,
                MediaRouter.CALLBACK_FLAG_PASSIVE_DISCOVERY);
    }

    @Override
    public void onSwitchUser(int userId) {
        mMediaRouter.rebindAsUser(userId);
        synchronized (mLock) {
            if (mProjectionGrant != null) {
                mProjectionGrant.stop();
            }
        }
    }

    @Override
    public void monitor() {
        synchronized (mLock) { /* check for deadlock */ }
    }

    private void startProjectionLocked(final MediaProjection projection) {
        if (mProjectionGrant != null) {
            mProjectionGrant.stop();
        }
        if (mMediaRouteInfo != null) {
            mMediaRouter.getFallbackRoute().select();
        }
        mProjectionToken = projection.asBinder();
        mProjectionGrant = projection;
        dispatchStart(projection);
    }

    private void stopProjectionLocked(final MediaProjection projection) {
        mProjectionToken = null;
        mProjectionGrant = null;
        dispatchStop(projection);
    }

    private void addCallback(final IMediaProjectionWatcherCallback callback) {
        IBinder.DeathRecipient deathRecipient = new IBinder.DeathRecipient() {
            @Override
            public void binderDied() {
                removeCallback(callback);
            }
        };
        synchronized (mLock) {
            mCallbackDelegate.add(callback);
            linkDeathRecipientLocked(callback, deathRecipient);
        }
    }

    private void removeCallback(IMediaProjectionWatcherCallback callback) {
        synchronized (mLock) {
            unlinkDeathRecipientLocked(callback);
            mCallbackDelegate.remove(callback);
        }
    }

    private void linkDeathRecipientLocked(IMediaProjectionWatcherCallback callback,
            IBinder.DeathRecipient deathRecipient) {
        try {
            final IBinder token = callback.asBinder();
            token.linkToDeath(deathRecipient, 0);
            mDeathEaters.put(token, deathRecipient);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to link to death for media projection monitoring callback", e);
        }
    }

    private void unlinkDeathRecipientLocked(IMediaProjectionWatcherCallback callback) {
        final IBinder token = callback.asBinder();
        IBinder.DeathRecipient deathRecipient = mDeathEaters.remove(token);
        if (deathRecipient != null) {
            token.unlinkToDeath(deathRecipient, 0);
        }
    }

    private void dispatchStart(MediaProjection projection) {
        mCallbackDelegate.dispatchStart(projection);
    }

    private void dispatchStop(MediaProjection projection) {
        mCallbackDelegate.dispatchStop(projection);
    }

    private boolean isValidMediaProjection(IBinder token) {
        synchronized (mLock) {
            if (mProjectionToken != null) {
                return mProjectionToken.equals(token);
            }
            return false;
        }
    }

    private MediaProjectionInfo getActiveProjectionInfo() {
        synchronized (mLock) {
            if (mProjectionGrant == null) {
                return null;
            }
            return mProjectionGrant.getProjectionInfo();
        }
    }

    private void dump(final PrintWriter pw) {
        pw.println("MEDIA PROJECTION MANAGER (dumpsys media_projection)");
        synchronized (mLock) {
            pw.println("Media Projection: ");
            if (mProjectionGrant != null ) {
                mProjectionGrant.dump(pw);
            } else {
                pw.println("null");
            }
        }
    }

    private final class BinderService extends IMediaProjectionManager.Stub {

        @Override // Binder call
        public boolean hasProjectionPermission(int uid, String packageName) {
            long token = Binder.clearCallingIdentity();
            boolean hasPermission = false;
            try {
                hasPermission |= checkPermission(packageName,
                        android.Manifest.permission.CAPTURE_VIDEO_OUTPUT)
                        || mAppOps.noteOpNoThrow(
                                AppOpsManager.OP_PROJECT_MEDIA, uid, packageName)
                        == AppOpsManager.MODE_ALLOWED;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return hasPermission;
        }

        @Override // Binder call
        public IMediaProjection createProjection(int uid, String packageName, int type,
                boolean isPermanentGrant) {
            if (mContext.checkCallingPermission(Manifest.permission.MANAGE_MEDIA_PROJECTION)
                        != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires MANAGE_MEDIA_PROJECTION in order to grant "
                        + "projection permission");
            }
            if (packageName == null || packageName.isEmpty()) {
                throw new IllegalArgumentException("package name must not be empty");
            }
            long callingToken = Binder.clearCallingIdentity();
            MediaProjection projection;
            try {
                projection = new MediaProjection(type, uid, packageName);
                if (isPermanentGrant) {
                    mAppOps.setMode(AppOpsManager.OP_PROJECT_MEDIA,
                            projection.uid, projection.packageName, AppOpsManager.MODE_ALLOWED);
                }
            } finally {
                Binder.restoreCallingIdentity(callingToken);
            }
            return projection;
        }

        @Override // Binder call
        public boolean isValidMediaProjection(IMediaProjection projection) {
            return MediaProjectionManagerService.this.isValidMediaProjection(
                    projection.asBinder());
        }

        @Override // Binder call
        public MediaProjectionInfo getActiveProjectionInfo() {
            if (mContext.checkCallingPermission(Manifest.permission.MANAGE_MEDIA_PROJECTION)
                        != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires MANAGE_MEDIA_PROJECTION in order to add "
                        + "projection callbacks");
            }
            final long token = Binder.clearCallingIdentity();
            try {
                return MediaProjectionManagerService.this.getActiveProjectionInfo();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void stopActiveProjection() {
            if (mContext.checkCallingPermission(Manifest.permission.MANAGE_MEDIA_PROJECTION)
                        != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires MANAGE_MEDIA_PROJECTION in order to add "
                        + "projection callbacks");
            }
            final long token = Binder.clearCallingIdentity();
            try {
                if (mProjectionGrant != null) {
                    mProjectionGrant.stop();
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }

        }

        @Override //Binder call
        public void addCallback(final IMediaProjectionWatcherCallback callback) {
            if (mContext.checkCallingPermission(Manifest.permission.MANAGE_MEDIA_PROJECTION)
                        != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires MANAGE_MEDIA_PROJECTION in order to add "
                        + "projection callbacks");
            }
            final long token = Binder.clearCallingIdentity();
            try {
                MediaProjectionManagerService.this.addCallback(callback);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void removeCallback(IMediaProjectionWatcherCallback callback) {
            if (mContext.checkCallingPermission(Manifest.permission.MANAGE_MEDIA_PROJECTION)
                        != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires MANAGE_MEDIA_PROJECTION in order to remove "
                        + "projection callbacks");
            }
            final long token = Binder.clearCallingIdentity();
            try {
                MediaProjectionManagerService.this.removeCallback(callback);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void dump(FileDescriptor fd, final PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
            final long token = Binder.clearCallingIdentity();
            try {
                MediaProjectionManagerService.this.dump(pw);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }


        private boolean checkPermission(String packageName, String permission) {
            return mContext.getPackageManager().checkPermission(permission, packageName)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    private final class MediaProjection extends IMediaProjection.Stub {
        public final int uid;
        public final String packageName;
        public final UserHandle userHandle;

        private IMediaProjectionCallback mCallback;
        private IBinder mToken;
        private IBinder.DeathRecipient mDeathEater;
        private int mType;

        public MediaProjection(int type, int uid, String packageName) {
            mType = type;
            this.uid = uid;
            this.packageName = packageName;
            userHandle = new UserHandle(UserHandle.getUserId(uid));
        }

        @Override // Binder call
        public boolean canProjectVideo() {
            return mType == MediaProjectionManager.TYPE_MIRRORING ||
                    mType == MediaProjectionManager.TYPE_SCREEN_CAPTURE;
        }

        @Override // Binder call
        public boolean canProjectSecureVideo() {
            return false;
        }

        @Override // Binder call
        public boolean canProjectAudio() {
            return mType == MediaProjectionManager.TYPE_MIRRORING ||
                    mType == MediaProjectionManager.TYPE_PRESENTATION;
        }

        @Override // Binder call
        public int applyVirtualDisplayFlags(int flags) {
            if (mType == MediaProjectionManager.TYPE_SCREEN_CAPTURE) {
                flags &= ~DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
                flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                        | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
                return flags;
            } else if (mType == MediaProjectionManager.TYPE_MIRRORING) {
                flags &= ~(DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC |
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR);
                flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY |
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
                return flags;
            } else if (mType == MediaProjectionManager.TYPE_PRESENTATION) {
                flags &= ~DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
                flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC |
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION |
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;
                return flags;
            } else  {
                throw new RuntimeException("Unknown MediaProjection type");
            }
        }

        @Override // Binder call
        public void start(final IMediaProjectionCallback callback) {
            if (callback == null) {
                throw new IllegalArgumentException("callback must not be null");
            }
            synchronized (mLock) {
                if (isValidMediaProjection(asBinder())) {
                    throw new IllegalStateException(
                            "Cannot start already started MediaProjection");
                }
                mCallback = callback;
                registerCallback(mCallback);
                try {
                    mToken = callback.asBinder();
                    mDeathEater = new IBinder.DeathRecipient() {
                        @Override
                        public void binderDied() {
                            mCallbackDelegate.remove(callback);
                            stop();
                        }
                    };
                    mToken.linkToDeath(mDeathEater, 0);
                } catch (RemoteException e) {
                    Slog.w(TAG,
                            "MediaProjectionCallbacks must be valid, aborting MediaProjection", e);
                    return;
                }
                startProjectionLocked(this);
            }
        }

        @Override // Binder call
        public void stop() {
            synchronized (mLock) {
                if (!isValidMediaProjection(asBinder())) {
                    Slog.w(TAG, "Attempted to stop inactive MediaProjection "
                            + "(uid=" + Binder.getCallingUid() + ", "
                            + "pid=" + Binder.getCallingPid() + ")");
                    return;
                }
                stopProjectionLocked(this);
                mToken.unlinkToDeath(mDeathEater, 0);
                mToken = null;
                unregisterCallback(mCallback);
                mCallback = null;
            }
        }

        @Override
        public void registerCallback(IMediaProjectionCallback callback) {
            if (callback == null) {
                throw new IllegalArgumentException("callback must not be null");
            }
            mCallbackDelegate.add(callback);
        }

        @Override
        public void unregisterCallback(IMediaProjectionCallback callback) {
            if (callback == null) {
                throw new IllegalArgumentException("callback must not be null");
            }
            mCallbackDelegate.remove(callback);
        }

        public MediaProjectionInfo getProjectionInfo() {
            return new MediaProjectionInfo(packageName, userHandle);
        }

        public void dump(PrintWriter pw) {
            pw.println("(" + packageName + ", uid=" + uid + "): " + typeToString(mType));
        }
    }

    private class MediaRouterCallback extends MediaRouter.SimpleCallback {
        @Override
        public void onRouteSelected(MediaRouter router, int type, MediaRouter.RouteInfo info) {
            synchronized (mLock) {
                if ((type & MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY) != 0) {
                    mMediaRouteInfo = info;
                    if (mProjectionGrant != null) {
                        mProjectionGrant.stop();
                    }
                }
            }
        }

        @Override
        public void onRouteUnselected(MediaRouter route, int type, MediaRouter.RouteInfo info) {
            if (mMediaRouteInfo == info) {
                mMediaRouteInfo = null;
            }
        }
    }


    private static class CallbackDelegate {
        private Map<IBinder, IMediaProjectionCallback> mClientCallbacks;
        private Map<IBinder, IMediaProjectionWatcherCallback> mWatcherCallbacks;
        private Handler mHandler;
        private Object mLock = new Object();

        public CallbackDelegate() {
            mHandler = new Handler(Looper.getMainLooper(), null, true /*async*/);
            mClientCallbacks = new ArrayMap<IBinder, IMediaProjectionCallback>();
            mWatcherCallbacks = new ArrayMap<IBinder, IMediaProjectionWatcherCallback>();
        }

        public void add(IMediaProjectionCallback callback) {
            synchronized (mLock) {
                mClientCallbacks.put(callback.asBinder(), callback);
            }
        }

        public void add(IMediaProjectionWatcherCallback callback) {
            synchronized (mLock) {
                mWatcherCallbacks.put(callback.asBinder(), callback);
            }
        }

        public void remove(IMediaProjectionCallback callback) {
            synchronized (mLock) {
                mClientCallbacks.remove(callback.asBinder());
            }
        }

        public void remove(IMediaProjectionWatcherCallback callback) {
            synchronized (mLock) {
                mWatcherCallbacks.remove(callback.asBinder());
            }
        }

        public void dispatchStart(MediaProjection projection) {
            if (projection == null) {
                Slog.e(TAG, "Tried to dispatch start notification for a null media projection."
                        + " Ignoring!");
                return;
            }
            synchronized (mLock) {
                for (IMediaProjectionWatcherCallback callback : mWatcherCallbacks.values()) {
                    MediaProjectionInfo info = projection.getProjectionInfo();
                    mHandler.post(new WatcherStartCallback(info, callback));
                }
            }
        }

        public void dispatchStop(MediaProjection projection) {
            if (projection == null) {
                Slog.e(TAG, "Tried to dispatch stop notification for a null media projection."
                        + " Ignoring!");
                return;
            }
            synchronized (mLock) {
                for (IMediaProjectionCallback callback : mClientCallbacks.values()) {
                    mHandler.post(new ClientStopCallback(callback));
                }

                for (IMediaProjectionWatcherCallback callback : mWatcherCallbacks.values()) {
                    MediaProjectionInfo info = projection.getProjectionInfo();
                    mHandler.post(new WatcherStopCallback(info, callback));
                }
            }
        }
    }

    private static final class WatcherStartCallback implements Runnable {
        private IMediaProjectionWatcherCallback mCallback;
        private MediaProjectionInfo mInfo;

        public WatcherStartCallback(MediaProjectionInfo info,
                IMediaProjectionWatcherCallback callback) {
            mInfo = info;
            mCallback = callback;
        }

        @Override
        public void run() {
            try {
                mCallback.onStart(mInfo);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify media projection has stopped", e);
            }
        }
    }

    private static final class WatcherStopCallback implements Runnable {
        private IMediaProjectionWatcherCallback mCallback;
        private MediaProjectionInfo mInfo;

        public WatcherStopCallback(MediaProjectionInfo info,
                IMediaProjectionWatcherCallback callback) {
            mInfo = info;
            mCallback = callback;
        }

        @Override
        public void run() {
            try {
                mCallback.onStop(mInfo);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify media projection has stopped", e);
            }
        }
    }

    private static final class ClientStopCallback implements Runnable {
        private IMediaProjectionCallback mCallback;

        public ClientStopCallback(IMediaProjectionCallback callback) {
            mCallback = callback;
        }

        @Override
        public void run() {
            try {
                mCallback.onStop();
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify media projection has stopped", e);
            }
        }
    }


    private static String typeToString(int type) {
        switch (type) {
            case MediaProjectionManager.TYPE_SCREEN_CAPTURE:
                return "TYPE_SCREEN_CAPTURE";
            case MediaProjectionManager.TYPE_MIRRORING:
                return "TYPE_MIRRORING";
            case MediaProjectionManager.TYPE_PRESENTATION:
                return "TYPE_PRESENTATION";
        }
        return Integer.toString(type);
    }
}
