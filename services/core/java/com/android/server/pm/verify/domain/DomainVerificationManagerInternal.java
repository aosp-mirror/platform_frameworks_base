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

package com.android.server.pm.verify.domain;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.content.Intent;
import android.content.pm.IntentFilterVerificationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.verify.domain.DomainVerificationInfo;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.content.pm.verify.domain.DomainVerificationState;
import android.os.Binder;
import android.os.UserHandle;
import android.util.IndentingPrintWriter;
import android.util.Pair;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import com.android.server.pm.Computer;
import com.android.server.pm.PackageSetting;
import com.android.server.pm.Settings;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.verify.domain.models.DomainVerificationPkgState;
import com.android.server.pm.verify.domain.proxy.DomainVerificationProxy;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

public interface DomainVerificationManagerInternal {

    UUID DISABLED_ID = new UUID(0, 0);

    /**
     * The app was not installed for the user.
     */
    int APPROVAL_LEVEL_NOT_INSTALLED = -4;

    /**
     * The app was not enabled for the user.
     */
    int APPROVAL_LEVEL_DISABLED = -3;

    /**
     * The app has not declared this domain in a valid web intent-filter in their manifest, and so
     * would never be able to be approved for this domain.
     */
    int APPROVAL_LEVEL_UNDECLARED = -2;

    /**
     * The app has declared this domain as a valid autoVerify domain, but it failed or has not
     * succeeded verification.
     */
    int APPROVAL_LEVEL_UNVERIFIED = -1;

    /**
     * The app has not been approved for this domain and should never be able to open it through
     * an implicit web intent.
     */
    int APPROVAL_LEVEL_NONE = 0;

    /**
     * The app has been approved through the legacy
     * {@link PackageManager#updateIntentVerificationStatusAsUser(String, int, int)} API, which has
     * been preserved for migration purposes, but is otherwise ignored. Corresponds to
     * {@link PackageManager#INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK} and
     * {@link PackageManager#INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS_ASK}.
     *
     * This should be used as the cutoff for showing a picker if no better approved app exists
     * during the legacy transition period.
     *
     * TODO(b/177923646): The legacy values can be removed once the Settings API changes are
     * shipped. These values are not stable, so just deleting the constant and shifting others is
     * fine.
     */
    int APPROVAL_LEVEL_LEGACY_ASK = 1;

    /**
     * The app has been approved through the legacy
     * {@link PackageManager#updateIntentVerificationStatusAsUser(String, int, int)} API, which has
     * been preserved for migration purposes, but is otherwise ignored. Corresponds to
     * {@link PackageManager#INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS}.
     */
    int APPROVAL_LEVEL_LEGACY_ALWAYS = 2;

    /**
     * The app has been chosen by the user through
     * {@link DomainVerificationManager#setDomainVerificationUserSelection(UUID, Set, boolean)},
     * indicating an explicit choice to use this app to open an unverified domain.
     */
    int APPROVAL_LEVEL_SELECTION = 3;

    /**
     * The app is approved through the digital asset link statement being hosted at the domain
     * it is capturing. This is set through
     * {@link DomainVerificationManager#setDomainVerificationStatus(UUID, Set, int)} by
     * the domain verification agent on device.
     */
    int APPROVAL_LEVEL_VERIFIED = 4;

    /**
     * The app has been installed as an instant app, which grants it total authority on the domains
     * that it declares. It is expected that the package installer validate the domains the app
     * declares against the digital asset link statements before allowing it to be installed.
     *
     * The user is still able to disable instant app link handling through
     * {@link DomainVerificationManager#setDomainVerificationLinkHandlingAllowed(String, boolean)}.
     */
    int APPROVAL_LEVEL_INSTANT_APP = 5;

    /**
     * Defines the possible values for
     * {@link #approvalLevelForDomain(PackageStateInternal, Intent, int, int)} which sorts packages
     * by approval priority. A higher numerical value means the package should override all lower
     * values. This means that comparison using less/greater than IS valid.
     *
     * Negative values are possible, used for tracking specific reasons for why an app doesn't have
     * approval.
     */
    @IntDef({
            APPROVAL_LEVEL_NOT_INSTALLED,
            APPROVAL_LEVEL_DISABLED,
            APPROVAL_LEVEL_UNDECLARED,
            APPROVAL_LEVEL_UNVERIFIED,
            APPROVAL_LEVEL_NONE,
            APPROVAL_LEVEL_LEGACY_ASK,
            APPROVAL_LEVEL_LEGACY_ALWAYS,
            APPROVAL_LEVEL_SELECTION,
            APPROVAL_LEVEL_VERIFIED,
            APPROVAL_LEVEL_INSTANT_APP
    })
    @interface ApprovalLevel {
    }

