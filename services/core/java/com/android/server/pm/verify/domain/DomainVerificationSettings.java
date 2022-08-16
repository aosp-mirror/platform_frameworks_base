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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.pm.verify.domain.DomainVerificationState;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.Computer;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.verify.domain.models.DomainVerificationInternalUserState;
import com.android.server.pm.verify.domain.models.DomainVerificationPkgState;
import com.android.server.pm.verify.domain.models.DomainVerificationStateMap;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.function.Function;

class DomainVerificationSettings {

    @NonNull
    private final DomainVerificationCollector mCollector;

    /**
     * States read from disk that have yet to attach to a package, but are expected to, generally in
     * the context of scanning packages already on disk. This is expected to be empty once the boot
     * package scan completes.
     **/
    @GuardedBy("mLock")
    @NonNull
    private final ArrayMap<String, DomainVerificationPkgState> mPendingPkgStates = new ArrayMap<>();

    /**
     * States from restore that have yet to attach to a package. These are special in that their IDs
     * are dropped when the package is installed/otherwise becomes available, because the ID will
     * not match if the data is restored from a different device install.
     * <p>
     * If multiple restore calls come in and they overlap, the latest entry added for a package name
     * will be taken, dropping any previous versions.
     **/
    @GuardedBy("mLock")
    @NonNull
    private final ArrayMap<String, DomainVerificationPkgState> mRestoredPkgStates =
            new ArrayMap<>();

    /**
     * Lock for all state reads/writes.
     */
    private final Object mLock = new Object();

    public DomainVerificationSettings(@NonNull DomainVerificationCollector collector) {
        mCollector = collector;
    }

    public void writeSettings(@NonNull TypedXmlSerializer xmlSerializer,
            @NonNull DomainVerificationStateMap<DomainVerificationPkgState> liveState,
            @NonNull Function<String, String> pkgSignatureFunction) {

    }

    public void writeSettings(@NonNull TypedXmlSerializer xmlSerializer,
            @NonNull DomainVerificationStateMap<DomainVerificationPkgState> liveState,
            @UserIdInt int userId, @NonNull Function<String, String> pkgSignatureFunction)
            throws IOException {
        synchronized (mLock) {
            DomainVerificationPersistence.writeToXml(xmlSerializer, liveState,
                    mPendingPkgStates, mRestoredPkgStates, userId, pkgSignatureFunction);
        }
    }

    /**
     * Parses a previously stored set of states and merges them with {@param liveState}, directly
     * mutating the values. This is intended for reading settings written by {@link
     * #writeSettings(TypedXmlSerializer, DomainVerificationStateMap, int, Function)} on the same
     * device setup.
     */
    public void readSettings(@NonNull TypedXmlPullParser parser,
            @NonNull DomainVerificationStateMap<DomainVerificationPkgState> liveState,
            @NonNull Computer snapshot) throws IOException, XmlPullParserException {
        DomainVerificationPersistence.ReadResult result =
                DomainVerificationPersistence.readFromXml(parser);
        ArrayMap<String, DomainVerificationPkgState> active = result.active;
        ArrayMap<String, DomainVerificationPkgState> restored = result.restored;

        synchronized (mLock) {
            int activeSize = active.size();
            for (int activeIndex = 0; activeIndex < activeSize; activeIndex++) {
                DomainVerificationPkgState pkgState = active.valueAt(activeIndex);
                String pkgName = pkgState.getPackageName();
                DomainVerificationPkgState existingState = liveState.get(pkgName);
                if (existingState != null) {
                    // This branch should never be possible. Settings should be read from disk
                    // before any states are attached. But just in case, handle it.
                    if (!existingState.getId().equals(pkgState.getId())) {
                        mergePkgState(existingState, pkgState, snapshot);
                    }
                } else {
                    mPendingPkgStates.put(pkgName, pkgState);
                }
            }

            int restoredSize = restored.size();
            for (int restoredIndex = 0; restoredIndex < restoredSize; restoredIndex++) {
                DomainVerificationPkgState pkgState = restored.valueAt(restoredIndex);
                mRestoredPkgStates.put(pkgState.getPackageName(), pkgState);
            }
        }
    }

