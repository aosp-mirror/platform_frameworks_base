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
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.IntentFilterVerificationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageUserState;
import android.content.pm.domain.verify.DomainVerificationManager;
import android.content.pm.domain.verify.DomainVerificationSet;
import android.content.pm.domain.verify.DomainVerificationState;
import android.content.pm.domain.verify.DomainVerificationUserSelection;
import android.content.pm.domain.verify.IDomainVerificationManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Singleton;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import com.android.internal.annotations.GuardedBy;
import com.android.server.SystemConfig;
import com.android.server.SystemService;
import com.android.server.compat.PlatformCompat;
import com.android.server.pm.PackageSetting;
import com.android.server.pm.domain.verify.models.DomainVerificationPkgState;
import com.android.server.pm.domain.verify.models.DomainVerificationStateMap;
import com.android.server.pm.domain.verify.models.DomainVerificationUserState;
import com.android.server.pm.parsing.pkg.AndroidPackage;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class DomainVerificationService extends SystemService
        implements DomainVerificationManagerInternal {

    private static final String TAG = "DomainVerificationService";

    /**
     * States that are currently alive and attached to a package. Entries are exclusive with the
     * state stored in {@link DomainVerificationSettings}, as any pending/restored state should be
     * immediately attached once its available.
     **/
    @GuardedBy("mLock")
    @NonNull
    private final DomainVerificationStateMap<DomainVerificationPkgState> mAttachedPkgStates =
            new DomainVerificationStateMap<>();

    /**
     * Lock for all state reads/writes.
     */
    private final Object mLock = new Object();

    @NonNull
    private final Singleton<Connection> mConnection;

    @NonNull
    private final SystemConfig mSystemConfig;

    @NonNull
    private final DomainVerificationSettings mSettings;

    @NonNull
    private final DomainVerificationCollector mCollector;

    @NonNull
    private final IDomainVerificationManager.Stub mStub = new DomainVerificationManagerStub(this);

    public DomainVerificationService(@NonNull Context context, @NonNull SystemConfig systemConfig,
            @NonNull PlatformCompat platformCompat, @NonNull Singleton<Connection> connection) {
        super(context);
        mConnection = connection;
        mSystemConfig = systemConfig;
        mSettings = new DomainVerificationSettings();
        mCollector = new DomainVerificationCollector(platformCompat, systemConfig);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.DOMAIN_VERIFICATION_SERVICE, mStub);
    }

    @NonNull
    @Override
    public List<String> getValidVerificationPackageNames() {
        return null;
    }

    @Nullable
    @Override
    public DomainVerificationSet getDomainVerificationSet(@NonNull String packageName)
            throws NameNotFoundException {
        return null;
    }

    @Override
    public void setDomainVerificationStatus(@NonNull UUID domainSetId, @NonNull Set<String> domains,
            int state) throws InvalidDomainSetException, NameNotFoundException {
        //TODO(b/163565712): Implement method
        mConnection.get().scheduleWriteSettings();
    }

    @Override
    public void setDomainVerificationLinkHandlingAllowed(@NonNull String packageName,
            boolean allowed) throws NameNotFoundException {
        //TODO(b/163565712): Implement method
        mConnection.get().scheduleWriteSettings();
    }

    public void setDomainVerificationLinkHandlingAllowed(@NonNull String packageName,
            boolean allowed, @UserIdInt int userId) throws NameNotFoundException {
        //TODO(b/163565712): Implement method
        mConnection.get().scheduleWriteSettings();
    }

    @Override
    public void setDomainVerificationUserSelection(@NonNull UUID domainSetId,
            @NonNull Set<String> domains, boolean enabled)
            throws InvalidDomainSetException, NameNotFoundException {
        //TODO(b/163565712): Implement method
        mConnection.get().scheduleWriteSettings();
    }

    public void setDomainVerificationUserSelection(@NonNull UUID domainSetId,
            @NonNull Set<String> domains, boolean enabled, @UserIdInt int userId)
            throws InvalidDomainSetException, NameNotFoundException {
        //TODO(b/163565712): Implement method
        mConnection.get().scheduleWriteSettings();
    }

    @Nullable
    @Override
    public DomainVerificationUserSelection getDomainVerificationUserSelection(
            @NonNull String packageName) throws NameNotFoundException {
        return null;
    }

    @Nullable
    public DomainVerificationUserSelection getDomainVerificationUserSelection(
            @NonNull String packageName, @UserIdInt int userId) throws NameNotFoundException {
        return null;
    }

    @NonNull
    @Override
    public UUID generateNewId() {
        // TODO(b/159952358): Domain set ID collisions
        return UUID.randomUUID();
    }

    @Override
    public void migrateState(@NonNull PackageSetting oldPkgSetting,
            @NonNull PackageSetting newPkgSetting) {
        String pkgName = newPkgSetting.name;
        boolean sendBroadcast;

        synchronized (mLock) {
            UUID oldDomainSetId = oldPkgSetting.getDomainSetId();
            UUID newDomainSetId = newPkgSetting.getDomainSetId();
            DomainVerificationPkgState oldPkgState = mAttachedPkgStates.remove(oldDomainSetId);

            AndroidPackage oldPkg = oldPkgSetting.getPkg();
            AndroidPackage newPkg = newPkgSetting.getPkg();

            ArrayMap<String, Integer> newStateMap = new ArrayMap<>();
            SparseArray<DomainVerificationUserState> newUserStates = new SparseArray<>();

            if (oldPkgState == null || oldPkg == null || newPkg == null) {
                // Should be impossible, but to be safe, continue with a new blank state instead
                Slog.wtf(TAG, "Invalid state nullability old state = " + oldPkgState
                        + ", old pkgSetting = " + oldPkgSetting
                        + ", new pkgSetting = " + newPkgSetting
                        + ", old pkg = " + oldPkg
                        + ", new pkg = " + newPkg, new Exception());

                DomainVerificationPkgState newPkgState = new DomainVerificationPkgState(
                        pkgName, newDomainSetId, true, newStateMap, newUserStates);
                mAttachedPkgStates.put(pkgName, newDomainSetId, newPkgState);
                return;
            }

            ArrayMap<String, Integer> oldStateMap = oldPkgState.getStateMap();
            ArraySet<String> newAutoVerifyDomains = mCollector.collectAutoVerifyDomains(newPkg);
            int newDomainsSize = newAutoVerifyDomains.size();

            for (int newDomainsIndex = 0; newDomainsIndex < newDomainsSize; newDomainsIndex++) {
                String domain = newAutoVerifyDomains.valueAt(newDomainsIndex);
                Integer oldStateInteger = oldStateMap.get(domain);
                if (oldStateInteger != null) {
                    int oldState = oldStateInteger;
                    switch (oldState) {
                        case DomainVerificationState.STATE_SUCCESS:
                        case DomainVerificationState.STATE_RESTORED:
                        case DomainVerificationState.STATE_MIGRATED:
                            newStateMap.put(domain, oldState);
                            break;
                        default:
                            // In all other cases, the state code is left unset
                            // (STATE_NO_RESPONSE) to signal to the verification agent that any
                            // existing error has been cleared and the domain should be
                            // re-attempted. This makes update of a package a signal to
                            // re-verify.
                            break;
                    }
                }
            }

            SparseArray<DomainVerificationUserState> oldUserStates =
                    oldPkgState.getUserSelectionStates();
            int oldUserStatesSize = oldUserStates.size();
            if (oldUserStatesSize > 0) {
                ArraySet<String> newWebDomains = mCollector.collectAutoVerifyDomains(newPkg);
                for (int oldUserStatesIndex = 0; oldUserStatesIndex < oldUserStatesSize;
                        oldUserStatesIndex++) {
                    int userId = oldUserStates.keyAt(oldUserStatesIndex);
                    DomainVerificationUserState oldUserState = oldUserStates.valueAt(
                            oldUserStatesIndex);
                    ArraySet<String> oldEnabledHosts = oldUserState.getEnabledHosts();
                    ArraySet<String> newEnabledHosts = new ArraySet<>(oldEnabledHosts);
                    newEnabledHosts.retainAll(newWebDomains);
                    DomainVerificationUserState newUserState = new DomainVerificationUserState(
                            userId, newEnabledHosts, oldUserState.isDisallowLinkHandling());
                    newUserStates.put(userId, newUserState);
                }
            }

            boolean hasAutoVerifyDomains = newDomainsSize > 0;
            boolean stateApplied = applyImmutableState(pkgName, newStateMap, newAutoVerifyDomains);

            // TODO(b/159952358): sendBroadcast should be abstracted so it doesn't have to be aware
            //  of whether/what state was applied. Probably some method which iterates the map to
            //  check for any domains that actually have state changeable by the domain verification
            //  agent.
            sendBroadcast = hasAutoVerifyDomains && !stateApplied;

            mAttachedPkgStates.put(pkgName, newDomainSetId, new DomainVerificationPkgState(
                    pkgName, newDomainSetId, hasAutoVerifyDomains, newStateMap, newUserStates));
        }

        if (sendBroadcast) {
            sendBroadcastForPackage(pkgName);
        }
    }

    // TODO(b/159952358): Handle valid domainSetIds for PackageSettings with no AndroidPackage
    @Override
    public void addPackage(@NonNull PackageSetting newPkgSetting) {
        // TODO(b/159952358): Optimize packages without any domains. Those wouldn't have to be in
        //  the state map, but it would require handling the "migration" case where an app either
        //  gains or loses all domains.

        UUID domainSetId = newPkgSetting.getDomainSetId();
        String pkgName = newPkgSetting.name;

        boolean sendBroadcast = true;

        DomainVerificationPkgState pkgState;
        pkgState = mSettings.getPendingState(pkgName);
        if (pkgState != null) {
            // Don't send when attaching from pending read, which is usually boot scan. Re-send on
            // boot is handled in a separate method once all packages are added.
            sendBroadcast = false;
        } else {
            pkgState = mSettings.getRestoredState(pkgName);
        }

        AndroidPackage pkg = newPkgSetting.getPkg();
        ArraySet<String> domains = mCollector.collectAutoVerifyDomains(pkg);
        boolean hasAutoVerifyDomains = !domains.isEmpty();
        boolean isPendingOrRestored = pkgState != null;
        if (isPendingOrRestored) {
            pkgState.setId(domainSetId);
        } else {
            pkgState = new DomainVerificationPkgState(pkgName, domainSetId, hasAutoVerifyDomains);
        }

        boolean stateApplied = applyImmutableState(pkgState, domains);
        if (!stateApplied && !isPendingOrRestored) {
            // TODO(b/159952358): Test this behavior
            // Attempt to preserve user experience by automatically verifying all domains from
            // legacy state if they were previously approved, or by automatically enabling all
            // hosts through user selection if legacy state indicates a user previously made the
            // choice in settings to allow supported links. The domain verification agent should
            // re-verify these links (set to STATE_MIGRATED) at the next possible opportunity,
            // and disable them if appropriate.
            ArraySet<String> webDomains = null;

            @SuppressWarnings("deprecation")
            SparseArray<PackageUserState> userState = newPkgSetting.getUserState();
            int userStateSize = userState.size();
            for (int index = 0; index < userStateSize; index++) {
                int userId = userState.keyAt(index);
                int legacyStatus = userState.valueAt(index).domainVerificationStatus;
                if (legacyStatus
                        == PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS) {
                    if (webDomains == null) {
                        webDomains = mCollector.collectAllWebDomains(pkg);
                    }

                    pkgState.getOrCreateUserSelectionState(userId).addHosts(webDomains);
                }
            }

            IntentFilterVerificationInfo legacyInfo =
                    newPkgSetting.getIntentFilterVerificationInfo();
            if (legacyInfo != null
                    && legacyInfo.getStatus()
                    == PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS) {
                ArrayMap<String, Integer> stateMap = pkgState.getStateMap();
                int domainsSize = domains.size();
                for (int index = 0; index < domainsSize; index++) {
                    stateMap.put(domains.valueAt(index), DomainVerificationState.STATE_MIGRATED);
                }
            }
        }

        synchronized (mLock) {
            mAttachedPkgStates.put(pkgName, domainSetId, pkgState);
        }

        if (sendBroadcast && hasAutoVerifyDomains) {
            sendBroadcastForPackage(pkgName);
        }
    }

    private boolean applyImmutableState(@NonNull DomainVerificationPkgState pkgState,
            @NonNull ArraySet<String> autoVerifyDomains) {
        return applyImmutableState(pkgState.getPackageName(), pkgState.getStateMap(),
                autoVerifyDomains);
    }

    /**
     * Applies any immutable state as the final step when adding or migrating state. Currently only
     * applies {@link SystemConfig#getLinkedApps()}, which approves all domains for a package.
     */
    private boolean applyImmutableState(@NonNull String packageName,
            @NonNull ArrayMap<String, Integer> stateMap,
            @NonNull ArraySet<String> autoVerifyDomains) {
        if (mSystemConfig.getLinkedApps().contains(packageName)) {
            int domainsSize = autoVerifyDomains.size();
            for (int index = 0; index < domainsSize; index++) {
                stateMap.put(autoVerifyDomains.valueAt(index),
                        DomainVerificationState.STATE_APPROVED);
            }
            return true;
        }

        return false;
    }

    @Override
    public void writeSettings(@NonNull TypedXmlSerializer serializer) throws IOException {
        synchronized (mLock) {
            mSettings.writeSettings(serializer, mAttachedPkgStates);
        }
    }

    @Override
    public void readSettings(@NonNull TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        synchronized (mLock) {
            mSettings.readSettings(parser, mAttachedPkgStates);
        }
    }

    @Override
    public void restoreSettings(@NonNull TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        synchronized (mLock) {
            mSettings.restoreSettings(parser, mAttachedPkgStates);
        }
    }

    @Override
    public void clearPackage(@NonNull String packageName) {
        synchronized (mLock) {
            mAttachedPkgStates.remove(packageName);
        }

        mConnection.get().scheduleWriteSettings();
    }

    @Override
    public void clearUser(@UserIdInt int userId) {
        synchronized (mLock) {
            int attachedSize = mAttachedPkgStates.size();
            for (int index = 0; index < attachedSize; index++) {
                mAttachedPkgStates.valueAt(index).removeUser(userId);
            }

            mSettings.removeUser(userId);
        }

        mConnection.get().scheduleWriteSettings();
    }

    private void sendBroadcastForPackage(@NonNull String packageName) {
        // TODO(b/159952358): Implement proxy
    }

    public interface Connection {

        /**
         * Notify that a settings change has been made and that eventually
         * {@link #writeSettings(TypedXmlSerializer)} should be invoked by the parent.
         */
        void scheduleWriteSettings();
    }
}
