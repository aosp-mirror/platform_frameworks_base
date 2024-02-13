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

package android.os;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.admin.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * Parameters that specify what kind of bugreport should be taken.
 *
 * @hide
 */
@SystemApi
public final class BugreportParams {
    private final int mMode;
    private final int mFlags;

    /**
     * Constructs a BugreportParams object to specify what kind of bugreport should be taken.
     *
     * @param mode of the bugreport to request
     */
    public BugreportParams(@BugreportMode int mode) {
        mMode = mode;
        mFlags = 0;
    }

    /**
     * Constructs a BugreportParams object to specify what kind of bugreport should be taken.
     *
     * @param mode of the bugreport to request
     * @param flags to customize the bugreport request
     */
    public BugreportParams(@BugreportMode int mode, @BugreportFlag int flags) {
        mMode = mode;
        mFlags = flags;
    }

    /**
     * Returns the mode of the bugreport to request.
     */
    @BugreportMode
    public int getMode() {
        return mMode;
    }

    /**
     * Returns the flags to customize the bugreport request.
     */
    @BugreportFlag
    public int getFlags() {
        return mFlags;
    }

    /**
     * Defines acceptable types of bugreports.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "BUGREPORT_MODE_" }, value = {
            BUGREPORT_MODE_FULL,
            BUGREPORT_MODE_INTERACTIVE,
            BUGREPORT_MODE_REMOTE,
            BUGREPORT_MODE_WEAR,
            BUGREPORT_MODE_TELEPHONY,
            BUGREPORT_MODE_WIFI,
            BUGREPORT_MODE_ONBOARDING
    })
    public @interface BugreportMode {}

    /**
     * Options for a bugreport without user interference (and hence causing less
     * interference to the system), but includes all sections.
     */
    public static final int BUGREPORT_MODE_FULL = IDumpstate.BUGREPORT_MODE_FULL;

    /**
     * Options that allow user to monitor progress and enter additional data; might not
     * include all sections.
     */
    public static final int BUGREPORT_MODE_INTERACTIVE = IDumpstate.BUGREPORT_MODE_INTERACTIVE;

    /**
     * Options for a bugreport requested remotely by administrator of the Device Owner app,
     * not the device's user.
     */
    public static final int BUGREPORT_MODE_REMOTE = IDumpstate.BUGREPORT_MODE_REMOTE;

    /**
     * Options for a bugreport on a wearable device.
     */
    public static final int BUGREPORT_MODE_WEAR = IDumpstate.BUGREPORT_MODE_WEAR;

    /**
     * Options for a lightweight version of bugreport that only includes a few, urgent
     * sections used to report telephony bugs.
     */
    public static final int BUGREPORT_MODE_TELEPHONY = IDumpstate.BUGREPORT_MODE_TELEPHONY;

    /**
     * Options for a lightweight bugreport that only includes a few sections related to
     * Wifi.
     */
    public static final int BUGREPORT_MODE_WIFI = IDumpstate.BUGREPORT_MODE_WIFI;

    /**
     * Options for a lightweight bugreport intended to be taken for onboarding-related flows.
     */
    @FlaggedApi(Flags.FLAG_ONBOARDING_BUGREPORT_V2_ENABLED)
    public static final int BUGREPORT_MODE_ONBOARDING = IDumpstate.BUGREPORT_MODE_ONBOARDING;

    /**
     * The maximum value of supported bugreport mode.
     * @hide
     */
    @FlaggedApi(android.os.Flags.FLAG_BUGREPORT_MODE_MAX_VALUE)
    @TestApi
    public static final int BUGREPORT_MODE_MAX_VALUE = BUGREPORT_MODE_ONBOARDING;

    /**
     * Defines acceptable flags for customizing bugreport requests.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "BUGREPORT_FLAG_" }, value = {
            BUGREPORT_FLAG_USE_PREDUMPED_UI_DATA,
            BUGREPORT_FLAG_DEFER_CONSENT,
            BUGREPORT_FLAG_KEEP_BUGREPORT_ON_RETRIEVAL
    })
    public @interface BugreportFlag {}

    /**
     * Flag for reusing pre-dumped UI data. The pre-dump and bugreport request calls must be
     * performed by the same UID, otherwise the flag is ignored.
     */
    public static final int BUGREPORT_FLAG_USE_PREDUMPED_UI_DATA =
            IDumpstate.BUGREPORT_FLAG_USE_PREDUMPED_UI_DATA;

    /**
     * Flag for deferring user consent.
     *
     * <p>This flag should be used in cases where it may not be possible for the user to respond
     * to a consent dialog immediately, such as when the user is driving. The generated bugreport
     * may be retrieved at a later time using {@link BugreportManager#retrieveBugreport(
     * String, ParcelFileDescriptor, Executor, BugreportManager.BugreportCallback)}.
     */
    public static final int BUGREPORT_FLAG_DEFER_CONSENT = IDumpstate.BUGREPORT_FLAG_DEFER_CONSENT;

    /**
     * Flag for keeping a bugreport stored even after it has been retrieved via
     * {@link BugreportManager#retrieveBugreport}.
     *
     * <p>This flag can only be used when {@link #BUGREPORT_FLAG_DEFER_CONSENT} is set.
     * The bugreport may be retrieved multiple times using
     * {@link BugreportManager#retrieveBugreport(
     * String, ParcelFileDescriptor, Executor, BugreportManager.BugreportCallback)}.
     */
    @FlaggedApi(Flags.FLAG_ONBOARDING_BUGREPORT_V2_ENABLED)
    public static final int BUGREPORT_FLAG_KEEP_BUGREPORT_ON_RETRIEVAL =
            IDumpstate.BUGREPORT_FLAG_KEEP_BUGREPORT_ON_RETRIEVAL;
}
