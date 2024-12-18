/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.graphics.ColorSpace;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraDevice.CameraDeviceSetup;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.utils.HashCodeHelpers;
import android.media.ImageReader;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.camera.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * A helper class that aggregates all supported arguments for capture session initialization.
 */
public final class SessionConfiguration implements Parcelable {
    private static final String TAG = "SessionConfiguration";

    /**
     * A regular session type containing instances of {@link OutputConfiguration} running
     * at regular non high speed FPS ranges and optionally {@link InputConfiguration} for
     * reprocessable sessions.
     *
     * @see CameraDevice#createCaptureSession(SessionConfiguration)
     * @see CameraDevice#createReprocessableCaptureSession
     */
    public static final int SESSION_REGULAR = CameraDevice.SESSION_OPERATION_MODE_NORMAL;

    /**
     * A high speed session type that can only contain instances of {@link OutputConfiguration}.
     * The outputs can run using high speed FPS ranges. Calls to {@link #setInputConfiguration}
     * are not supported.
     * <p>
     * When using this type, the CameraCaptureSession returned by
     * {@link android.hardware.camera2.CameraCaptureSession.StateCallback} can be cast to a
     * {@link android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession} to access the extra
     * methods for constrained high speed recording.
     * </p>
     *
     * @see CameraDevice#createConstrainedHighSpeedCaptureSession
     */
    public static final int SESSION_HIGH_SPEED =
        CameraDevice.SESSION_OPERATION_MODE_CONSTRAINED_HIGH_SPEED;

    /**
     * First vendor-specific session mode
     * @hide
     */
    public static final int SESSION_VENDOR_START =
        CameraDevice.SESSION_OPERATION_MODE_VENDOR_START;

