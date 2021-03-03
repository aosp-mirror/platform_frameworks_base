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
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.Signature;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.content.pm.verify.domain.DomainVerificationState;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.PackageUtils;
import android.util.SparseArray;

import com.android.internal.util.CollectionUtils;
import com.android.server.pm.PackageSetting;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.verify.domain.models.DomainVerificationPkgState;
import com.android.server.pm.verify.domain.models.DomainVerificationStateMap;
import com.android.server.pm.verify.domain.models.DomainVerificationUserState;

import java.util.Arrays;
import java.util.function.Function;

@SuppressWarnings("PointlessBooleanExpression")
public class DomainVerificationDebug {

    // Disable to turn off all logging. This is used to allow a "basic" set of debug flags to be
    // enabled and checked in, without having everything be on or off.
    public static final boolean DEBUG_ANY = false;

    // Enable to turn on all logging. Requires enabling DEBUG_ANY.
    public static final boolean DEBUG_ALL = false;

    public static final boolean DEBUG_APPROVAL = DEBUG_ANY && (DEBUG_ALL || true);
    public static final boolean DEBUG_BROADCASTS = DEBUG_ANY && (DEBUG_ALL || false);
    public static final boolean DEBUG_PROXIES = DEBUG_ANY && (DEBUG_ALL || false);

    @NonNull
    private final DomainVerificationCollector mCollector;

    DomainVerificationDebug(DomainVerificationCollector collector) {
        mCollector = collector;
    }

    public void printState(@NonNull IndentingPrintWriter writer, @Nullable String packageName,
            @Nullable @UserIdInt Integer userId,
            @NonNull Function<String, PackageSetting> pkgSettingFunction,
            @NonNull DomainVerificationStateMap<DomainVerificationPkgState> stateMap)
            throws NameNotFoundException {
        ArrayMap<String, Integer> reusedMap = new ArrayMap<>();
        ArraySet<String> reusedSet = new ArraySet<>();

        if (packageName == null) {
            int size = stateMap.size();
            for (int index = 0; index < size; index++) {
                DomainVerificationPkgState pkgState = stateMap.valueAt(index);
                String pkgName = pkgState.getPackageName();
                PackageSetting pkgSetting = pkgSettingFunction.apply(pkgName);
                if (pkgSetting == null || pkgSetting.getPkg() == null) {
                    continue;
                }

                boolean wasHeaderPrinted = printState(writer, pkgState, pkgSetting.getPkg(),
                        reusedMap, false);
                printState(writer, pkgState, pkgSetting.getPkg(), userId, reusedSet,
                        wasHeaderPrinted);
            }
        } else {
            DomainVerificationPkgState pkgState = stateMap.get(packageName);
            if (pkgState == null) {
                throw DomainVerificationUtils.throwPackageUnavailable(packageName);
            }

            PackageSetting pkgSetting = pkgSettingFunction.apply(packageName);
            if (pkgSetting == null || pkgSetting.getPkg() == null) {
                throw DomainVerificationUtils.throwPackageUnavailable(packageName);
            }

            AndroidPackage pkg = pkgSetting.getPkg();
            printState(writer, pkgState, pkg, reusedMap, false);
            printState(writer, pkgState, pkg, userId, reusedSet, true);
        }
    }

    boolean printState(@NonNull IndentingPrintWriter writer,
            @NonNull DomainVerificationPkgState pkgState, @NonNull AndroidPackage pkg,
            @NonNull ArrayMap<String, Integer> reusedMap, boolean wasHeaderPrinted) {
        reusedMap.clear();
        reusedMap.putAll(pkgState.getStateMap());

        ArraySet<String> declaredDomains = mCollector.collectValidAutoVerifyDomains(pkg);
        int declaredSize = declaredDomains.size();
        for (int declaredIndex = 0; declaredIndex < declaredSize; declaredIndex++) {
            String domain = declaredDomains.valueAt(declaredIndex);
            reusedMap.putIfAbsent(domain, DomainVerificationState.STATE_NO_RESPONSE);
        }

        boolean printedHeader = false;

        if (!reusedMap.isEmpty()) {
            if (!wasHeaderPrinted) {
                Signature[] signatures = pkg.getSigningDetails().signatures;
                String signaturesDigest = signatures == null ? null : Arrays.toString(
                        PackageUtils.computeSignaturesSha256Digests(
                                pkg.getSigningDetails().signatures));

                writer.println(pkgState.getPackageName() + ":");
                writer.increaseIndent();
                writer.println("ID: " + pkgState.getId());
                writer.println("Signatures: " + signaturesDigest);
                writer.decreaseIndent();
                printedHeader = true;
            }

            writer.increaseIndent();
            final ArraySet<String> invalidDomains = mCollector.collectInvalidAutoVerifyDomains(pkg);
            if (!invalidDomains.isEmpty()) {
                writer.println("Invalid autoVerify domains:");
                writer.increaseIndent();
                int size = invalidDomains.size();
                for (int index = 0; index < size; index++) {
                    writer.println(invalidDomains.valueAt(index));
                }
                writer.decreaseIndent();
            }

            writer.println("Domain verification state:");
            writer.increaseIndent();
            int stateSize = reusedMap.size();
            for (int stateIndex = 0; stateIndex < stateSize; stateIndex++) {
                String domain = reusedMap.keyAt(stateIndex);
                Integer state = reusedMap.valueAt(stateIndex);
                writer.print(domain);
                writer.print(": ");
                writer.println(DomainVerificationManager.stateToDebugString(state));
            }
            writer.decreaseIndent();
            writer.decreaseIndent();
        }

        return printedHeader;
    }

