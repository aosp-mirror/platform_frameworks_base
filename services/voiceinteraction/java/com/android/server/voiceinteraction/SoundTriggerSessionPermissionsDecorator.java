/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.Manifest.permission.CAPTURE_AUDIO_HOTWORD;
import static android.Manifest.permission.RECORD_AUDIO;

import static com.android.server.voiceinteraction.HotwordDetectionConnection.DEBUG;

import android.annotation.NonNull;
import android.content.Context;
import android.content.PermissionChecker;
import android.hardware.soundtrigger.SoundTrigger;
import android.media.permission.Identity;
import android.media.permission.PermissionUtil;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.app.IHotwordRecognitionStatusCallback;
import com.android.internal.app.IVoiceInteractionSoundTriggerSession;

/**
 * Decorates {@link IVoiceInteractionSoundTriggerSession} with permission checks for {@link
 * android.Manifest.permission#RECORD_AUDIO} and
 * {@link android.Manifest.permission#CAPTURE_AUDIO_HOTWORD}.
 * <p>
 * Does not implement {@link #asBinder()} as it's intended to be wrapped by an
 * {@link IVoiceInteractionSoundTriggerSession.Stub} object.
 */
final class SoundTriggerSessionPermissionsDecorator implements
        IVoiceInteractionSoundTriggerSession {
    static final String TAG = "SoundTriggerSessionPermissionsDecorator";

    private final IVoiceInteractionSoundTriggerSession mDelegate;
    private final Context mContext;
    private final Identity mOriginatorIdentity;

    SoundTriggerSessionPermissionsDecorator(IVoiceInteractionSoundTriggerSession delegate,
            Context context, Identity originatorIdentity) {
        mDelegate = delegate;
        mContext = context;
        mOriginatorIdentity = originatorIdentity;
    }

    @Override
    public SoundTrigger.ModuleProperties getDspModuleProperties() throws RemoteException {
        // No permission needed here (the app must have the Assistant Role to retrieve the session).
        return mDelegate.getDspModuleProperties();
    }

    @Override
    public int startRecognition(int i, String s,
            IHotwordRecognitionStatusCallback iHotwordRecognitionStatusCallback,
            SoundTrigger.RecognitionConfig recognitionConfig, boolean b) throws RemoteException {
        if (DEBUG) {
            Slog.d(TAG, "startRecognition");
        }
        if (!isHoldingPermissions()) {
            return SoundTrigger.STATUS_PERMISSION_DENIED;
        }
        return mDelegate.startRecognition(i, s, iHotwordRecognitionStatusCallback,
                recognitionConfig, b);
    }

    @Override
    public int stopRecognition(int i,
            IHotwordRecognitionStatusCallback iHotwordRecognitionStatusCallback)
            throws RemoteException {
        // Stopping a model does not require special permissions. Having a handle to the session is
        // sufficient.
        return mDelegate.stopRecognition(i, iHotwordRecognitionStatusCallback);
    }

    @Override
    public int setParameter(int i, int i1, int i2) throws RemoteException {
        if (!isHoldingPermissions()) {
            return SoundTrigger.STATUS_PERMISSION_DENIED;
        }
        return mDelegate.setParameter(i, i1, i2);
    }

    @Override
    public int getParameter(int i, int i1) throws RemoteException {
        // No permission needed here (the app must have the Assistant Role to retrieve the session).
        return mDelegate.getParameter(i, i1);
    }

    @Override
    public SoundTrigger.ModelParamRange queryParameter(int i, int i1) throws RemoteException {
        // No permission needed here (the app must have the Assistant Role to retrieve the session).
        return mDelegate.queryParameter(i, i1);
    }

    @Override
    public IBinder asBinder() {
        throw new UnsupportedOperationException(
                "This object isn't intended to be used as a Binder.");
    }

    // TODO: Share this code with SoundTriggerMiddlewarePermission.
    private boolean isHoldingPermissions() {
        try {
            enforcePermissionForPreflight(mContext, mOriginatorIdentity, RECORD_AUDIO);
            enforcePermissionForPreflight(mContext, mOriginatorIdentity, CAPTURE_AUDIO_HOTWORD);
            return true;
        } catch (SecurityException e) {
            Slog.e(TAG, e.toString());
            return false;
        }
    }

    /**
     * Throws a {@link SecurityException} if originator permanently doesn't have the given
     * permission.
     * Soft (temporary) denials are considered OK for preflight purposes.
     *
     * @param context    A {@link Context}, used for permission checks.
     * @param identity   The identity to check.
     * @param permission The identifier of the permission we want to check.
     */
    static void enforcePermissionForPreflight(@NonNull Context context,
            @NonNull Identity identity, @NonNull String permission) {
        final int status = PermissionUtil.checkPermissionForPreflight(context, identity,
                permission);
        switch (status) {
            case PermissionChecker.PERMISSION_GRANTED:
            case PermissionChecker.PERMISSION_SOFT_DENIED:
                return;
            case PermissionChecker.PERMISSION_HARD_DENIED:
                throw new SecurityException(
                        TextUtils.formatSimple("Failed to obtain permission %s for identity %s",
                                permission, toString(identity)));
            default:
                throw new RuntimeException("Unexpected permission check result.");
        }
    }

    static String toString(Identity identity) {
        return "{uid=" + identity.uid
                + " pid=" + identity.pid
                + " packageName=" + identity.packageName
                + " attributionTag=" + identity.attributionTag
                + "}";
    }

    // Temporary hack for using the same status code as SoundTrigger, so we don't change behavior.
    // TODO: Reuse SoundTrigger code so we don't need to do this.
    private static final int TEMPORARY_PERMISSION_DENIED = 3;
}
