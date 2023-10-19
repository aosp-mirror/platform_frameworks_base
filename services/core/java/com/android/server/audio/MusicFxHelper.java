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
 * .
 */
public class MusicFxHelper {
    private static final String TAG = "AS.MusicFxHelper";

    @NonNull private final Context mContext;

    @NonNull private final AudioHandler mAudioHandler;

    // Synchronization UidSessionMap access between UidObserver and AudioServiceBroadcastReceiver.
    private final Object mClientUidMapLock = new Object();

    // The binder token identifying the UidObserver registration.
    private IBinder mUidObserverToken = null;

    // Hashmap of UID and list of open sessions for this UID.
    @GuardedBy("mClientUidMapLock")
    private SparseArray<List<Integer>> mClientUidSessionMap = new SparseArray<>();

    /*package*/ static final int MSG_EFFECT_CLIENT_GONE = MUSICFX_HELPER_MSG_START + 1;

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
     * If the intent is {@link #ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION}:
     *  - If the MusicFx process is not running, call bindService with AUTO_CREATE to create.
     *  - If this is the first audio session in MusicFx, call set foreground service delegate.
     *  - If this is the first audio session for a given UID, add the UID into observer.
     *
     * If the intent is {@link #ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION}:
     *  - MusicFx will not be foreground delegated anymore.
     *  - The KeepAliveService of MusicFx will be unbound.
     *  - The UidObserver will be removed.
     */
    public void handleAudioEffectBroadcast(Context context, Intent intent) {
        String target = intent.getPackage();
        if (target != null) {
            Log.w(TAG, "effect broadcast already targeted to " + target);
            return;
        }
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        final PackageManager pm = context.getPackageManager();
        // TODO this should target a user-selected panel
        List<ResolveInfo> ril = pm.queryBroadcastReceivers(intent, 0 /* flags */);
        if (ril != null && ril.size() != 0) {
            ResolveInfo ri = ril.get(0);
            final String senderPackageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME);
            try {
                final int senderUid = pm.getPackageUidAsUser(senderPackageName,
                        PackageManager.PackageInfoFlags.of(MATCH_ANY_USER), getCurrentUserId());
                if (ri != null && ri.activityInfo != null && ri.activityInfo.packageName != null) {
                    intent.setPackage(ri.activityInfo.packageName);
                    synchronized (mClientUidMapLock) {
                        setMusicFxServiceWithObserver(context, intent, senderUid);
                    }
                    context.sendBroadcastAsUser(intent, UserHandle.ALL);
                    return;
                }
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Not able to find UID from package: " + senderPackageName + " error: "
                        + e);
            }
        }
        Log.w(TAG, "couldn't find receiver package for effect intent");
    }

    /**
     * Handle the UidObserver onUidGone callback of MusicFx clients.
     * All open audio sessions of this UID will be closed.
     * If this is the last UID for MusicFx:
     *  - MusicFx will not be foreground delegated anymore.
     *  - The KeepAliveService of MusicFx will be unbound.
     *  - The UidObserver will be removed.
     */
    public void handleEffectClientUidGone(int uid) {
        synchronized (mClientUidMapLock) {
            Log.w(TAG, " inside handle MSG_EFFECT_CLIENT_GONE");
            // Once the uid is no longer running, close all remain audio session(s) for this UID
            if (mClientUidSessionMap.get(Integer.valueOf(uid)) != null) {
                final List<Integer> sessions =
                        new ArrayList(mClientUidSessionMap.get(Integer.valueOf(uid)));
                Log.i(TAG, "UID " + uid + " gone, closing " + sessions.size() + " sessions");
                for (Integer session : sessions) {
                    Intent intent = new Intent(
                            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
                    intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session);
                    setMusicFxServiceWithObserver(mContext, intent, uid);
                    Log.i(TAG, "Close session " + session + " of UID " + uid);
                }
                mClientUidSessionMap.remove(Integer.valueOf(uid));
            }
        }
    }