     /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"SESSION_"}, value =
            {SESSION_REGULAR,
             SESSION_HIGH_SPEED })
    public @interface SessionMode {};

    // Camera capture session related parameters.
    private final @NonNull List<OutputConfiguration> mOutputConfigurations;
    private CameraCaptureSession.StateCallback mStateCallback = null;
    private int mSessionType;
    private Executor mExecutor = null;
    private InputConfiguration mInputConfig = null;
    private CaptureRequest mSessionParameters = null;
    private int mColorSpace;

    /**
     * Create a new {@link SessionConfiguration}.
     *
     * @param sessionType The session type.
     * @param outputs A list of output configurations for the capture session.
     * @param executor The executor which should be used to invoke the callback. In general it is
     *                 recommended that camera operations are not done on the main (UI) thread.
     * @param cb A state callback interface implementation.
     *
     * @see #SESSION_REGULAR
     * @see #SESSION_HIGH_SPEED
     * @see CameraDevice#createCaptureSession(SessionConfiguration)
     */
    public SessionConfiguration(@SessionMode int sessionType,
            @NonNull List<OutputConfiguration> outputs,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull CameraCaptureSession.StateCallback cb) {
        mSessionType = sessionType;
        mOutputConfigurations = Collections.unmodifiableList(new ArrayList<>(outputs));
        mStateCallback = cb;
        mExecutor = executor;
    }

    /**
     * Create a new {@link SessionConfiguration} with sessionType and output configurations.
     *
     * <p>The SessionConfiguration objects created by this constructor can be used by
     * {@link CameraDeviceSetup#isSessionConfigurationSupported} and {@link
     * CameraDeviceSetup#getSessionCharacteristics} to query a camera device's feature
     * combination support and session specific characteristics. For the SessionConfiguration
     * object to be used to create a capture session, {@link #setStateCallback} must be called to
     * specify the state callback function, and any incomplete OutputConfigurations must be
     * completed via {@link OutputConfiguration#addSurface} or
     * {@link OutputConfiguration#setSurfacesForMultiResolutionOutput} as appropriate.</p>
     *
     * @param sessionType The session type.
     * @param outputs A list of output configurations for the capture session.
     *
     * @see #SESSION_REGULAR
     * @see #SESSION_HIGH_SPEED
     * @see CameraDevice#createCaptureSession(SessionConfiguration)
     * @see CameraDeviceSetup#isSessionConfigurationSupported
     * @see CameraDeviceSetup#getSessionCharacteristics
     */
    @FlaggedApi(Flags.FLAG_CAMERA_DEVICE_SETUP)
    public SessionConfiguration(@SessionMode int sessionType,
            @NonNull List<OutputConfiguration> outputs) {
        mSessionType = sessionType;
        mOutputConfigurations = Collections.unmodifiableList(new ArrayList<>(outputs));
    }

    /**
     * Create a SessionConfiguration from Parcel.
     * No support for parcelable 'mStateCallback' and 'mExecutor' yet.
     */
    private SessionConfiguration(@NonNull Parcel source) {
        int sessionType = source.readInt();
        int inputWidth = source.readInt();
        int inputHeight = source.readInt();
        int inputFormat = source.readInt();
        boolean isInputMultiResolution = source.readBoolean();
        ArrayList<OutputConfiguration> outConfigs = new ArrayList<OutputConfiguration>();
        source.readTypedList(outConfigs, OutputConfiguration.CREATOR);
        // Ignore the values for hasSessionParameters and settings because we cannot reconstruct
        // the CaptureRequest object.
        boolean hasSessionParameters = source.readBoolean();
        if (hasSessionParameters) {
            CameraMetadataNative settings = new CameraMetadataNative();
            settings.readFromParcel(source);
        }

        if ((inputWidth > 0) && (inputHeight > 0) && (inputFormat != -1)) {
            mInputConfig = new InputConfiguration(inputWidth, inputHeight,
                    inputFormat, isInputMultiResolution);
        }
        mSessionType = sessionType;
        mOutputConfigurations = outConfigs;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<SessionConfiguration> CREATOR =
            new Parcelable.Creator<SessionConfiguration> () {
        @Override
        public SessionConfiguration createFromParcel(Parcel source) {
            return new SessionConfiguration(source);
        }

        @Override
        public SessionConfiguration[] newArray(int size) {
            return new SessionConfiguration[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (dest == null) {
            throw new IllegalArgumentException("dest must not be null");
        }
        dest.writeInt(mSessionType);
        if (mInputConfig != null) {
            dest.writeInt(mInputConfig.getWidth());
            dest.writeInt(mInputConfig.getHeight());
            dest.writeInt(mInputConfig.getFormat());
            dest.writeBoolean(mInputConfig.isMultiResolution());
        } else {
            dest.writeInt(/*inputWidth*/ 0);
            dest.writeInt(/*inputHeight*/ 0);
            dest.writeInt(/*inputFormat*/ -1);
            dest.writeBoolean(/*isMultiResolution*/ false);
        }
        dest.writeTypedList(mOutputConfigurations);
        if (mSessionParameters != null) {
            dest.writeBoolean(/*hasSessionParameters*/true);
            CameraMetadataNative metadata = mSessionParameters.getNativeCopy();
            metadata.writeToParcel(dest, /*flags*/0);
        } else {
            dest.writeBoolean(/*hasSessionParameters*/false);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Check if this {@link SessionConfiguration} is equal to another {@link SessionConfiguration}.
     *
     * <p>Two output session configurations are only equal if and only if the underlying input
     * configuration, output configurations, and session type are equal. </p>
     *
     * @return {@code true} if the objects were equal, {@code false} otherwise
     */
    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj == null) {
            return false;
        } else if (this == obj) {
            return true;
        } else if (obj instanceof SessionConfiguration) {
            final SessionConfiguration other = (SessionConfiguration) obj;
            if (mInputConfig != other.mInputConfig || mSessionType != other.mSessionType ||
                    mOutputConfigurations.size() != other.mOutputConfigurations.size()) {
                return false;
            }

            for (int i = 0;  i < mOutputConfigurations.size(); i++) {
                if (!mOutputConfigurations.get(i).equals(other.mOutputConfigurations.get(i)))
                    return false;
            }

            return true;
        }

        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return HashCodeHelpers.hashCode(mOutputConfigurations.hashCode(),
                Objects.hashCode(mInputConfig),
                mSessionType);
    }

    /**
     * Retrieve the type of the capture session.
     *
     * @return The capture session type.
     */
    public @SessionMode int getSessionType() {
        return mSessionType;
    }

    /**
     * Retrieve the {@link OutputConfiguration} list for the capture session.
     *
     * @return A list of output configurations for the capture session.
     */
    public List<OutputConfiguration> getOutputConfigurations() {
        return mOutputConfigurations;
    }

    /**
     * Retrieve the {@link CameraCaptureSession.StateCallback} for the capture session.
     *
     * @return A state callback interface implementation.
     */
    public CameraCaptureSession.StateCallback getStateCallback() {
        return mStateCallback;
    }

    /**
     * Retrieve the {@link java.util.concurrent.Executor} for the capture session.
     *
     * @return The Executor on which the callback will be invoked.
     */
    public Executor getExecutor() {
        return mExecutor;
    }

    /**
     * Sets the {@link InputConfiguration} for a reprocessable session. Input configuration are not
     * supported for {@link #SESSION_HIGH_SPEED}.
     *
     * @param input Input configuration.
     * @throws UnsupportedOperationException In case it is called for {@link #SESSION_HIGH_SPEED}
     *                                       type session configuration.
     */
    public void setInputConfiguration(@NonNull InputConfiguration input) {
        if (mSessionType != SESSION_HIGH_SPEED) {
            mInputConfig = input;
        } else {
            throw new UnsupportedOperationException("Method not supported for high speed session" +
                    " types");
        }
    }

    /**
     * Retrieve the {@link InputConfiguration}.
     *
     * @return The capture session input configuration.
     */
    public InputConfiguration getInputConfiguration() {
        return mInputConfig;
    }

    /**
     * Sets the session wide camera parameters (see {@link CaptureRequest}). This argument can
     * be set for every supported session type and will be passed to the camera device as part
     * of the capture session initialization. Session parameters are a subset of the available
     * capture request parameters (see {@link CameraCharacteristics#getAvailableSessionKeys})
     * and their application can introduce internal camera delays. To improve camera performance
     * it is suggested to change them sparingly within the lifetime of the capture session and
     * to pass their initial values as part of this method.
     *
     * @param params A capture request that includes the initial values for any available
     *               session wide capture keys. Tags (see {@link CaptureRequest.Builder#setTag}) and
     *               output targets (see {@link CaptureRequest.Builder#addTarget}) are ignored if
     *               set. Parameter values not part of
     *               {@link CameraCharacteristics#getAvailableSessionKeys} will also be ignored. It
     *               is recommended to build the session parameters using the same template type as
     *               the initial capture request, so that the session and initial request parameters
     *               match as much as possible.
     */
    public void setSessionParameters(CaptureRequest params) {
        mSessionParameters = params;
    }

    /**
     * Retrieve the session wide camera parameters (see {@link CaptureRequest}).
     *
     * @return A capture request that includes the initial values for any available
     *         session wide capture keys.
     */
    public CaptureRequest getSessionParameters() {
        return mSessionParameters;
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
    public void setColorSpace(@NonNull ColorSpace.Named colorSpace) {
        mColorSpace = colorSpace.ordinal();
        for (OutputConfiguration outputConfiguration : mOutputConfigurations) {
            outputConfiguration.setColorSpace(colorSpace);
        }
    }

    /**
     * Clear the color space, such that the default color space will be used.
     */
    public void clearColorSpace() {
        mColorSpace = ColorSpaceProfiles.UNSPECIFIED;
        for (OutputConfiguration outputConfiguration : mOutputConfigurations) {
            outputConfiguration.clearColorSpace();
        }
    }

    /**
     * Return the current color space.
     *
     * @return the currently set color space
     */
    @SuppressLint("MethodNameUnits")
    public @Nullable ColorSpace getColorSpace() {
        if (mColorSpace != ColorSpaceProfiles.UNSPECIFIED) {
            return ColorSpace.get(ColorSpace.Named.values()[mColorSpace]);
        } else {
            return null;
        }
    }

    /**
     * Set the state callback and executor.
     *
     * <p>This function must be called for the SessionConfiguration object created via {@link
     * #SessionConfiguration(int, List) SessionConfiguration(int, List&lt;OutputConfiguration&gt;)}
     * before it's used to create a capture session.</p>
     *
     * @param executor The executor which should be used to invoke the callback. In general it is
     *                 recommended that camera operations are not done on the main (UI) thread.
     * @param cb A state callback interface implementation.
     */
    @FlaggedApi(Flags.FLAG_CAMERA_DEVICE_SETUP)
    public void setStateCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull CameraCaptureSession.StateCallback cb) {
        mStateCallback = cb;
        mExecutor = executor;
    }
}
