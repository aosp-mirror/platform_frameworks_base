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

package com.android.server.companion.datatransfer.contextsync;

import android.annotation.NonNull;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.VideoProfile;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Data holder for a telecom call and additional metadata. */
public class CrossDeviceCall {

    private static final String TAG = "CrossDeviceCall";

    public static final String EXTRA_CALL_ID =
            "com.android.companion.datatransfer.contextsync.extra.CALL_ID";

    private final String mId;
    private Call mCall;
    @VisibleForTesting boolean mIsEnterprise;
    @VisibleForTesting boolean mIsOtt;
    private final String mCallingAppPackageName;
    private String mCallingAppName;
    private byte[] mCallingAppIcon;
    private String mCallerDisplayName;
    private int mStatus = android.companion.Telecom.Call.UNKNOWN_STATUS;
    private String mContactDisplayName;
    private boolean mIsMuted;
    private final Set<Integer> mControls = new HashSet<>();

    public CrossDeviceCall(PackageManager packageManager, @NonNull Call call,
            CallAudioState callAudioState) {
        this(packageManager, call.getDetails(), callAudioState);
        mCall = call;
        call.putExtra(EXTRA_CALL_ID, mId);
    }

    CrossDeviceCall(PackageManager packageManager, Call.Details callDetails,
            CallAudioState callAudioState) {
        final String predefinedId = callDetails.getIntentExtras() != null
                ? callDetails.getIntentExtras().getString(EXTRA_CALL_ID) : null;
        mId = predefinedId != null ? predefinedId : UUID.randomUUID().toString();
        mCallingAppPackageName =
                callDetails.getAccountHandle().getComponentName().getPackageName();
        mIsOtt = (callDetails.getCallCapabilities() & Call.Details.PROPERTY_SELF_MANAGED)
                == Call.Details.PROPERTY_SELF_MANAGED;
        mIsEnterprise = (callDetails.getCallProperties() & Call.Details.PROPERTY_ENTERPRISE_CALL)
                == Call.Details.PROPERTY_ENTERPRISE_CALL;
        try {
            final ApplicationInfo applicationInfo = packageManager
                    .getApplicationInfo(mCallingAppPackageName,
                            PackageManager.ApplicationInfoFlags.of(0));
            mCallingAppName = packageManager.getApplicationLabel(applicationInfo).toString();
            mCallingAppIcon = BitmapUtils.renderDrawableToByteArray(
                    packageManager.getApplicationIcon(applicationInfo));
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Could not get application info for package " + mCallingAppPackageName, e);
        }
        mIsMuted = callAudioState != null && callAudioState.isMuted();
        updateCallDetails(callDetails);
    }

    /**
     * Update the mute state of this call. No-op if the call is not capable of being muted.
     *
     * @param isMuted true if the call should be muted, and false if the call should be unmuted.
     */
    public void updateMuted(boolean isMuted) {
        mIsMuted = isMuted;
        updateCallDetails(mCall.getDetails());
    }

    /**
     * Update the state of the call to be ringing silently if it is currently ringing. No-op if the
     * call is not
     * currently ringing.
     */
    public void updateSilencedIfRinging() {
        if (mStatus == android.companion.Telecom.Call.RINGING) {
            mStatus = android.companion.Telecom.Call.RINGING_SILENCED;
        }
        mControls.remove(android.companion.Telecom.SILENCE);
    }

    @VisibleForTesting
    void updateCallDetails(Call.Details callDetails) {
        mCallerDisplayName = callDetails.getCallerDisplayName();
        mContactDisplayName = callDetails.getContactDisplayName();
        mStatus = convertStateToStatus(callDetails.getState());
        mControls.clear();
        if (mStatus == android.companion.Telecom.Call.RINGING
                || mStatus == android.companion.Telecom.Call.RINGING_SILENCED) {
            mControls.add(android.companion.Telecom.ACCEPT);
            mControls.add(android.companion.Telecom.REJECT);
            if (mStatus == android.companion.Telecom.Call.RINGING) {
                mControls.add(android.companion.Telecom.SILENCE);
            }
        }
        if (mStatus == android.companion.Telecom.Call.ONGOING
                || mStatus == android.companion.Telecom.Call.ON_HOLD) {
            mControls.add(android.companion.Telecom.END);
            if (callDetails.can(Call.Details.CAPABILITY_HOLD)) {
                mControls.add(
                        mStatus == android.companion.Telecom.Call.ON_HOLD
                                ? android.companion.Telecom.TAKE_OFF_HOLD
                                : android.companion.Telecom.PUT_ON_HOLD);
            }
        }
        if (mStatus == android.companion.Telecom.Call.ONGOING && callDetails.can(
                Call.Details.CAPABILITY_MUTE)) {
            mControls.add(mIsMuted ? android.companion.Telecom.UNMUTE
                    : android.companion.Telecom.MUTE);
        }
    }

    /** Converts a Telecom call state to a Context Sync status. */
    public static int convertStateToStatus(int callState) {
        switch (callState) {
            case Call.STATE_HOLDING:
                return android.companion.Telecom.Call.ON_HOLD;
            case Call.STATE_ACTIVE:
                return android.companion.Telecom.Call.ONGOING;
            case Call.STATE_RINGING:
                return android.companion.Telecom.Call.RINGING;
            default:
                return android.companion.Telecom.Call.UNKNOWN_STATUS;
        }
    }

    /**
     * Converts a Context Sync status to a Telecom call state. Note that this is lossy for
     * and RINGING_SILENCED, as Telecom does not distinguish between RINGING and RINGING_SILENCED.
     */
    public static int convertStatusToState(int status) {
        switch (status) {
            case android.companion.Telecom.Call.ON_HOLD:
                return Call.STATE_HOLDING;
            case android.companion.Telecom.Call.ONGOING:
                return Call.STATE_ACTIVE;
            case android.companion.Telecom.Call.RINGING:
            case android.companion.Telecom.Call.RINGING_SILENCED:
                return Call.STATE_RINGING;
            case android.companion.Telecom.Call.UNKNOWN_STATUS:
            default:
                return Call.STATE_NEW;
        }
    }

    public String getId() {
        return mId;
    }

    public Call getCall() {
        return mCall;
    }

    public String getCallingAppName() {
        return mCallingAppName;
    }

    public byte[] getCallingAppIcon() {
        return mCallingAppIcon;
    }

    public String getCallingAppPackageName() {
        return mCallingAppPackageName;
    }

    /**
     * Get a human-readable "caller id" to display as the origin of the call.
     *
     * @param isAdminBlocked whether there is an admin that has blocked contacts over Bluetooth
     */
    public String getReadableCallerId(boolean isAdminBlocked) {
        if (mIsOtt) {
            return mCallerDisplayName;
        }
        return mIsEnterprise && isAdminBlocked ? mCallerDisplayName : mContactDisplayName;
    }

    public int getStatus() {
        return mStatus;
    }

    public Set<Integer> getControls() {
        return mControls;
    }

    void doAccept() {
        mCall.answer(VideoProfile.STATE_AUDIO_ONLY);
    }

    void doReject() {
        if (mStatus == android.companion.Telecom.Call.RINGING) {
            mCall.reject(Call.REJECT_REASON_DECLINED);
        }
    }

    void doEnd() {
        mCall.disconnect();
    }

    void doPutOnHold() {
        mCall.hold();
    }

    void doTakeOffHold() {
        mCall.unhold();
    }
}
