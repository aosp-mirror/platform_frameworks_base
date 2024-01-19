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

import static android.Manifest.permission.MODIFY_PHONE_STATE;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.CarrierConfigManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArraySet;

import com.android.internal.telephony.flags.Flags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
     * Integer extra which determines the order in which {@link PhoneAccount}s are sorted
     *
     * This is an extras key set via {@link Builder#setExtras} which determines the order in which
     * {@link PhoneAccount}s from the same {@link ConnectionService} are sorted. The accounts
     * are sorted in ascending order by this key, and this ordering is used to
     * determine priority when a call can be placed via multiple accounts.
     *
     * When multiple {@link PhoneAccount}s are supplied with the same sort order key, no ordering is
     * guaranteed between those {@link PhoneAccount}s. Additionally, no ordering is guaranteed
     * between {@link PhoneAccount}s that do not supply this extra, and all such accounts
     * will be sorted after the accounts that do supply this extra.
     *
     * An example of a sort order key is slot index (see {@link TelephonyManager#getSlotIndex()}),
     * which is the one used by the cell Telephony stack.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_SORT_ORDER =
            "android.telecom.extra.SORT_ORDER";

    /**
     * {@link PhoneAccount} extras key (see {@link PhoneAccount#getExtras()}) which determines the
     * maximum permitted length of a call subject specified via the
     * {@link TelecomManager#EXTRA_CALL_SUBJECT} extra on an
     * {@link android.content.Intent#ACTION_CALL} intent.  Ultimately a {@link ConnectionService} is
     * responsible for enforcing the maximum call subject length when sending the message, however
     * this extra is provided so that the user interface can proactively limit the length of the
     * call subject as the user types it.
     */
    public static final String EXTRA_CALL_SUBJECT_MAX_LENGTH =
            "android.telecom.extra.CALL_SUBJECT_MAX_LENGTH";

    /**
     * {@link PhoneAccount} extras key (see {@link PhoneAccount#getExtras()}) which determines the
     * character encoding to be used when determining the length of messages.
     * The user interface can use this when determining the number of characters the user may type
     * in a call subject.  If empty-string, the call subject message size limit will be enforced on
     * a 1:1 basis.  That is, each character will count towards the messages size limit as a single
     * character.  If a character encoding is specified, the message size limit will be based on the
     * number of bytes in the message per the specified encoding.  See
     * {@link #EXTRA_CALL_SUBJECT_MAX_LENGTH} for more information on the call subject maximum
     * length.
     */
    public static final String EXTRA_CALL_SUBJECT_CHARACTER_ENCODING =
            "android.telecom.extra.CALL_SUBJECT_CHARACTER_ENCODING";

    /**
     * Boolean {@link PhoneAccount} extras key (see {@link PhoneAccount#getExtras()}) which
     * indicates that all calls from this {@link PhoneAccount} should be treated as VoIP calls
     * rather than cellular calls by the Telecom audio handling logic.
     */
    public static final String EXTRA_ALWAYS_USE_VOIP_AUDIO_MODE =
            "android.telecom.extra.ALWAYS_USE_VOIP_AUDIO_MODE";

    /**
     * Boolean {@link PhoneAccount} extras key (see {@link PhoneAccount#getExtras()}) which
     * indicates whether this {@link PhoneAccount} is capable of supporting a request to handover a
     * connection (see {@code android.telecom.Call#handoverTo()}) to this {@link PhoneAccount} from
     * a {@link PhoneAccount} specifying {@link #EXTRA_SUPPORTS_HANDOVER_FROM}.
     * <p>
     * A handover request is initiated by the user from the default dialer app to indicate a desire
     * to handover a call from one {@link PhoneAccount}/{@link ConnectionService} to another.
     */
    public static final String EXTRA_SUPPORTS_HANDOVER_TO =
            "android.telecom.extra.SUPPORTS_HANDOVER_TO";

    /**
     * Boolean {@link PhoneAccount} extras key (see {@link PhoneAccount#getExtras()}) which
     * indicates whether this {@link PhoneAccount} supports using a fallback if video calling is
     * not available. This extra is for device level support, {@link
     * android.telephony.CarrierConfigManager#KEY_ALLOW_VIDEO_CALLING_FALLBACK_BOOL} should also
     * be checked to ensure it is not disabled by individual carrier.
     *
     * @hide
     */
    public static final String EXTRA_SUPPORTS_VIDEO_CALLING_FALLBACK =
            "android.telecom.extra.SUPPORTS_VIDEO_CALLING_FALLBACK";

    /**
     * Boolean {@link PhoneAccount} extras key (see {@link PhoneAccount#getExtras()}) which
     * indicates whether this {@link PhoneAccount} is capable of supporting a request to handover a
     * connection from this {@link PhoneAccount} to another {@link PhoneAccount}.
     * (see {@code android.telecom.Call#handoverTo()}) which specifies
     * {@link #EXTRA_SUPPORTS_HANDOVER_TO}.
     * <p>
     * A handover request is initiated by the user from the default dialer app to indicate a desire
     * to handover a call from one {@link PhoneAccount}/{@link ConnectionService} to another.
     */
    public static final String EXTRA_SUPPORTS_HANDOVER_FROM =
            "android.telecom.extra.SUPPORTS_HANDOVER_FROM";


    /**
     * Boolean {@link PhoneAccount} extras key (see {@link PhoneAccount#getExtras()}) which
     * indicates whether a Self-Managed {@link PhoneAccount} should log its calls to the call log.
     * Self-Managed {@link PhoneAccount}s are responsible for their own notifications, so the system
     * will not create a notification when a missed call is logged.
     * <p>
     * By default, Self-Managed {@link PhoneAccount}s do not log their calls to the call log.
     * Setting this extra to {@code true} provides a means for them to log their calls.
     * <p>
     * Note: Only calls where the {@link Call.Details#getHandle()} {@link Uri#getScheme()} is
     * {@link #SCHEME_SIP} or {@link #SCHEME_TEL} will be logged at the current time.
     */
    public static final String EXTRA_LOG_SELF_MANAGED_CALLS =
            "android.telecom.extra.LOG_SELF_MANAGED_CALLS";

    /**
     * Boolean {@link PhoneAccount} extras key (see {@link PhoneAccount#getExtras()}) which
     * indicates whether calls for a {@link PhoneAccount} should generate a "call recording tone"
     * when the user is recording audio on the device.
     * <p>
     * The call recording tone is played over the telephony audio stream so that the remote party
     * has an audible indication that it is possible their call is being recorded using a call
     * recording app on the device.
     * <p>
     * This extra only has an effect for calls placed via Telephony (e.g.
     * {@link #CAPABILITY_SIM_SUBSCRIPTION}).
     * <p>
     * The call recording tone is a 1400 hz tone which repeats every 15 seconds while recording is
     * in progress.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_PLAY_CALL_RECORDING_TONE =
            "android.telecom.extra.PLAY_CALL_RECORDING_TONE";

    /**
     * Boolean {@link PhoneAccount} extras key (see {@link PhoneAccount#getExtras()} which
     * indicates whether calls for a {@link PhoneAccount} should skip call filtering.
     * <p>
     * If not specified, this will default to false; all calls will undergo call filtering unless
     * specifically exempted (e.g. {@link Connection#PROPERTY_EMERGENCY_CALLBACK_MODE}.) However,
     * this may be used to skip call filtering when it has already been performed on another device.
     * @hide
     */
    public static final String EXTRA_SKIP_CALL_FILTERING =
        "android.telecom.extra.SKIP_CALL_FILTERING";

    /**
     * Boolean {@link PhoneAccount} extras key (see {@link PhoneAccount#getExtras()}) which
     * indicates whether a Self-managed {@link PhoneAccount} want to expose its calls to all
     * {@link InCallService} which declares the metadata
     * {@link TelecomManager#METADATA_INCLUDE_SELF_MANAGED_CALLS}.
     */
    public static final String EXTRA_ADD_SELF_MANAGED_CALLS_TO_INCALLSERVICE =
            "android.telecom.extra.ADD_SELF_MANAGED_CALLS_TO_INCALLSERVICE";

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
     * Flag indicating that this {@code PhoneAccount} is currently able to place video calls.
     * <p>
     * See also {@link #CAPABILITY_SUPPORTS_VIDEO_CALLING} which indicates whether the
     * {@code PhoneAccount} supports placing video calls.
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
     * Flag indicating that this {@code PhoneAccount} should only be used for emergency calls.
     * <p>
     * See {@link #getCapabilities}
     * @hide
     */
    @SystemApi
    public static final int CAPABILITY_EMERGENCY_CALLS_ONLY = 0x80;

    /**
     * Flag indicating that for this {@code PhoneAccount}, the ability to make a video call to a
     * number relies on presence.  Should only be set if the {@code PhoneAccount} also has
     * {@link #CAPABILITY_VIDEO_CALLING}.
     * <p>
     * Note: As of Android 12, using the
     * {@link android.provider.ContactsContract.Data#CARRIER_PRESENCE_VT_CAPABLE} bit on the
     * {@link android.provider.ContactsContract.Data#CARRIER_PRESENCE} column to indicate whether
     * a contact's phone number supports video calling has been deprecated and should only be used
     * on devices where {@link CarrierConfigManager#KEY_USE_RCS_PRESENCE_BOOL} is set. On newer
     * devices, applications must use {@link android.telephony.ims.RcsUceAdapter} instead to
     * determine whether or not a contact's phone number supports carrier video calling.
     * <p>
     * See {@link #getCapabilities}
     */
    public static final int CAPABILITY_VIDEO_CALLING_RELIES_ON_PRESENCE = 0x100;

    /**
     * Flag indicating that for this {@link PhoneAccount}, emergency video calling is allowed.
     * <p>
     * When set, Telecom will allow emergency video calls to be placed.  When not set, Telecom will
     * convert all outgoing video calls to emergency numbers to audio-only.
     * @hide
     */
    @SystemApi
    public static final int CAPABILITY_EMERGENCY_VIDEO_CALLING = 0x200;

    /**
     * Flag indicating that this {@link PhoneAccount} supports video calling.
     * This is not an indication that the {@link PhoneAccount} is currently able to make a video
     * call, but rather that it has the ability to make video calls (but not necessarily at this
     * time).
     * <p>
     * Whether a {@link PhoneAccount} can make a video call is ultimately controlled by
     * {@link #CAPABILITY_VIDEO_CALLING}, which indicates whether the {@link PhoneAccount} is
     * currently capable of making a video call.  Consider a case where, for example, a
     * {@link PhoneAccount} supports making video calls (e.g.
     * {@link #CAPABILITY_SUPPORTS_VIDEO_CALLING}), but a current lack of network connectivity
     * prevents video calls from being made (e.g. {@link #CAPABILITY_VIDEO_CALLING}).
     * <p>
     * See {@link #getCapabilities}
     */
    public static final int CAPABILITY_SUPPORTS_VIDEO_CALLING = 0x400;

    /**
     * Flag indicating that this {@link PhoneAccount} is responsible for managing its own
     * {@link Connection}s.  This type of {@link PhoneAccount} is ideal for use with standalone
     * calling apps which do not wish to use the default phone app for {@link Connection} UX,
     * but which want to leverage the call and audio routing capabilities of the Telecom framework.
     * <p>
     * When set, {@link Connection}s created by the self-managed {@link ConnectionService} will not
     * be surfaced to implementations of the {@link InCallService} API.  Thus it is the
     * responsibility of a self-managed {@link ConnectionService} to provide a user interface for
     * its {@link Connection}s.
     * <p>
     * Self-managed {@link Connection}s will, however, be displayed on connected Bluetooth devices.
     */
    public static final int CAPABILITY_SELF_MANAGED = 0x800;

    /**
     * Flag indicating that this {@link PhoneAccount} is capable of making a call with an
     * RTT (Real-time text) session.
     * When set, Telecom will attempt to open an RTT session on outgoing calls that specify
     * that they should be placed with an RTT session , and the in-call app will be displayed
     * with text entry fields for RTT. Likewise, the in-call app can request that an RTT
     * session be opened during a call if this bit is set.
     */
    public static final int CAPABILITY_RTT = 0x1000;

    /**
     * Flag indicating that this {@link PhoneAccount} is the preferred SIM subscription for
     * emergency calls. A {@link PhoneAccount} that sets this capability must also
     * set the {@link #CAPABILITY_SIM_SUBSCRIPTION} and {@link #CAPABILITY_PLACE_EMERGENCY_CALLS}
     * capabilities. There must only be one emergency preferred {@link PhoneAccount} on the device.
     * <p>
     * When set, Telecom will prefer this {@link PhoneAccount} over others for emergency calling,
     * even if the emergency call was placed with a specific {@link PhoneAccount} set using the
     * extra{@link TelecomManager#EXTRA_PHONE_ACCOUNT_HANDLE} in
     * {@link Intent#ACTION_CALL_EMERGENCY} or {@link TelecomManager#placeCall(Uri, Bundle)}.
     *
     * @hide
     */
    @SystemApi
    public static final int CAPABILITY_EMERGENCY_PREFERRED = 0x2000;

    /**
     * An adhoc conference call is established by providing a list of addresses to
     * {@code TelecomManager#startConference(List<Uri>, int videoState)} where the
     * {@link ConnectionService} is responsible for connecting all indicated participants
     * to a conference simultaneously.
     * This is in contrast to conferences formed by merging calls together (e.g. using
     * {@link android.telecom.Call#mergeConference()}).
     */
    public static final int CAPABILITY_ADHOC_CONFERENCE_CALLING = 0x4000;

    /**
     * Flag indicating whether this {@link PhoneAccount} is capable of supporting the call composer
     * functionality for enriched calls.
     */
    public static final int CAPABILITY_CALL_COMPOSER = 0x8000;

    /**
     * Flag indicating that this {@link PhoneAccount} provides SIM-based voice calls, potentially as
     * an over-the-top solution such as wi-fi calling.
     *
     * <p>Similar to {@link #CAPABILITY_SUPPORTS_VIDEO_CALLING}, this capability indicates this
     * {@link PhoneAccount} has the ability to make voice calls (but not necessarily at this time).
     * Whether this {@link PhoneAccount} can make a voice call is ultimately controlled by {@link
     * #CAPABILITY_VOICE_CALLING_AVAILABLE}, which indicates whether this {@link PhoneAccount} is
     * currently capable of making a voice call. Consider a case where, for example, a {@link
     * PhoneAccount} supports making voice calls (e.g. {@link
     * #CAPABILITY_SUPPORTS_VOICE_CALLING_INDICATIONS}), but a current lack of network connectivity
     * prevents voice calls from being made (e.g. {@link #CAPABILITY_VOICE_CALLING_AVAILABLE}).
     *
     * <p>In order to declare this capability, this {@link PhoneAccount} must also declare {@link
     * #CAPABILITY_SIM_SUBSCRIPTION} or {@link #CAPABILITY_CONNECTION_MANAGER} and satisfy the
     * associated requirements.
     *
     * @see #CAPABILITY_VOICE_CALLING_AVAILABLE
     * @see #getCapabilities
     */
    public static final int CAPABILITY_SUPPORTS_VOICE_CALLING_INDICATIONS = 0x10000;

    /**
     * Flag indicating that this {@link PhoneAccount} is <em>currently</em> able to place SIM-based
     * voice calls, similar to {@link #CAPABILITY_VIDEO_CALLING}.
     *
     * <p>See also {@link #CAPABILITY_SUPPORTS_VOICE_CALLING_INDICATIONS}, which indicates whether
     * the {@code PhoneAccount} supports placing SIM-based voice calls or not.
     *
     * <p>In order to declare this capability, this {@link PhoneAccount} must also declare {@link
     * #CAPABILITY_SIM_SUBSCRIPTION} or {@link #CAPABILITY_CONNECTION_MANAGER} and satisfy the
     * associated requirements.
     *
     * @see #CAPABILITY_SUPPORTS_VOICE_CALLING_INDICATIONS
     * @see #getCapabilities
     */
    public static final int CAPABILITY_VOICE_CALLING_AVAILABLE = 0x20000;


    /**
     * Flag indicating that this {@link PhoneAccount} supports the use TelecomManager APIs that
     * utilize {@link android.os.OutcomeReceiver}s or {@link java.util.function.Consumer}s.
     * Be aware, if this capability is set, {@link #CAPABILITY_SELF_MANAGED} will be amended by
     * Telecom when this {@link PhoneAccount} is registered via
     * {@link TelecomManager#registerPhoneAccount(PhoneAccount)}.
     *
     * <p>
     * {@link android.os.OutcomeReceiver}s and {@link java.util.function.Consumer}s represent
     * transactional operations because the operation can succeed or fail.  An app wishing to use
     * transactional operations should define behavior for a successful and failed TelecomManager
     * API call.
     *
     * @see #CAPABILITY_SELF_MANAGED
     * @see #getCapabilities
     */
    public static final int CAPABILITY_SUPPORTS_TRANSACTIONAL_OPERATIONS = 0x40000;

    /**
     * Flag indicating that this voip app {@link PhoneAccount} supports the call streaming session
     * to stream call audio to another remote device via streaming app.
     *
     * @see #getCapabilities
     */
    public static final int CAPABILITY_SUPPORTS_CALL_STREAMING = 0x80000;

    /* NEXT CAPABILITY: [0x100000, 0x200000, 0x400000] */

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
    private final int mSupportedAudioRoutes;
    private final Icon mIcon;
    private final Bundle mExtras;
    private boolean mIsEnabled;
    private String mGroupId;
    private final Set<PhoneAccountHandle> mSimultaneousCallingRestriction;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PhoneAccount that = (PhoneAccount) o;
        return mCapabilities == that.mCapabilities &&
                mHighlightColor == that.mHighlightColor &&
                mSupportedAudioRoutes == that.mSupportedAudioRoutes &&
                mIsEnabled == that.mIsEnabled &&
                Objects.equals(mAccountHandle, that.mAccountHandle) &&
                Objects.equals(mAddress, that.mAddress) &&
                Objects.equals(mSubscriptionAddress, that.mSubscriptionAddress) &&
                Objects.equals(mLabel, that.mLabel) &&
                Objects.equals(mShortDescription, that.mShortDescription) &&
                Objects.equals(mSupportedUriSchemes, that.mSupportedUriSchemes) &&
                areBundlesEqual(mExtras, that.mExtras) &&
                Objects.equals(mGroupId, that.mGroupId)
                && Objects.equals(mSimultaneousCallingRestriction,
                        that.mSimultaneousCallingRestriction);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAccountHandle, mAddress, mSubscriptionAddress, mCapabilities,
                mHighlightColor, mLabel, mShortDescription, mSupportedUriSchemes,
                mSupportedAudioRoutes,
                mExtras, mIsEnabled, mGroupId, mSimultaneousCallingRestriction);
    }

    /**
     * Helper class for creating a {@link PhoneAccount}.
     */
    public static class Builder {

        private PhoneAccountHandle mAccountHandle;
        private Uri mAddress;
        private Uri mSubscriptionAddress;
        private int mCapabilities;
        private int mSupportedAudioRoutes = CallAudioState.ROUTE_ALL;
        private int mHighlightColor = NO_HIGHLIGHT_COLOR;
        private CharSequence mLabel;
        private CharSequence mShortDescription;
        private List<String> mSupportedUriSchemes = new ArrayList<String>();
        private Icon mIcon;
        private Bundle mExtras;
        private boolean mIsEnabled = false;
        private String mGroupId = "";
        private Set<PhoneAccountHandle> mSimultaneousCallingRestriction = null;

        /**
         * Creates a builder with the specified {@link PhoneAccountHandle} and label.
         * <p>
         * Note: each CharSequence or String field is limited to 256 characters. This check is
         * enforced when registering the PhoneAccount via
         * {@link TelecomManager#registerPhoneAccount(PhoneAccount)} and will cause an
         * {@link IllegalArgumentException} to be thrown if the character field limit is over 256.
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
            mExtras = phoneAccount.getExtras();
            mGroupId = phoneAccount.getGroupId();
            mSupportedAudioRoutes = phoneAccount.getSupportedAudioRoutes();
        }

        /**
         * Sets the label. See {@link PhoneAccount#getLabel()}.
         * <p>
         * Note: Each CharSequence or String field is limited to 256 characters. This check is
         * enforced when registering the PhoneAccount via
         * {@link TelecomManager#registerPhoneAccount(PhoneAccount)} and will cause an
         * {@link IllegalArgumentException} to be thrown if the character field limit is over 256.
         *
         * @param label The label of the phone account.
         * @return The builder.
         * @hide
         */
        public Builder setLabel(CharSequence label) {
            this.mLabel = label;
            return this;
        }

        /**
         * Sets the address. See {@link PhoneAccount#getAddress}.
         * <p>
         * Note: The entire URI value is limited to 256 characters. This check is
         * enforced when registering the PhoneAccount via
         * {@link TelecomManager#registerPhoneAccount(PhoneAccount)} and will cause an
         * {@link IllegalArgumentException} to be thrown if URI is over 256.
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
         * <p>
         * Note: An {@link IllegalArgumentException} if the Icon cannot be written to memory.
         * This check is enforced when registering the PhoneAccount via
         * {@link TelecomManager#registerPhoneAccount(PhoneAccount)}
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
         * <p>
         * Note: Each CharSequence or String field is limited to 256 characters. This check is
         * enforced when registering the PhoneAccount via
         * {@link TelecomManager#registerPhoneAccount(PhoneAccount)} and will cause an
         * {@link IllegalArgumentException} to be thrown if the character field limit is over 256.
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
         * <p>
         * Each URI scheme is limited to 256 characters.  Adding a scheme over 256 characters will
         * cause an {@link IllegalArgumentException} to be thrown when the account is registered.
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
         * <p>
         * A max of 10 URI schemes can be added per account.  Additionally, each URI scheme is
         * limited to 256 characters. Adding more than 10 URI schemes or 256 characters on any
         * scheme will cause an {@link IllegalArgumentException} to be thrown when the account
         * is registered.
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
         * Specifies the extras associated with the {@link PhoneAccount}.
         * <p>
         * {@code PhoneAccount}s only support extra values of type: {@link String}, {@link Integer},
         * and {@link Boolean}.  Extras which are not of these types are ignored.
         * <p>
         * Note: Each Bundle (Key, Value) String field is limited to 256 characters. Additionally,
         * the bundle is limited to 100 (Key, Value) pairs total.  This check is
         * enforced when registering the PhoneAccount via
         * {@link TelecomManager#registerPhoneAccount(PhoneAccount)} and will cause an
         * {@link IllegalArgumentException} to be thrown if the character field limit is over 256
         * or more than 100 (Key, Value) pairs are in the Bundle.
         *
         * @param extras
         * @return
         */
        public Builder setExtras(Bundle extras) {
            mExtras = extras;
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
         * Sets the group Id of the {@link PhoneAccount}. When a new {@link PhoneAccount} is
         * registered to Telecom, it will replace another {@link PhoneAccount} that is already
         * registered in Telecom and take on the current user defaults and enabled status. There can
         * only be one {@link PhoneAccount} with a non-empty group number registered to Telecom at a
         * time. By default, there is no group Id for a {@link PhoneAccount} (an empty String). Only
         * grouped {@link PhoneAccount}s with the same {@link ConnectionService} can be replaced.
         * <p>
         * Note: This is an API specific to the Telephony stack; the group Id will be ignored for
         * callers not holding the correct permission.
         * <p>
         * Additionally, each CharSequence or String field is limited to 256 characters.
         * This check is enforced when registering the PhoneAccount via
         * {@link TelecomManager#registerPhoneAccount(PhoneAccount)} and will cause an
         * {@link IllegalArgumentException} to be thrown if the character field limit is over 256.
         *
         * @param groupId The group Id of the {@link PhoneAccount} that will replace any other
         * registered {@link PhoneAccount} in Telecom with the same Group Id.
         * @return The builder
         * @hide
         */
        @SystemApi
        @RequiresPermission(MODIFY_PHONE_STATE)
        public @NonNull Builder setGroupId(@NonNull String groupId) {
            if (groupId != null) {
                mGroupId = groupId;
            } else {
                mGroupId = "";
            }
            return this;
        }

        /**
         * Sets the audio routes supported by this {@link PhoneAccount}.
         *
         * @param routes bit mask of available routes.
         * @return The builder.
         * @hide
         */
        public Builder setSupportedAudioRoutes(int routes) {
            mSupportedAudioRoutes = routes;
            return this;
        }

        /**
         * Restricts the ability of this {@link PhoneAccount} to ONLY support simultaneous calling
         * with the other {@link PhoneAccountHandle}s in this Set.
         * <p>
         * If two or more {@link PhoneAccount}s support calling simultaneously, it means that
         * Telecom allows the user to place additional outgoing calls and receive additional
         * incoming calls using other {@link PhoneAccount}s while this PhoneAccount also has one or
         * more active calls.
         * <p>
         * If this setter method is never called or cleared using
         * {@link #clearSimultaneousCallingRestriction()}, there is no restriction and all
         * {@link PhoneAccount}s registered to Telecom by this package support simultaneous calling.
         * <p>
         * Note: Simultaneous calling restrictions can only be placed on {@link PhoneAccount}s that
         * were registered by the same application. Simultaneous calling across applications is
         * always possible as long as the {@link Connection} supports hold. If a
         * {@link PhoneAccountHandle} is included here and the package name doesn't match this
         * application's package name, {@link TelecomManager#registerPhoneAccount(PhoneAccount)}
         * will throw a {@link SecurityException}.
         *
         * @param handles The other {@link PhoneAccountHandle}s that support calling simultaneously
         * with this one. If set to null, there is no restriction and simultaneous calling is
         * supported across all {@link PhoneAccount}s registered by this package.
         * @return The Builder used to set up the new PhoneAccount.
         */
        @FlaggedApi(Flags.FLAG_SIMULTANEOUS_CALLING_INDICATIONS)
        public @NonNull Builder setSimultaneousCallingRestriction(
                @NonNull Set<PhoneAccountHandle> handles) {
            if (handles == null) {
                throw new IllegalArgumentException("the Set of PhoneAccountHandles must not be "
                        + "null");
            }
            mSimultaneousCallingRestriction = handles;
            return this;
        }

        /**
         * Clears a previously set simultaneous calling restriction set when
         * {@link PhoneAccount.Builder#Builder(PhoneAccount)} is used to create a new PhoneAccount
         * from an existing one.
         *
         * @return The Builder used to set up the new PhoneAccount.
         * @see #setSimultaneousCallingRestriction(Set)
         */
        @FlaggedApi(Flags.FLAG_SIMULTANEOUS_CALLING_INDICATIONS)
        public @NonNull Builder clearSimultaneousCallingRestriction() {
            mSimultaneousCallingRestriction = null;
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
                    mExtras,
                    mSupportedAudioRoutes,
                    mIsEnabled,
                    mGroupId,
                    mSimultaneousCallingRestriction);
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
            Bundle extras,
            int supportedAudioRoutes,
            boolean isEnabled,
            String groupId,
            Set<PhoneAccountHandle> simultaneousCallingRestriction) {
        mAccountHandle = account;
        mAddress = address;
        mSubscriptionAddress = subscriptionAddress;
        mCapabilities = capabilities;
        mIcon = icon;
        mHighlightColor = highlightColor;
        mLabel = label;
        mShortDescription = shortDescription;
        mSupportedUriSchemes = Collections.unmodifiableList(supportedUriSchemes);
        mExtras = extras;
        mSupportedAudioRoutes = supportedAudioRoutes;
        mIsEnabled = isEnabled;
        mGroupId = groupId;
        mSimultaneousCallingRestriction = simultaneousCallingRestriction;
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
     * Determines if this {@code PhoneAccount} has routes specified by the passed in bit mask.
     *
     * @param route The routes to check.
     * @return {@code true} if the phone account has the routes.
     * @hide
     */
    public boolean hasAudioRoutes(int routes) {
        return (mSupportedAudioRoutes & routes) == routes;
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
     * The extras associated with this {@code PhoneAccount}.
     * <p>
     * A {@link ConnectionService} may provide implementation specific information about the
     * {@link PhoneAccount} via the extras.
     *
     * @return The extras.
     */
    public Bundle getExtras() {
        return mExtras;
    }

    /**
     * The audio routes supported by this {@code PhoneAccount}.
     *
     * @hide
     */
    public int getSupportedAudioRoutes() {
        return mSupportedAudioRoutes;
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
     * A non-empty {@link String} representing the group that A {@link PhoneAccount} is in or an
     * empty {@link String} if the {@link PhoneAccount} is not in a group. If this
     * {@link PhoneAccount} is in a group, this new {@link PhoneAccount} will replace a registered
     * {@link PhoneAccount} that is in the same group. When the {@link PhoneAccount} is replaced,
     * its user defined defaults and enabled status will also pass to this new {@link PhoneAccount}.
     * Only {@link PhoneAccount}s that share the same {@link ConnectionService} can be replaced.
     *
     * @return A non-empty String Id if this {@link PhoneAccount} belongs to a group.
     * @hide
     */
    public String getGroupId() {
        return mGroupId;
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

    /**
     * @return {@code true} if the {@link PhoneAccount} is self-managed, {@code false} otherwise.
     * @hide
     */
    public boolean isSelfManaged() {
        return (mCapabilities & CAPABILITY_SELF_MANAGED) == CAPABILITY_SELF_MANAGED;
    }

    /**
     * If a restriction is set (see {@link #hasSimultaneousCallingRestriction()}), this method
     * returns the Set of {@link PhoneAccountHandle}s that are allowed to support calls
     * simultaneously with this {@link PhoneAccount}.
     * <p>
     * If this {@link PhoneAccount} is busy with one or more ongoing calls, a restriction is set on
     * this PhoneAccount (see {@link #hasSimultaneousCallingRestriction()} to check),  and a new
     * incoming or outgoing call is received or placed on a PhoneAccount that is not in this Set,
     * Telecom will reject or cancel the pending call in favor of keeping the ongoing call alive.
     * <p>
     * Note: Simultaneous calling restrictions can only be placed on {@link PhoneAccount}s that
     * were registered by the same application. Simultaneous calling across applications is
     * always possible as long as the {@link Connection} supports hold.
     *
     * @return the Set of {@link PhoneAccountHandle}s that this {@link PhoneAccount} supports
     * simultaneous calls with.
     * @throws IllegalStateException If there is no restriction set on this {@link PhoneAccount}
     * and this method is called. Whether or not there is a restriction can be checked using
     * {@link #hasSimultaneousCallingRestriction()}.
     */
    @FlaggedApi(Flags.FLAG_SIMULTANEOUS_CALLING_INDICATIONS)
    public @NonNull Set<PhoneAccountHandle> getSimultaneousCallingRestriction() {
        if (mSimultaneousCallingRestriction == null) {
            throw new IllegalStateException("This method can not be called if there is no "
                    + "simultaneous calling restriction. See #hasSimultaneousCallingRestriction");
        }
        return mSimultaneousCallingRestriction;
    }

    /**
     * Whether or not this {@link PhoneAccount} contains a simultaneous calling restriction on it.
     *
     * @return {@code true} if this PhoneAccount contains a simultaneous calling restriction,
     * {@code false} if it does not. Use {@link #getSimultaneousCallingRestriction()} to query which
     * other {@link PhoneAccount}s support simultaneous calling with this one.
     * @see #getSimultaneousCallingRestriction() for more information on how the sinultaneous
     * calling restriction works.
     */
    @FlaggedApi(Flags.FLAG_SIMULTANEOUS_CALLING_INDICATIONS)
    public boolean hasSimultaneousCallingRestriction() {
        return mSimultaneousCallingRestriction != null;
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
        out.writeBundle(mExtras);
        out.writeString(mGroupId);
        out.writeInt(mSupportedAudioRoutes);
        if (mSimultaneousCallingRestriction == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            out.writeTypedList(mSimultaneousCallingRestriction.stream().toList());
        }
    }

    public static final @android.annotation.NonNull Creator<PhoneAccount> CREATOR
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
        mExtras = in.readBundle();
        mGroupId = in.readString();
        mSupportedAudioRoutes = in.readInt();
        if (in.readBoolean()) {
            List<PhoneAccountHandle> list = new ArrayList<>();
            in.readTypedList(list, PhoneAccountHandle.CREATOR);
            mSimultaneousCallingRestriction = new ArraySet<>(list);
        } else {
            mSimultaneousCallingRestriction = null;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder().append("[[")
                .append(mIsEnabled ? 'X' : ' ')
                .append("] PhoneAccount: ")
                .append(mAccountHandle)
                .append(" Capabilities: ")
                .append(capabilitiesToString())
                .append(" Audio Routes: ")
                .append(audioRoutesToString())
                .append(" Schemes: ");
        for (String scheme : mSupportedUriSchemes) {
            sb.append(scheme)
                    .append(" ");
        }
        sb.append(" Extras: ");
        sb.append(mExtras);
        sb.append(" GroupId: ");
        sb.append(Log.pii(mGroupId));
        sb.append("]");
        return sb.toString();
    }

    /**
     * Generates a string representation of a capabilities bitmask.
     *
     * @return String representation of the capabilities bitmask.
     * @hide
     */
    public String capabilitiesToString() {
        StringBuilder sb = new StringBuilder();
        if (hasCapabilities(CAPABILITY_SELF_MANAGED)) {
            sb.append("SelfManaged ");
        }
        if (hasCapabilities(CAPABILITY_SUPPORTS_VIDEO_CALLING)) {
            sb.append("SuppVideo ");
        }
        if (hasCapabilities(CAPABILITY_VIDEO_CALLING)) {
            sb.append("Video ");
        }
        if (hasCapabilities(CAPABILITY_VIDEO_CALLING_RELIES_ON_PRESENCE)) {
            sb.append("Presence ");
        }
        if (hasCapabilities(CAPABILITY_CALL_PROVIDER)) {
            sb.append("CallProvider ");
        }
        if (hasCapabilities(CAPABILITY_CALL_SUBJECT)) {
            sb.append("CallSubject ");
        }
        if (hasCapabilities(CAPABILITY_CONNECTION_MANAGER)) {
            sb.append("ConnectionMgr ");
        }
        if (hasCapabilities(CAPABILITY_EMERGENCY_CALLS_ONLY)) {
            sb.append("EmergOnly ");
        }
        if (hasCapabilities(CAPABILITY_MULTI_USER)) {
            sb.append("MultiUser ");
        }
        if (hasCapabilities(CAPABILITY_PLACE_EMERGENCY_CALLS)) {
            sb.append("PlaceEmerg ");
        }
        if (hasCapabilities(CAPABILITY_EMERGENCY_PREFERRED)) {
            sb.append("EmerPrefer ");
        }
        if (hasCapabilities(CAPABILITY_EMERGENCY_VIDEO_CALLING)) {
            sb.append("EmergVideo ");
        }
        if (hasCapabilities(CAPABILITY_SIM_SUBSCRIPTION)) {
            sb.append("SimSub ");
        }
        if (hasCapabilities(CAPABILITY_RTT)) {
            sb.append("Rtt ");
        }
        if (hasCapabilities(CAPABILITY_ADHOC_CONFERENCE_CALLING)) {
            sb.append("AdhocConf ");
        }
        if (hasCapabilities(CAPABILITY_CALL_COMPOSER)) {
            sb.append("CallComposer ");
        }
        if (hasCapabilities(CAPABILITY_SUPPORTS_VOICE_CALLING_INDICATIONS)) {
            sb.append("SuppVoice ");
        }
        if (hasCapabilities(CAPABILITY_VOICE_CALLING_AVAILABLE)) {
            sb.append("Voice ");
        }
        if (hasCapabilities(CAPABILITY_SUPPORTS_TRANSACTIONAL_OPERATIONS)) {
            sb.append("TransactOps ");
        }
        if (hasCapabilities(CAPABILITY_SUPPORTS_CALL_STREAMING)) {
            sb.append("Stream ");
        }
        return sb.toString();
    }

    private String audioRoutesToString() {
        StringBuilder sb = new StringBuilder();

        if (hasAudioRoutes(CallAudioState.ROUTE_BLUETOOTH)) {
            sb.append("B");
        }
        if (hasAudioRoutes(CallAudioState.ROUTE_EARPIECE)) {
            sb.append("E");
        }
        if (hasAudioRoutes(CallAudioState.ROUTE_SPEAKER)) {
            sb.append("S");
        }
        if (hasAudioRoutes(CallAudioState.ROUTE_WIRED_HEADSET)) {
            sb.append("W");
        }

        return sb.toString();
    }

    /**
     * Determines if two {@link Bundle}s are equal.
     * @param extras First {@link Bundle} to check.
     * @param newExtras {@link Bundle} to compare against.
     * @return {@code true} if the {@link Bundle}s are equal, {@code false} otherwise.
     */
    private static boolean areBundlesEqual(Bundle extras, Bundle newExtras) {
        if (extras == null || newExtras == null) {
            return extras == newExtras;
        }

        if (extras.size() != newExtras.size()) {
            return false;
        }

        for(String key : extras.keySet()) {
            if (key != null) {
                final Object value = extras.get(key);
                final Object newValue = newExtras.get(key);
                if (!Objects.equals(value, newValue)) {
                    return false;
                }
            }
        }
        return true;
    }
}
