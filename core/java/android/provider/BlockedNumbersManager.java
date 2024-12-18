/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.provider;

import static android.provider.BlockedNumberContract.AUTHORITY_URI;
import static android.provider.BlockedNumberContract.EXTRA_ENHANCED_SETTING_KEY;
import static android.provider.BlockedNumberContract.EXTRA_ENHANCED_SETTING_VALUE;
import static android.provider.BlockedNumberContract.RES_BLOCK_STATUS;
import static android.provider.BlockedNumberContract.RES_ENHANCED_SETTING_IS_ENABLED;
import static android.provider.BlockedNumberContract.RES_SHOW_EMERGENCY_CALL_NOTIFICATION;
import static android.provider.BlockedNumberContract.STATUS_NOT_BLOCKED;
import static android.provider.BlockedNumberContract.SystemContract.METHOD_END_BLOCK_SUPPRESSION;
import static android.provider.BlockedNumberContract.SystemContract.METHOD_GET_BLOCK_SUPPRESSION_STATUS;
import static android.provider.BlockedNumberContract.SystemContract.METHOD_GET_ENHANCED_BLOCK_SETTING;
import static android.provider.BlockedNumberContract.SystemContract.METHOD_NOTIFY_EMERGENCY_CONTACT;
import static android.provider.BlockedNumberContract.SystemContract.METHOD_SET_ENHANCED_BLOCK_SETTING;
import static android.provider.BlockedNumberContract.SystemContract.METHOD_SHOULD_SHOW_EMERGENCY_CALL_NOTIFICATION;
import static android.provider.BlockedNumberContract.SystemContract.METHOD_SHOULD_SYSTEM_BLOCK_NUMBER;
import static android.provider.BlockedNumberContract.SystemContract.RES_BLOCKING_SUPPRESSED_UNTIL_TIMESTAMP;
import static android.provider.BlockedNumberContract.SystemContract.RES_IS_BLOCKING_SUPPRESSED;

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.Bundle;
import android.telecom.Log;
import android.telecom.TelecomManager;

import com.android.server.telecom.flags.Flags;

