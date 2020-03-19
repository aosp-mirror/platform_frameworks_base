/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.os;

import android.annotation.NonNull;
import android.media.AudioAttributes;
import android.util.Slog;

import com.android.internal.util.Preconditions;

/**
 * An ExternalVibration represents an on-going vibration being controlled by something other than
 * the core vibrator service.
 *
 * @hide
 */
public class ExternalVibration implements Parcelable {
    private static final String TAG = "ExternalVibration";
    private int mUid;
    @NonNull
    private String mPkg;
    @NonNull
    private AudioAttributes mAttrs;
    @NonNull
    private IExternalVibrationController mController;
    // A token used to maintain equality comparisons when passing objects across process
    // boundaries.
    @NonNull
    private IBinder mToken;

    public ExternalVibration(int uid, @NonNull String pkg, @NonNull AudioAttributes attrs,
            @NonNull IExternalVibrationController controller) {
        mUid = uid;
        mPkg = Preconditions.checkNotNull(pkg);
        mAttrs = Preconditions.checkNotNull(attrs);
        mController = Preconditions.checkNotNull(controller);
        mToken = new Binder();
    }

    private ExternalVibration(Parcel in) {
        mUid = in.readInt();
        mPkg = in.readString();
        mAttrs = readAudioAttributes(in);
        mController = IExternalVibrationController.Stub.asInterface(in.readStrongBinder());
        mToken = in.readStrongBinder();
    }

    private AudioAttributes readAudioAttributes(Parcel in) {
        int usage = in.readInt();
        int contentType = in.readInt();
        int capturePreset = in.readInt();
        int flags = in.readInt();
        AudioAttributes.Builder builder = new AudioAttributes.Builder();
        return builder.setUsage(usage)
                .setContentType(contentType)
                .setCapturePreset(capturePreset)
                .setFlags(flags)
                .build();
    }

    public int getUid() {
        return mUid;
    }

    public String getPackage() {
        return mPkg;
    }

    public AudioAttributes getAudioAttributes() {
        return mAttrs;
    }

    /**
     * Mutes the external vibration if it's playing and unmuted.
     *
     * @return whether the muting operation was successful
     */
    public boolean mute() {
        try {
            mController.mute();
        } catch (RemoteException e) {
            Slog.wtf(TAG, "Failed to mute vibration stream: " + this, e);
            return false;
        }
        return true;
    }

    /**
     * Unmutes the external vibration if it's playing and muted.
     *
     * @return whether the unmuting operation was successful
     */
    public boolean unmute() {
        try {
            mController.unmute();
        } catch (RemoteException e) {
            Slog.wtf(TAG, "Failed to unmute vibration stream: " + this, e);
            return false;
        }
        return true;
    }

    /**
     * Links a recipient to death against this external vibration token
     */
    public void linkToDeath(IBinder.DeathRecipient recipient) {
        try {
            mToken.linkToDeath(recipient, 0);
        } catch (RemoteException e) {
            return;
        }
    }

    /**
     * Unlinks a recipient to death against this external vibration token
     */
    public void unlinkToDeath(IBinder.DeathRecipient recipient) {
        mToken.unlinkToDeath(recipient, 0);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || !(o instanceof ExternalVibration)) {
            return false;
        }
        ExternalVibration other = (ExternalVibration) o;
        return mToken.equals(other.mToken);
    }

    @Override
    public String toString() {
        return "ExternalVibration{"
            + "uid=" + mUid + ", "
            + "pkg=" + mPkg + ", "
            + "attrs=" + mAttrs + ", "
            + "controller=" + mController
            + "token=" + mController
            + "}";
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mUid);
        out.writeString(mPkg);
        writeAudioAttributes(mAttrs, out, flags);
        out.writeStrongBinder(mController.asBinder());
        out.writeStrongBinder(mToken);
    }

    private static void writeAudioAttributes(AudioAttributes attrs, Parcel out, int flags) {
        out.writeInt(attrs.getUsage());
        out.writeInt(attrs.getContentType());
        out.writeInt(attrs.getCapturePreset());
        out.writeInt(attrs.getAllFlags());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ExternalVibration> CREATOR =
            new Parcelable.Creator<ExternalVibration>() {
                @Override
                public ExternalVibration createFromParcel(Parcel in) {
                    return new ExternalVibration(in);
                }

                @Override
                public ExternalVibration[] newArray(int size) {
                    return new ExternalVibration[size];
                }
            };
}
