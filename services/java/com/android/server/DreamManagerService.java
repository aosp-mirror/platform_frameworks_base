/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server;

import static android.provider.Settings.Secure.SCREENSAVER_COMPONENTS;
import static android.provider.Settings.Secure.SCREENSAVER_DEFAULT_COMPONENT;

import android.app.ActivityManagerNative;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.dreams.Dream;
import android.service.dreams.IDreamManager;
import android.util.Slog;
import android.util.SparseArray;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Service api for managing dreams.
 *
 * @hide
 */
public final class DreamManagerService
        extends IDreamManager.Stub
        implements ServiceConnection {
    private static final boolean DEBUG = true;
    private static final String TAG = DreamManagerService.class.getSimpleName();

    private static final Intent mDreamingStartedIntent = new Intent(Dream.ACTION_DREAMING_STARTED)
            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
    private static final Intent mDreamingStoppedIntent = new Intent(Dream.ACTION_DREAMING_STOPPED)
            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);

    private final Object mLock = new Object();
    private final DreamController mController;
    private final DreamControllerHandler mHandler;
    private final Context mContext;

    private final CurrentUserManager mCurrentUserManager = new CurrentUserManager();

    private final DeathRecipient mAwakenOnBinderDeath = new DeathRecipient() {
        @Override
        public void binderDied() {
            if (DEBUG) Slog.v(TAG, "binderDied()");
            awaken();
        }
    };

    private final DreamController.Listener mControllerListener = new DreamController.Listener() {
        @Override
        public void onDreamStopped(boolean wasTest) {
            synchronized(mLock) {
                setDreamingLocked(false, wasTest);
            }
        }};

    private boolean mIsDreaming;

    public DreamManagerService(Context context) {
        if (DEBUG) Slog.v(TAG, "DreamManagerService startup");
        mContext = context;
        mController = new DreamController(context, mAwakenOnBinderDeath, this, mControllerListener);
        mHandler = new DreamControllerHandler(mController);
        mController.setHandler(mHandler);
    }

    public void systemReady() {
        mCurrentUserManager.init(mContext);

        if (DEBUG) Slog.v(TAG, "Ready to dream!");
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, TAG);

        pw.println("Dreamland:");
        mController.dump(pw);
        mCurrentUserManager.dump(pw);
    }

    // begin IDreamManager api
    @Override
    public ComponentName[] getDreamComponents() {
        checkPermission(android.Manifest.permission.READ_DREAM_STATE);
        int userId = UserHandle.getCallingUserId();

        final long ident = Binder.clearCallingIdentity();
        try {
            return getDreamComponentsForUser(userId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void setDreamComponents(ComponentName[] componentNames) {
        checkPermission(android.Manifest.permission.WRITE_DREAM_STATE);
        int userId = UserHandle.getCallingUserId();

        final long ident = Binder.clearCallingIdentity();
        try {
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    SCREENSAVER_COMPONENTS,
                    componentsToString(componentNames),
                    userId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public ComponentName getDefaultDreamComponent() {
        checkPermission(android.Manifest.permission.READ_DREAM_STATE);
        int userId = UserHandle.getCallingUserId();

        final long ident = Binder.clearCallingIdentity();
        try {
            String name = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                    SCREENSAVER_DEFAULT_COMPONENT,
                    userId);
            return name == null ? null : ComponentName.unflattenFromString(name);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

    }

    @Override
    public boolean isDreaming() {
        checkPermission(android.Manifest.permission.READ_DREAM_STATE);

        return mIsDreaming;
    }

    @Override
    public void dream() {
        checkPermission(android.Manifest.permission.WRITE_DREAM_STATE);

        final long ident = Binder.clearCallingIdentity();
        try {
            if (DEBUG) Slog.v(TAG, "Dream now");
            ComponentName[] dreams = getDreamComponentsForUser(mCurrentUserManager.getCurrentUserId());
            ComponentName firstDream = dreams != null && dreams.length > 0 ? dreams[0] : null;
            if (firstDream != null) {
                mHandler.requestStart(firstDream, false /*isTest*/);
                synchronized (mLock) {
                    setDreamingLocked(true, false /*isTest*/);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void testDream(ComponentName dream) {
        checkPermission(android.Manifest.permission.WRITE_DREAM_STATE);

        final long ident = Binder.clearCallingIdentity();
        try {
            if (DEBUG) Slog.v(TAG, "Test dream name=" + dream);
            if (dream != null) {
                mHandler.requestStart(dream, true /*isTest*/);
                synchronized (mLock) {
                    setDreamingLocked(true, true /*isTest*/);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

    }

    @Override
    public void awaken() {
        checkPermission(android.Manifest.permission.WRITE_DREAM_STATE);

        final long ident = Binder.clearCallingIdentity();
        try {
            if (DEBUG) Slog.v(TAG, "Wake up");
            mHandler.requestStop();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void awakenSelf(IBinder token) {
        // requires no permission, called by Dream from an arbitrary process

        final long ident = Binder.clearCallingIdentity();
        try {
            if (DEBUG) Slog.v(TAG, "Wake up from dream: " + token);
            if (token != null) {
                mHandler.requestStopSelf(token);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }
    // end IDreamManager api

    // begin ServiceConnection
    @Override
    public void onServiceConnected(ComponentName name, IBinder dream) {
        if (DEBUG) Slog.v(TAG, "Service connected: " + name + " binder=" +
                dream + " thread=" + Thread.currentThread().getId());
        mHandler.requestAttach(name, dream);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        if (DEBUG) Slog.v(TAG, "Service disconnected: " + name);
        // Only happens in exceptional circumstances, awaken just to be safe
        awaken();
    }
    // end ServiceConnection

    private void checkPermission(String permission) {
        if (PackageManager.PERMISSION_GRANTED != mContext.checkCallingOrSelfPermission(permission)) {
            throw new SecurityException("Access denied to process: " + Binder.getCallingPid()
                    + ", must have permission " + permission);
        }
    }

    private void setDreamingLocked(boolean isDreaming, boolean isTest) {
        boolean wasDreaming = mIsDreaming;
        if (!isTest) {
            if (!wasDreaming && isDreaming) {
                if (DEBUG) Slog.v(TAG, "Firing ACTION_DREAMING_STARTED");
                mContext.sendBroadcast(mDreamingStartedIntent);
            } else if (wasDreaming && !isDreaming) {
                if (DEBUG) Slog.v(TAG, "Firing ACTION_DREAMING_STOPPED");
                mContext.sendBroadcast(mDreamingStoppedIntent);
            }
        }
        mIsDreaming = isDreaming;
    }

    private ComponentName[] getDreamComponentsForUser(int userId) {
        String names = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                SCREENSAVER_COMPONENTS,
                userId);
        return names == null ? null : componentsFromString(names);
    }

    private static String componentsToString(ComponentName[] componentNames) {
        StringBuilder names = new StringBuilder();
        if (componentNames != null) {
            for (ComponentName componentName : componentNames) {
                if (names.length() > 0) {
                    names.append(',');
                }
                names.append(componentName.flattenToString());
            }
        }
        return names.toString();
    }

    private static ComponentName[] componentsFromString(String names) {
        String[] namesArray = names.split(",");
        ComponentName[] componentNames = new ComponentName[namesArray.length];
        for (int i = 0; i < namesArray.length; i++) {
            componentNames[i] = ComponentName.unflattenFromString(namesArray[i]);
        }
        return componentNames;
    }

    /**
     * Keeps track of the current user, since dream() uses the current user's configuration.
     */
    private static class CurrentUserManager {
        private final Object mLock = new Object();
        private int mCurrentUserId;

        public void init(Context context) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_USER_SWITCHED);
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                        synchronized(mLock) {
                            mCurrentUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                            if (DEBUG) Slog.v(TAG, "userId " + mCurrentUserId + " is in the house");
                        }
                    }
                }}, filter);
            try {
                synchronized (mLock) {
                    mCurrentUserId = ActivityManagerNative.getDefault().getCurrentUser().id;
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Couldn't get current user ID; guessing it's 0", e);
            }
        }

        public void dump(PrintWriter pw) {
            pw.print("  user="); pw.println(getCurrentUserId());
        }

        public int getCurrentUserId() {
            synchronized(mLock) {
                return mCurrentUserId;
            }
        }
    }

    /**
     * Handler for asynchronous operations performed by the dream manager.
     *
     * Ensures operations to {@link DreamController} are single-threaded.
     */
    private static final class DreamControllerHandler extends Handler {
        private final DreamController mController;
        private final Runnable mStopRunnable = new Runnable() {
            @Override
            public void run() {
                mController.stop();
            }};

        public DreamControllerHandler(DreamController controller) {
            super(true /*async*/);
            mController = controller;
        }

        public void requestStart(final ComponentName name, final boolean isTest) {
            post(new Runnable(){
                @Override
                public void run() {
                    mController.start(name, isTest);
                }});
        }

        public void requestAttach(final ComponentName name, final IBinder dream) {
            post(new Runnable(){
                @Override
                public void run() {
                    mController.attach(name, dream);
                }});
        }

        public void requestStopSelf(final IBinder token) {
            post(new Runnable(){
                @Override
                public void run() {
                    mController.stopSelf(token);
                }});
        }

        public void requestStop() {
            post(mStopRunnable);
        }

    }

}
