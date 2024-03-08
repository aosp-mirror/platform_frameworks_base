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

package android.content.pm.verify.domain;

import android.annotation.CheckResult;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.content.Intent;
import android.content.UriRelativeFilterGroup;
import android.content.UriRelativeFilterGroupParcel;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.UserHandle;
import android.util.ArrayMap;

import com.android.internal.util.CollectionUtils;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;

/**
 * System service to access domain verification APIs.
 *
 * Applications should use {@link #getDomainVerificationUserState(String)} if necessary to
 * check if/how they are verified for a domain, which is required starting from platform
 * {@link android.os.Build.VERSION_CODES#S} in order to open {@link Intent}s which declare
 * {@link Intent#CATEGORY_BROWSABLE} or no category and also match against
 * {@link Intent#CATEGORY_DEFAULT} {@link android.content.IntentFilter}s, either through an
 * explicit declaration of {@link Intent#CATEGORY_DEFAULT} or through the use of
 * {@link android.content.pm.PackageManager#MATCH_DEFAULT_ONLY}, which is usually added for the
 * caller when using {@link Context#startActivity(Intent)} and similar.
 */
@SystemService(Context.DOMAIN_VERIFICATION_SERVICE)
public final class DomainVerificationManager {

    /**
     * Extra field name for a {@link DomainVerificationRequest} for the requested packages. Passed
     * to an the domain verification agent that handles
     * {@link Intent#ACTION_DOMAINS_NEED_VERIFICATION}.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_VERIFICATION_REQUEST =
            "android.content.pm.verify.domain.extra.VERIFICATION_REQUEST";

    /**
     * Default return code for when a method has succeeded.
     *
     * @hide
     */
    @SystemApi
    public static final int STATUS_OK = 0;

    /**
     * The provided domain set ID was invalid, probably due to the package being updated between
     * the initial request that provided the ID and the method call that used it. This usually
     * means the work being processed by the verification agent is outdated and a new request
     * should be scheduled, which should already be in progress as part of the
     * {@link Intent#ACTION_DOMAINS_NEED_VERIFICATION} broadcast.
     *
     * @hide
     */
    @SystemApi
    public static final int ERROR_DOMAIN_SET_ID_INVALID = 1;

    /**
     * The provided set of domains contains a domain not declared by the target package. This
     * usually means the work being processed by the verification agent is outdated and a new
     * request should be scheduled, which should already be in progress as part of the
     * {@link Intent#ACTION_DOMAINS_NEED_VERIFICATION} broadcast.
     *
     * @hide
     */
    @SystemApi
    public static final int ERROR_UNKNOWN_DOMAIN = 2;

    /**
     * The system was unable to select the domain for approval. This indicates another application
     * has been granted a higher approval, usually through domain verification, and the target
     * package is unable to override it.
     *
     * @hide
     */
    @SystemApi
    public static final int ERROR_UNABLE_TO_APPROVE = 3;

    /**
     * Used to communicate through {@link ServiceSpecificException}. Should not be exposed as API.
     *
     * @hide
     */
    public static final int INTERNAL_ERROR_NAME_NOT_FOUND = 1;

    /**
     * @hide
     */
    @IntDef(prefix = {"ERROR_"}, value = {
            ERROR_DOMAIN_SET_ID_INVALID,
            ERROR_UNKNOWN_DOMAIN,
            ERROR_UNABLE_TO_APPROVE,
    })
    public @interface Error {
    }

    private final Context mContext;

    private final IDomainVerificationManager mDomainVerificationManager;

    /**
     * System service to access the domain verification APIs.
     * <p>
     * Allows the approved domain verification agent on the device (the sole holder of {@link
     * android.Manifest.permission#DOMAIN_VERIFICATION_AGENT}) to update the approval status of
     * domains declared by applications in their AndroidManifest.xml, to allow them to open those
     * links inside the app when selected by the user. This is done through querying {@link
     * #getDomainVerificationInfo(String)} and calling {@link #setDomainVerificationStatus(UUID,
     * Set, int)}.
     * <p>
     * Also allows the domain preference settings (holder of
     * {@link android.Manifest.permission#UPDATE_DOMAIN_VERIFICATION_USER_SELECTION})
     * to update the preferences of the user, when they have chosen to explicitly allow an
     * application to open links. This is done through querying
     * {@link #getDomainVerificationUserState(String)} and calling
     * {@link #setDomainVerificationUserSelection(UUID, Set, boolean)} and
     * {@link #setDomainVerificationLinkHandlingAllowed(String, boolean)}.
     *
     * @hide
     */
    public DomainVerificationManager(Context context,
            IDomainVerificationManager domainVerificationManager) {
        mContext = context;
        mDomainVerificationManager = domainVerificationManager;
    }

