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

package com.android.server.pm.domain.verify;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.content.Intent;
import android.content.pm.IntentFilterVerificationInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.domain.verify.DomainVerificationManager;
import android.content.pm.domain.verify.DomainVerificationSet;
import android.util.IndentingPrintWriter;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import com.android.server.pm.PackageSetting;
import com.android.server.pm.domain.verify.models.DomainVerificationPkgState;
import com.android.server.pm.domain.verify.proxy.DomainVerificationProxy;
import com.android.server.pm.parsing.pkg.AndroidPackage;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

public interface DomainVerificationManagerInternal extends DomainVerificationManager {

    UUID DISABLED_ID = new UUID(0, 0);

    /**
     * Generate a new domain set ID to be used for attaching new packages.
     */
    @NonNull
    UUID generateNewId();

    @NonNull
    DomainVerificationProxy getProxy();

    /**
     * Update the proxy implementation that talks to the domain verification agent on device. The
     * default proxy is a stub that does nothing, and broadcast functionality will only work once a
     * real implementation is attached.
     */
    void setProxy(@NonNull DomainVerificationProxy proxy);

    /**
     * @see DomainVerificationProxy.BaseConnection#runMessage(int, Object)
     */
    boolean runMessage(int messageCode, Object object);

    /**
     * Restores or creates internal state for the new package. This can either be from scanning a
     * package at boot, or a truly new installation on the device. It is expected that the {@link
     * PackageSetting#getDomainSetId()} already be set to the correct value.
     * <p>
     * If this is from scan, there should be a pending state that was previous read using {@link
     * #readSettings(TypedXmlPullParser)}, which will be attached as-is to the package. In this
     * case, a broadcast will not be sent to the domain verification agent on device, as it is
     * assumed nothing has changed since the device rebooted.
     * <p>
     * If this is a new install, state will be restored from a previous call to {@link
     * #restoreSettings(TypedXmlPullParser)}, or a new one will be generated. In either case, a
     * broadcast will be sent to the domain verification agent so it may re-run any verification
     * logic for the newly associated domains.
     * <p>
     * This will mutate internal {@link DomainVerificationPkgState} and so will hold the internal
     * lock. This should never be called from within the domain verification classes themselves.
     * <p>
     * This will NOT call {@link #writeSettings(TypedXmlSerializer)}. That must be handled by the
     * caller.
     */
    void addPackage(@NonNull PackageSetting newPkgSetting);

    /**
     * Migrates verification state from a previous install to a new one. It is expected that the
     * {@link PackageSetting#getDomainSetId()} already be set to the correct value, usually from
     * {@link #generateNewId()}. This will preserve {@link #STATE_SUCCESS} domains under the
     * assumption that the new package will pass the same server side config as the previous
     * package, as they have matching signatures.
     * <p>
     * This will mutate internal {@link DomainVerificationPkgState} and so will hold the internal
     * lock. This should never be called from within the domain verification classes themselves.
     * <p>
     * This will NOT call {@link #writeSettings(TypedXmlSerializer)}. That must be handled by the
     * caller.
     */
    void migrateState(@NonNull PackageSetting oldPkgSetting, @NonNull PackageSetting newPkgSetting);

    /**
     * Serializes the entire internal state. This is equivalent to a full backup of the existing
     * verification state. This write includes legacy state, as a sibling tag the modern state.
     */
    void writeSettings(@NonNull TypedXmlSerializer serializer) throws IOException;

    /**
     * Read back a list of {@link DomainVerificationPkgState}s previously written by {@link
     * #writeSettings(TypedXmlSerializer)}. Assumes that the
     * {@link DomainVerificationPersistence#TAG_DOMAIN_VERIFICATIONS} tag has already been entered.
     * <p>
     * This is expected to only be used to re-attach states for packages already known to be on the
     * device. If restoring from a backup, use {@link #restoreSettings(TypedXmlPullParser)}.
     */
    void readSettings(@NonNull TypedXmlPullParser parser)
            throws IOException, XmlPullParserException;

