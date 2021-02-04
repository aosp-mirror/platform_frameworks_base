/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.Manifest.permission.SOUNDTRIGGER_DELEGATE_IDENTITY;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.soundtrigger.V2_0.ISoundTriggerHw;
import android.media.permission.ClearCallingIdentityContext;
import android.media.permission.Identity;
import android.media.permission.PermissionUtil;
import android.media.permission.SafeCloseable;
import android.media.soundtrigger_middleware.ISoundTriggerCallback;
import android.media.soundtrigger_middleware.ISoundTriggerMiddlewareService;
import android.media.soundtrigger_middleware.ISoundTriggerModule;
import android.media.soundtrigger_middleware.ModelParameterRange;
import android.media.soundtrigger_middleware.PhraseSoundModel;
import android.media.soundtrigger_middleware.RecognitionConfig;
import android.media.soundtrigger_middleware.SoundModel;
import android.media.soundtrigger_middleware.SoundTriggerModuleDescriptor;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;

import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Objects;

/**
 * This is a wrapper around an {@link ISoundTriggerMiddlewareService} implementation, which exposes
 * it as a Binder service.
 * <p>
 * This is intended to facilitate a pattern of decorating the core implementation (business logic)
 * of the interface with every decorator implementing a different aspect of the service, such as
 * validation and logging. This class acts as the top-level decorator, which also adds the binder-
 * related functionality (hence, it extends ISoundTriggerMiddlewareService.Stub as rather than
 * implements ISoundTriggerMiddlewareService), and does the same thing for child interfaces
 * returned.
 * <p>
 * The inner class {@link Lifecycle} acts as both a factory, composing the various aspect-decorators
 * to create a full-featured implementation, as well as as an entry-point for presenting this
 * implementation as a system service.
 * <p>
 * <b>Exposing this service as a System Service:</b><br>
 * Insert this line into {@link com.android.server.SystemServer}:
 * <code><pre>
 * mSystemServiceManager.startService(SoundTriggerMiddlewareService.Lifecycle.class);
 * </pre></code>
 *
 * {@hide}
 */
public class SoundTriggerMiddlewareService extends ISoundTriggerMiddlewareService.Stub {
    static private final String TAG = "SoundTriggerMiddlewareService";

    private final @NonNull
    ISoundTriggerMiddlewareInternal mDelegate;
    private final @NonNull
    Context mContext;