    /**
     * Update the URI relative filter groups for a package. The groups set using this API acts
     * as an additional filtering layer during intent resolution. It does not replace any
     * existing groups that have been added to the package's intent filters either using the
     * {@link android.content.IntentFilter#addUriRelativeFilterGroup(UriRelativeFilterGroup)}
     * API or defined in the manifest.
     * <p>
     * Groups can be indexed to any domain or can be indexed for all subdomains by prefixing the
     * hostname with a wildcard (i.e. "*.example.com"). Priority will be first given to groups
     * that are indexed to the specific subdomain of the intent's data URI followed by any groups
     * indexed to wildcard subdomains. If the subdomain consists of more than one label, priority
     * will decrease corresponding to the decreasing number of subdomain labels after the wildcard.
     * For example "a.b.c.d" will match "*.b.c.d" before "*.c.d".
     * <p>
     * All previously existing groups set for a domain index using this API will be cleared when
     * new groups are set.
     *
     * @param packageName The name of the package.
     * @param domainToGroupsMap A map of domains to a list of {@link UriRelativeFilterGroup}s that
     *                         should apply to them. Groups for each domain will replace any groups
     *                         provided for that domain in a prior call to this method. To clear
     *                         existing groups, set the list to null or a empty list. Groups will
     *                         be evaluated in the order they are provided.
     *
     * @see UriRelativeFilterGroup
     * @see android.content.IntentFilter
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.DOMAIN_VERIFICATION_AGENT)
    @FlaggedApi(android.content.pm.Flags.FLAG_RELATIVE_REFERENCE_INTENT_FILTERS)
    public void setUriRelativeFilterGroups(@NonNull String packageName,
            @NonNull Map<String, List<UriRelativeFilterGroup>> domainToGroupsMap) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(domainToGroupsMap);
        Bundle bundle = new Bundle();
        for (String domain : domainToGroupsMap.keySet()) {
            List<UriRelativeFilterGroup> groups = domainToGroupsMap.get(domain);
            bundle.putParcelableList(domain, UriRelativeFilterGroup.groupsToParcels(groups));
        }
        try {
            mDomainVerificationManager.setUriRelativeFilterGroups(packageName, bundle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves a map of a package's verified domains to a list of {@link UriRelativeFilterGroup}s
     * that applies to them.
     *
     * @param packageName The name of the package.
     * @param domains List of domains for which to retrieve group matches.
     * @return A map of domains to the lists of {@link UriRelativeFilterGroup}s that apply to them.
     * @hide
     */
    @NonNull
    @SystemApi
    @FlaggedApi(android.content.pm.Flags.FLAG_RELATIVE_REFERENCE_INTENT_FILTERS)
    public Map<String, List<UriRelativeFilterGroup>> getUriRelativeFilterGroups(
            @NonNull String packageName,
            @NonNull List<String> domains) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(domains);
        if (domains.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            Bundle bundle = mDomainVerificationManager.getUriRelativeFilterGroups(packageName,
                    domains);
            ArrayMap<String, List<UriRelativeFilterGroup>> map = new ArrayMap<>();
            if (!bundle.isEmpty()) {
                for (String domain : bundle.keySet()) {
                    List<UriRelativeFilterGroupParcel> parcels =
                            bundle.getParcelableArrayList(domain,
                                    UriRelativeFilterGroupParcel.class);
                    map.put(domain, UriRelativeFilterGroup.parcelsToGroups(parcels));
                }
            }
            return map;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Used to iterate all {@link DomainVerificationInfo} values to do cleanup or retries. This is
     * usually a heavy workload and should be done infrequently.
     *
     * @return the current snapshot of package names with valid autoVerify URLs.
     * @hide
     */
    @SystemApi
    @NonNull
    @RequiresPermission(android.Manifest.permission.DOMAIN_VERIFICATION_AGENT)
    public List<String> queryValidVerificationPackageNames() {
        try {
            return mDomainVerificationManager.queryValidVerificationPackageNames();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves the domain verification state for a given package.
     *
     * @return the data for the package, or null if it does not declare any autoVerify domains
     * @throws NameNotFoundException If the package is unavailable. This is an unrecoverable error
     *                               and should not be re-tried except on a time scheduled basis.
     * @hide
     */
    @SystemApi
    @Nullable
    @RequiresPermission(android.Manifest.permission.DOMAIN_VERIFICATION_AGENT)
    public DomainVerificationInfo getDomainVerificationInfo(@NonNull String packageName)
            throws NameNotFoundException {
        try {
            return mDomainVerificationManager.getDomainVerificationInfo(packageName);
        } catch (Exception e) {
            Exception converted = rethrow(e, packageName);
            if (converted instanceof NameNotFoundException) {
                throw (NameNotFoundException) converted;
            } else if (converted instanceof RuntimeException) {
                throw (RuntimeException) converted;
            } else {
                throw new RuntimeException(converted);
            }
        }
    }

    /**
     * Change the verification status of the {@param domains} of the package associated with {@param
     * domainSetId}.
     *
     * @param domainSetId See {@link DomainVerificationInfo#getIdentifier()}.
     * @param domains     List of host names to change the state of.
     * @param state       See {@link DomainVerificationInfo#getHostToStateMap()}.
     * @return error code or {@link #STATUS_OK} if successful
     * @throws NameNotFoundException If the ID is known to be good, but the package is
     *                               unavailable. This may be because the package is installed on
     *                               a volume that is no longer mounted. This error is
     *                               unrecoverable until the package is available again, and
     *                               should not be re-tried except on a time scheduled basis.
     * @hide
     */
    @CheckResult
    @SystemApi
    @RequiresPermission(android.Manifest.permission.DOMAIN_VERIFICATION_AGENT)
    public int setDomainVerificationStatus(@NonNull UUID domainSetId, @NonNull Set<String> domains,
            int state) throws NameNotFoundException {
        validateInput(domainSetId, domains);

        try {
            return mDomainVerificationManager.setDomainVerificationStatus(domainSetId.toString(),
                    new DomainSet(domains), state);
        } catch (Exception e) {
            Exception converted = rethrow(e, null);
            if (converted instanceof NameNotFoundException) {
                throw (NameNotFoundException) converted;
            } else if (converted instanceof RuntimeException) {
                throw (RuntimeException) converted;
            } else {
                throw new RuntimeException(converted);
            }
        }
    }

    /**
     * Change whether the given packageName is allowed to handle BROWSABLE and DEFAULT category web
     * (HTTP/HTTPS) {@link Intent} Activity open requests. The final state is determined along with
     * the verification status for the specific domain being opened and other system state. An app
     * with this enabled is not guaranteed to be the sole link handler for its domains.
     * <p>
     * By default, all apps are allowed to open links. Users must disable them explicitly.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.UPDATE_DOMAIN_VERIFICATION_USER_SELECTION)
    public void setDomainVerificationLinkHandlingAllowed(@NonNull String packageName,
            boolean allowed) throws NameNotFoundException {
        try {
            mDomainVerificationManager.setDomainVerificationLinkHandlingAllowed(packageName,
                    allowed, mContext.getUserId());
        } catch (Exception e) {
            Exception converted = rethrow(e, null);
            if (converted instanceof NameNotFoundException) {
                throw (NameNotFoundException) converted;
            } else if (converted instanceof RuntimeException) {
                throw (RuntimeException) converted;
            } else {
                throw new RuntimeException(converted);
            }
        }
    }

    /**
     * Update the recorded user selection for the given {@param domains} for the given {@param
     * domainSetId}. This state is recorded for the lifetime of a domain for a package on device,
     * and will never be reset by the system short of an app data clear.
     * <p>
     * This state is stored per device user. If another user needs to be changed, the appropriate
     * permissions must be acquired and {@link Context#createContextAsUser(UserHandle, int)} should
     * be used.
     * <p>
     * Enabling an unverified domain will allow an application to open it, but this can only occur
     * if no other app on the device is approved for a higher approval level. This can queried
     * using {@link #getOwnersForDomain(String)}.
     *
     * If all owners for a domain are {@link DomainOwner#isOverrideable()}, then calling this to
     * enable that domain will disable all other owners.
     *
     * On the other hand, if any of the owners are non-overrideable, then this must be called with
     * false for all of the other owners to disable them before the domain can be taken by a new
     * owner.
     *
     * @param domainSetId See {@link DomainVerificationInfo#getIdentifier()}.
     * @param domains     The domains to toggle the state of.
     * @param enabled     Whether or not the app should automatically open the domains specified.
     * @return error code or {@link #STATUS_OK} if successful
     * @throws NameNotFoundException If the ID is known to be good, but the package is
     *                               unavailable. This may be because the package is installed on
     *                               a volume that is no longer mounted. This error is
     *                               unrecoverable until the package is available again, and
     *                               should not be re-tried except on a time scheduled basis.
     * @hide
     */
    @CheckResult
    @SystemApi
    @RequiresPermission(android.Manifest.permission.UPDATE_DOMAIN_VERIFICATION_USER_SELECTION)
    public int setDomainVerificationUserSelection(@NonNull UUID domainSetId,
            @NonNull Set<String> domains, boolean enabled) throws NameNotFoundException {
        validateInput(domainSetId, domains);

        try {
            return mDomainVerificationManager.setDomainVerificationUserSelection(
                    domainSetId.toString(), new DomainSet(domains), enabled, mContext.getUserId());
        } catch (Exception e) {
            Exception converted = rethrow(e, null);
            if (converted instanceof NameNotFoundException) {
                throw (NameNotFoundException) converted;
            } else if (converted instanceof RuntimeException) {
                throw (RuntimeException) converted;
            } else {
                throw new RuntimeException(converted);
            }
        }
    }

    /**
     * Retrieve the user state for the given package and the {@link Context}'s user.
     *
     * @param packageName The app to query state for.
     * @return The user selection verification data for the given package for the user, or null if
     * the package does not declare any HTTP/HTTPS domains.
     */
    @Nullable
    public DomainVerificationUserState getDomainVerificationUserState(
            @NonNull String packageName) throws NameNotFoundException {
        try {
            return mDomainVerificationManager.getDomainVerificationUserState(packageName,
                    mContext.getUserId());
        } catch (Exception e) {
            Exception converted = rethrow(e, packageName);
            if (converted instanceof NameNotFoundException) {
                throw (NameNotFoundException) converted;
            } else if (converted instanceof RuntimeException) {
                throw (RuntimeException) converted;
            } else {
                throw new RuntimeException(converted);
            }
        }
    }

    /**
     * For the given domain, return all apps which are approved to open it in a
     * greater than 0 priority. This does not mean that all apps can actually open
     * an Intent with that domain. That will be decided by the set of apps which
     * are the highest priority level, ignoring all lower priority levels.
     *
     * The set will be ordered from lowest to highest priority.
     *
     * @param domain The host to query for. An invalid domain will result in an empty set.
     *
     * @hide
     */
    @SystemApi
    @NonNull
    @RequiresPermission(android.Manifest.permission.UPDATE_DOMAIN_VERIFICATION_USER_SELECTION)
    public SortedSet<DomainOwner> getOwnersForDomain(@NonNull String domain) {
        try {
            Objects.requireNonNull(domain);
            final List<DomainOwner> orderedList = mDomainVerificationManager.getOwnersForDomain(
                    domain, mContext.getUserId());
            SortedSet<DomainOwner> set = new TreeSet<>(
                    Comparator.comparingInt(orderedList::indexOf));
            set.addAll(orderedList);
            return set;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private Exception rethrow(Exception exception, @Nullable String packageName) {
        if (exception instanceof ServiceSpecificException) {
            int serviceSpecificErrorCode = ((ServiceSpecificException) exception).errorCode;
            if (packageName == null) {
                packageName = exception.getMessage();
            }

            if (serviceSpecificErrorCode == INTERNAL_ERROR_NAME_NOT_FOUND) {
                return new NameNotFoundException(packageName);
            }

            return exception;
        } else if (exception instanceof RemoteException) {
            return ((RemoteException) exception).rethrowFromSystemServer();
        } else {
            return exception;
        }
    }

    private void validateInput(@Nullable UUID domainSetId, @Nullable Set<String> domains) {
        if (domainSetId == null) {
            throw new IllegalArgumentException("domainSetId cannot be null");
        } else if (CollectionUtils.isEmpty(domains)) {
            throw new IllegalArgumentException("Provided domain set cannot be empty");
        }
    }
}
