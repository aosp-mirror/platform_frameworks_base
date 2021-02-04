/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.content.pm.domain.verify;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.UserHandle;
import android.util.AndroidException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * System service to access the domain verification APIs.
 *
 * Allows the approved domain verification
 * agent on the device (the sole holder of
 * {@link android.Manifest.permission#DOMAIN_VERIFICATION_AGENT}) to update the approval status
 * of domains declared by applications in their AndroidManifest.xml, to allow them to open those
 * links inside the app when selected by the user. This is done through querying
 * {@link #getDomainVerificationSet(String)} and calling
 * {@link #setDomainVerificationStatus(UUID, Set, int)}.
 *
 * Also allows the domain preference settings (holder of
 * {@link android.Manifest.permission#UPDATE_DOMAIN_VERIFICATION_USER_SELECTION}) to update the
 * preferences of the user, when they have chosen to explicitly allow an application to open links.
 * This is done through querying {@link #getDomainVerificationUserSelection(String)} and calling
 * {@link #setDomainVerificationUserSelection(UUID, Set, boolean)} and
 * {@link #setDomainVerificationLinkHandlingAllowed(String, boolean)}.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.DOMAIN_VERIFICATION_SERVICE)
public interface DomainVerificationManager {

    /**
     * Extra field name for a {@link DomainVerificationRequest} for the requested packages.
     * Passed to an the domain verification agent that handles
     * {@link Intent#ACTION_DOMAINS_NEED_VERIFICATION}.
     */
    String EXTRA_VERIFICATION_REQUEST =
            "android.content.pm.domain.verify.extra.VERIFICATION_REQUEST";

    /**
     * No response has been recorded by either the system or any verification agent.
     */
    int STATE_NO_RESPONSE = DomainVerificationState.STATE_NO_RESPONSE;

    /** The verification agent has explicitly verified the domain at some point. */
    int STATE_SUCCESS = DomainVerificationState.STATE_SUCCESS;

    /**
     * The first available custom response code. This and any greater integer, along with
     * {@link #STATE_SUCCESS} are the only values settable by the verification agent. All values
     * will be treated as if the domain is unverified.
     */
    int STATE_FIRST_VERIFIER_DEFINED = DomainVerificationState.STATE_FIRST_VERIFIER_DEFINED;

    /** @hide */
    @NonNull
    static String stateToDebugString(@DomainVerificationState.State int state) {
        switch (state) {
            case DomainVerificationState.STATE_NO_RESPONSE:
                return "none";
            case DomainVerificationState.STATE_SUCCESS:
                return "verified";
            case DomainVerificationState.STATE_APPROVED:
                return "approved";
            case DomainVerificationState.STATE_DENIED:
                return "denied";
            case DomainVerificationState.STATE_MIGRATED:
                return "migrated";
            case DomainVerificationState.STATE_RESTORED:
                return "restored";
            case DomainVerificationState.STATE_LEGACY_FAILURE:
                return "legacy_failure";
            case DomainVerificationState.STATE_SYS_CONFIG:
                return "system_configured";
            default:
                return String.valueOf(state);
        }
    }

    /**
     * Checks if a state considers the corresponding domain to be successfully verified. The
     * domain verification agent may use this to determine whether or not to re-verify a domain.
     */
    static boolean isStateVerified(@DomainVerificationState.State int state) {
        switch (state) {
            case DomainVerificationState.STATE_SUCCESS:
            case DomainVerificationState.STATE_APPROVED:
            case DomainVerificationState.STATE_MIGRATED:
            case DomainVerificationState.STATE_RESTORED:
            case DomainVerificationState.STATE_SYS_CONFIG:
                return true;
            case DomainVerificationState.STATE_NO_RESPONSE:
            case DomainVerificationState.STATE_DENIED:
            case DomainVerificationState.STATE_LEGACY_FAILURE:
            default:
                return false;
        }
    }

    /**
     * Checks if a state is modifiable by the domain verification agent. This is useful as the
     * platform may add new state codes in newer versions, and older verification agents can use
     * this method to determine if a state can be changed without having to be aware of what the
     * new state means.
     */
    static boolean isStateModifiable(@DomainVerificationState.State int state) {
        switch (state) {
            case DomainVerificationState.STATE_NO_RESPONSE:
            case DomainVerificationState.STATE_SUCCESS:
            case DomainVerificationState.STATE_MIGRATED:
            case DomainVerificationState.STATE_RESTORED:
            case DomainVerificationState.STATE_LEGACY_FAILURE:
                return true;
            case DomainVerificationState.STATE_APPROVED:
            case DomainVerificationState.STATE_DENIED:
            case DomainVerificationState.STATE_SYS_CONFIG:
                return false;
            default:
                return state >= DomainVerificationState.STATE_FIRST_VERIFIER_DEFINED;
        }
    }

    /**
     * For determine re-verify policy. This is hidden from the domain verification agent so that
     * no behavior is made based on the result.
     * @hide
     */
    static boolean isStateDefault(@DomainVerificationState.State int state) {
        switch (state) {
            case DomainVerificationState.STATE_NO_RESPONSE:
            case DomainVerificationState.STATE_MIGRATED:
            case DomainVerificationState.STATE_RESTORED:
                return true;
            case DomainVerificationState.STATE_SUCCESS:
            case DomainVerificationState.STATE_APPROVED:
            case DomainVerificationState.STATE_DENIED:
            case DomainVerificationState.STATE_LEGACY_FAILURE:
            case DomainVerificationState.STATE_SYS_CONFIG:
            default:
                return false;
        }
    }

    /**
     * Used to iterate all {@link DomainVerificationSet} values to do cleanup or retries. This is
     * usually a heavy workload and should be done infrequently.
     *
     * @return the current snapshot of package names with valid autoVerify URLs.
     */
    @NonNull
    @RequiresPermission(allOf = {
            android.Manifest.permission.DOMAIN_VERIFICATION_AGENT,
            android.Manifest.permission.QUERY_ALL_PACKAGES
    })
    List<String> getValidVerificationPackageNames();

    /**
     * Retrieves the domain verification state for a given package. The caller must be the domain
     * verification agent for the device with
     * {@link android.Manifest.permission#DOMAIN_VERIFICATION_AGENT}, or hold
     * {@link android.Manifest.permission#UPDATE_DOMAIN_VERIFICATION_USER_SELECTION}.
     * Also requires that the caller have the
     * {@link android.Manifest.permission#QUERY_ALL_PACKAGES} permission in addition to either of
     * the requirements above.
     *
     * @return the data for the package, or null if it does not declare any autoVerify domains
     * @throws NameNotFoundException If the package is unavailable. This is an unrecoverable error
     *                               and should not be re-tried except on a time scheduled basis.
     */
    @Nullable
    @RequiresPermission(allOf = {
            android.Manifest.permission.DOMAIN_VERIFICATION_AGENT,
            android.Manifest.permission.QUERY_ALL_PACKAGES,
            android.Manifest.permission.UPDATE_DOMAIN_VERIFICATION_USER_SELECTION
    }, conditional = true)
    DomainVerificationSet getDomainVerificationSet(@NonNull String packageName)
            throws NameNotFoundException;

    /**
     * Change the verification status of the {@param domains} of the package associated with
     * {@param domainSetId}.
     *
     * @param domainSetId See {@link DomainVerificationSet#getIdentifier()}.
     * @param domains     List of host names to change the state of.
     * @param state       See {@link DomainVerificationSet#getHostToStateMap()}.
     * @throws InvalidDomainSetException If the ID is invalidated or the {@param domains} are
     *                                   invalid. This usually means the work being processed by the
     *                                   verification agent is outdated and a new request should
     *                                   be scheduled, if one has not already been done as part of
     *                                   the {@link Intent#ACTION_DOMAINS_NEED_VERIFICATION}
     *                                   broadcast.
     * @throws NameNotFoundException     If the ID is known to be good, but the package is
     *                                   unavailable. This may be because the package is
     *                                   installed on a volume that is no longer mounted. This
     *                                   error is unrecoverable until the package is available
     *                                   again, and should not be re-tried except on a time
     *                                   scheduled basis.
     */
    @RequiresPermission(allOf = {
            android.Manifest.permission.DOMAIN_VERIFICATION_AGENT,
            android.Manifest.permission.QUERY_ALL_PACKAGES
    })
    void setDomainVerificationStatus(@NonNull UUID domainSetId, @NonNull Set<String> domains,
            @DomainVerificationState.State int state)
            throws InvalidDomainSetException, NameNotFoundException;

    /**
     * TODO(b/178525735): This documentation is incorrect in the context of UX changes.
     * Change whether the given {@param packageName} is allowed to automatically open verified
     * HTTP/HTTPS domains. The final state is determined along with the verification status for the
     * specific domain being opened and other system state. An app with this enabled is not
     * guaranteed to be the sole link handler for its domains.
     *
     * By default, all apps are allowed to open verified links. Users must disable them explicitly.
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DOMAIN_VERIFICATION_USER_SELECTION)
    void setDomainVerificationLinkHandlingAllowed(@NonNull String packageName, boolean allowed)
            throws NameNotFoundException;

    /**
     * Update the recorded user selection for the given {@param domains} for the given {@param
     * domainSetId}. This state is recorded for the lifetime of a domain for a package on device,
     * and will never be reset by the system short of an app data clear.
     *
     * This state is stored per device user. If another user needs to be changed, the appropriate
     * permissions must be acquired and
     * {@link Context#createPackageContextAsUser(String, int, UserHandle)} should be used.
     *
     * This will be combined with the verification status and other system state to determine which
     * application is launched to handle an app link.
     *
     * @param domainSetId See {@link DomainVerificationSet#getIdentifier()}.
     * @param domains     The domains to toggle the state of.
     * @param enabled     Whether or not the app should automatically open the domains specified.
     * @throws InvalidDomainSetException If the ID is invalidated or the {@param domains} are
     *                                   invalid.
     * @throws NameNotFoundException     If the ID is known to be good, but the package is
     *                                   unavailable. This may be because the package is
     *                                   installed on a volume that is no longer mounted. This
     *                                   error is unrecoverable until the package is available
     *                                   again, and should not be re-tried except on a time
     *                                   scheduled basis.
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DOMAIN_VERIFICATION_USER_SELECTION)
    void setDomainVerificationUserSelection(@NonNull UUID domainSetId,
            @NonNull Set<String> domains, boolean enabled)
            throws InvalidDomainSetException, NameNotFoundException;

    /**
     * Retrieve the user selection data for the given {@param packageName} and the current user.
     * It is the responsibility of the caller to ensure that the
     * {@link DomainVerificationUserSelection#getIdentifier()} matches any prior API calls.
     *
     * This state is stored per device user. If another user needs to be accessed, the appropriate
     * permissions must be acquired and
     * {@link Context#createPackageContextAsUser(String, int, UserHandle)} should be used.
     *
     * @param packageName The app to query state for.
     * @return the user selection verification data for the given package for the current user,
     * or null if the package does not declare any HTTP/HTTPS domains.
     */
    @Nullable
    @RequiresPermission(android.Manifest.permission.UPDATE_DOMAIN_VERIFICATION_USER_SELECTION)
    DomainVerificationUserSelection getDomainVerificationUserSelection(@NonNull String packageName)
            throws NameNotFoundException;

    /**
     * Thrown if a {@link DomainVerificationSet#getIdentifier()}} or an associated set of domains
     * provided by the caller is no longer valid. This may be recoverable, and the caller should
     * re-query the package name associated with the ID using
     * {@link #getDomainVerificationSet(String)} in order to check. If that also fails, then the
     * package is no longer known to the device and thus all pending work for it should be dropped.
     */
    class InvalidDomainSetException extends AndroidException {

        public static final int REASON_ID_NULL = 1;
        public static final int REASON_ID_INVALID = 2;
        public static final int REASON_SET_NULL_OR_EMPTY = 3;
        public static final int REASON_UNKNOWN_DOMAIN = 4;

        /** @hide */
        @IntDef({
                REASON_ID_NULL,
                REASON_ID_INVALID,
                REASON_SET_NULL_OR_EMPTY,
                REASON_UNKNOWN_DOMAIN
        })
        public @interface Reason {
        }

        private static String buildMessage(@Nullable UUID domainSetId, @Nullable String packageName,
                @Reason int reason) {
            switch (reason) {
                case REASON_ID_NULL:
                    return "Domain set ID cannot be null";
                case REASON_ID_INVALID:
                    return "Domain set ID " + domainSetId + " has been invalidated";
                case REASON_SET_NULL_OR_EMPTY:
                    return "Domain set cannot be null or empty";
                case REASON_UNKNOWN_DOMAIN:
                    return "Domain set contains value that was not declared by the target package "
                            + packageName;
                default:
                    return "Unknown failure";
            }
        }

        @Reason
        private final int mReason;

        @Nullable
        private final UUID mDomainSetId;

        @Nullable
        private final String mPackageName;

        /** @hide */
        public InvalidDomainSetException(@Nullable UUID domainSetId, @Nullable String packageName,
                @Reason int reason) {
            super(buildMessage(domainSetId, packageName, reason));
            mDomainSetId = domainSetId;
            mPackageName = packageName;
            mReason = reason;
        }

        @Nullable
        public UUID getDomainSetId() {
            return mDomainSetId;
        }

        @Nullable
        public String getPackageName() {
            return mPackageName;
        }

        @Reason
        public int getReason() {
            return mReason;
        }
    }
}
