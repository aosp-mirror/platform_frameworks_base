/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.ComponentName;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.server.telecom.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents a voip call requested to stream to another device that the general streaming sender
 * app should present to the receiver.
 *
 * @hide
 */
@SystemApi
public final class StreamingCall implements Parcelable {
    /**
     * The state of a {@code StreamingCall} when newly created. General streaming sender should
     * continuously stream call audio to the sender device as long as the {@code StreamingCall} is
     * in this state.
     */
    public static final int STATE_STREAMING = 1;

    /**
     * The state of a {@code StreamingCall} when in a holding state.
     */
    public static final int STATE_HOLDING = 2;

    /**
     * The state of a {@code StreamingCall} when it's either disconnected or pulled back to the
     * original device.
     */
    public static final int STATE_DISCONNECTED = 3;

    /**
     * The ID associated with this call.  This is the same value as {@link CallControl#getCallId()}.
     */
    @FlaggedApi(Flags.FLAG_CALL_DETAILS_ID_CHANGES)
    public static final String EXTRA_CALL_ID = "android.telecom.extra.CALL_ID";

    /**
     * @hide
     */
    private StreamingCall(@NonNull Parcel in) {
        mComponentName = in.readParcelable(ComponentName.class.getClassLoader());
        mDisplayName = in.readCharSequence();
        mAddress = in.readParcelable(Uri.class.getClassLoader());
        mExtras = in.readBundle();
        mState = in.readInt();
    }

    @NonNull
    public static final Creator<StreamingCall> CREATOR = new Creator<>() {
        @Override
        public StreamingCall createFromParcel(@NonNull Parcel in) {
            return new StreamingCall(in);
        }

        @Override
        public StreamingCall[] newArray(int size) {
            return new StreamingCall[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@androidx.annotation.NonNull Parcel dest, int flags) {
        dest.writeParcelable(mComponentName, flags);
        dest.writeCharSequence(mDisplayName);
        dest.writeParcelable(mAddress, flags);
        dest.writeBundle(mExtras);
        dest.writeInt(mState);
    }

    /**
     * @hide
     */
    @IntDef(prefix = { "STATE_" },
            value = {
                    STATE_STREAMING,
                    STATE_HOLDING,
                    STATE_DISCONNECTED
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StreamingCallState {}

    private final ComponentName mComponentName;
    private final CharSequence mDisplayName;
    private final Uri mAddress;
    private final Bundle mExtras;
    @StreamingCallState
    private int mState;
    private StreamingCallAdapter mAdapter = null;

    public StreamingCall(@NonNull ComponentName componentName, @NonNull CharSequence displayName,
            @NonNull Uri address, @NonNull Bundle extras) {
        mComponentName = componentName;
        mDisplayName = displayName;
        mAddress = address;
        mExtras = extras;
        mState = STATE_STREAMING;
    }

    /**
     * @hide
     */
    public void setAdapter(StreamingCallAdapter adapter) {
        mAdapter = adapter;
    }

    /**
     * @return The {@link ComponentName} to identify the original voip app of this
     * {@code StreamingCall}. General streaming sender app can use this to query necessary
     * information (app icon etc.) in order to present notification of the streaming call on the
     * receiver side.
     */
    @NonNull
    public ComponentName getComponentName() {
        return mComponentName;
    }

    /**
     * @return The display name that the general streaming sender app can use this to present the
     * {@code StreamingCall} to the receiver side.
     */
    @NonNull
    public CharSequence getDisplayName() {
        return mDisplayName;
    }

    /**
     * @return The address (e.g., phone number) to which the {@code StreamingCall} is currently
     * connected.
     */
    @NonNull
    public Uri getAddress() {
        return mAddress;
    }

    /**
     * @return The state of this {@code StreamingCall}.
     */
    @StreamingCallState
    public int getState() {
        return mState;
    }

    /**
     * @return The extra info the general streaming app need to stream the call from voip app or
     * D2DI sdk.
     */
    @NonNull
    public Bundle getExtras() {
        return mExtras;
    }

    /**
     * Sets the state of this {@code StreamingCall}. The general streaming sender app can use this
     * to request holding, unholding and disconnecting this {@code StreamingCall}.
     * @param state The current streaming state of the call.
     */
    public void requestStreamingState(@StreamingCallState int state) {
        mAdapter.setStreamingState(state);
    }
}
