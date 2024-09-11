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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.graphics.ColorSpace;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraExtensionCharacteristics.Extension;
import android.hardware.camera2.CameraExtensionSession;
import android.media.ImageReader;

import com.android.internal.camera.flags.Flags;

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
    private OutputConfiguration mPostviewOutput = null;
    private Executor mExecutor = null;
    private CameraExtensionSession.StateCallback mCallback = null;
    private int mColorSpace;

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
     * Set the postview for still capture output configuration.
     *
     * @param postviewOutput output configuration for postview
     * @see android.hardware.camera2.CameraExtensionCharacteristics#isPostviewAvailable
     */
    public
    void setPostviewOutputConfiguration(@Nullable OutputConfiguration postviewOutput) {
        mPostviewOutput = postviewOutput;
    }

    /**
     * Get the postview for still capture output configuration.
     *
     * @return output configuration for postview
     * @see android.hardware.camera2.CameraExtensionCharacteristics#isPostviewAvailable
     */
    public @Nullable // Postview output is optional
    OutputConfiguration getPostviewOutputConfiguration() {
        return mPostviewOutput;
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

    /**
     * Set a specific device-supported color space.
     *
     * <p>Clients can choose from any profile advertised as supported in
     * {@link CameraCharacteristics#REQUEST_AVAILABLE_COLOR_SPACE_PROFILES}
     * queried using {@link ColorSpaceProfiles#getSupportedColorSpaces}.
     * When set, the colorSpace will override the default color spaces of the output targets,
     * or the color space implied by the dataSpace passed into an {@link ImageReader}'s
     * constructor.</p>
     */
    @FlaggedApi(Flags.FLAG_EXTENSION_10_BIT)
    public void setColorSpace(@NonNull ColorSpace.Named colorSpace) {
        mColorSpace = colorSpace.ordinal();
        for (OutputConfiguration outputConfiguration : mOutputs) {
            outputConfiguration.setColorSpace(colorSpace);
        }
        if (mPostviewOutput != null) {
            mPostviewOutput.setColorSpace(colorSpace);
        }
    }

    /**
     * Clear the color space, such that the default color space will be used.
     */
    @FlaggedApi(Flags.FLAG_EXTENSION_10_BIT)
    public void clearColorSpace() {
        mColorSpace = ColorSpaceProfiles.UNSPECIFIED;
        for (OutputConfiguration outputConfiguration : mOutputs) {
            outputConfiguration.clearColorSpace();
        }
        if (mPostviewOutput != null) {
            mPostviewOutput.clearColorSpace();
        }
    }

    /**
     * Return the current color space.
     *
     * @return the currently set color space, or null
     *         if not set
     */
    @FlaggedApi(Flags.FLAG_EXTENSION_10_BIT)
    @SuppressLint("MethodNameUnits")
    public @Nullable ColorSpace getColorSpace() {
        if (mColorSpace != ColorSpaceProfiles.UNSPECIFIED) {
            return ColorSpace.get(ColorSpace.Named.values()[mColorSpace]);
        } else {
            return null;
        }
    }
}