    void printState(@NonNull IndentingPrintWriter writer,
            @NonNull DomainVerificationPkgState pkgState, @NonNull AndroidPackage pkg,
            @Nullable @UserIdInt Integer userId, @NonNull ArraySet<String> reusedSet,
            boolean wasHeaderPrinted) {
        if (userId == null) {
            return;
        }

        ArraySet<String> allWebDomains = mCollector.collectAllWebDomains(pkg);
        SparseArray<DomainVerificationUserState> userStates =
                pkgState.getUserSelectionStates();
        if (userId == UserHandle.USER_ALL) {
            int size = userStates.size();
            if (size == 0) {
                printState(writer, pkgState, userId, null, reusedSet, allWebDomains,
                        wasHeaderPrinted);
            } else {
                for (int index = 0; index < size; index++) {
                    DomainVerificationUserState userState = userStates.valueAt(index);
                    printState(writer, pkgState, userState.getUserId(), userState, reusedSet,
                            allWebDomains, wasHeaderPrinted);
                }
            }
        } else {
            DomainVerificationUserState userState = userStates.get(userId);
            printState(writer, pkgState, userId, userState, reusedSet, allWebDomains,
                    wasHeaderPrinted);
        }
    }

    boolean printState(@NonNull IndentingPrintWriter writer,
            @NonNull DomainVerificationPkgState pkgState, @UserIdInt int userId,
            @Nullable DomainVerificationUserState userState, @NonNull ArraySet<String> reusedSet,
            @NonNull ArraySet<String> allWebDomains, boolean wasHeaderPrinted) {
        reusedSet.clear();
        reusedSet.addAll(allWebDomains);
        if (userState != null) {
            reusedSet.removeAll(userState.getEnabledHosts());
        }

        boolean printedHeader = false;

        ArraySet<String> enabledHosts = userState == null ? null : userState.getEnabledHosts();
        int enabledSize = CollectionUtils.size(enabledHosts);
        int disabledSize = reusedSet.size();
        if (enabledSize > 0 || disabledSize > 0) {
            if (!wasHeaderPrinted) {
                writer.println(pkgState.getPackageName() + " " + pkgState.getId() + ":");
                printedHeader = true;
            }

            boolean isLinkHandlingAllowed = userState == null || userState.isLinkHandlingAllowed();

            writer.increaseIndent();
            writer.print("User ");
            writer.print(userId == UserHandle.USER_ALL ? "all" : userId);
            writer.println(":");
            writer.increaseIndent();
            writer.print("Verification link handling allowed: ");
            writer.println(isLinkHandlingAllowed);
            writer.println("Selection state:");
            writer.increaseIndent();

            if (enabledSize > 0) {
                writer.println("Enabled:");
                writer.increaseIndent();
                for (int enabledIndex = 0; enabledIndex < enabledSize; enabledIndex++) {
                    //noinspection ConstantConditions
                    writer.println(enabledHosts.valueAt(enabledIndex));
                }
                writer.decreaseIndent();
            }

            if (disabledSize > 0) {
                writer.println("Disabled:");
                writer.increaseIndent();
                for (int disabledIndex = 0; disabledIndex < disabledSize; disabledIndex++) {
                    writer.println(reusedSet.valueAt(disabledIndex));
                }
                writer.decreaseIndent();
            }

            writer.decreaseIndent();
            writer.decreaseIndent();
            writer.decreaseIndent();
        }

        return printedHeader;
    }
}