    /**
     * Parses a previously stored set of states and merges them with {@param liveState}, directly
     * mutating the values. This is intended for restoration across device setups.
     */
    public void restoreSettings(@NonNull TypedXmlPullParser parser,
            @NonNull DomainVerificationStateMap<DomainVerificationPkgState> liveState,
            @NonNull Computer snapshot) throws IOException, XmlPullParserException {
        // TODO(b/170746586): Restoration assumes user IDs match, which is probably not the case on
        //  a new device.

        DomainVerificationPersistence.ReadResult result =
                DomainVerificationPersistence.readFromXml(parser);

        // When restoring settings, both active and previously restored are merged, since they
        // should both go into the newly restored data. Active is added on top of restored just
        // in case a duplicate is found. Active should be preferred.
        ArrayMap<String, DomainVerificationPkgState> stateList = result.restored;
        stateList.putAll(result.active);

        synchronized (mLock) {
            for (int stateIndex = 0; stateIndex < stateList.size(); stateIndex++) {
                DomainVerificationPkgState newState = stateList.valueAt(stateIndex);
                String pkgName = newState.getPackageName();
                DomainVerificationPkgState existingState = liveState.get(pkgName);
                if (existingState == null) {
                    existingState = mPendingPkgStates.get(pkgName);
                }
                if (existingState == null) {
                    existingState = mRestoredPkgStates.get(pkgName);
                }

                if (existingState != null) {
                    mergePkgState(existingState, newState, snapshot);
                } else {
                    // If there's no existing state, that means the new state has to be transformed
                    // in preparation for attaching to brand new package that may eventually be
                    // installed. This means coercing STATE_SUCCESS and STATE_RESTORED to
                    // STATE_RESTORED and dropping everything else, the same logic that
                    // mergePkgState runs, without the merge part.
                    ArrayMap<String, Integer> stateMap = newState.getStateMap();
                    int size = stateMap.size();
                    for (int index = size - 1; index >= 0; index--) {
                        Integer stateInteger = stateMap.valueAt(index);
                        if (stateInteger != null) {
                            int state = stateInteger;
                            if (state == DomainVerificationState.STATE_SUCCESS
                                    || state == DomainVerificationState.STATE_RESTORED) {
                                stateMap.setValueAt(index, DomainVerificationState.STATE_RESTORED);
                            } else {
                                stateMap.removeAt(index);
                            }
                        }
                    }

                    mRestoredPkgStates.put(pkgName, newState);
                }
            }
        }
    }

