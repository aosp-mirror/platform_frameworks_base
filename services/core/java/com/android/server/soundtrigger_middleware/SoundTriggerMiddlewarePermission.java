/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.soundtrigger_middleware;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.PermissionChecker;
import android.media.soundtrigger_middleware.ISoundTriggerCallback;
import android.media.soundtrigger_middleware.ISoundTriggerMiddlewareService;
import android.media.soundtrigger_middleware.ISoundTriggerModule;
import android.media.soundtrigger_middleware.ModelParameterRange;
import android.media.soundtrigger_middleware.PhraseRecognitionEvent;
import android.media.soundtrigger_middleware.PhraseSoundModel;
import android.media.soundtrigger_middleware.RecognitionConfig;
import android.media.soundtrigger_middleware.RecognitionEvent;
import android.media.soundtrigger_middleware.SoundModel;
import android.media.soundtrigger_middleware.SoundTriggerModuleDescriptor;
import android.media.soundtrigger_middleware.Status;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * This is a decorator of an {@link ISoundTriggerMiddlewareService}, which enforces permissions.
 * <p>
 * Every public method in this class, overriding an interface method, must follow the following
 * pattern:
 * <code><pre>
 * @Override public T method(S arg) {
 *     // Permission check.
 *     checkPermissions();
 *     return mDelegate.method(arg);
 * }
 * </pre></code>
 *
 * {@hide}
 */
public class SoundTriggerMiddlewarePermission implements ISoundTriggerMiddlewareInternal, Dumpable {
    private static final String TAG = "SoundTriggerMiddlewarePermission";

    private final @NonNull ISoundTriggerMiddlewareInternal mDelegate;
    private final @NonNull Context mContext;

    public SoundTriggerMiddlewarePermission(
            @NonNull ISoundTriggerMiddlewareInternal delegate, @NonNull Context context) {
        mDelegate = delegate;
        mContext = context;
    }

    @Override
    public @NonNull
    SoundTriggerModuleDescriptor[] listModules() throws RemoteException {
        checkPermissions();
        return mDelegate.listModules();
    }

    @Override
    public @NonNull
    ISoundTriggerModule attach(int handle,
            @NonNull ISoundTriggerCallback callback) throws RemoteException {
        checkPermissions();
        return new ModuleWrapper(
                mDelegate.attach(handle, new CallbackWrapper(callback)));
    }

    @Override
    public void setCaptureState(boolean active) throws RemoteException {
        // This is an internal call. No permissions needed.
        mDelegate.setCaptureState(active);
    }

    // Override toString() in order to have the delegate's ID in it.
    @Override
    public String toString() {
        return mDelegate.toString();
    }

    @Override
    public IBinder asBinder() {
        throw new UnsupportedOperationException(
                "This implementation is not inteded to be used directly with Binder.");
    }

    /**
     * Throws a {@link SecurityException} if caller permanently doesn't have the given permission,
     * or a {@link ServiceSpecificException} with a {@link Status#TEMPORARY_PERMISSION_DENIED} if
     * caller temporarily doesn't have the right permissions to use this service.
     */
    private void checkPermissions() {
        enforcePermission(Manifest.permission.RECORD_AUDIO);
        enforcePermission(Manifest.permission.CAPTURE_AUDIO_HOTWORD);
    }

