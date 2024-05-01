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

package com.android.server.audio;

import static android.content.pm.PackageManager.MATCH_ANY_USER;

import static com.android.server.audio.AudioService.MUSICFX_HELPER_MSG_START;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.app.ActivityManager;
import android.app.IUidObserver;
import android.app.UidObserver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.os.Binder;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.audio.AudioService.AudioHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * MusicFx management.
 */
public class MusicFxHelper {
    private static final String TAG = "AS.MusicFxHelper";

    @NonNull private final Context mContext;

    @NonNull private final AudioHandler mAudioHandler;

    // Synchronization UidSessionMap access between UidObserver and AudioServiceBroadcastReceiver.
    private final Object mClientUidMapLock = new Object();

    private final String mPackageName = this.getClass().getPackage().getName();

    private final String mMusicFxPackageName = "com.android.musicfx";

    /*package*/ static final int MSG_EFFECT_CLIENT_GONE = MUSICFX_HELPER_MSG_START + 1;

    // The binder token identifying the UidObserver registration.
    private IBinder mUidObserverToken = null;

    // Package name and list of open audio sessions for this package
    private static class PackageSessions {
        String mPackageName;
        List<Integer> mSessions;
    }

    /*
     * Override of SparseArray class to add bind/unbind and UID observer in the put/remove methods.
     *
     * put:
     *  - the first key/value set put into MySparseArray will trigger a procState bump (bindService)
     *  - if no valid observer token exist, will call registerUidObserver for put
     *  - for each new uid put into array, it will be added to uid observer list
     *
     * remove:
     *  - for each uid removed from array, it will be removed from uid observer list as well
     *  - if it's the last uid in array, no more MusicFx procState bump (unbindService), uid
     *    observer will also be removed, and observer token reset to null
     */
    private class MySparseArray extends SparseArray<PackageSessions> {

        @RequiresPermission(anyOf = {
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                android.Manifest.permission.INTERACT_ACROSS_USERS,
                android.Manifest.permission.INTERACT_ACROSS_PROFILES
        })
        @Override
        public void put(int uid, PackageSessions pkgSessions) {
            if (size() == 0) {
                int procState = ActivityManager.PROCESS_STATE_NONEXISTENT;
                try {
                    procState = ActivityManager.getService().getPackageProcessState(
                            mMusicFxPackageName, mPackageName);
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException with getPackageProcessState: " + e);
                }
                if (procState > ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND) {
                    Intent bindIntent = new Intent().setClassName(mMusicFxPackageName,
                            "com.android.musicfx.KeepAliveService");
                    mContext.bindServiceAsUser(
                            bindIntent, mMusicFxBindConnection, Context.BIND_AUTO_CREATE,
                            UserHandle.of(getCurrentUserId()));
                    Log.i(TAG, "bindService to " + mMusicFxPackageName);
                }

                Log.i(TAG, mMusicFxPackageName + " procState " + procState);
            }
            try {
                if (mUidObserverToken == null) {
                    mUidObserverToken = ActivityManager.getService().registerUidObserverForUids(
                            mEffectUidObserver, ActivityManager.UID_OBSERVER_GONE,
                            ActivityManager.PROCESS_STATE_UNKNOWN, mPackageName,
                            new int[]{uid});
                    Log.i(TAG, "registered to observer with UID " + uid);
                } else if (get(uid) == null) { // addUidToObserver if this is a new UID
                    ActivityManager.getService().addUidToObserver(mUidObserverToken, mPackageName,
                            uid);
                    Log.i(TAG, " UID " + uid + " add to observer");
                }
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException with UID observer add/register: " + e);
            }

            super.put(uid, pkgSessions);
        }

        @Override
        public void remove(int uid) {
            if (get(uid) != null) {
                try {
                    ActivityManager.getService().removeUidFromObserver(mUidObserverToken,
                            mPackageName, uid);
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException with removeUidFromObserver: " + e);
                }
            }

            super.remove(uid);

            // stop foreground service delegate and unregister UID observers with the last UID
            if (size() == 0) {
                try {
                    ActivityManager.getService().unregisterUidObserver(mEffectUidObserver);
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException with unregisterUidObserver: " + e);
                }
                mUidObserverToken = null;
                mContext.unbindService(mMusicFxBindConnection);
                Log.i(TAG, "last session closed, unregister UID observer, and unbind "
                        + mMusicFxPackageName);
            }
        }
    }

    // Hashmap of UID and list of open sessions for this UID.
    @GuardedBy("mClientUidMapLock")
    private MySparseArray mClientUidSessionMap = new MySparseArray();

