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

import static android.Manifest.permission.CAPTURE_AUDIO_HOTWORD;
import static android.Manifest.permission.RECORD_AUDIO;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.PermissionChecker;
import android.media.soundtrigger.ModelParameterRange;
import android.media.soundtrigger.PhraseRecognitionEvent;
import android.media.soundtrigger.PhraseSoundModel;
import android.media.soundtrigger.RecognitionConfig;
import android.media.soundtrigger.RecognitionEvent;
import android.media.soundtrigger.SoundModel;
import android.media.soundtrigger.Status;
import android.media.permission.Identity;
import android.media.permission.IdentityContext;
import android.media.permission.PermissionUtil;
import android.media.soundtrigger_middleware.ISoundTriggerCallback;
import android.media.soundtrigger_middleware.ISoundTriggerMiddlewareService;
import android.media.soundtrigger_middleware.ISoundTriggerModule;
import android.media.soundtrigger_middleware.SoundTriggerModuleDescriptor;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * This is a decorator of an {@link ISoundTriggerMiddlewareService}, which enforces permissions.
 * <p>
 * Every public method in this class, overriding an interface method, must follow a similar
 * pattern:
 * <code><pre>
 * @Override public T method(S arg) {
 *     // Permission check.
 *     enforcePermissions*(...);
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
    SoundTriggerModuleDescriptor[] listModules() {
        Identity identity = getIdentity();
        enforcePermissionsForPreflight(identity);
        return mDelegate.listModules();
    }

    @Override
    public @NonNull
    ISoundTriggerModule attach(int handle,
            @NonNull ISoundTriggerCallback callback) {
        Identity identity = getIdentity();
        enforcePermissionsForPreflight(identity);
        ModuleWrapper wrapper = new ModuleWrapper(identity, callback);
        return wrapper.attach(mDelegate.attach(handle, wrapper.getCallbackWrapper()));
    }

    // Override toString() in order to have the delegate's ID in it.
    @Override
    public String toString() {
        return Objects.toString(mDelegate);
    }

    /**
     * Get the identity context, or throws an InternalServerError if it has not been established.
     *
     * @return The identity.
     */
    private static @NonNull
    Identity getIdentity() {
        return IdentityContext.getNonNull();
    }

    /**
     * Throws a {@link SecurityException} if originator permanently doesn't have the given
     * permission,
     * or a {@link ServiceSpecificException} with a {@link Status#TEMPORARY_PERMISSION_DENIED} if
     * originator temporarily doesn't have the right permissions to use this service.
     */
    private void enforcePermissionsForPreflight(@NonNull Identity identity) {
        enforcePermissionForPreflight(mContext, identity, RECORD_AUDIO);
        enforcePermissionForPreflight(mContext, identity, CAPTURE_AUDIO_HOTWORD);
    }

    /**
     * Throws a {@link SecurityException} iff the originator has permission to receive data.
     */
    void enforcePermissionsForDataDelivery(@NonNull Identity identity, @NonNull String reason) {
        enforcePermissionForDataDelivery(mContext, identity, RECORD_AUDIO, reason);
        enforcePermissionForDataDelivery(mContext, identity, CAPTURE_AUDIO_HOTWORD,
                reason);
    }

    /**
     * Throws a {@link SecurityException} iff the given identity has given permission to receive
     * data.
     *
     * @param context    A {@link Context}, used for permission checks.
     * @param identity   The identity to check.
     * @param permission The identifier of the permission we want to check.
     * @param reason     The reason why we're requesting the permission, for auditing purposes.
     */
    private static void enforcePermissionForDataDelivery(@NonNull Context context,
            @NonNull Identity identity,
            @NonNull String permission, @NonNull String reason) {
        final int status = PermissionUtil.checkPermissionForDataDelivery(context, identity,
                permission, reason);
        if (status != PermissionChecker.PERMISSION_GRANTED) {
            throw new SecurityException(
                    String.format("Failed to obtain permission %s for identity %s", permission,
                            ObjectPrinter.print(identity, true, 16)));
        }
    }

    /**
     * Throws a {@link SecurityException} if originator permanently doesn't have the given
     * permission, or a {@link ServiceSpecificException} with a {@link
     * Status#TEMPORARY_PERMISSION_DENIED} if caller originator doesn't have the given permission.
     *
     * @param context    A {@link Context}, used for permission checks.
     * @param identity   The identity to check.
     * @param permission The identifier of the permission we want to check.
     */
    private static void enforcePermissionForPreflight(@NonNull Context context,
            @NonNull Identity identity, @NonNull String permission) {
        final int status = PermissionUtil.checkPermissionForPreflight(context, identity,
                permission);
        switch (status) {
            case PermissionChecker.PERMISSION_GRANTED:
                return;
            case PermissionChecker.PERMISSION_HARD_DENIED:
                throw new SecurityException(
                        String.format("Failed to obtain permission %s for identity %s", permission,
                                ObjectPrinter.print(identity, true, 16)));
            case PermissionChecker.PERMISSION_SOFT_DENIED:
                throw new ServiceSpecificException(Status.TEMPORARY_PERMISSION_DENIED,
                        String.format("Failed to obtain permission %s for identity %s", permission,
                                ObjectPrinter.print(identity, true, 16)));
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
        private ISoundTriggerModule mDelegate;
        private final @NonNull Identity mOriginatorIdentity;
        private final @NonNull CallbackWrapper mCallbackWrapper;

        ModuleWrapper(@NonNull Identity originatorIdentity,
                @NonNull ISoundTriggerCallback callback) {
            mOriginatorIdentity = originatorIdentity;
            mCallbackWrapper = new CallbackWrapper(callback);
        }

        ModuleWrapper attach(@NonNull ISoundTriggerModule delegate) {
            mDelegate = delegate;
            return this;
        }

        ISoundTriggerCallback getCallbackWrapper() {
            return mCallbackWrapper;
        }

        @Override
        public int loadModel(@NonNull SoundModel model) throws RemoteException {
            enforcePermissions();
            return mDelegate.loadModel(model);
        }

        @Override
        public int loadPhraseModel(@NonNull PhraseSoundModel model) throws RemoteException {
            enforcePermissions();
            return mDelegate.loadPhraseModel(model);
        }

        @Override
        public void unloadModel(int modelHandle) throws RemoteException {
            // Unloading a model does not require special permissions. Having a handle to the
            // session is sufficient.
            mDelegate.unloadModel(modelHandle);

        }

        @Override
        public void startRecognition(int modelHandle, @NonNull RecognitionConfig config)
                throws RemoteException {
            enforcePermissions();
            mDelegate.startRecognition(modelHandle, config);
        }

        @Override
        public void stopRecognition(int modelHandle) throws RemoteException {
            // Stopping a model does not require special permissions. Having a handle to the
            // session is sufficient.
            mDelegate.stopRecognition(modelHandle);
        }

        @Override
        public void forceRecognitionEvent(int modelHandle) throws RemoteException {
            enforcePermissions();
            mDelegate.forceRecognitionEvent(modelHandle);
        }

        @Override
        public void setModelParameter(int modelHandle, int modelParam, int value)
                throws RemoteException {
            enforcePermissions();
            mDelegate.setModelParameter(modelHandle, modelParam, value);
        }

        @Override
        public int getModelParameter(int modelHandle, int modelParam) throws RemoteException {
            enforcePermissions();
            return mDelegate.getModelParameter(modelHandle, modelParam);
        }

        @Override
        @Nullable
        public ModelParameterRange queryModelParameterSupport(int modelHandle, int modelParam)
                throws RemoteException {
            enforcePermissions();
            return mDelegate.queryModelParameterSupport(modelHandle,
                    modelParam);
        }

        @Override
        public void detach() throws RemoteException {
            // Detaching does not require special permissions. Having a handle to the session is
            // sufficient.
            mDelegate.detach();
        }

        // Override toString() in order to have the delegate's ID in it.
        @Override
        public String toString() {
            return Objects.toString(mDelegate);
        }

        private void enforcePermissions() {
            enforcePermissionsForPreflight(mOriginatorIdentity);
        }

        private class CallbackWrapper implements ISoundTriggerCallback {
            private final ISoundTriggerCallback mDelegate;

            private CallbackWrapper(ISoundTriggerCallback delegate) {
                mDelegate = delegate;
            }

            @Override
            public void onRecognition(int modelHandle, RecognitionEvent event, int captureSession)
                    throws RemoteException {
                enforcePermissions("Sound trigger recognition.");
                mDelegate.onRecognition(modelHandle, event, captureSession);
            }

            @Override
            public void onPhraseRecognition(int modelHandle, PhraseRecognitionEvent event,
                    int captureSession)
                    throws RemoteException {
                enforcePermissions("Sound trigger phrase recognition.");
                mDelegate.onPhraseRecognition(modelHandle, event, captureSession);
            }

            @Override
            public void onResourcesAvailable() throws RemoteException {
                mDelegate.onResourcesAvailable();
            }

            @Override
            public void onModelUnloaded(int modelHandle) throws RemoteException {
                mDelegate.onModelUnloaded(modelHandle);
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
                return mDelegate.toString();
            }

            private void enforcePermissions(String reason) {
                enforcePermissionsForDataDelivery(mOriginatorIdentity, reason);
            }
        }
    }
}