    /**
     * Throws a {@link SecurityException} if caller permanently doesn't have the given permission,
     * or a {@link ServiceSpecificException} with a {@link Status#TEMPORARY_PERMISSION_DENIED} if
     * caller temporarily doesn't have the given permission.
     *
     * @param permission The permission to check.
     */
    private void enforcePermission(String permission) {
        final int status = PermissionChecker.checkCallingOrSelfPermissionForPreflight(mContext,
                permission);
        switch (status) {
            case PermissionChecker.PERMISSION_GRANTED:
                return;
            case PermissionChecker.PERMISSION_HARD_DENIED:
                throw new SecurityException(
                        String.format("Caller must have the %s permission.", permission));
            case PermissionChecker.PERMISSION_SOFT_DENIED:
                throw new ServiceSpecificException(Status.TEMPORARY_PERMISSION_DENIED,
                        String.format("Caller must have the %s permission.", permission));
            default:
                throw new RuntimeException("Unexpected perimission check result.");
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        if (mDelegate instanceof Dumpable) {
            ((Dumpable) mDelegate).dump(pw);
        }
    }

    /**
     * A wrapper around an {@link ISoundTriggerModule} implementation, to address the same aspects
     * mentioned in {@link SoundTriggerModule} above. This class follows the same conventions.
     */
    private class ModuleWrapper extends ISoundTriggerModule.Stub {
        private final ISoundTriggerModule mDelegate;

        ModuleWrapper(@NonNull ISoundTriggerModule delegate) {
            mDelegate = delegate;
        }

        @Override
        public int loadModel(@NonNull SoundModel model) throws RemoteException {
            checkPermissions();
            return mDelegate.loadModel(model);
        }

        @Override
        public int loadPhraseModel(@NonNull PhraseSoundModel model) throws RemoteException {
            checkPermissions();
            return mDelegate.loadPhraseModel(model);
        }

        @Override
        public void unloadModel(int modelHandle) throws RemoteException {
            checkPermissions();
            mDelegate.unloadModel(modelHandle);

        }

        @Override
        public void startRecognition(int modelHandle, @NonNull RecognitionConfig config)
                throws RemoteException {
            checkPermissions();
            mDelegate.startRecognition(modelHandle, config);
        }

        @Override
        public void stopRecognition(int modelHandle) throws RemoteException {
            checkPermissions();
            mDelegate.stopRecognition(modelHandle);
        }

        @Override
        public void forceRecognitionEvent(int modelHandle) throws RemoteException {
            checkPermissions();
            mDelegate.forceRecognitionEvent(modelHandle);
        }

        @Override
        public void setModelParameter(int modelHandle, int modelParam, int value)
                throws RemoteException {
            checkPermissions();
            mDelegate.setModelParameter(modelHandle, modelParam, value);
        }

        @Override
        public int getModelParameter(int modelHandle, int modelParam) throws RemoteException {
            checkPermissions();
            return mDelegate.getModelParameter(modelHandle, modelParam);
        }

        @Override
        @Nullable
        public ModelParameterRange queryModelParameterSupport(int modelHandle, int modelParam)
                throws RemoteException {
            checkPermissions();
            return mDelegate.queryModelParameterSupport(modelHandle,
                    modelParam);
        }

        @Override
        public void detach() throws RemoteException {
            checkPermissions();
            mDelegate.detach();
        }

        // Override toString() in order to have the delegate's ID in it.
        @Override
        public String toString() {
            return Objects.toString(mDelegate);
        }
    }

    private class CallbackWrapper implements ISoundTriggerCallback {
        private final ISoundTriggerCallback mDelegate;

        private CallbackWrapper(ISoundTriggerCallback delegate) {
            mDelegate = delegate;
        }

        @Override
        public void onRecognition(int modelHandle, RecognitionEvent event) throws RemoteException {
            mDelegate.onRecognition(modelHandle, event);
        }

        @Override
        public void onPhraseRecognition(int modelHandle, PhraseRecognitionEvent event)
                throws RemoteException {
            mDelegate.onPhraseRecognition(modelHandle, event);
        }

        @Override
        public void onRecognitionAvailabilityChange(boolean available) throws RemoteException {
            mDelegate.onRecognitionAvailabilityChange(available);
        }

        @Override
        public void onModuleDied() throws RemoteException {
            mDelegate.onModuleDied();
        }

        @Override
        public IBinder asBinder() {
            return mDelegate.asBinder();
        }

        // Override toString() in order to have the delegate's ID in it.
        @Override
        public String toString() {
            return Objects.toString(mDelegate);
        }
    }
}
