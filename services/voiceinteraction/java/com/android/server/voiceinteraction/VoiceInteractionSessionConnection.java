/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.voiceinteraction;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.assist.AssistContent;
import android.app.assist.AssistStructure;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.voice.IVoiceInteractionSession;
import android.service.voice.IVoiceInteractionSessionService;
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionSession;
import android.util.Slog;
import android.view.IWindowManager;
import android.view.WindowManager;
import com.android.internal.app.IAssistScreenshotReceiver;
import com.android.internal.app.IVoiceInteractionSessionShowCallback;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.os.IResultReceiver;
import com.android.server.LocalServices;
import com.android.server.statusbar.StatusBarManagerInternal;

import java.io.PrintWriter;
import java.util.ArrayList;

final class VoiceInteractionSessionConnection implements ServiceConnection {
    final static String TAG = "VoiceInteractionServiceManager";

    final IBinder mToken = new Binder();
    final Object mLock;
    final ComponentName mSessionComponentName;
    final Intent mBindIntent;
    final int mUser;
    final Context mContext;
    final Callback mCallback;
    final int mCallingUid;
    final Handler mHandler;
    final IActivityManager mAm;
    final IWindowManager mIWindowManager;
    final AppOpsManager mAppOps;
    final IBinder mPermissionOwner;
    boolean mShown;
    Bundle mShowArgs;
    int mShowFlags;
    boolean mBound;
    boolean mFullyBound;
    boolean mCanceled;
    IVoiceInteractionSessionService mService;
    IVoiceInteractionSession mSession;
    IVoiceInteractor mInteractor;
    boolean mHaveAssistData;
    Bundle mAssistData;
    boolean mHaveScreenshot;
    Bitmap mScreenshot;
    ArrayList<IVoiceInteractionSessionShowCallback> mPendingShowCallbacks = new ArrayList<>();

    IVoiceInteractionSessionShowCallback mShowCallback =
            new IVoiceInteractionSessionShowCallback.Stub() {
        @Override
        public void onFailed() throws RemoteException {
            synchronized (mLock) {
                notifyPendingShowCallbacksFailedLocked();
            }
        }

        @Override
        public void onShown() throws RemoteException {
            synchronized (mLock) {
                // TODO: Figure out whether this is good enough or whether we need to hook into
                // Window manager to actually wait for the window to be drawn.
                notifyPendingShowCallbacksShownLocked();
            }
        }
    };

    public interface Callback {
        public void sessionConnectionGone(VoiceInteractionSessionConnection connection);
    }