    /**
     * Merges a newly restored state with existing state. This should only be called for restore,
     * when the IDs aren't required to match.
     * <p>
     * If the existing state for a domain is
     * {@link DomainVerificationState#STATE_NO_RESPONSE}, then it will be overridden with
     * {@link DomainVerificationState#STATE_RESTORED} if the restored state is
     * {@link DomainVerificationState#STATE_SUCCESS} or
     * {@link DomainVerificationState#STATE_RESTORED}.
     * <p>
     * Otherwise the existing state is preserved, assuming any system rules, success state, or
     * specific error codes are fresher than the restored state. Essentially state is only restored
     * to grant additional verifications to an app.
     * <p>
     * For user selection state, presence in either state will be considered an enabled host. This
     * assumes that all user IDs on the device match. If this isn't the case, then restore may set
     * unexpected values.
     *
     * NOTE: This will mutate {@param oldState} to contain the merged state.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public void mergePkgState(@NonNull DomainVerificationPkgState oldState,
            @NonNull DomainVerificationPkgState newState, @NonNull Computer snapshot) {
        PackageStateInternal pkgSetting =
                snapshot.getPackageStateInternal(oldState.getPackageName());
        AndroidPackage pkg = pkgSetting == null ? null : pkgSetting.getPkg();
        Set<String> validDomains = pkg == null
                ? Collections.emptySet() : mCollector.collectValidAutoVerifyDomains(pkg);

        ArrayMap<String, Integer> oldStateMap = oldState.getStateMap();
        ArrayMap<String, Integer> newStateMap = newState.getStateMap();
        int size = newStateMap.size();
        for (int index = 0; index < size; index++) {
            String domain = newStateMap.keyAt(index);
            Integer newStateCode = newStateMap.valueAt(index);
            if (!validDomains.contains(domain)) {
                // Cannot add domains to an app
                continue;
            }

            Integer oldStateCode = oldStateMap.get(domain);
            if (oldStateCode == null || oldStateCode == DomainVerificationState.STATE_NO_RESPONSE) {
                if (newStateCode == DomainVerificationState.STATE_SUCCESS
                        || newStateCode == DomainVerificationState.STATE_RESTORED) {
                    oldStateMap.put(domain, DomainVerificationState.STATE_RESTORED);
                }
            }
        }

        SparseArray<DomainVerificationInternalUserState> oldSelectionStates =
                oldState.getUserStates();

        SparseArray<DomainVerificationInternalUserState> newSelectionStates =
                newState.getUserStates();

        final int userStateSize = newSelectionStates.size();
        for (int index = 0; index < userStateSize; index++) {
            int userId = newSelectionStates.keyAt(index);
            DomainVerificationInternalUserState newUserState = newSelectionStates.valueAt(index);
            if (newUserState != null) {
                ArraySet<String> newEnabledHosts = newUserState.getEnabledHosts();
                DomainVerificationInternalUserState oldUserState = oldSelectionStates.get(userId);
                boolean linkHandlingAllowed = newUserState.isLinkHandlingAllowed();
                if (oldUserState == null) {
                    oldSelectionStates.put(userId, new DomainVerificationInternalUserState(userId,
                            newEnabledHosts, linkHandlingAllowed));
                } else {
                    oldUserState.addHosts(newEnabledHosts)
                            .setLinkHandlingAllowed(linkHandlingAllowed);
                }
            }
        }
    }

    public void removePackage(@NonNull String packageName) {
        synchronized (mLock) {
            mPendingPkgStates.remove(packageName);
            mRestoredPkgStates.remove(packageName);
        }
    }

    public void removePackageForUser(@NonNull String packageName, @UserIdInt int userId) {
        synchronized (mLock) {
            final DomainVerificationPkgState pendingPkgState = mPendingPkgStates.get(packageName);
            if (pendingPkgState != null) {
                pendingPkgState.removeUser(userId);
            }
            // TODO(b/170746586): Restored assumes user IDs match, which is probably not the case
            //  on a new device
            final DomainVerificationPkgState restoredPkgState = mRestoredPkgStates.get(packageName);
            if (restoredPkgState != null) {
                restoredPkgState.removeUser(userId);
            }
        }
    }

    public void removeUser(@UserIdInt int userId) {
        synchronized (mLock) {
            int pendingSize = mPendingPkgStates.size();
            for (int index = 0; index < pendingSize; index++) {
                mPendingPkgStates.valueAt(index).removeUser(userId);
            }

            // TODO(b/170746586): Restored assumes user IDs match, which is probably not the case
            //  on a new device
            int restoredSize = mRestoredPkgStates.size();
            for (int index = 0; index < restoredSize; index++) {
                mRestoredPkgStates.valueAt(index).removeUser(userId);
            }
        }
    }

    @Nullable
    public DomainVerificationPkgState removePendingState(@NonNull String pkgName) {
        synchronized (mLock) {
            return mPendingPkgStates.remove(pkgName);
        }
    }

    @Nullable
    public DomainVerificationPkgState removeRestoredState(@NonNull String pkgName) {
        synchronized (mLock) {
            return mRestoredPkgStates.remove(pkgName);
        }
    }
}