    @GuardedBy("mClientUidMapLock")
    private void setMusicFxServiceWithObserver(Context context, Intent intent, int senderUid) {
        PackageManager pm = context.getPackageManager();
        try {
            final int audioSession = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (AudioManager.AUDIO_SESSION_ID_GENERATE == audioSession) {
                Log.e(TAG, "Intent missing audio session: " + audioSession);
                return;
            }

            // only apply to com.android.musicfx and KeepAliveService for now
            final String musicFxPackageName = "com.android.musicfx";
            final String musicFxKeepAliveService = "com.android.musicfx.KeepAliveService";
            final int musicFxUid = pm.getPackageUidAsUser(musicFxPackageName,
                    PackageManager.PackageInfoFlags.of(MATCH_ANY_USER), getCurrentUserId());

            if (intent.getAction().equals(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)) {
                List<Integer> sessions = new ArrayList<>();
                Log.d(TAG, "UID " + senderUid + ", open MusicFx session " + audioSession);
                // start foreground service delegate and register UID observer with the first
                // session of first UID open
                if (0 == mClientUidSessionMap.size()) {
                    final int procState = ActivityManager.getService().getPackageProcessState(
                            musicFxPackageName, this.getClass().getPackage().getName());
                    // if musicfx process not in binding state, call bindService with AUTO_CREATE
                    if (procState > ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND) {
                        Intent bindIntent = new Intent().setClassName(musicFxPackageName,
                                musicFxKeepAliveService);
                        context.bindServiceAsUser(
                                bindIntent, mMusicFxBindConnection, Context.BIND_AUTO_CREATE,
                                UserHandle.of(getCurrentUserId()));
                        Log.i(TAG, "bindService to " + musicFxPackageName);
                    }

                    Log.i(TAG, "Package " + musicFxPackageName + " uid " + musicFxUid
                            + " procState " + procState);
                } else if (mClientUidSessionMap.get(Integer.valueOf(senderUid)) != null) {
                    sessions = mClientUidSessionMap.get(Integer.valueOf(senderUid));
                    if (sessions.contains(audioSession)) {
                        Log.e(TAG, "Audio session " + audioSession + " already exist for UID "
                                + senderUid + ", abort");
                        return;
                    }
                }
                // first session of this UID
                if (sessions.size() == 0) {
                    // call registerUidObserverForUids with the first UID and first session
                    if (mClientUidSessionMap.size() == 0 || mUidObserverToken == null) {
                        mUidObserverToken = ActivityManager.getService().registerUidObserverForUids(
                                mEffectUidObserver, ActivityManager.UID_OBSERVER_GONE,
                                ActivityManager.PROCESS_STATE_UNKNOWN, null, new int[]{senderUid});
                        Log.i(TAG, "UID " + senderUid + " registered to observer");
                    } else {
                        // add UID to observer for each new UID
                        ActivityManager.getService().addUidToObserver(mUidObserverToken, TAG,
                                senderUid);
                        Log.i(TAG, "UID " + senderUid + " addeded to observer");
                    }
                }

                sessions.add(Integer.valueOf(audioSession));
                mClientUidSessionMap.put(Integer.valueOf(senderUid), sessions);
            } else {
                if (mClientUidSessionMap.get(senderUid) != null) {
                    Log.d(TAG, "UID " + senderUid + ", close MusicFx session " + audioSession);
                    List<Integer> sessions = mClientUidSessionMap.get(Integer.valueOf(senderUid));
                    sessions.remove(Integer.valueOf(audioSession));
                    if (0 == sessions.size()) {
                        mClientUidSessionMap.remove(Integer.valueOf(senderUid));
                    } else {
                        mClientUidSessionMap.put(Integer.valueOf(senderUid), sessions);
                    }

                    // stop foreground service delegate and unregister UID observer with the
                    // last session of last UID close
                    if (0 == mClientUidSessionMap.size()) {
                        ActivityManager.getService().unregisterUidObserver(mEffectUidObserver);
                        mClientUidSessionMap.clear();
                        context.unbindService(mMusicFxBindConnection);
                        Log.i(TAG, " remove all sessions, unregister UID observer, and unbind "
                                + musicFxPackageName);
                    }
                } else {
                    // if the audio session already closed, print an error
                    Log.e(TAG, "UID " + senderUid + " close audio session " + audioSession
                            + " which does not exist");
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Not able to find UID from package: " + e);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException " + e + " with handling intent");
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