    final ServiceConnection mFullConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    };

    final IResultReceiver mAssistReceiver = new IResultReceiver.Stub() {
        @Override
        public void send(int resultCode, Bundle resultData) throws RemoteException {
            synchronized (mLock) {
                if (mShown) {
                    mHaveAssistData = true;
                    mAssistData = resultData;
                    deliverSessionDataLocked();
                }
            }
        }
    };

    final IAssistScreenshotReceiver mScreenshotReceiver = new IAssistScreenshotReceiver.Stub() {
        @Override
        public void send(Bitmap screenshot) throws RemoteException {
            synchronized (mLock) {
                if (mShown) {
                    mHaveScreenshot = true;
                    mScreenshot = screenshot;
                    deliverSessionDataLocked();
                }
            }
        }
    };

    public VoiceInteractionSessionConnection(Object lock, ComponentName component, int user,
            Context context, Callback callback, int callingUid, Handler handler) {
        mLock = lock;
        mSessionComponentName = component;
        mUser = user;
        mContext = context;
        mCallback = callback;
        mCallingUid = callingUid;
        mHandler = handler;
        mAm = ActivityManagerNative.getDefault();
        mIWindowManager = IWindowManager.Stub.asInterface(
                ServiceManager.getService(Context.WINDOW_SERVICE));
        mAppOps = context.getSystemService(AppOpsManager.class);
        IBinder permOwner = null;
        try {
            permOwner = mAm.newUriPermissionOwner("voicesession:"
                    + component.flattenToShortString());
        } catch (RemoteException e) {
            Slog.w("voicesession", "AM dead", e);
        }
        mPermissionOwner = permOwner;
        mBindIntent = new Intent(VoiceInteractionService.SERVICE_INTERFACE);
        mBindIntent.setComponent(mSessionComponentName);
        mBound = mContext.bindServiceAsUser(mBindIntent, this,
                Context.BIND_AUTO_CREATE | Context.BIND_WAIVE_PRIORITY
                        | Context.BIND_ALLOW_OOM_MANAGEMENT, new UserHandle(mUser));
        if (mBound) {
            try {
                mIWindowManager.addWindowToken(mToken,
                        WindowManager.LayoutParams.TYPE_VOICE_INTERACTION);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed adding window token", e);
            }
        } else {
            Slog.w(TAG, "Failed binding to voice interaction session service "
                    + mSessionComponentName);
        }
    }

    public int getUserDisabledShowContextLocked() {
        int flags = 0;
        if (Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.ASSIST_STRUCTURE_ENABLED, 1, mUser) == 0) {
            flags |= VoiceInteractionSession.SHOW_WITH_ASSIST;
        }
        if (Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.ASSIST_SCREENSHOT_ENABLED, 1, mUser) == 0) {
            flags |= VoiceInteractionSession.SHOW_WITH_SCREENSHOT;
        }
        return flags;
    }

    public boolean showLocked(Bundle args, int flags, int disabledContext,
            IVoiceInteractionSessionShowCallback showCallback, IBinder activityToken) {
        if (mBound) {
            if (!mFullyBound) {
                mFullyBound = mContext.bindServiceAsUser(mBindIntent, mFullConnection,
                        Context.BIND_AUTO_CREATE | Context.BIND_TREAT_LIKE_ACTIVITY
                                | Context.BIND_FOREGROUND_SERVICE,
                        new UserHandle(mUser));
            }
            mShown = true;
            boolean isAssistDataAllowed = true;
            try {
                isAssistDataAllowed = mAm.isAssistDataAllowedOnCurrentActivity();
            } catch (RemoteException e) {
            }
            disabledContext |= getUserDisabledShowContextLocked();
            boolean structureEnabled = isAssistDataAllowed
                    && (disabledContext&VoiceInteractionSession.SHOW_WITH_ASSIST) == 0;
            boolean screenshotEnabled = isAssistDataAllowed && structureEnabled
                    && (disabledContext&VoiceInteractionSession.SHOW_WITH_SCREENSHOT) == 0;
            mShowArgs = args;
            mShowFlags = flags;
            mHaveAssistData = false;
            boolean needDisclosure = false;
            if ((flags&VoiceInteractionSession.SHOW_WITH_ASSIST) != 0) {
                if (mAppOps.noteOpNoThrow(AppOpsManager.OP_ASSIST_STRUCTURE, mCallingUid,
                        mSessionComponentName.getPackageName()) == AppOpsManager.MODE_ALLOWED
                        && structureEnabled) {
                    try {
                        MetricsLogger.count(mContext, "assist_with_context", 1);
                        if (mAm.requestAssistContextExtras(ActivityManager.ASSIST_CONTEXT_FULL,
                                mAssistReceiver, activityToken)) {
                            needDisclosure = true;
                        } else {
                            // Wasn't allowed...  given that, let's not do the screenshot either.
                            mHaveAssistData = true;
                            mAssistData = null;
                            screenshotEnabled = false;
                        }
                    } catch (RemoteException e) {
                    }
                } else {
                    mHaveAssistData = true;
                    mAssistData = null;
                }
            } else {
                mAssistData = null;
            }
            mHaveScreenshot = false;
            if ((flags&VoiceInteractionSession.SHOW_WITH_SCREENSHOT) != 0) {
                if (mAppOps.noteOpNoThrow(AppOpsManager.OP_ASSIST_SCREENSHOT, mCallingUid,
                        mSessionComponentName.getPackageName()) == AppOpsManager.MODE_ALLOWED
                        && screenshotEnabled) {
                    try {
                        MetricsLogger.count(mContext, "assist_with_screen", 1);
                        needDisclosure = true;
                        mIWindowManager.requestAssistScreenshot(mScreenshotReceiver);
                    } catch (RemoteException e) {
                    }
                } else {
                    mHaveScreenshot = true;
                    mScreenshot = null;
                }
            } else {
                mScreenshot = null;
            }
            if (needDisclosure) {
                mHandler.post(mShowAssistDisclosureRunnable);
            }
            if (mSession != null) {
                try {
                    mSession.show(mShowArgs, mShowFlags, showCallback);
                    mShowArgs = null;
                    mShowFlags = 0;
                } catch (RemoteException e) {
                }
                deliverSessionDataLocked();
            } else if (showCallback != null) {
                mPendingShowCallbacks.add(showCallback);
            }
            return true;
        }
        if (showCallback != null) {
            try {
                showCallback.onFailed();
            } catch (RemoteException e) {
            }
        }
        return false;
    }

    void grantUriPermission(Uri uri, int mode, int srcUid, int destUid, String destPkg) {
        if (!"content".equals(uri.getScheme())) {
            return;
        }
        long ident = Binder.clearCallingIdentity();
        try {
            // This will throw SecurityException for us.
            mAm.checkGrantUriPermission(srcUid, null, ContentProvider.getUriWithoutUserId(uri),
                    mode, ContentProvider.getUserIdFromUri(uri, UserHandle.getUserId(srcUid)));
            // No security exception, do the grant.
            int sourceUserId = ContentProvider.getUserIdFromUri(uri, mUser);
            uri = ContentProvider.getUriWithoutUserId(uri);
            mAm.grantUriPermissionFromOwner(mPermissionOwner, srcUid, destPkg,
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION, sourceUserId, mUser);
        } catch (RemoteException e) {
        } catch (SecurityException e) {
            Slog.w(TAG, "Can't propagate permission", e);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

    }

    void grantClipDataItemPermission(ClipData.Item item, int mode, int srcUid, int destUid,
            String destPkg) {
        if (item.getUri() != null) {
            grantUriPermission(item.getUri(), mode, srcUid, destUid, destPkg);
        }
        Intent intent = item.getIntent();
        if (intent != null && intent.getData() != null) {
            grantUriPermission(intent.getData(), mode, srcUid, destUid, destPkg);
        }
    }

    void grantClipDataPermissions(ClipData data, int mode, int srcUid, int destUid,
            String destPkg) {
        final int N = data.getItemCount();
        for (int i=0; i<N; i++) {
            grantClipDataItemPermission(data.getItemAt(i), mode, srcUid, destUid, destPkg);
        }
    }

    void deliverSessionDataLocked() {
        if (mSession == null) {
            return;
        }
        if (mHaveAssistData) {
            Bundle assistData;
            AssistStructure structure;
            AssistContent content;
            if (mAssistData != null) {
                assistData = mAssistData.getBundle("data");
                structure = mAssistData.getParcelable("structure");
                content = mAssistData.getParcelable("content");
                int uid = mAssistData.getInt(Intent.EXTRA_ASSIST_UID, -1);
                if (uid >= 0 && content != null) {
                    Intent intent = content.getIntent();
                    if (intent != null) {
                        ClipData data = intent.getClipData();
                        if (data != null && Intent.isAccessUriMode(intent.getFlags())) {
                            grantClipDataPermissions(data, intent.getFlags(), uid,
                                    mCallingUid, mSessionComponentName.getPackageName());
                        }
                    }
                    ClipData data = content.getClipData();
                    if (data != null) {
                        grantClipDataPermissions(data,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                uid, mCallingUid, mSessionComponentName.getPackageName());
                    }
                }
            } else {
                assistData = null;
                structure = null;
                content = null;
            }
            try {
                mSession.handleAssist(assistData, structure, content);
            } catch (RemoteException e) {
            }
            mAssistData = null;
            mHaveAssistData = false;
        }
        if (mHaveScreenshot) {
            try {
                mSession.handleScreenshot(mScreenshot);
            } catch (RemoteException e) {
            }
            mScreenshot = null;
            mHaveScreenshot = false;
        }
    }

    public boolean hideLocked() {
        if (mBound) {
            if (mShown) {
                mShown = false;
                mShowArgs = null;
                mShowFlags = 0;
                mHaveAssistData = false;
                mAssistData = null;
                if (mSession != null) {
                    try {
                        mSession.hide();
                    } catch (RemoteException e) {
                    }
                }
                try {
                    mAm.revokeUriPermissionFromOwner(mPermissionOwner, null,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                            mUser);
                } catch (RemoteException e) {
                }
                if (mSession != null) {
                    try {
                        mAm.finishVoiceTask(mSession);
                    } catch (RemoteException e) {
                    }
                }
            }
            if (mFullyBound) {
                mContext.unbindService(mFullConnection);
                mFullyBound = false;
            }
            return true;
        }
        return false;
    }

    public void cancelLocked() {
        hideLocked();
        mCanceled = true;
        if (mBound) {
            if (mSession != null) {
                try {
                    mSession.destroy();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Voice interation session already dead");
                }
            }
            if (mSession != null) {
                try {
                    mAm.finishVoiceTask(mSession);
                } catch (RemoteException e) {
                }
            }
            mContext.unbindService(this);
            try {
                mIWindowManager.removeWindowToken(mToken);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed removing window token", e);
            }
            mBound = false;
            mService = null;
            mSession = null;
            mInteractor = null;
        }
        if (mFullyBound) {
            mContext.unbindService(mFullConnection);
            mFullyBound = false;
        }
    }

    public boolean deliverNewSessionLocked(IVoiceInteractionSession session,
            IVoiceInteractor interactor) {
        mSession = session;
        mInteractor = interactor;
        if (mShown) {
            try {
                session.show(mShowArgs, mShowFlags, mShowCallback);
                mShowArgs = null;
                mShowFlags = 0;
            } catch (RemoteException e) {
            }
            deliverSessionDataLocked();
        }
        return true;
    }

    private void notifyPendingShowCallbacksShownLocked() {
        for (int i = 0; i < mPendingShowCallbacks.size(); i++) {
            try {
                mPendingShowCallbacks.get(i).onShown();
            } catch (RemoteException e) {
            }
        }
        mPendingShowCallbacks.clear();
    }

    private void notifyPendingShowCallbacksFailedLocked() {
        for (int i = 0; i < mPendingShowCallbacks.size(); i++) {
            try {
                mPendingShowCallbacks.get(i).onFailed();
            } catch (RemoteException e) {
            }
        }
        mPendingShowCallbacks.clear();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        synchronized (mLock) {
            mService = IVoiceInteractionSessionService.Stub.asInterface(service);
            if (!mCanceled) {
                try {
                    mService.newSession(mToken, mShowArgs, mShowFlags);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed adding window token", e);
                }
            }
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        mCallback.sessionConnectionGone(this);
        mService = null;
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("mToken="); pw.println(mToken);
        pw.print(prefix); pw.print("mShown="); pw.println(mShown);
        pw.print(prefix); pw.print("mShowArgs="); pw.println(mShowArgs);
        pw.print(prefix); pw.print("mShowFlags=0x"); pw.println(Integer.toHexString(mShowFlags));
        pw.print(prefix); pw.print("mBound="); pw.println(mBound);
        if (mBound) {
            pw.print(prefix); pw.print("mService="); pw.println(mService);
            pw.print(prefix); pw.print("mSession="); pw.println(mSession);
            pw.print(prefix); pw.print("mInteractor="); pw.println(mInteractor);
        }
        pw.print(prefix); pw.print("mHaveAssistData="); pw.println(mHaveAssistData);
        if (mHaveAssistData) {
            pw.print(prefix); pw.print("mAssistData="); pw.println(mAssistData);
        }
    }

    private Runnable mShowAssistDisclosureRunnable = new Runnable() {
        @Override
        public void run() {
            StatusBarManagerInternal statusBarInternal = LocalServices.getService(
                    StatusBarManagerInternal.class);
            if (statusBarInternal != null) {
                statusBarInternal.showAssistDisclosure();
            }
        }
    };
};
