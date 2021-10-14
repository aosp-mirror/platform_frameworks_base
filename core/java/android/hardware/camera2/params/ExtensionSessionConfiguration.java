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
package android.hardware.camera2.params;

import android.annotation.NonNull;

import android.hardware.camera2.CameraExtensionCharacteristics.Extension;
import android.hardware.camera2.CameraExtensionSession;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * A class that aggregates all supported arguments for
 * {@link CameraExtensionSession} initialization.
 */
public final class ExtensionSessionConfiguration {
    private static final String TAG = "ExtensionSessionConfiguration";

    private int mExtensionType;
    private List<OutputConfiguration> mOutputs;
    private Executor mExecutor = null;
    private CameraExtensionSession.StateCallback mCallback = null;

    /**
     * Create a new ExtensionSessionConfiguration
     *
     * @param extension to be used for processing
     * @param outputs   a list of output configurations for the capture session
     * @param executor  the executor which will be used for invoking the callbacks
     * @param listener  callbacks to be invoked when the state of the
     *                  CameraExtensionSession changes
     */
    public ExtensionSessionConfiguration(@Extension int extension,
            @NonNull List<OutputConfiguration> outputs, @NonNull Executor executor,
            @NonNull CameraExtensionSession.StateCallback listener) {
        mExtensionType = extension;
        mOutputs = outputs;
        mExecutor = executor;
        mCallback = listener;
    }

    /**
     * Retrieve the extension type.
     *
     * @return the extension type.
     */
    public @Extension
    int getExtension() {
        return mExtensionType;
    }

    /**
     * Retrieve the {@link OutputConfiguration} list for the capture
     * session.
     *
     * @return A list of output configurations for the capture session.
     */
    public @NonNull
    List<OutputConfiguration> getOutputConfigurations() {
        return mOutputs;
    }

    /**
     * Retrieve the CameraCaptureSession.StateCallback
     * listener.
     *
     * @return A state callback interface implementation.
     */
    public @NonNull
    CameraExtensionSession.StateCallback getStateCallback() {
        return mCallback;
    }

    /**
     * Retrieve the Executor for the CameraExtensionSession instance.
     *
     * @return The Executor on which the callback will be invoked.
     */
    public @NonNull
    Executor getExecutor() {
        return mExecutor;
    }
}
