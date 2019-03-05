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

import static android.app.AppOpsManager.OP_ASSIST_SCREENSHOT;
import static android.app.AppOpsManager.OP_ASSIST_STRUCTURE;
import static android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;
import static android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION;

import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_CONTENT;
import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_DATA;
import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_STRUCTURE;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.UriGrantsManager;
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

import com.android.internal.app.AssistUtils;
import com.android.internal.app.IVoiceInteractionSessionShowCallback;
import com.android.internal.app.IVoiceInteractor;
import com.android.server.LocalServices;
import com.android.server.am.AssistDataRequester;
import com.android.server.am.AssistDataRequester.AssistDataRequesterCallbacks;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.uri.UriGrantsManagerInternal;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

final class VoiceInteractionSessionConnection implements ServiceConnection,
        AssistDataRequesterCallbacks {

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
    final UriGrantsManagerInternal mUgmInternal;
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
    ArrayList<IVoiceInteractionSessionShowCallback> mPendingShowCallbacks = new ArrayList<>();
    AssistDataRequester mAssistDataRequester;

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
        public void onSessionShown(VoiceInteractionSessionConnection connection);
        public void onSessionHidden(VoiceInteractionSessionConnection connection);
    }

    final ServiceConnection mFullConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
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
        mAm = ActivityManager.getService();
        mUgmInternal = LocalServices.getService(UriGrantsManagerInternal.class);
        mIWindowManager = IWindowManager.Stub.asInterface(
                ServiceManager.getService(Context.WINDOW_SERVICE));
        mAppOps = context.getSystemService(AppOpsManager.class);
        mAssistDataRequester = new AssistDataRequester(mContext, mIWindowManager,
                (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE),
                this, mLock, OP_ASSIST_STRUCTURE, OP_ASSIST_SCREENSHOT);
        final IBinder permOwner = mUgmInternal.newUriPermissionOwner("voicesession:"
                    + component.flattenToShortString());
        mPermissionOwner = permOwner;
        mBindIntent = new Intent(VoiceInteractionService.SERVICE_INTERFACE);
        mBindIntent.setComponent(mSessionComponentName);
        mBound = mContext.bindServiceAsUser(mBindIntent, this,
                Context.BIND_AUTO_CREATE | Context.BIND_WAIVE_PRIORITY
                        | Context.BIND_ALLOW_OOM_MANAGEMENT, new UserHandle(mUser));
        if (mBound) {
            try {
                mIWindowManager.addWindowToken(mToken, TYPE_VOICE_INTERACTION, DEFAULT_DISPLAY);
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
            IVoiceInteractionSessionShowCallback showCallback, List<IBinder> topActivities) {
        if (mBound) {
            if (!mFullyBound) {
                mFullyBound = mContext.bindServiceAsUser(mBindIntent, mFullConnection,
                        Context.BIND_AUTO_CREATE | Context.BIND_TREAT_LIKE_ACTIVITY
                                | Context.BIND_FOREGROUND_SERVICE,
                        new UserHandle(mUser));
            }

            mShown = true;
            mShowArgs = args;
            mShowFlags = flags;

            disabledContext |= getUserDisabledShowContextLocked();
            mAssistDataRequester.requestAssistData(topActivities,
                    (flags & VoiceInteractionSession.SHOW_WITH_ASSIST) != 0,
                    (flags & VoiceInteractionSession.SHOW_WITH_SCREENSHOT) != 0,
                    (disabledContext & VoiceInteractionSession.SHOW_WITH_ASSIST) == 0,
                    (disabledContext & VoiceInteractionSession.SHOW_WITH_SCREENSHOT) == 0,
                    mCallingUid, mSessionComponentName.getPackageName());

            boolean needDisclosure = mAssistDataRequester.getPendingDataCount() > 0
                    || mAssistDataRequester.getPendingScreenshotCount() > 0;
            if (needDisclosure && AssistUtils.shouldDisclose(mContext, mSessionComponentName)) {
                mHandler.post(mShowAssistDisclosureRunnable);
            }
            if (mSession != null) {
                try {
                    mSession.show(mShowArgs, mShowFlags, showCallback);
                    mShowArgs = null;
                    mShowFlags = 0;
                } catch (RemoteException e) {
                }
                mAssistDataRequester.processPendingAssistData();
            } else if (showCallback != null) {
                mPendingShowCallbacks.add(showCallback);
            }
            mCallback.onSessionShown(this);
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

    @Override
    public boolean canHandleReceivedAssistDataLocked() {
        return mSession != null;
    }

    @Override
    public void onAssistDataReceivedLocked(Bundle data, int activityIndex, int activityCount) {
        // Return early if we have no session
        if (mSession == null) {
            return;
        }

        if (data == null) {
            try {
                mSession.handleAssist(null, null, null, 0, 0);
            } catch (RemoteException e) {
                // Ignore
            }
        } else {
            final Bundle assistData = data.getBundle(ASSIST_KEY_DATA);
            final AssistStructure structure = data.getParcelable(ASSIST_KEY_STRUCTURE);
            final AssistContent content = data.getParcelable(ASSIST_KEY_CONTENT);
            int uid = -1;
            if (assistData != null) {
                uid = assistData.getInt(Intent.EXTRA_ASSIST_UID, -1);
            }
            if (uid >= 0 && content != null) {
                Intent intent = content.getIntent();
                if (intent != null) {
                    ClipData clipData = intent.getClipData();
                    if (clipData != null && Intent.isAccessUriMode(intent.getFlags())) {
                        grantClipDataPermissions(clipData, intent.getFlags(), uid,
                                mCallingUid, mSessionComponentName.getPackageName());
                    }
                }
                ClipData clipData = content.getClipData();
                if (clipData != null) {
                    grantClipDataPermissions(clipData, FLAG_GRANT_READ_URI_PERMISSION,
                            uid, mCallingUid, mSessionComponentName.getPackageName());
                }
            }
            try {
                mSession.handleAssist(assistData, structure, content, activityIndex,
                        activityCount);
            } catch (RemoteException e) {
                // Ignore
            }
        }
    }

    @Override
    public void onAssistScreenshotReceivedLocked(Bitmap screenshot) {
        // Return early if we have no session
        if (mSession == null) {
            return;
        }

        try {
            mSession.handleScreenshot(screenshot);
        } catch (RemoteException e) {
            // Ignore
        }
    }

    void grantUriPermission(Uri uri, int mode, int srcUid, int destUid, String destPkg) {
        if (!"content".equals(uri.getScheme())) {
            return;
        }
        long ident = Binder.clearCallingIdentity();
        try {
            // This will throw SecurityException for us.
            mUgmInternal.checkGrantUriPermission(srcUid, null,
                    ContentProvider.getUriWithoutUserId(uri), mode,
                    ContentProvider.getUserIdFromUri(uri, UserHandle.getUserId(srcUid)));
            // No security exception, do the grant.
            int sourceUserId = ContentProvider.getUserIdFromUri(uri, mUser);
            uri = ContentProvider.getUriWithoutUserId(uri);
            UriGrantsManager.getService().grantUriPermissionFromOwner(mPermissionOwner, srcUid,
                    destPkg, uri, FLAG_GRANT_READ_URI_PERMISSION, sourceUserId, mUser);
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

    public boolean hideLocked() {
        if (mBound) {
            if (mShown) {
                mShown = false;
                mShowArgs = null;
                mShowFlags = 0;
                mAssistDataRequester.cancel();
                mPendingShowCallbacks.clear();
                if (mSession != null) {
                    try {
                        mSession.hide();
                    } catch (RemoteException e) {
                    }
                }
                mUgmInternal.revokeUriPermissionFromOwner(mPermissionOwner, null,
                        FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_WRITE_URI_PERMISSION, mUser);
                if (mSession != null) {
                    try {
                        ActivityTaskManager.getService().finishVoiceTask(mSession);
                    } catch (RemoteException e) {
                    }
                }
                mCallback.onSessionHidden(this);
            }
            if (mFullyBound) {
                mContext.unbindService(mFullConnection);
                mFullyBound = false;
            }
            return true;
        }
        return false;
    }

    public void cancelLocked(boolean finishTask) {
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
            if (finishTask && mSession != null) {
                try {
                    ActivityTaskManager.getService().finishVoiceTask(mSession);
                } catch (RemoteException e) {
                }
            }
            mContext.unbindService(this);
            try {
                mIWindowManager.removeWindowToken(mToken, DEFAULT_DISPLAY);
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
            mAssistDataRequester.processPendingAssistData();
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
        synchronized (mLock) {
            mService = null;
        }
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
        mAssistDataRequester.dump(prefix, pw);
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
