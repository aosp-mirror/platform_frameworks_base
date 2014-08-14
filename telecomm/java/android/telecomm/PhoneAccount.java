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

package android.telecomm;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources.NotFoundException;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.MissingResourceException;

/**
 * Describes a distinct account, line of service or call placement method that the system
 * can use to place phone calls.
 */
public class PhoneAccount implements Parcelable {

    /**
     * Flag indicating that this {@code PhoneAccount} can act as a connection manager for
     * other connections. The {@link ConnectionService} associated with this {@code PhoneAccount}
     * will be allowed to manage phone calls including using its own proprietary phone-call
     * implementation (like VoIP calling) to make calls instead of the telephony stack.
     * <p>
     * When a user opts to place a call using the SIM-based telephony stack, the
     * {@link ConnectionService} associated with this {@code PhoneAccount} will be attempted first
     * if the user has explicitly selected it to be used as the default connection manager.
     * <p>
     * See {@link #getCapabilities}
     */
    public static final int CAPABILITY_CONNECTION_MANAGER = 0x1;

    /**
     * Flag indicating that this {@code PhoneAccount} can make phone calls in place of
     * traditional SIM-based telephony calls. This account will be treated as a distinct method
     * for placing calls alongside the traditional SIM-based telephony stack. This flag is
     * distinct from {@link #CAPABILITY_CONNECTION_MANAGER} in that it is not allowed to manage
     * calls from or use the built-in telephony stack to place its calls.
     * <p>
     * See {@link #getCapabilities}
     * <p>
     * {@hide}
     */
    public static final int CAPABILITY_CALL_PROVIDER = 0x2;

    /**
     * Flag indicating that this {@code PhoneAccount} represents a built-in PSTN SIM
     * subscription.
     * <p>
     * Only the Android framework can register a {@code PhoneAccount} having this capability.
     * <p>
     * See {@link #getCapabilities}
     */
    public static final int CAPABILITY_SIM_SUBSCRIPTION = 0x4;

    /**
     * Flag indicating that this {@code PhoneAccount} is capable of placing video calls.
     * <p>
     * See {@link #getCapabilities}
     */
    public static final int CAPABILITY_VIDEO_CALLING = 0x8;

    private final PhoneAccountHandle mAccountHandle;
    private final Uri mHandle;
    private final String mSubscriptionNumber;
    private final int mCapabilities;
    private final int mIconResId;
    private final CharSequence mLabel;
    private final CharSequence mShortDescription;

    public static class Builder {
        private PhoneAccountHandle mAccountHandle;
        private Uri mHandle;
        private String mSubscriptionNumber;
        private int mCapabilities;
        private int mIconResId;
        private CharSequence mLabel;
        private CharSequence mShortDescription;

        private Builder() {}

        public Builder withAccountHandle(PhoneAccountHandle value) {
            this.mAccountHandle = value;
            return this;
        }

        public Builder withHandle(Uri value) {
            this.mHandle = value;
            return this;
        }

        public Builder withSubscriptionNumber(String value) {
            this.mSubscriptionNumber = value;
            return this;
        }

        public Builder withCapabilities(int value) {
            this.mCapabilities = value;
            return this;
        }

        public Builder withIconResId(int value) {
            this.mIconResId = value;
            return this;
        }

        public Builder withLabel(CharSequence value) {
            this.mLabel = value;
            return this;
        }

        public Builder withShortDescription(CharSequence value) {
            this.mShortDescription = value;
            return this;
        }

        public PhoneAccount build() {
            return new PhoneAccount(
                    mAccountHandle,
                    mHandle,
                    mSubscriptionNumber,
                    mCapabilities,
                    mIconResId,
                    mLabel,
                    mShortDescription);
        }
    }

    private PhoneAccount(
            PhoneAccountHandle account,
            Uri handle,
            String subscriptionNumber,
            int capabilities,
            int iconResId,
            CharSequence label,
            CharSequence shortDescription) {
        mAccountHandle = account;
        mHandle = handle;
        mSubscriptionNumber = subscriptionNumber;
        mCapabilities = capabilities;
        mIconResId = iconResId;
        mLabel = label;
        mShortDescription = shortDescription;
    }