    /**
     * Constructor for internal use only. Could be exposed for testing purposes in the future.
     * Users should access this class via {@link Lifecycle}.
     */
    private SoundTriggerMiddlewareService(@NonNull ISoundTriggerMiddlewareInternal delegate,
            @NonNull Context context) {
        mDelegate = Objects.requireNonNull(delegate);
        mContext = context;
        new ExternalCaptureStateTracker(active -> {
            try {
                mDelegate.setCaptureState(active);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        });
    }

    @Override
    public SoundTriggerModuleDescriptor[] listModulesAsOriginator(Identity identity) {
        try (SafeCloseable ignored = establishIdentityDirect(identity)) {
            return mDelegate.listModules();
        }
    }

    @Override
    public SoundTriggerModuleDescriptor[] listModulesAsMiddleman(Identity middlemanIdentity,
            Identity originatorIdentity) {
        try (SafeCloseable ignored = establishIdentityIndirect(middlemanIdentity,
                originatorIdentity)) {
            return mDelegate.listModules();
        }
    }

    @Override
    public ISoundTriggerModule attachAsOriginator(int handle, Identity identity,
            ISoundTriggerCallback callback) {
        try (SafeCloseable ignored = establishIdentityDirect(Objects.requireNonNull(identity))) {
            return new ModuleService(mDelegate.attach(handle, callback));
        }
    }

    @Override
    public ISoundTriggerModule attachAsMiddleman(int handle, Identity middlemanIdentity,
            Identity originatorIdentity, ISoundTriggerCallback callback) {
        try (SafeCloseable ignored = establishIdentityIndirect(
                Objects.requireNonNull(middlemanIdentity),
                Objects.requireNonNull(originatorIdentity))) {
            return new ModuleService(mDelegate.attach(handle, callback));
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        if (mDelegate instanceof Dumpable) {
            ((Dumpable) mDelegate).dump(fout);
        }
    }

    private @NonNull
    SafeCloseable establishIdentityIndirect(Identity middlemanIdentity,
            Identity originatorIdentity) {
        return PermissionUtil.establishIdentityIndirect(mContext, SOUNDTRIGGER_DELEGATE_IDENTITY,
                middlemanIdentity, originatorIdentity);
    }

    private @NonNull
    SafeCloseable establishIdentityDirect(Identity originatorIdentity) {
        return PermissionUtil.establishIdentityDirect(originatorIdentity);
    }

    private final static class ModuleService extends ISoundTriggerModule.Stub {
        private final ISoundTriggerModule mDelegate;

        private ModuleService(ISoundTriggerModule delegate) {
            mDelegate = delegate;
        }

        @Override
        public int loadModel(SoundModel model) throws RemoteException {
            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                return mDelegate.loadModel(model);
            }
        }

        @Override
        public int loadPhraseModel(PhraseSoundModel model) throws RemoteException {
            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                return mDelegate.loadPhraseModel(model);
            }
        }

        @Override
        public void unloadModel(int modelHandle) throws RemoteException {
            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                mDelegate.unloadModel(modelHandle);
            }
        }

        @Override
        public void startRecognition(int modelHandle, RecognitionConfig config)
                throws RemoteException {
            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                mDelegate.startRecognition(modelHandle, config);
            }
        }

        @Override
        public void stopRecognition(int modelHandle) throws RemoteException {
            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                mDelegate.stopRecognition(modelHandle);
            }
        }

        @Override
        public void forceRecognitionEvent(int modelHandle) throws RemoteException {
            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                mDelegate.forceRecognitionEvent(modelHandle);
            }
        }

        @Override
        public void setModelParameter(int modelHandle, int modelParam, int value)
                throws RemoteException {
            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                mDelegate.setModelParameter(modelHandle, modelParam, value);
            }
        }

        @Override
        public int getModelParameter(int modelHandle, int modelParam) throws RemoteException {
            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                return mDelegate.getModelParameter(modelHandle, modelParam);
            }
        }

        @Override
        public ModelParameterRange queryModelParameterSupport(int modelHandle, int modelParam)
                throws RemoteException {
            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                return mDelegate.queryModelParameterSupport(modelHandle, modelParam);
            }
        }

        @Override
        public void detach() throws RemoteException {
            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                mDelegate.detach();
            }
        }
    }

    /**
     * Entry-point to this module: exposes the module as a {@link SystemService}.
     */
    public static final class Lifecycle extends SystemService {
        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            HalFactory[] factories = new HalFactory[]{() -> {
                try {
                    Log.d(TAG, "Connecting to default ISoundTriggerHw");
                    return new SoundTriggerHw2Compat(ISoundTriggerHw.getService(true),
                            () -> {
                                // This property needs to be defined in an init.rc script and
                                // trigger a HAL reboot.
                                SystemProperties.set("sys.audio.restart.hal", "1");
                            });
                } catch (RemoteException e) {
                    throw e.rethrowAsRuntimeException();
                }
            }};

            publishBinderService(Context.SOUND_TRIGGER_MIDDLEWARE_SERVICE,
                    new SoundTriggerMiddlewareService(
                            new SoundTriggerMiddlewareLogging(
                                    new SoundTriggerMiddlewarePermission(
                                            new SoundTriggerMiddlewareValidation(
                                                    new SoundTriggerMiddlewareImpl(factories,
                                                            new AudioSessionProviderImpl())),
                                            getContext())), getContext()));
        }
    }
}
