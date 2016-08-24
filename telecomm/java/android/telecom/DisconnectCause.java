/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.telecom;

import android.os.Parcel;
import android.os.Parcelable;
import android.media.ToneGenerator;
import android.text.TextUtils;

import java.util.Objects;

/**
 * Describes the cause of a disconnected call. This always includes a code describing the generic
 * cause of the disconnect. Optionally, it may include a label and/or description to display to the
 * user. It is the responsibility of the {@link ConnectionService} to provide localized versions of
 * the label and description. It also may contain a reason for the disconnect, which is intended for
 * logging and not for display to the user.
 */
public final class DisconnectCause implements Parcelable {

    /** Disconnected because of an unknown or unspecified reason. */
    public static final int UNKNOWN = 0;
    /** Disconnected because there was an error, such as a problem with the network. */
    public static final int ERROR = 1;
    /** Disconnected because of a local user-initiated action, such as hanging up. */
    public static final int LOCAL = 2;
    /**
     * Disconnected because of a remote user-initiated action, such as the other party hanging up
     * up.
     */
    public static final int REMOTE = 3;
    /** Disconnected because it has been canceled. */
    public static final int CANCELED = 4;
    /** Disconnected because there was no response to an incoming call. */
    public static final int MISSED = 5;
    /** Disconnected because the user rejected an incoming call. */
    public static final int REJECTED = 6;
    /** Disconnected because the other party was busy. */
    public static final int BUSY = 7;
    /**
     * Disconnected because of a restriction on placing the call, such as dialing in airplane
     * mode.
     */
    public static final int RESTRICTED = 8;
    /** Disconnected for reason not described by other disconnect codes. */
    public static final int OTHER = 9;
    /**
     * Disconnected because the connection manager did not support the call. The call will be tried
     * again without a connection manager. See {@link PhoneAccount#CAPABILITY_CONNECTION_MANAGER}.
     */
    public static final int CONNECTION_MANAGER_NOT_SUPPORTED = 10;

    /**
     * Disconnected because the user did not locally answer the incoming call, but it was answered
     * on another device where the call was ringing.
     * @hide
     */
    public static final int ANSWERED_ELSEWHERE = 11;

    /**
     * Disconnected because the call was pulled from the current device to another device.
     * @hide
     */
    public static final int CALL_PULLED = 12;

    private int mDisconnectCode;
    private CharSequence mDisconnectLabel;
    private CharSequence mDisconnectDescription;
    private String mDisconnectReason;
    private int mToneToPlay;

    /**
     * Creates a new DisconnectCause.
     *
     * @param code The code for the disconnect cause.
     */
    public DisconnectCause(int code) {
        this(code, null, null, null, ToneGenerator.TONE_UNKNOWN);
    }

    /**
     * Creates a new DisconnectCause.
     *
     * @param code The code for the disconnect cause.
     * @param reason The reason for the disconnect.
     */
    public DisconnectCause(int code, String reason) {
        this(code, null, null, reason, ToneGenerator.TONE_UNKNOWN);
    }

    /**
     * Creates a new DisconnectCause.
     *
     * @param code The code for the disconnect cause.
     * @param label The localized label to show to the user to explain the disconnect.
     * @param description The localized description to show to the user to explain the disconnect.
     * @param reason The reason for the disconnect.
     */
    public DisconnectCause(int code, CharSequence label, CharSequence description, String reason) {
        this(code, label, description, reason, ToneGenerator.TONE_UNKNOWN);
    }

    /**
     * Creates a new DisconnectCause.
     *
     * @param code The code for the disconnect cause.
     * @param label The localized label to show to the user to explain the disconnect.
     * @param description The localized description to show to the user to explain the disconnect.
     * @param reason The reason for the disconnect.
     * @param toneToPlay The tone to play on disconnect, as defined in {@link ToneGenerator}.
     */
    public DisconnectCause(int code, CharSequence label, CharSequence description, String reason,
            int toneToPlay) {
        mDisconnectCode = code;
        mDisconnectLabel = label;
        mDisconnectDescription = description;
        mDisconnectReason = reason;
        mToneToPlay = toneToPlay;
    }

    /**
     * Returns the code for the reason for this disconnect.
     *
     * @return The disconnect code.
     */
    public int getCode() {
        return mDisconnectCode;
    }