/**
 * Constants and methods to interact with the blocked numbers list. This class also serves as
 * a mediator between the BlockedNumber provider and the system: it manages blocking behavior
 * when the user contacts emergency services. Currently, this is only used internally by Telecom.
 *
 * Refer to {@link BlockedNumberContract} for more context.
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_TELECOM_MAINLINE_BLOCKED_NUMBERS_MANAGER)
public final class BlockedNumbersManager {
    private static final String LOG_TAG = BlockedNumbersManager.class.getSimpleName();
    private Context mContext;

    /**
     * @hide
     */
    public BlockedNumbersManager(Context context) {
        mContext = context;
    }

    /**
     * A protected broadcast intent action for letting components with
     * {@link android.Manifest.permission#READ_BLOCKED_NUMBERS} know that the block suppression
     * status as returned by {@link #getBlockSuppressionStatus()} has been updated.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_TELECOM_MAINLINE_BLOCKED_NUMBERS_MANAGER)
    public static final String ACTION_BLOCK_SUPPRESSION_STATE_CHANGED =
            "android.provider.action.BLOCK_SUPPRESSION_STATE_CHANGED";

    /**
     * Preference key of block numbers not in contacts setting.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_TELECOM_MAINLINE_BLOCKED_NUMBERS_MANAGER)
    public static final String ENHANCED_SETTING_KEY_BLOCK_UNREGISTERED =
            "block_numbers_not_in_contacts_setting";

    /**
     * Preference key of block private number calls setting.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_TELECOM_MAINLINE_BLOCKED_NUMBERS_MANAGER)
    public static final String ENHANCED_SETTING_KEY_BLOCK_PRIVATE =
            "block_private_number_calls_setting";

    /**
     * Preference key of block payphone calls setting.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_TELECOM_MAINLINE_BLOCKED_NUMBERS_MANAGER)
    public static final String ENHANCED_SETTING_KEY_BLOCK_PAYPHONE =
            "block_payphone_calls_setting";

    /**
     * Preference key of block unknown calls setting.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_TELECOM_MAINLINE_BLOCKED_NUMBERS_MANAGER)
    public static final String ENHANCED_SETTING_KEY_BLOCK_UNKNOWN =
            "block_unknown_calls_setting";

    /**
     * Preference key for whether should show an emergency call notification.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_TELECOM_MAINLINE_BLOCKED_NUMBERS_MANAGER)
    public static final String ENHANCED_SETTING_KEY_SHOW_EMERGENCY_CALL_NOTIFICATION =
            "show_emergency_call_notification";

    /**
     * Preference key of block unavailable calls setting.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_TELECOM_MAINLINE_BLOCKED_NUMBERS_MANAGER)
    public static final String ENHANCED_SETTING_KEY_BLOCK_UNAVAILABLE =
            "block_unavailable_calls_setting";

    /**
     * Notifies the provider that emergency services were contacted by the user.
     * <p> This results in {@link #shouldSystemBlockNumber} returning {@code false} independent
     * of the contents of the provider for a duration defined by
     * {@link android.telephony.CarrierConfigManager#KEY_DURATION_BLOCKING_DISABLED_AFTER_EMERGENCY_INT}
     * the provider unless {@link #endBlockSuppression()} is called.
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {
            android.Manifest.permission.READ_BLOCKED_NUMBERS,
            android.Manifest.permission.WRITE_BLOCKED_NUMBERS
    })
    @FlaggedApi(Flags.FLAG_TELECOM_MAINLINE_BLOCKED_NUMBERS_MANAGER)
    public void notifyEmergencyContact() {
        verifyBlockedNumbersPermission();
        try {
            Log.i(LOG_TAG, "notifyEmergencyContact; caller=%s", mContext.getOpPackageName());
            mContext.getContentResolver().call(AUTHORITY_URI, METHOD_NOTIFY_EMERGENCY_CONTACT,
                    null, null);
        } catch (NullPointerException | IllegalArgumentException ex) {
            // The content resolver can throw an NPE or IAE; we don't want to crash Telecom if
            // either of these happen.
            Log.w(null, "notifyEmergencyContact: provider not ready.");
        }
    }

    /**
     * Notifies the provider to disable suppressing blocking. If emergency services were not
     * contacted recently at all, calling this method is a no-op.
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {
            android.Manifest.permission.READ_BLOCKED_NUMBERS,
            android.Manifest.permission.WRITE_BLOCKED_NUMBERS
    })
    @FlaggedApi(Flags.FLAG_TELECOM_MAINLINE_BLOCKED_NUMBERS_MANAGER)
    public void endBlockSuppression() {
        verifyBlockedNumbersPermission();
        String caller = mContext.getOpPackageName();
        Log.i(LOG_TAG, "endBlockSuppression: caller=%s", caller);
        mContext.getContentResolver().call(AUTHORITY_URI, METHOD_END_BLOCK_SUPPRESSION, null, null);
    }

    /**
     * Returns {@code true} if {@code phoneNumber} is blocked taking
     * {@link #notifyEmergencyContact()} into consideration. If emergency services
     * have not been contacted recently and enhanced call blocking not been enabled, this
     * method is equivalent to {@link BlockedNumberContract#isBlocked(Context, String)}.
     *
     * @param phoneNumber the number to check.
     * @param numberPresentation the presentation code associated with the call.
     * @param isNumberInContacts indicates if the provided number exists as a contact.
     * @return result code indicating if the number should be blocked, and if so why.
     *         Valid values are: {@link BlockedNumberContract#STATUS_NOT_BLOCKED},
     *         {@link BlockedNumberContract#STATUS_BLOCKED_IN_LIST},
     *         {@link BlockedNumberContract#STATUS_BLOCKED_NOT_IN_CONTACTS},
     *         {@link BlockedNumberContract#STATUS_BLOCKED_PAYPHONE},
     *         {@link BlockedNumberContract#STATUS_BLOCKED_RESTRICTED},
     *         {@link BlockedNumberContract#STATUS_BLOCKED_UNKNOWN_NUMBER}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {
            android.Manifest.permission.READ_BLOCKED_NUMBERS,
            android.Manifest.permission.WRITE_BLOCKED_NUMBERS
    })
    @FlaggedApi(Flags.FLAG_TELECOM_MAINLINE_BLOCKED_NUMBERS_MANAGER)
    public int shouldSystemBlockNumber(@NonNull String phoneNumber,
            @TelecomManager.Presentation int numberPresentation, boolean isNumberInContacts) {
        verifyBlockedNumbersPermission();
        try {
            String caller = mContext.getOpPackageName();
            Bundle extras = new Bundle();
            extras.putInt(BlockedNumberContract.EXTRA_CALL_PRESENTATION, numberPresentation);
            extras.putBoolean(BlockedNumberContract.EXTRA_CONTACT_EXIST, isNumberInContacts);
            final Bundle res = mContext.getContentResolver().call(AUTHORITY_URI,
                    METHOD_SHOULD_SYSTEM_BLOCK_NUMBER, phoneNumber, extras);
            int blockResult = res != null ? res.getInt(RES_BLOCK_STATUS, STATUS_NOT_BLOCKED) :
                    BlockedNumberContract.STATUS_NOT_BLOCKED;
            Log.d(LOG_TAG, "shouldSystemBlockNumber: number=%s, caller=%s, result=%s",
                    Log.piiHandle(phoneNumber), caller,
                    BlockedNumberContract.SystemContract.blockStatusToString(blockResult));
            return blockResult;
        } catch (NullPointerException | IllegalArgumentException ex) {
            // The content resolver can throw an NPE or IAE; we don't want to crash Telecom if
            // either of these happen.
            Log.w(null, "shouldSystemBlockNumber: provider not ready.");
            return BlockedNumberContract.STATUS_NOT_BLOCKED;
        }
    }

    /**
     * @return The current status of block suppression.
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {
            android.Manifest.permission.READ_BLOCKED_NUMBERS,
            android.Manifest.permission.WRITE_BLOCKED_NUMBERS
    })
    @FlaggedApi(Flags.FLAG_TELECOM_MAINLINE_BLOCKED_NUMBERS_MANAGER)
    public @NonNull BlockSuppressionStatus getBlockSuppressionStatus() {
        verifyBlockedNumbersPermission();
        final Bundle res = mContext.getContentResolver().call(
                AUTHORITY_URI, METHOD_GET_BLOCK_SUPPRESSION_STATUS, null, null);
        BlockSuppressionStatus blockSuppressionStatus = new BlockSuppressionStatus(
                res.getBoolean(RES_IS_BLOCKING_SUPPRESSED, false),
                res.getLong(RES_BLOCKING_SUPPRESSED_UNTIL_TIMESTAMP, 0));
        Log.d(LOG_TAG, "getBlockSuppressionStatus: caller=%s, status=%s",
                mContext.getOpPackageName(), blockSuppressionStatus);
        return blockSuppressionStatus;
    }

    /**
     * Check whether should show the emergency call notification.
     *
     * @return {@code true} if should show emergency call notification. {@code false} otherwise.
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {
            android.Manifest.permission.READ_BLOCKED_NUMBERS,
            android.Manifest.permission.WRITE_BLOCKED_NUMBERS
    })
    @FlaggedApi(Flags.FLAG_TELECOM_MAINLINE_BLOCKED_NUMBERS_MANAGER)
    public boolean shouldShowEmergencyCallNotification() {
        verifyBlockedNumbersPermission();
        try {
            final Bundle res = mContext.getContentResolver().call(AUTHORITY_URI,
                    METHOD_SHOULD_SHOW_EMERGENCY_CALL_NOTIFICATION, null, null);
            return res != null && res.getBoolean(RES_SHOW_EMERGENCY_CALL_NOTIFICATION, false);
        } catch (NullPointerException | IllegalArgumentException ex) {
            // The content resolver can throw an NPE or IAE; we don't want to crash Telecom if
            // either of these happen.
            Log.w(null, "shouldShowEmergencyCallNotification: provider not ready.");
            return false;
        }
    }

    /**
     * Check whether the enhanced block setting is enabled.
     *
     * @param key the key of the setting to check, can be
     *        {@link BlockedNumberContract.SystemContract#ENHANCED_SETTING_KEY_BLOCK_UNREGISTERED}
     *        {@link BlockedNumberContract.SystemContract#ENHANCED_SETTING_KEY_BLOCK_PRIVATE}
     *        {@link BlockedNumberContract.SystemContract#ENHANCED_SETTING_KEY_BLOCK_PAYPHONE}
     *        {@link BlockedNumberContract.SystemContract#ENHANCED_SETTING_KEY_BLOCK_UNKNOWN}
     *        {@link BlockedNumberContract.SystemContract#ENHANCED_SETTING_KEY_BLOCK_UNAVAILABLE}
     *        {@link BlockedNumberContract.SystemContract
     *               #ENHANCED_SETTING_KEY_SHOW_EMERGENCY_CALL_NOTIFICATION}
     * @return {@code true} if the setting is enabled. {@code false} otherwise.
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {
            android.Manifest.permission.READ_BLOCKED_NUMBERS,
            android.Manifest.permission.WRITE_BLOCKED_NUMBERS
    })
    @FlaggedApi(Flags.FLAG_TELECOM_MAINLINE_BLOCKED_NUMBERS_MANAGER)
    public boolean getBlockedNumberSetting(@NonNull String key) {
        verifyBlockedNumbersPermission();
        Bundle extras = new Bundle();
        extras.putString(EXTRA_ENHANCED_SETTING_KEY, key);
        try {
            final Bundle res = mContext.getContentResolver().call(AUTHORITY_URI,
                    METHOD_GET_ENHANCED_BLOCK_SETTING, null, extras);
            return res != null && res.getBoolean(RES_ENHANCED_SETTING_IS_ENABLED, false);
        } catch (NullPointerException | IllegalArgumentException ex) {
            // The content resolver can throw an NPE or IAE; we don't want to crash Telecom if
            // either of these happen.
            Log.w(null, "getEnhancedBlockSetting: provider not ready.");
            return false;
        }
    }

    /**
     * Set the enhanced block setting enabled status.
     *
     * @param key the key of the setting to set, can be
     *        {@link BlockedNumberContract.SystemContract#ENHANCED_SETTING_KEY_BLOCK_UNREGISTERED}
     *        {@link BlockedNumberContract.SystemContract#ENHANCED_SETTING_KEY_BLOCK_PRIVATE}
     *        {@link BlockedNumberContract.SystemContract#ENHANCED_SETTING_KEY_BLOCK_PAYPHONE}
     *        {@link BlockedNumberContract.SystemContract#ENHANCED_SETTING_KEY_BLOCK_UNKNOWN}
     *        {@link BlockedNumberContract.SystemContract#ENHANCED_SETTING_KEY_BLOCK_UNAVAILABLE}
     *        {@link BlockedNumberContract.SystemContract
     *               #ENHANCED_SETTING_KEY_SHOW_EMERGENCY_CALL_NOTIFICATION}
     * @param value the enabled statue of the setting to set.
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {
            android.Manifest.permission.READ_BLOCKED_NUMBERS,
            android.Manifest.permission.WRITE_BLOCKED_NUMBERS
    })
    @FlaggedApi(Flags.FLAG_TELECOM_MAINLINE_BLOCKED_NUMBERS_MANAGER)
    public void setBlockedNumberSetting(@NonNull String key, boolean value) {
        verifyBlockedNumbersPermission();
        Bundle extras = new Bundle();
        extras.putString(EXTRA_ENHANCED_SETTING_KEY, key);
        extras.putBoolean(EXTRA_ENHANCED_SETTING_VALUE, value);
        mContext.getContentResolver().call(AUTHORITY_URI, METHOD_SET_ENHANCED_BLOCK_SETTING,
                null, extras);
    }

    /**
     * Represents the current status of
     * {@link #shouldSystemBlockNumber(String, int, boolean)}. If emergency services
     * have been contacted recently, {@link #mIsSuppressed} is {@code true}, and blocking
     * is disabled until the timestamp {@link #mUntilTimestampMillis}.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_TELECOM_MAINLINE_BLOCKED_NUMBERS_MANAGER)
    public static final class BlockSuppressionStatus {
        /**
         * Indicates if block suppression is enabled.
         */
        private boolean mIsSuppressed;

        /**
         * Timestamp in milliseconds from epoch.
         */
        private long mUntilTimestampMillis;

        public BlockSuppressionStatus(boolean isSuppressed, long untilTimestampMillis) {
            this.mIsSuppressed = isSuppressed;
            this.mUntilTimestampMillis = untilTimestampMillis;
        }

        @Override
        public String toString() {
            return "[BlockSuppressionStatus; isSuppressed=" + mIsSuppressed + ", until="
                    + mUntilTimestampMillis + "]";
        }

        /**
         * @return mIsSuppressed Indicates whether or not block suppression is enabled.
         */
        public boolean getIsSuppressed() {
            return mIsSuppressed;
        }

        /**
         * @return mUntilTimestampMillis The timestamp until which block suppression would be
         * enabled for
         */
        public long getUntilTimestampMillis() {
            return mUntilTimestampMillis;
        }
    }

    /**
     * Verifies that the caller holds both the
     * {@link android.Manifest.permission#READ_BLOCKED_NUMBERS} permission and the
     * {@link android.Manifest.permission#WRITE_BLOCKED_NUMBERS} permission.
     *
     * @throws SecurityException if the caller is missing the necessary permissions
     */
    private void verifyBlockedNumbersPermission() {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.READ_BLOCKED_NUMBERS,
                "Caller does not have the android.permission.READ_BLOCKED_NUMBERS permission");
        mContext.enforceCallingOrSelfPermission(Manifest.permission.WRITE_BLOCKED_NUMBERS,
                "Caller does not have the android.permission.WRITE_BLOCKED_NUMBERS permission");
    }
}