    /**
     * Read back data from
     * {@link DomainVerificationLegacySettings#writeSettings(TypedXmlSerializer)}. Assumes that the
     * {@link DomainVerificationLegacySettings#TAG_DOMAIN_VERIFICATIONS_LEGACY} tag has already
     * been entered.
     */
    void readLegacySettings(@NonNull TypedXmlPullParser parser)
            throws IOException, XmlPullParserException;

    /**
     * Remove all state for the given package.
     */
    void clearPackage(@NonNull String packageName);

    /**
     * Delete all the state for a user. This can be because the user has been removed from the
     * device, or simply that the state for a user should be deleted.
     */
    void clearUser(@UserIdInt int userId);

    /**
     * Restore a list of {@link DomainVerificationPkgState}s previously written by {@link
     * #writeSettings(TypedXmlSerializer)}. Assumes that the
     * {@link DomainVerificationPersistence#TAG_DOMAIN_VERIFICATIONS}
     * tag has already been entered.
     * <p>
     * This is <b>only</b> for restore, and will override package states, ignoring if their {@link
     * DomainVerificationSet#getIdentifier()}s match. It's expected that any restored domains marked
     * as success verify against the server correctly, although the verification agent may decide to
     * re-verify them when it gets the chance.
     */
    /*
     * TODO(b/170746586): Figure out how to verify that package signatures match at snapshot time
     *  and restore time.
     */
    void restoreSettings(@NonNull TypedXmlPullParser parser)
            throws IOException, XmlPullParserException;

    /**
     * Set aside a legacy {@link IntentFilterVerificationInfo} that will be restored to a pending
     * {@link DomainVerificationPkgState} once it's added through
     * {@link #addPackage(PackageSetting)}.
     */
    void addLegacySetting(@NonNull String packageName, @NonNull IntentFilterVerificationInfo info);

    /**
     * Set aside a legacy user selection that will be restored to a pending
     * {@link DomainVerificationPkgState} once it's added through
     * {@link #addPackage(PackageSetting)}.
     */
    void setLegacyUserState(@NonNull String packageName, @UserIdInt int userId, int state);

    /**
     * Until the legacy APIs are entirely removed, returns the legacy state from the previously
     * written info stored in {@link com.android.server.pm.Settings}.
     */
    int getLegacyState(@NonNull String packageName, @UserIdInt int userId);

    /**
     * Serialize a legacy setting that wasn't attached yet.
     * TODO: Does this even matter? Should consider for removal.
     */
    void writeLegacySettings(TypedXmlSerializer serializer, String name);

    /**
     * Print the verification state and user selection state of a package.
     *
     * @param packageName the package whose state to change, or all packages if none is specified
     * @param userId      the specific user to print, or null to skip printing user selection
     *                    states, supports {@link android.os.UserHandle#USER_ALL}
     */
    void printState(@NonNull IndentingPrintWriter writer, @Nullable String packageName,
            @Nullable @UserIdInt Integer userId) throws NameNotFoundException;

    @NonNull
    DomainVerificationShell getShell();

    @NonNull
    DomainVerificationCollector getCollector();

    /**
     * Check if a resolving URI is approved to takeover the domain as the sole resolved target.
     * This can be because the domain was auto-verified for the package, or if the user manually
     * chose to enable the domain for the package.
     */
    boolean isApprovedForDomain(@NonNull PackageSetting pkgSetting, @NonNull Intent intent,
            @UserIdInt int userId);

    /**
     * @return the domain verification set ID for the given package, or null if the ID is
     * unavailable
     */
    @Nullable
    UUID getDomainVerificationSetId(@NonNull String packageName);

    @RequiresPermission(android.Manifest.permission.DOMAIN_VERIFICATION_AGENT)
    void setDomainVerificationStatusInternal(int callingUid, @NonNull UUID domainSetId,
            @NonNull Set<String> domains, int state)
            throws InvalidDomainSetException, NameNotFoundException;
}