    public static Builder builder() { return new Builder(); }

    /**
     * The unique identifier of this {@code PhoneAccount}.
     *
     * @return A {@code PhoneAccountHandle}.
     */
    public PhoneAccountHandle getAccountHandle() {
        return mAccountHandle;
    }

    /**
     * The handle (e.g., a phone number) associated with this {@code PhoneAccount}. This
     * represents the destination from which outgoing calls using this {@code PhoneAccount}
     * will appear to come, if applicable, and the destination to which incoming calls using this
     * {@code PhoneAccount} may be addressed.
     *
     * @return A handle expressed as a {@code Uri}, for example, a phone number.
     */
    public Uri getHandle() {
        return mHandle;
    }

    /**
     * The raw callback number used for this {@code PhoneAccount}, as distinct from
     * {@link #getHandle()}. For the majority of {@code PhoneAccount}s this should be registered
     * as {@code null}.  It is used by the system for SIM-based {@code PhoneAccount} registration
     * where {@link android.telephony.TelephonyManager#setLine1NumberForDisplay(String, String)}
     * or {@link android.telephony.TelephonyManager#setLine1NumberForDisplay(long, String, String)}
     * has been used to alter the callback number.
     * <p>
     * TODO: Should this also be a URI, for consistency? Should it be called the
     * "subscription handle"?
     *
     * @return The subscription number, suitable for display to the user.
     */
    public String getSubscriptionNumber() {
        return mSubscriptionNumber;
    }

    /**
     * The capabilities of this {@code PhoneAccount}.
     *
     * @return A bit field of flags describing this {@code PhoneAccount}'s capabilities.
     */
    public int getCapabilities() {
        return mCapabilities;
    }

    /**
     * A short label describing a {@code PhoneAccount}.
     *
     * @return A label for this {@code PhoneAccount}.
     */
    public CharSequence getLabel() {
        return mLabel;
    }

    /**
     * A short paragraph describing this {@code PhoneAccount}.
     *
     * @return A description for this {@code PhoneAccount}.
     */
    public CharSequence getShortDescription() {
        return mShortDescription;
    }

    /**
     * The icon resource ID for the icon of this {@code PhoneAccount}.
     *
     * @return A resource ID.
     */
    public int getIconResId() {
        return mIconResId;
    }

    /**
     * An icon to represent this {@code PhoneAccount} in a user interface.
     *
     * @return An icon for this {@code PhoneAccount}.
     */
    public Drawable getIcon(Context context) {
        return getIcon(context, mIconResId);
    }

    private Drawable getIcon(Context context, int resId) {
        Context packageContext;
        try {
            packageContext = context.createPackageContext(
                    mAccountHandle.getComponentName().getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(this, "Cannot find package %s", mAccountHandle.getComponentName().getPackageName());
            return null;
        }
        try {
            return packageContext.getDrawable(resId);
        } catch (NotFoundException|MissingResourceException e) {
            Log.e(this, e, "Cannot find icon %d in package %s",
                    resId, mAccountHandle.getComponentName().getPackageName());
            return null;
        }
    }

    //
    // Parcelable implementation
    //

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeParcelable(mAccountHandle, 0);
        out.writeParcelable(mHandle, 0);
        out.writeString(mSubscriptionNumber);
        out.writeInt(mCapabilities);
        out.writeInt(mIconResId);
        out.writeCharSequence(mLabel);
        out.writeCharSequence(mShortDescription);
    }

    public static final Creator<PhoneAccount> CREATOR
            = new Creator<PhoneAccount>() {
        @Override
        public PhoneAccount createFromParcel(Parcel in) {
            return new PhoneAccount(in);
        }

        @Override
        public PhoneAccount[] newArray(int size) {
            return new PhoneAccount[size];
        }
    };

    private PhoneAccount(Parcel in) {
        mAccountHandle = in.readParcelable(getClass().getClassLoader());
        mHandle = in.readParcelable(getClass().getClassLoader());
        mSubscriptionNumber = in.readString();
        mCapabilities = in.readInt();
        mIconResId = in.readInt();
        mLabel = in.readCharSequence();
        mShortDescription = in.readCharSequence();
    }
}
