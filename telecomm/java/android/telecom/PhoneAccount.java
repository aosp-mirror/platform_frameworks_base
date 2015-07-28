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

import android.annotation.SystemApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources.NotFoundException;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.lang.String;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.MissingResourceException;

/**
 * Represents a distinct method to place or receive a phone call. Apps which can place calls and
 * want those calls to be integrated into the dialer and in-call UI should build an instance of
 * this class and register it with the system using {@link TelecomManager}.
 * <p>
 * {@link TelecomManager} uses registered {@link PhoneAccount}s to present the user with
 * alternative options when placing a phone call. When building a {@link PhoneAccount}, the app
 * should supply a valid {@link PhoneAccountHandle} that references the connection service
 * implementation Telecom will use to interact with the app.
 */
public final class PhoneAccount implements Parcelable {

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
     * or place calls from the built-in telephony stack.
     * <p>
     * See {@link #getCapabilities}
     * <p>
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

    /**
     * Flag indicating that this {@code PhoneAccount} is capable of placing emergency calls.
     * By default all PSTN {@code PhoneAccount}s are capable of placing emergency calls.
     * <p>
     * See {@link #getCapabilities}
     */
    public static final int CAPABILITY_PLACE_EMERGENCY_CALLS = 0x10;

    /**
     * Flag indicating that this {@code PhoneAccount} is capable of being used by all users. This
     * should only be used by system apps (and will be ignored for all other apps trying to use it).
     * <p>
     * See {@link #getCapabilities}
     * @hide
     */
    @SystemApi
    public static final int CAPABILITY_MULTI_USER = 0x20;

    /**
     * Flag indicating that this {@code PhoneAccount} supports a subject for Calls.  This means a
     * caller is able to specify a short subject line for an outgoing call.  A capable receiving
     * device displays the call subject on the incoming call screen.
     * <p>
     * See {@link #getCapabilities}
     */
    public static final int CAPABILITY_CALL_SUBJECT = 0x40;

    /**
     * URI scheme for telephone number URIs.
     */
    public static final String SCHEME_TEL = "tel";

    /**
     * URI scheme for voicemail URIs.
     */
    public static final String SCHEME_VOICEMAIL = "voicemail";

    /**
     * URI scheme for SIP URIs.
     */
    public static final String SCHEME_SIP = "sip";

    /**
     * Indicating no icon tint is set.
     * @hide
     */
    public static final int NO_ICON_TINT = 0;

    /**
     * Indicating no hightlight color is set.
     */
    public static final int NO_HIGHLIGHT_COLOR = 0;

    /**
     * Indicating no resource ID is set.
     */
    public static final int NO_RESOURCE_ID = -1;

    private final PhoneAccountHandle mAccountHandle;
    private final Uri mAddress;
    private final Uri mSubscriptionAddress;
    private final int mCapabilities;
    private final int mHighlightColor;
    private final CharSequence mLabel;
    private final CharSequence mShortDescription;
    private final List<String> mSupportedUriSchemes;
    private final Icon mIcon;
    private boolean mIsEnabled;

    /**
     * Helper class for creating a {@link PhoneAccount}.
     */
    public static class Builder {
        private PhoneAccountHandle mAccountHandle;
        private Uri mAddress;
        private Uri mSubscriptionAddress;
        private int mCapabilities;
        private int mHighlightColor = NO_HIGHLIGHT_COLOR;
        private CharSequence mLabel;
        private CharSequence mShortDescription;
        private List<String> mSupportedUriSchemes = new ArrayList<String>();
        private Icon mIcon;
        private boolean mIsEnabled = false;

        /**
         * Creates a builder with the specified {@link PhoneAccountHandle} and label.
         */
        public Builder(PhoneAccountHandle accountHandle, CharSequence label) {
            this.mAccountHandle = accountHandle;
            this.mLabel = label;
        }

        /**
         * Creates an instance of the {@link PhoneAccount.Builder} from an existing
         * {@link PhoneAccount}.
         *
         * @param phoneAccount The {@link PhoneAccount} used to initialize the builder.
         */
        public Builder(PhoneAccount phoneAccount) {
            mAccountHandle = phoneAccount.getAccountHandle();
            mAddress = phoneAccount.getAddress();
            mSubscriptionAddress = phoneAccount.getSubscriptionAddress();
            mCapabilities = phoneAccount.getCapabilities();
            mHighlightColor = phoneAccount.getHighlightColor();
            mLabel = phoneAccount.getLabel();
            mShortDescription = phoneAccount.getShortDescription();
            mSupportedUriSchemes.addAll(phoneAccount.getSupportedUriSchemes());
            mIcon = phoneAccount.getIcon();
            mIsEnabled = phoneAccount.isEnabled();
        }

        /**
         * Sets the address. See {@link PhoneAccount#getAddress}.
         *
         * @param value The address of the phone account.
         * @return The builder.
         */
        public Builder setAddress(Uri value) {
            this.mAddress = value;
            return this;
        }

        /**
         * Sets the subscription address. See {@link PhoneAccount#getSubscriptionAddress}.
         *
         * @param value The subscription address.
         * @return The builder.
         */
        public Builder setSubscriptionAddress(Uri value) {
            this.mSubscriptionAddress = value;
            return this;
        }

        /**
         * Sets the capabilities. See {@link PhoneAccount#getCapabilities}.
         *
         * @param value The capabilities to set.
         * @return The builder.
         */
        public Builder setCapabilities(int value) {
            this.mCapabilities = value;
            return this;
        }

        /**
         * Sets the icon. See {@link PhoneAccount#getIcon}.
         *
         * @param icon The icon to set.
         */
        public Builder setIcon(Icon icon) {
            mIcon = icon;
            return this;
        }

        /**
         * Sets the highlight color. See {@link PhoneAccount#getHighlightColor}.
         *
         * @param value The highlight color.
         * @return The builder.
         */
        public Builder setHighlightColor(int value) {
            this.mHighlightColor = value;
            return this;
        }

        /**
         * Sets the short description. See {@link PhoneAccount#getShortDescription}.
         *
         * @param value The short description.
         * @return The builder.
         */
        public Builder setShortDescription(CharSequence value) {
            this.mShortDescription = value;
            return this;
        }

        /**
         * Specifies an additional URI scheme supported by the {@link PhoneAccount}.
         *
         * @param uriScheme The URI scheme.
         * @return The builder.
         */
        public Builder addSupportedUriScheme(String uriScheme) {
            if (!TextUtils.isEmpty(uriScheme) && !mSupportedUriSchemes.contains(uriScheme)) {
                this.mSupportedUriSchemes.add(uriScheme);
            }
            return this;
        }

        /**
         * Specifies the URI schemes supported by the {@link PhoneAccount}.
         *
         * @param uriSchemes The URI schemes.
         * @return The builder.
         */
        public Builder setSupportedUriSchemes(List<String> uriSchemes) {
            mSupportedUriSchemes.clear();

            if (uriSchemes != null && !uriSchemes.isEmpty()) {
                for (String uriScheme : uriSchemes) {
                    addSupportedUriScheme(uriScheme);
                }
            }
            return this;
        }

        /**
         * Sets the enabled state of the phone account.
         *
         * @param isEnabled The enabled state.
         * @return The builder.
         * @hide
         */
        public Builder setIsEnabled(boolean isEnabled) {
            mIsEnabled = isEnabled;
            return this;
        }

        /**
         * Creates an instance of a {@link PhoneAccount} based on the current builder settings.
         *
         * @return The {@link PhoneAccount}.
         */
        public PhoneAccount build() {
            // If no supported URI schemes were defined, assume "tel" is supported.
            if (mSupportedUriSchemes.isEmpty()) {
                addSupportedUriScheme(SCHEME_TEL);
            }

            return new PhoneAccount(
                    mAccountHandle,
                    mAddress,
                    mSubscriptionAddress,
                    mCapabilities,
                    mIcon,
                    mHighlightColor,
                    mLabel,
                    mShortDescription,
                    mSupportedUriSchemes,
                    mIsEnabled);
        }
    }

    private PhoneAccount(
            PhoneAccountHandle account,
            Uri address,
            Uri subscriptionAddress,
            int capabilities,
            Icon icon,
            int highlightColor,
            CharSequence label,
            CharSequence shortDescription,
            List<String> supportedUriSchemes,
            boolean isEnabled) {
        mAccountHandle = account;
        mAddress = address;
        mSubscriptionAddress = subscriptionAddress;
        mCapabilities = capabilities;
        mIcon = icon;
        mHighlightColor = highlightColor;
        mLabel = label;
        mShortDescription = shortDescription;
        mSupportedUriSchemes = Collections.unmodifiableList(supportedUriSchemes);
        mIsEnabled = isEnabled;
    }

    public static Builder builder(
            PhoneAccountHandle accountHandle,
            CharSequence label) {
        return new Builder(accountHandle, label);
    }

    /**
     * Returns a builder initialized with the current {@link PhoneAccount} instance.
     *
     * @return The builder.
     */
    public Builder toBuilder() { return new Builder(this); }

    /**
     * The unique identifier of this {@code PhoneAccount}.
     *
     * @return A {@code PhoneAccountHandle}.
     */
    public PhoneAccountHandle getAccountHandle() {
        return mAccountHandle;
    }

    /**
     * The address (e.g., a phone number) associated with this {@code PhoneAccount}. This
     * represents the destination from which outgoing calls using this {@code PhoneAccount}
     * will appear to come, if applicable, and the destination to which incoming calls using this
     * {@code PhoneAccount} may be addressed.
     *
     * @return A address expressed as a {@code Uri}, for example, a phone number.
     */
    public Uri getAddress() {
        return mAddress;
    }

    /**
     * The raw callback number used for this {@code PhoneAccount}, as distinct from
     * {@link #getAddress()}. For the majority of {@code PhoneAccount}s this should be registered
     * as {@code null}.  It is used by the system for SIM-based {@code PhoneAccount} registration
     * where {@link android.telephony.TelephonyManager#setLine1NumberForDisplay(String, String)}
     * has been used to alter the callback number.
     * <p>
     *
     * @return The subscription number, suitable for display to the user.
     */
    public Uri getSubscriptionAddress() {
        return mSubscriptionAddress;
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
     * Determines if this {@code PhoneAccount} has a capabilities specified by the passed in
     * bit mask.
     *
     * @param capability The capabilities to check.
     * @return {@code true} if the phone account has the capability.
     */
    public boolean hasCapabilities(int capability) {
        return (mCapabilities & capability) == capability;
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
     * The URI schemes supported by this {@code PhoneAccount}.
     *
     * @return The URI schemes.
     */
    public List<String> getSupportedUriSchemes() {
        return mSupportedUriSchemes;
    }

    /**
     * The icon to represent this {@code PhoneAccount}.
     *
     * @return The icon.
     */
    public Icon getIcon() {
        return mIcon;
    }

    /**
     * Indicates whether the user has enabled this {@code PhoneAccount} or not. This value is only
     * populated for {@code PhoneAccount}s returned by {@link TelecomManager#getPhoneAccount}.
     *
     * @return {@code true} if the account is enabled by the user, {@code false} otherwise.
     */
    public boolean isEnabled() {
        return mIsEnabled;
    }

    /**
     * Determines if the {@link PhoneAccount} supports calls to/from addresses with a specified URI
     * scheme.
     *
     * @param uriScheme The URI scheme to check.
     * @return {@code true} if the {@code PhoneAccount} supports calls to/from addresses with the
     * specified URI scheme.
     */
    public boolean supportsUriScheme(String uriScheme) {
        if (mSupportedUriSchemes == null || uriScheme == null) {
            return false;
        }

        for (String scheme : mSupportedUriSchemes) {
            if (scheme != null && scheme.equals(uriScheme)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A highlight color to use in displaying information about this {@code PhoneAccount}.
     *
     * @return A hexadecimal color value.
     */
    public int getHighlightColor() {
        return mHighlightColor;
    }

    /**
     * Sets the enabled state of the phone account.
     * @hide
     */
    public void setIsEnabled(boolean isEnabled) {
        mIsEnabled = isEnabled;
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
        if (mAccountHandle == null) {
            out.writeInt(0);
        } else {
            out.writeInt(1);
            mAccountHandle.writeToParcel(out, flags);
        }
        if (mAddress == null) {
            out.writeInt(0);
        } else {
            out.writeInt(1);
            mAddress.writeToParcel(out, flags);
        }
        if (mSubscriptionAddress == null) {
            out.writeInt(0);
        } else {
            out.writeInt(1);
            mSubscriptionAddress.writeToParcel(out, flags);
        }
        out.writeInt(mCapabilities);
        out.writeInt(mHighlightColor);
        out.writeCharSequence(mLabel);
        out.writeCharSequence(mShortDescription);
        out.writeStringList(mSupportedUriSchemes);

        if (mIcon == null) {
            out.writeInt(0);
        } else {
            out.writeInt(1);
            mIcon.writeToParcel(out, flags);
        }
        out.writeByte((byte) (mIsEnabled ? 1 : 0));
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
        if (in.readInt() > 0) {
            mAccountHandle = PhoneAccountHandle.CREATOR.createFromParcel(in);
        } else {
            mAccountHandle = null;
        }
        if (in.readInt() > 0) {
            mAddress = Uri.CREATOR.createFromParcel(in);
        } else {
            mAddress = null;
        }
        if (in.readInt() > 0) {
            mSubscriptionAddress = Uri.CREATOR.createFromParcel(in);
        } else {
            mSubscriptionAddress = null;
        }
        mCapabilities = in.readInt();
        mHighlightColor = in.readInt();
        mLabel = in.readCharSequence();
        mShortDescription = in.readCharSequence();
        mSupportedUriSchemes = Collections.unmodifiableList(in.createStringArrayList());
        if (in.readInt() > 0) {
            mIcon = Icon.CREATOR.createFromParcel(in);
        } else {
            mIcon = null;
        }
        mIsEnabled = in.readByte() == 1;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder().append("[[")
                .append(mIsEnabled ? 'X' : ' ')
                .append("] PhoneAccount: ")
                .append(mAccountHandle)
                .append(" Capabilities: ")
                .append(mCapabilities)
                .append(" Schemes: ");
        for (String scheme : mSupportedUriSchemes) {
            sb.append(scheme)
                    .append(" ");
        }
        sb.append("]");
        return sb.toString();
    }
}