    /**
     * Returns a short label which explains the reason for the disconnect cause and is for display
     * in the user interface. If not null, it is expected that the In-Call UI should display this
     * text where it would normally display the call state ("Dialing", "Disconnected") and is
     * therefore expected to be relatively small. The {@link ConnectionService } is responsible for
     * providing and localizing this label. If there is no string provided, returns null.
     *
     * @return The disconnect label.
     */
    public CharSequence getLabel() {
        return mDisconnectLabel;
    }

    /**
     * Returns a description which explains the reason for the disconnect cause and is for display
     * in the user interface. This optional text is generally a longer and more descriptive version
     * of {@link #getLabel}, however it can exist even if {@link #getLabel} is empty. The In-Call UI
     * should display this relatively prominently; the traditional implementation displays this as
     * an alert dialog. The {@link ConnectionService} is responsible for providing and localizing
     * this message. If there is no string provided, returns null.
     *
     * @return The disconnect description.
     */
    public CharSequence getDescription() {
        return mDisconnectDescription;
    }

    /**
     * Returns an explanation of the reason for the disconnect. This is not intended for display to
     * the user and is used mainly for logging.
     *
     * @return The disconnect reason.
     */
    public String getReason() {
        return mDisconnectReason;
    }

    /**
     * Returns the tone to play when disconnected.
     *
     * @return the tone as defined in {@link ToneGenerator} to play when disconnected.
     */
    public int getTone() {
        return mToneToPlay;
    }

    public static final Creator<DisconnectCause> CREATOR = new Creator<DisconnectCause>() {
        @Override
        public DisconnectCause createFromParcel(Parcel source) {
            int code = source.readInt();
            CharSequence label = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
            CharSequence description = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
            String reason = source.readString();
            int tone = source.readInt();
            return new DisconnectCause(code, label, description, reason, tone);
        }

        @Override
        public DisconnectCause[] newArray(int size) {
            return new DisconnectCause[size];
        }
    };

    @Override
    public void writeToParcel(Parcel destination, int flags) {
        destination.writeInt(mDisconnectCode);
        TextUtils.writeToParcel(mDisconnectLabel, destination, flags);
        TextUtils.writeToParcel(mDisconnectDescription, destination, flags);
        destination.writeString(mDisconnectReason);
        destination.writeInt(mToneToPlay);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mDisconnectCode)
                + Objects.hashCode(mDisconnectLabel)
                + Objects.hashCode(mDisconnectDescription)
                + Objects.hashCode(mDisconnectReason)
                + Objects.hashCode(mToneToPlay);
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof DisconnectCause) {
            DisconnectCause d = (DisconnectCause) o;
            return Objects.equals(mDisconnectCode, d.getCode())
                    && Objects.equals(mDisconnectLabel, d.getLabel())
                    && Objects.equals(mDisconnectDescription, d.getDescription())
                    && Objects.equals(mDisconnectReason, d.getReason())
                    && Objects.equals(mToneToPlay, d.getTone());
        }
        return false;
    }

    @Override
    public String toString() {
        String code = "";
        switch (mDisconnectCode) {
            case UNKNOWN:
                code = "UNKNOWN";
                break;
            case ERROR:
                code = "ERROR";
                break;
            case LOCAL:
                code = "LOCAL";
                break;
            case REMOTE:
                code = "REMOTE";
                break;
            case CANCELED:
                code = "CANCELED";
                break;
            case MISSED:
                code = "MISSED";
                break;
            case REJECTED:
                code = "REJECTED";
                break;
            case BUSY:
                code = "BUSY";
                break;
            case RESTRICTED:
                code = "RESTRICTED";
                break;
            case OTHER:
                code = "OTHER";
                break;
            case CONNECTION_MANAGER_NOT_SUPPORTED:
                code = "CONNECTION_MANAGER_NOT_SUPPORTED";
                break;
            default:
                code = "invalid code: " + mDisconnectCode;
                break;
        }
        String label = mDisconnectLabel == null ? "" : mDisconnectLabel.toString();
        String description = mDisconnectDescription == null
                ? "" : mDisconnectDescription.toString();
        String reason = mDisconnectReason == null ? "" : mDisconnectReason;
        return "DisconnectCause [ Code: (" + code + ")"
                + " Label: (" + label + ")"
                + " Description: (" + description + ")"
                + " Reason: (" + reason + ")"
                + " Tone: (" + mToneToPlay + ") ]";
    }
}
