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

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.VideoProfile;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Data holder for a telecom call and additional metadata. */
public class CrossDeviceCall {

    private static final String TAG = "CrossDeviceCall";

    public static final String EXTRA_CALL_ID =
            "com.android.companion.datatransfer.contextsync.extra.CALL_ID";
    private static final int APP_ICON_BITMAP_DIMENSION = 256;

    private static final AtomicLong sNextId = new AtomicLong(1);

    private final long mId;
    private final Call mCall;
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

    public CrossDeviceCall(PackageManager packageManager, Call call,
            CallAudioState callAudioState) {
        mId = sNextId.getAndIncrement();
        mCall = call;
        mCallingAppPackageName = call != null
                ? call.getDetails().getAccountHandle().getComponentName().getPackageName() : null;
        mIsOtt = call != null
                && (call.getDetails().getCallCapabilities() & Call.Details.PROPERTY_SELF_MANAGED)
                == Call.Details.PROPERTY_SELF_MANAGED;
        mIsEnterprise = call != null
                && (call.getDetails().getCallProperties() & Call.Details.PROPERTY_ENTERPRISE_CALL)
                == Call.Details.PROPERTY_ENTERPRISE_CALL;
        try {
            final ApplicationInfo applicationInfo = packageManager
                    .getApplicationInfo(mCallingAppPackageName,
                            PackageManager.ApplicationInfoFlags.of(0));
            mCallingAppName = packageManager.getApplicationLabel(applicationInfo).toString();
            mCallingAppIcon = renderDrawableToByteArray(
                    packageManager.getApplicationIcon(applicationInfo));
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Could not get application info for package " + mCallingAppPackageName, e);
        }
        mIsMuted = callAudioState != null && callAudioState.isMuted();
        if (call != null) {
            updateCallDetails(call.getDetails());
        }
    }

    private byte[] renderDrawableToByteArray(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            // Can't recycle the drawable's bitmap, so handle separately
            final Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            if (bitmap.getWidth() > APP_ICON_BITMAP_DIMENSION
                    || bitmap.getHeight() > APP_ICON_BITMAP_DIMENSION) {
                // Downscale, as the original drawable bitmap is too large.
                final Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap,
                        APP_ICON_BITMAP_DIMENSION, APP_ICON_BITMAP_DIMENSION, /* filter= */ true);
                final byte[] renderedBitmap = renderBitmapToByteArray(scaledBitmap);
                scaledBitmap.recycle();
                return renderedBitmap;
            }
            return renderBitmapToByteArray(bitmap);
        }
        final Bitmap bitmap = Bitmap.createBitmap(APP_ICON_BITMAP_DIMENSION,
                APP_ICON_BITMAP_DIMENSION,
                Bitmap.Config.ARGB_8888);
        try {
            final Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
            drawable.draw(canvas);
        } finally {
            bitmap.recycle();
        }
        return renderBitmapToByteArray(bitmap);
    }

    private byte[] renderBitmapToByteArray(Bitmap bitmap) {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream(bitmap.getByteCount());
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
        return baos.toByteArray();
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
        mControls.remove(android.companion.Telecom.Call.SILENCE);
    }

    @VisibleForTesting
    void updateCallDetails(Call.Details callDetails) {
        mCallerDisplayName = callDetails.getCallerDisplayName();
        mContactDisplayName = callDetails.getContactDisplayName();
        mStatus = convertStateToStatus(callDetails.getState());
        mControls.clear();
        if (mStatus == android.companion.Telecom.Call.RINGING
                || mStatus == android.companion.Telecom.Call.RINGING_SILENCED) {
            mControls.add(android.companion.Telecom.Call.ACCEPT);
            mControls.add(android.companion.Telecom.Call.REJECT);
            if (mStatus == android.companion.Telecom.Call.RINGING) {
                mControls.add(android.companion.Telecom.Call.SILENCE);
            }
        }
        if (mStatus == android.companion.Telecom.Call.ONGOING
                || mStatus == android.companion.Telecom.Call.ON_HOLD) {
            mControls.add(android.companion.Telecom.Call.END);
            if (callDetails.can(Call.Details.CAPABILITY_HOLD)) {
                mControls.add(
                        mStatus == android.companion.Telecom.Call.ON_HOLD
                                ? android.companion.Telecom.Call.TAKE_OFF_HOLD
                                : android.companion.Telecom.Call.PUT_ON_HOLD);
            }
        }
        if (mStatus == android.companion.Telecom.Call.ONGOING && callDetails.can(
                Call.Details.CAPABILITY_MUTE)) {
            mControls.add(mIsMuted ? android.companion.Telecom.Call.UNMUTE
                    : android.companion.Telecom.Call.MUTE);
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

    public long getId() {
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
