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
import android.media.projection.IMediaProjectionManager;
import android.media.projection.IMediaProjection;
import android.media.projection.IMediaProjectionCallback;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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
    private final Map<IBinder, MediaProjection> mProjectionGrants;

    private final Context mContext;
    private final AppOpsManager mAppOps;

    public MediaProjectionManagerService(Context context) {
        super(context);
        mContext = context;
        mProjectionGrants = new ArrayMap<IBinder, MediaProjection>();
        mAppOps = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        Watchdog.getInstance().addMonitor(this);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.MEDIA_PROJECTION_SERVICE, new BinderService(),
                false /*allowIsolated*/);
    }

    @Override
    public void monitor() {
        synchronized (mLock) { /* check for deadlock */ }
    }

    private void dump(final PrintWriter pw) {
        pw.println("MEDIA PROJECTION MANAGER (dumpsys media_projection)");
        synchronized (mLock) {
            Collection<MediaProjection> projections = mProjectionGrants.values();
            pw.println("Media Projections: size=" + projections.size());
            for (MediaProjection mp : projections) {
                mp.dump(pw, "  ");
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
            if (mContext.checkCallingPermission(Manifest.permission.CREATE_MEDIA_PROJECTION)
                        != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires CREATE_MEDIA_PROJECTION in order to grant "
                        + "projection permission");
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
            return mProjectionGrants.containsKey(projection.asBinder());
        }

        @Override // Binder call
        public void dump(FileDescriptor fd, final PrintWriter pw, String[] args) {
            if (mContext == null
                    || mContext.checkCallingOrSelfPermission(Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump MediaProjectionManager from from pid="
                        + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid());
                return;
            }

            final long token = Binder.clearCallingIdentity();
            try {
                dump(pw);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        private boolean checkPermission(String packageName, String permission) {
            return mContext.getPackageManager().checkPermission(permission, packageName)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    private final class MediaProjection extends IMediaProjection.Stub implements DeathRecipient {
        public int uid;
        public String packageName;

        private IBinder mToken;
        private int mType;
        private CallbackDelegate mCallbackDelegate;

        public MediaProjection(int type, int uid, String packageName) {
            mType = type;
            this.uid = uid;
            this.packageName = packageName;
            mCallbackDelegate = new CallbackDelegate();
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
        public void start(IMediaProjectionCallback callback) {
            if (callback == null) {
                throw new IllegalArgumentException("callback must not be null");
            }
            synchronized (mLock) {
                if (mProjectionGrants.containsKey(asBinder())) {
                    throw new IllegalStateException(
                            "Cannot start already started MediaProjection");
                }
                addCallback(callback);
                try {
                    mToken = callback.asBinder();
                    mToken.linkToDeath(this, 0);
                } catch (RemoteException e) {
                    Slog.w(TAG,
                            "MediaProjectionCallbacks must be valid, aborting MediaProjection", e);
                    return;
                }
                mProjectionGrants.put(asBinder(), this);
            }
        }

        @Override // Binder call
        public void stop() {
            synchronized (mLock) {
                if (!mProjectionGrants.containsKey(asBinder())) {
                    Slog.w(TAG, "Attempted to stop inactive MediaProjection "
                            + "(uid=" + Binder.getCallingUid() + ", "
                            + "pid=" + Binder.getCallingPid() + ")");
                    return;
                }
                mToken.unlinkToDeath(this, 0);
                mCallbackDelegate.dispatchStop();
                mProjectionGrants.remove(asBinder());
            }
        }

        @Override
        public void binderDied() {
            stop();
        }

        @Override
        public void addCallback(IMediaProjectionCallback callback) {
            if (callback == null) {
                throw new IllegalArgumentException("callback must not be null");
            }
            mCallbackDelegate.add(callback);
        }

        @Override
        public void removeCallback(IMediaProjectionCallback callback) {
            if (callback == null) {
                throw new IllegalArgumentException("callback must not be null");
            }
            mCallbackDelegate.remove(callback);
        }

        public void dump(PrintWriter pw, String prefix) {
            pw.println(prefix + "(" + packageName + ", uid=" + uid + "): " + typeToString(mType));
        }
    }

    private static class CallbackDelegate {
        private static final int MSG_ON_STOP = 0;
        private List<IMediaProjectionCallback> mCallbacks;
        private Handler mHandler;
        private Object mLock = new Object();

        public CallbackDelegate() {
            mHandler = new Handler(Looper.getMainLooper(), null, true /*async*/);
            mCallbacks = new ArrayList<IMediaProjectionCallback>();
        }

        public void add(IMediaProjectionCallback callback) {
            synchronized (mLock) {
                mCallbacks.add(callback);
            }
        }

        public void remove(IMediaProjectionCallback callback) {
            synchronized (mLock) {
                mCallbacks.remove(callback);
            }
        }

        public void dispatchStop() {
            synchronized (mLock) {
                for (final IMediaProjectionCallback callback : mCallbacks) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                callback.onStop();
                            } catch (RemoteException e) {
                                Slog.w(TAG, "Failed to notify media projection has stopped", e);
                            }
                        }
                    });
                }
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
