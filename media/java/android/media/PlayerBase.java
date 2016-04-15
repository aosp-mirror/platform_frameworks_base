/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.media;

import java.lang.IllegalArgumentException;

import android.annotation.NonNull;
import android.app.ActivityThread;
import android.app.AppOpsManager;
import android.content.Context;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsService;

/**
 * Class to encapsulate a number of common player operations:
 *   - AppOps for OP_PLAY_AUDIO
 *   - more to come (routing, transport control)
 * @hide
 */
public abstract class PlayerBase {

    // parameters of the player that affect AppOps
    protected AudioAttributes mAttributes;
    protected float mLeftVolume = 1.0f;
    protected float mRightVolume = 1.0f;
    protected float mAuxEffectSendLevel = 0.0f;

    // for AppOps
    private final IAppOpsService mAppOps;
    private final IAppOpsCallback mAppOpsCallback;
    private boolean mHasAppOpsPlayAudio = true;
    private final Object mAppOpsLock = new Object();


    /**
     * Constructor. Must be given audio attributes, as they are required for AppOps.
     * @param attr non-null audio attributes
     */
    PlayerBase(@NonNull AudioAttributes attr) {
        if (attr == null) {
            throw new IllegalArgumentException("Illegal null AudioAttributes");
        }
        mAttributes = attr;
        IBinder b = ServiceManager.getService(Context.APP_OPS_SERVICE);
        mAppOps = IAppOpsService.Stub.asInterface(b);
        // initialize mHasAppOpsPlayAudio
        updateAppOpsPlayAudio_sync();
        // register a callback to monitor whether the OP_PLAY_AUDIO is still allowed
        mAppOpsCallback = new IAppOpsCallback.Stub() {
            public void opChanged(int op, int uid, String packageName) {
                synchronized (mAppOpsLock) {
                    if (op == AppOpsManager.OP_PLAY_AUDIO) {
                        updateAppOpsPlayAudio_sync();
                    }
                }
            }
        };
        try {
            mAppOps.startWatchingMode(AppOpsManager.OP_PLAY_AUDIO,
                    ActivityThread.currentPackageName(), mAppOpsCallback);
        } catch (RemoteException e) {
            mHasAppOpsPlayAudio = false;
        }
    }


    /**
     * To be called whenever the audio attributes of the player change
     * @param attr non-null audio attributes
     */
    void baseUpdateAudioAttributes(@NonNull AudioAttributes attr) {
        if (attr == null) {
            throw new IllegalArgumentException("Illegal null AudioAttributes");
        }
        synchronized (mAppOpsLock) {
            mAttributes = attr;
            updateAppOpsPlayAudio_sync();
        }
    }

    void baseStart() {
        synchronized (mAppOpsLock) {
            if (isRestricted_sync()) {
                playerSetVolume(0, 0);
            }
        }
    }

    void baseSetVolume(float leftVolume, float rightVolume) {
        synchronized (mAppOpsLock) {
            mLeftVolume = leftVolume;
            mRightVolume = rightVolume;
            if (isRestricted_sync()) {
                return;
            }
        }
        playerSetVolume(leftVolume, rightVolume);
    }

    int baseSetAuxEffectSendLevel(float level) {
        synchronized (mAppOpsLock) {
            mAuxEffectSendLevel = level;
            if (isRestricted_sync()) {
                return AudioSystem.SUCCESS;
            }
        }
        return playerSetAuxEffectSendLevel(level);
    }

    /**
     * To be called from a subclass release or finalize method.
     * Releases AppOps related resources.
     */
    void baseRelease() {
        try {
            mAppOps.stopWatchingMode(mAppOpsCallback);
        } catch (RemoteException e) {
            // nothing to do here, the object is supposed to be released anyway
        }
    }

    /**
     * To be called whenever a condition that might affect audibility of this player is updated.
     * Must be called synchronized on mAppOpsLock.
     */
    void updateAppOpsPlayAudio_sync() {
        boolean oldHasAppOpsPlayAudio = mHasAppOpsPlayAudio;
        try {
            final int mode = mAppOps.checkAudioOperation(AppOpsManager.OP_PLAY_AUDIO,
                    mAttributes.getUsage(),
                    Process.myUid(), ActivityThread.currentPackageName());
            mHasAppOpsPlayAudio = (mode == AppOpsManager.MODE_ALLOWED);
        } catch (RemoteException e) {
            mHasAppOpsPlayAudio = false;
        }

        // AppsOps alters a player's volume; when the restriction changes, reflect it on the actual
        // volume used by the player
        try {
            if (oldHasAppOpsPlayAudio != mHasAppOpsPlayAudio) {
                if (mHasAppOpsPlayAudio) {
                    playerSetVolume(mLeftVolume, mRightVolume);
                    playerSetAuxEffectSendLevel(mAuxEffectSendLevel);
                } else {
                    playerSetVolume(0.0f, 0.0f);
                    playerSetAuxEffectSendLevel(0.0f);
                }
            }
        } catch (Exception e) {
            // failing silently, player might not be in right state
        }
    }


    /**
     * To be called by the subclass whenever an operation is potentially restricted.
     * As the media player-common behavior are incorporated into this class, the subclass's need
     * to call this method should be removed, and this method could become private.
     * FIXME can this method be private so subclasses don't have to worry about when to check
     *    the restrictions.
     * @return
     */
    boolean isRestricted_sync() {
        if ((mAttributes.getAllFlags() & AudioAttributes.FLAG_BYPASS_INTERRUPTION_POLICY) != 0) {
            return false;
        }
        return !mHasAppOpsPlayAudio;
    }

    // Abstract methods a subclass needs to implement
    abstract void playerSetVolume(float leftVolume, float rightVolume);
    abstract int playerSetAuxEffectSendLevel(float level);
}