    static String approvalLevelToDebugString(@ApprovalLevel int level) {
        switch (level) {
            case APPROVAL_LEVEL_NOT_INSTALLED:
                return "NOT_INSTALLED";
            case APPROVAL_LEVEL_DISABLED:
                return "DISABLED";
            case APPROVAL_LEVEL_UNDECLARED:
                return "UNDECLARED";
            case APPROVAL_LEVEL_UNVERIFIED:
                return "UNVERIFIED";
            case APPROVAL_LEVEL_NONE:
                return "NONE";
            case APPROVAL_LEVEL_LEGACY_ASK:
                return "LEGACY_ASK";
            case APPROVAL_LEVEL_LEGACY_ALWAYS:
                return "LEGACY_ALWAYS";
            case APPROVAL_LEVEL_SELECTION:
                return "USER_SELECTION";
            case APPROVAL_LEVEL_VERIFIED:
                return "VERIFIED";
            case APPROVAL_LEVEL_INSTANT_APP:
                return "INSTANT_APP";
            default:
                return "UNKNOWN";
        }
    }

    /** @see DomainVerificationManager#getDomainVerificationInfo(String) */
    @Nullable
    @RequiresPermission(anyOf = {
            android.Manifest.permission.DOMAIN_VERIFICATION_AGENT,
            android.Manifest.permission.UPDATE_DOMAIN_VERIFICATION_USER_SELECTION
    })
    DomainVerificationInfo getDomainVerificationInfo(@NonNull String packageName)
            throws NameNotFoundException;

    /**
     * Generate a new domain set ID to be used for attaching new packages.
     */
    @NonNull
    UUID generateNewId();

