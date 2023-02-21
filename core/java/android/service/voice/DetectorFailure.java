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

package android.service.voice;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This is used by the assistant application to know what went wrong during using the detector
 * and which action the application should take. The detector can be a HotwordDector or a visual
 * query detector.
 *
 * <p>Any class that derives this class must only add an integer value of error source type, an
 * integer value of error code and a string of error message passed into the constructor. Any other
 * field will not be parceled through. If the derived class has custom parceling implementation,
 * this class will not be able to unpack the parcel without having access to that implementation.
 *
 * @hide
 */
@SuppressLint("ParcelNotFinal") // Safe because the constructor is package-private
@SystemApi
public abstract class DetectorFailure implements Parcelable {

    /**
     * A suggested action due to an unknown error occurs.
     */
    public static final int SUGGESTED_ACTION_UNKNOWN = 0;

    /**
     * Indicates that an error occurs, but no action is needed for the client. The error will be
     * recovered from within the framework.
     */
    public static final int SUGGESTED_ACTION_NONE = 1;

    /**
     * Indicates that an error occurs, but no action is needed for the client due to the error can
     * not be recovered. It means that the detection will not work even though the assistant
     * application creates the detector again.
     *
     * Example: The detection service always crashes after assistant application creates the
     * detector. The assistant application can stop re-creating the detector and show a suitable
     * error dialog to notify the user.
     */
    public static final int SUGGESTED_ACTION_DISABLE_DETECTION = 2;

    /**
     * Indicates that the detection service is invalid, the client needs to destroy its detector
     * first and recreate its detector later.
     */
    public static final int SUGGESTED_ACTION_RECREATE_DETECTOR = 3;

    /**
     * Indicates that the detection has stopped. The client needs to start recognition again.
     *
     * Example: The system server receives a Dsp trigger event.
     */
    public static final int SUGGESTED_ACTION_RESTART_RECOGNITION = 4;

    /**
     * @hide
     */
    @IntDef(prefix = {"SUGGESTED_ACTION_"}, value = {
            SUGGESTED_ACTION_UNKNOWN,
            SUGGESTED_ACTION_NONE,
            SUGGESTED_ACTION_DISABLE_DETECTION,
            SUGGESTED_ACTION_RECREATE_DETECTOR,
            SUGGESTED_ACTION_RESTART_RECOGNITION
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SuggestedAction {}

    /**
     * Indicates that an error occurs from the unknown error source.
     *
     * @hide
     */
    public static final int ERROR_SOURCE_TYPE_UNKNOWN = -1;

    /**
     * Indicates that an error occurs from the hotword detection.
     *
     * @see HotwordDetectionServiceFailure#ERROR_CODE_BIND_FAILURE
     * @see HotwordDetectionServiceFailure#ERROR_CODE_BINDING_DIED
     * @see HotwordDetectionServiceFailure#ERROR_CODE_COPY_AUDIO_DATA_FAILURE
     * @see HotwordDetectionServiceFailure#ERROR_CODE_DETECT_TIMEOUT
     * @see HotwordDetectionServiceFailure#ERROR_CODE_ON_DETECTED_SECURITY_EXCEPTION
     * @see HotwordDetectionServiceFailure#ERROR_CODE_ON_DETECTED_STREAM_COPY_FAILURE
     * @see HotwordDetectionServiceFailure#ERROR_CODE_REMOTE_EXCEPTION
     *
     * @hide
     */
    public static final int ERROR_SOURCE_TYPE_HOTWORD_DETECTION = 0;

    /**
     * Indicates that an error occurs from the sound trigger system service
     * {@link com.android.server.soundtrigger.SoundTriggerService} and
     * {@link com.android.server.soundtrigger_middleware.SoundTriggerMiddlewareService}.
     *
     * @see SoundTriggerFailure#ERROR_CODE_MODULE_DIED
     * @see SoundTriggerFailure#ERROR_CODE_RECOGNITION_RESUME_FAILED
     * @see SoundTriggerFailure#ERROR_CODE_UNEXPECTED_PREEMPTION
     *
     * @hide
     */
    public static final int ERROR_SOURCE_TYPE_SOUND_TRIGGER = 1;

    /**
     * Indicates that an error occurs from {@link VisualQueryDetectionService}.
     *
     * @see VisualQueryDetectionServiceFailure#ERROR_CODE_BIND_FAILURE
     * @see VisualQueryDetectionServiceFailure#ERROR_CODE_BINDING_DIED
     * @see VisualQueryDetectionServiceFailure#ERROR_CODE_ILLEGAL_ATTENTION_STATE
     * @see VisualQueryDetectionServiceFailure#ERROR_CODE_ILLEGAL_STREAMING_STATE
     * @see VisualQueryDetectionServiceFailure#ERROR_CODE_REMOTE_EXCEPTION
     *
     * @hide
     */
    public static final int ERROR_SOURCE_TYPE_VISUAL_QUERY_DETECTION = 2;

    private int mErrorSourceType = ERROR_SOURCE_TYPE_UNKNOWN;
    private int mErrorCode = UnknownFailure.ERROR_CODE_UNKNOWN;
    private String mErrorMessage = "Unknown";

    DetectorFailure(int errorSourceType, int errorCode, @NonNull String errorMessage) {
        Preconditions.checkArgumentInRange(errorSourceType, ERROR_SOURCE_TYPE_UNKNOWN,
                ERROR_SOURCE_TYPE_VISUAL_QUERY_DETECTION, "errorSourceType");
        if (TextUtils.isEmpty(errorMessage)) {
            throw new IllegalArgumentException("errorMessage is empty or null.");
        }
        mErrorSourceType = errorSourceType;
        mErrorCode = errorCode;
        mErrorMessage = errorMessage;
    }

    /**
     * Returns the suggested action.
     */
    @SuggestedAction
    public abstract int getSuggestedAction();

    /**
     * Returns the error code.
     *
     * @hide
     */
    public int getErrorCode() {
        return mErrorCode;
    }

    /**
     * Returns the error message.
     */
    @NonNull
    public String getErrorMessage() {
        return mErrorMessage;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mErrorSourceType);
        dest.writeInt(mErrorCode);
        dest.writeString8(mErrorMessage);
    }

    public static final @NonNull Parcelable.Creator<DetectorFailure> CREATOR =
            new Parcelable.Creator<DetectorFailure>() {
                @Override
                public DetectorFailure[] newArray(int size) {
                    return new DetectorFailure[size];
                }

                @Override
                public DetectorFailure createFromParcel(@NonNull Parcel in) {
                    final int errorSourceType = in.readInt();
                    final int errorCode = in.readInt();
                    final String errorMessage = in.readString8();
                    switch (errorSourceType) {
                        case ERROR_SOURCE_TYPE_HOTWORD_DETECTION:
                            return new HotwordDetectionServiceFailure(errorCode, errorMessage);
                        case ERROR_SOURCE_TYPE_SOUND_TRIGGER:
                            return new SoundTriggerFailure(errorCode, errorMessage);
                        case ERROR_SOURCE_TYPE_VISUAL_QUERY_DETECTION:
                            return new VisualQueryDetectionServiceFailure(errorCode, errorMessage);
                        default:
                            return new UnknownFailure(errorMessage);
                    }
                }
            };
}