    // UID observer for effect MusicFx clients
    private final IUidObserver mEffectUidObserver = new UidObserver() {
        @Override public void onUidGone(int uid, boolean disabled) {
            Log.w(TAG, " send MSG_EFFECT_CLIENT_GONE");
            mAudioHandler.sendMessageAtTime(
                    mAudioHandler.obtainMessage(MSG_EFFECT_CLIENT_GONE,
                            uid /* arg1 */, 0 /* arg2 */,
                            null /* obj */), 0 /* delay */);
        }
    };

    // BindService connection implementation, we don't need any implementation now
    private ServiceConnection mMusicFxBindConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, " service connected to " + name);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, " service disconnected from " + name);
        }
    };

    MusicFxHelper(@NonNull Context context, @NonNull AudioHandler audioHandler) {
        mContext = context;
        mAudioHandler = audioHandler;
    }

    /**
     * Handle the broadcast {@link #ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION} and
     * {@link #ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION} intents.
     *
     * Only intents without target application package {@link android.content.Intent#getPackage}
     * will be handled by the MusicFxHelper, all intents handled and forwarded by MusicFxHelper
     * will have the target application package.
     *
     * If the intent is {@link #ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION}:
     *  - If the MusicFx process is not running, call bindServiceAsUser with AUTO_CREATE to create.
     *  - If this is the first audio session of MusicFx, call set foreground service delegate.
     *  - If this is the first audio session for a given UID, add the UID into observer.
     *
     * If the intent is {@link #ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION}
     *  - The KeepAliveService of MusicFx will be unbound, and MusicFx will not be foreground
     *    delegated anymore if the last session of the last package was closed.
     *  - The Uid Observer will be removed when the last session of a package was closed.
     */
    @RequiresPermission(allOf = {android.Manifest.permission.INTERACT_ACROSS_USERS})
    public void handleAudioEffectBroadcast(Context context, Intent intent) {
        String target = intent.getPackage();
        if (target != null) {
            Log.w(TAG, "effect broadcast already targeted to " + target);
            return;
        }
        final PackageManager pm = context.getPackageManager();
        // TODO this should target a user-selected panel
        List<ResolveInfo> ril = pm.queryBroadcastReceivers(intent, 0 /* flags */);
        if (ril != null && ril.size() != 0) {
            ResolveInfo ri = ril.get(0);
            final String senderPackageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME);
            if (senderPackageName == null) {
                Log.w(TAG, "Intent package name must not be null");
                return;
            }
            try {
                if (ri != null && ri.activityInfo != null && ri.activityInfo.packageName != null) {
                    final int senderUid = pm.getPackageUidAsUser(senderPackageName,
                            PackageManager.PackageInfoFlags.of(MATCH_ANY_USER), getCurrentUserId());
                    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                    intent.setPackage(ri.activityInfo.packageName);
                    if (setMusicFxServiceWithObserver(intent, senderUid, senderPackageName)) {
                        context.sendBroadcastAsUser(intent, UserHandle.ALL);
                    }
                    return;
                }
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Not able to find UID from package: " + senderPackageName + " error: "
                        + e);
            }
        }
        Log.w(TAG, "couldn't find receiver package for effect intent");
    }

    @RequiresPermission(anyOf = {
            android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
            android.Manifest.permission.INTERACT_ACROSS_USERS,
            android.Manifest.permission.INTERACT_ACROSS_PROFILES
    })
    @GuardedBy("mClientUidMapLock")
    private boolean handleAudioEffectSessionOpen(
            int senderUid, String senderPackageName, int sessionId) {
        Log.d(TAG, senderPackageName + " UID " + senderUid + " open MusicFx session " + sessionId);

        PackageSessions pkgSessions = mClientUidSessionMap.get(Integer.valueOf(senderUid));
        if (pkgSessions != null && pkgSessions.mSessions != null) {
            if (pkgSessions.mSessions.contains(sessionId)) {
                Log.e(TAG, "Audio session " + sessionId + " already open for UID: "
                        + senderUid + ", package: " + senderPackageName + ", abort");
                return false;
            }
            if (!pkgSessions.mPackageName.equals(senderPackageName)) {
                Log.w(TAG, "Inconsistency package names for UID open: " + senderUid + " prev: "
                        + pkgSessions.mPackageName + ", now: " + senderPackageName);
                return false;
            }
        } else {
            // first session for this UID, create a new Package/Sessions pair
            pkgSessions = new PackageSessions();
            pkgSessions.mSessions = new ArrayList();
            pkgSessions.mPackageName = senderPackageName;
        }

        pkgSessions.mSessions.add(Integer.valueOf(sessionId));
        mClientUidSessionMap.put(Integer.valueOf(senderUid), pkgSessions);
        return true;
    }

    @RequiresPermission(anyOf = {
            android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
            android.Manifest.permission.INTERACT_ACROSS_USERS,
            android.Manifest.permission.INTERACT_ACROSS_PROFILES
    })
    @GuardedBy("mClientUidMapLock")
    private boolean handleAudioEffectSessionClose(
            int senderUid, String senderPackageName, int sessionId) {
        Log.d(TAG, senderPackageName + " UID " + senderUid + " close MusicFx session " + sessionId);

        PackageSessions pkgSessions = mClientUidSessionMap.get(Integer.valueOf(senderUid));
        if (pkgSessions == null) {
            Log.e(TAG, senderPackageName + " UID " + senderUid + " does not exist in map, abort");
            return false;
        }
        if (!pkgSessions.mPackageName.equals(senderPackageName)) {
            Log.w(TAG, "Inconsistency package names for UID " + senderUid + " close, prev: "
                    + pkgSessions.mPackageName + ", now: " + senderPackageName);
            return false;
        }

        if (pkgSessions.mSessions != null && pkgSessions.mSessions.size() != 0) {
            if (!pkgSessions.mSessions.contains(sessionId)) {
                Log.e(TAG, senderPackageName + " UID " + senderUid + " session " + sessionId
                        + " does not exist in map, abort");
                return false;
            }

            pkgSessions.mSessions.remove(Integer.valueOf(sessionId));
        }

        if (pkgSessions.mSessions == null || pkgSessions.mSessions.size() == 0) {
            // remove UID from map as well as the UID observer with the last session close
            mClientUidSessionMap.remove(Integer.valueOf(senderUid));
        } else {
            mClientUidSessionMap.put(Integer.valueOf(senderUid), pkgSessions);
        }

        return true;
    }

    /**
     * @return true if the intent is validated and handled successfully, false with any error
     * (invalid sender/intent for example).
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
            android.Manifest.permission.INTERACT_ACROSS_USERS,
            android.Manifest.permission.INTERACT_ACROSS_PROFILES
    })
    private boolean setMusicFxServiceWithObserver(
            Intent intent, int senderUid, String packageName) {
        final int session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION,
                AudioManager.AUDIO_SESSION_ID_GENERATE);
        if (AudioManager.AUDIO_SESSION_ID_GENERATE == session) {
            Log.e(TAG, packageName + " intent have no invalid audio session");
            return false;
        }

        synchronized (mClientUidMapLock) {
            if (intent.getAction().equals(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)) {
                return handleAudioEffectSessionOpen(senderUid, packageName, session);
            } else {
                return handleAudioEffectSessionClose(senderUid, packageName, session);
            }
        }
    }

    private int getCurrentUserId() {
        final long ident = Binder.clearCallingIdentity();
        try {
            UserInfo currentUser = ActivityManager.getService().getCurrentUser();
            return currentUser.id;
        } catch (RemoteException e) {
            // Activity manager not running, nothing we can do assume user 0.
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return UserHandle.USER_SYSTEM;
    }


    /**
     * Handle the UidObserver onUidGone callback of MusicFx clients.
     * Send close intent for all open audio sessions of this UID. The mClientUidSessionMap will be
     * updated with the handling of close intent in setMusicFxServiceWithObserver.
     */
    @RequiresPermission(allOf = {android.Manifest.permission.INTERACT_ACROSS_USERS})
    private void handleEffectClientUidGone(int uid) {
        synchronized (mClientUidMapLock) {
            Log.d(TAG, "handle MSG_EFFECT_CLIENT_GONE uid: " + uid + " mapSize: "
                    + mClientUidSessionMap.size());
            // Once the uid is no longer running, close all remain audio session(s) for this UID
            final PackageSessions pkgSessions = mClientUidSessionMap.get(Integer.valueOf(uid));
            if (pkgSessions != null) {
                Log.i(TAG, "UID " + uid + " gone, closing all sessions");

                // send close intent for each open session of the gone UID
                for (Integer sessionId : pkgSessions.mSessions) {
                    Intent closeIntent =
                            new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
                    closeIntent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, pkgSessions.mPackageName);
                    closeIntent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId);
                    closeIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                    // set broadcast target
                    closeIntent.setPackage(mMusicFxPackageName);
                    mContext.sendBroadcastAsUser(closeIntent, UserHandle.ALL);
                }
                mClientUidSessionMap.remove(Integer.valueOf(uid));
            }
        }
    }

    @RequiresPermission(allOf = {android.Manifest.permission.INTERACT_ACROSS_USERS})
    /*package*/ void handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_EFFECT_CLIENT_GONE:
                Log.w(TAG, " handle MSG_EFFECT_CLIENT_GONE");
                handleEffectClientUidGone(msg.arg1 /* uid */);
                break;
            default:
                Log.e(TAG, "Unexpected msg to handle in MusicFxHelper: " + msg.what);
                break;
        }
    }
}