    void setConnection(@NonNull Connection connection);

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
     * #restoreSettings(Computer, TypedXmlPullParser)}, or a new one will be generated. In either case, a
     * broadcast will be sent to the domain verification agent so it may re-run any verification
     * logic for the newly associated domains.
     * <p>
     * This will mutate internal {@link DomainVerificationPkgState} and so will hold the internal
     * lock. This should never be called from within the domain verification classes themselves.
     * <p>
     * This will NOT call {@link #writeSettings(Computer, TypedXmlSerializer, boolean, int)}. That must be
     * handled by the caller.
     */
    void addPackage(@NonNull PackageStateInternal newPkgSetting);

    /**
     * Migrates verification state from a previous install to a new one. It is expected that the
     * {@link PackageStateInternal#getDomainSetId()} already be set to the correct value, usually from
     * {@link #generateNewId()}. This will preserve {@link DomainVerificationState#STATE_SUCCESS}
     * domains under the assumption that the new package will pass the same server side config as
     * the previous package, as they have matching signatures.
     * <p>
     * This will mutate internal {@link DomainVerificationPkgState} and so will hold the internal
     * lock. This should never be called from within the domain verification classes themselves.
     * <p>
     * This will NOT call {@link #writeSettings(Computer, TypedXmlSerializer, boolean, int)}. That must be
     * handled by the caller.
     */
    void migrateState(@NonNull PackageStateInternal oldPkgSetting,
            @NonNull PackageStateInternal newPkgSetting);

    /**
     * Serializes the entire internal state. This is equivalent to a full backup of the existing
     * verification state. This write includes legacy state, as a sibling tag the modern state.
     *
     * @param snapshot
     * @param includeSignatures Whether to include the package signatures in the output, mainly
     *                          used for backing up the user settings and ensuring they're
     *                          re-attached to the same package.
     * @param userId The user to write out. Supports {@link UserHandle#USER_ALL} if all users
     */
    void writeSettings(@NonNull Computer snapshot, @NonNull TypedXmlSerializer serializer,
            boolean includeSignatures, @UserIdInt int userId) throws IOException;

    /**
     * Read back a list of {@link DomainVerificationPkgState}s previously written by {@link
     * #writeSettings(Computer, TypedXmlSerializer, boolean, int)}. Assumes that the
     * {@link DomainVerificationPersistence#TAG_DOMAIN_VERIFICATIONS} tag has already been entered.
     * <p>
     * This is expected to only be used to re-attach states for packages already known to be on the
     * device. If restoring from a backup, use {@link #restoreSettings(Computer, TypedXmlPullParser)}.
     */
    void readSettings(@NonNull Computer snapshot, @NonNull TypedXmlPullParser parser)
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
     * Remove all state for the given package for the given user.
     */
    void clearPackageForUser(@NonNull String packageName, @UserIdInt int userId);

    /**
     * Delete all the state for a user. This can be because the user has been removed from the
     * device, or simply that the state for a user should be deleted.
     */
    void clearUser(@UserIdInt int userId);

    /**
     * Restore a list of {@link DomainVerificationPkgState}s previously written by {@link
     * #writeSettings(Computer, TypedXmlSerializer, boolean, int)}. Assumes that the
     * {@link DomainVerificationPersistence#TAG_DOMAIN_VERIFICATIONS}
     * tag has already been entered.
     * <p>
     * This is <b>only</b> for restore, and will override package states, ignoring if their {@link
     * DomainVerificationInfo#getIdentifier()}s match. It's expected that any restored domains
     * marked
     * as success verify against the server correctly, although the verification agent may decide
     * to
     * re-verify them when it gets the chance.
     */
    /*
     * TODO(b/170746586): Figure out how to verify that package signatures match at snapshot time
     *  and restore time.
     */
    void restoreSettings(@NonNull Computer snapshot, @NonNull TypedXmlPullParser parser)
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
     *
     * @return true if state changed successfully
     */
    boolean setLegacyUserState(@NonNull String packageName, @UserIdInt int userId, int state);

    /**
     * Until the legacy APIs are entirely removed, returns the legacy state from the previously
     * written info stored in {@link Settings}.
     */
    int getLegacyState(@NonNull String packageName, @UserIdInt int userId);

    /**
     * Print the verification state and user selection state of a package.
     *
     * @param snapshot
     * @param packageName        the package whose state to change, or all packages if none is
     *                           specified
     * @param userId             the specific user to print, or null to skip printing user selection
 *                           states, supports {@link UserHandle#USER_ALL}
     */
    void printState(@NonNull Computer snapshot, @NonNull IndentingPrintWriter writer,
            @Nullable String packageName, @Nullable @UserIdInt Integer userId)
            throws NameNotFoundException;

    @NonNull
    DomainVerificationShell getShell();

    @NonNull
    DomainVerificationCollector getCollector();

    /**
     * Filters the provided list down to the {@link ResolveInfo} objects that should be allowed
     * to open the domain inside the {@link Intent}. It is possible for no packages represented in
     * the list to be approved, in which case an empty list will be returned.
     *
     * @return the filtered list and the corresponding approval level
     */
    @NonNull
    Pair<List<ResolveInfo>, Integer> filterToApprovedApp(@NonNull Intent intent,
            @NonNull List<ResolveInfo> infos, @UserIdInt int userId,
            @NonNull Function<String, PackageStateInternal> pkgSettingFunction);

    /**
     * Check at what precedence a package resolving a URI is approved to takeover the domain.
     * This can be because the domain was auto-verified for the package, or if the user manually
     * chose to enable the domain for the package. If an app is auto-verified, it will be
     * preferred over apps that were manually selected.
     *
     * NOTE: This should not be used for filtering intent resolution. See
     * {@link #filterToApprovedApp(Intent, List, int, Function)} for that.
     */
    @ApprovalLevel
    int approvalLevelForDomain(@NonNull PackageStateInternal pkgSetting, @NonNull Intent intent,
            @PackageManager.ResolveInfoFlagsBits long resolveInfoFlags, @UserIdInt int userId);

    /**
     * @return the domain verification set ID for the given package, or null if the ID is
     * unavailable
     */
    @Nullable
    UUID getDomainVerificationInfoId(@NonNull String packageName);

    @DomainVerificationManager.Error
    @RequiresPermission(android.Manifest.permission.DOMAIN_VERIFICATION_AGENT)
    int setDomainVerificationStatusInternal(int callingUid, @NonNull UUID domainSetId,
            @NonNull Set<String> domains, int state) throws NameNotFoundException;


    interface Connection extends DomainVerificationEnforcer.Callback {

        /**
         * Notify that a settings change has been made and that eventually
         * {@link #writeSettings(Computer, TypedXmlSerializer, boolean, int)} should be invoked by the parent.
         */
        void scheduleWriteSettings();

        /**
         * Delegate to {@link Binder#getCallingUid()} to allow mocking in tests.
         */
        int getCallingUid();

        /**
         * Delegate to {@link UserHandle#getCallingUserId()} to allow mocking in tests.
         */
        @UserIdInt
        int getCallingUserId();

        /**
         * @see DomainVerificationProxy.BaseConnection#schedule(int, java.lang.Object)
         */
        void schedule(int code, @Nullable Object object);

        @UserIdInt
        int[] getAllUserIds();

        @NonNull
        Computer snapshot();
    }
}
