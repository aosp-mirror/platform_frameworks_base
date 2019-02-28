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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.IntDef;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.InputConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.utils.HashCodeHelpers;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static com.android.internal.util.Preconditions.*;

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
     * @see CameraDevice#createCaptureSession
     * @see CameraDevice#createReprocessableCaptureSession
     */
    public static final int SESSION_REGULAR = CameraDevice.SESSION_OPERATION_MODE_NORMAL;

    /**
     * A high speed session type that can only contain instances of {@link OutputConfiguration}.
     * The outputs can run using high speed FPS ranges. Calls to {@link #setInputConfiguration}
     * are not supported.
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
    private List<OutputConfiguration> mOutputConfigurations;
    private CameraCaptureSession.StateCallback mStateCallback;
    private int mSessionType;
    private Executor mExecutor = null;
    private InputConfiguration mInputConfig = null;
    private CaptureRequest mSessionParameters = null;

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
     * @see CameraDevice#createCaptureSession(List, CameraCaptureSession.StateCallback, Handler)
     * @see CameraDevice#createCaptureSessionByOutputConfigurations
     * @see CameraDevice#createReprocessableCaptureSession
     * @see CameraDevice#createConstrainedHighSpeedCaptureSession
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
     * Create a SessionConfiguration from Parcel.
     * No support for parcelable 'mStateCallback', 'mExecutor' and 'mSessionParameters' yet.
     */
    private SessionConfiguration(@NonNull Parcel source) {
        int sessionType = source.readInt();
        int inputWidth = source.readInt();
        int inputHeight = source.readInt();
        int inputFormat = source.readInt();
        ArrayList<OutputConfiguration> outConfigs = new ArrayList<OutputConfiguration>();
        source.readTypedList(outConfigs, OutputConfiguration.CREATOR);

        if ((inputWidth > 0) && (inputHeight > 0) && (inputFormat != -1)) {
            mInputConfig = new InputConfiguration(inputWidth, inputHeight, inputFormat);
        }
        mSessionType = sessionType;
        mOutputConfigurations = outConfigs;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<SessionConfiguration> CREATOR =
            new Parcelable.Creator<SessionConfiguration> () {
        @Override
        public SessionConfiguration createFromParcel(Parcel source) {
            try {
                SessionConfiguration sessionConfiguration = new SessionConfiguration(source);
                return sessionConfiguration;
            } catch (Exception e) {
                Log.e(TAG, "Exception creating SessionConfiguration from parcel", e);
                return null;
            }
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
        } else {
            dest.writeInt(/*inputWidth*/ 0);
            dest.writeInt(/*inputHeight*/ 0);
            dest.writeInt(/*inputFormat*/ -1);
        }
        dest.writeTypedList(mOutputConfigurations);
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
    public boolean equals(Object obj) {
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
        return HashCodeHelpers.hashCode(mOutputConfigurations.hashCode(), mInputConfig.hashCode(),
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
}
