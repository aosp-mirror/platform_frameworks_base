/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm;

import static android.content.pm.PackageManager.MATCH_DEBUG_TRIAGED_MISSING;
import static android.content.pm.PackageManagerInternal.LAST_KNOWN_PACKAGE;

import static com.android.server.pm.PackageManagerServiceUtils.dumpCriticalInfo;

import android.content.ComponentName;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.SharedLibraryInfo;
import android.os.Binder;
import android.os.UserHandle;
import android.os.incremental.PerUidReadTimeouts;
import android.service.pm.PackageServiceDumpProto;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.pm.verify.domain.proxy.DomainVerificationProxy;
import com.android.server.utils.WatchedLongSparseArray;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Dumps PackageManagerService internal states.
 */
final class DumpHelper {
    final PackageManagerService mPm;

    DumpHelper(PackageManagerService pm) {
        mPm = pm;
    }

    public void doDump(FileDescriptor fd, PrintWriter pw, String[] args) {
        DumpState dumpState = new DumpState();
        ArraySet<String> permissionNames = null;

        int opti = 0;
        while (opti < args.length) {
            String opt = args[opti];
            if (opt == null || opt.length() <= 0 || opt.charAt(0) != '-') {
                break;
            }
            opti++;

            if ("-a".equals(opt)) {
                // Right now we only know how to print all.
            } else if ("-h".equals(opt)) {
                printHelp(pw);
                return;
            } else if ("--checkin".equals(opt)) {
                dumpState.setCheckIn(true);
            } else if ("--all-components".equals(opt)) {
                dumpState.setOptionEnabled(DumpState.OPTION_DUMP_ALL_COMPONENTS);
            } else if ("-f".equals(opt)) {
                dumpState.setOptionEnabled(DumpState.OPTION_SHOW_FILTERS);
            } else if ("--proto".equals(opt)) {
                dumpProto(fd);
                return;
            } else {
                pw.println("Unknown argument: " + opt + "; use -h for help");
            }
        }

        // Is the caller requesting to dump a particular piece of data?
        if (opti < args.length) {
            String cmd = args[opti];
            opti++;
            // Is this a package name?
            if ("android".equals(cmd) || cmd.contains(".")) {
                dumpState.setTargetPackageName(cmd);
                // When dumping a single package, we always dump all of its
                // filter information since the amount of data will be reasonable.
                dumpState.setOptionEnabled(DumpState.OPTION_SHOW_FILTERS);
            } else if ("check-permission".equals(cmd)) {
                if (opti >= args.length) {
                    pw.println("Error: check-permission missing permission argument");
                    return;
                }
                String perm = args[opti];
                opti++;
                if (opti >= args.length) {
                    pw.println("Error: check-permission missing package argument");
                    return;
                }

                String pkg = args[opti];
                opti++;
                int user = UserHandle.getUserId(Binder.getCallingUid());
                if (opti < args.length) {
                    try {
                        user = Integer.parseInt(args[opti]);
                    } catch (NumberFormatException e) {
                        pw.println("Error: check-permission user argument is not a number: "
                                + args[opti]);
                        return;
                    }
                }

                // Normalize package name to handle renamed packages and static libs
                pkg = mPm.resolveInternalPackageNameLPr(pkg, PackageManager.VERSION_CODE_HIGHEST);

                pw.println(mPm.checkPermission(perm, pkg, user));
                return;
            } else if ("l".equals(cmd) || "libraries".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_LIBS);
            } else if ("f".equals(cmd) || "features".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_FEATURES);
            } else if ("r".equals(cmd) || "resolvers".equals(cmd)) {
                if (opti >= args.length) {
                    dumpState.setDump(DumpState.DUMP_ACTIVITY_RESOLVERS
                            | DumpState.DUMP_SERVICE_RESOLVERS
                            | DumpState.DUMP_RECEIVER_RESOLVERS
                            | DumpState.DUMP_CONTENT_RESOLVERS);
                } else {
                    while (opti < args.length) {
                        String name = args[opti];
                        if ("a".equals(name) || "activity".equals(name)) {
                            dumpState.setDump(DumpState.DUMP_ACTIVITY_RESOLVERS);
                        } else if ("s".equals(name) || "service".equals(name)) {
                            dumpState.setDump(DumpState.DUMP_SERVICE_RESOLVERS);
                        } else if ("r".equals(name) || "receiver".equals(name)) {
                            dumpState.setDump(DumpState.DUMP_RECEIVER_RESOLVERS);
                        } else if ("c".equals(name) || "content".equals(name)) {
                            dumpState.setDump(DumpState.DUMP_CONTENT_RESOLVERS);
                        } else {
                            pw.println("Error: unknown resolver table type: " + name);
                            return;
                        }
                        opti++;
                    }
                }
            } else if ("perm".equals(cmd) || "permissions".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_PERMISSIONS);
            } else if ("permission".equals(cmd)) {
                if (opti >= args.length) {
                    pw.println("Error: permission requires permission name");
                    return;
                }
                permissionNames = new ArraySet<>();
                while (opti < args.length) {
                    permissionNames.add(args[opti]);
                    opti++;
                }
                dumpState.setDump(DumpState.DUMP_PERMISSIONS
                        | DumpState.DUMP_PACKAGES | DumpState.DUMP_SHARED_USERS);
            } else if ("pref".equals(cmd) || "preferred".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_PREFERRED);
            } else if ("preferred-xml".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_PREFERRED_XML);
                if (opti < args.length && "--full".equals(args[opti])) {
                    dumpState.setFullPreferred(true);
                    opti++;
                }
            } else if ("d".equals(cmd) || "domain-preferred-apps".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_DOMAIN_PREFERRED);
            } else if ("p".equals(cmd) || "packages".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_PACKAGES);
            } else if ("q".equals(cmd) || "queries".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_QUERIES);
            } else if ("s".equals(cmd) || "shared-users".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_SHARED_USERS);
                if (opti < args.length && "noperm".equals(args[opti])) {
                    dumpState.setOptionEnabled(DumpState.OPTION_SKIP_PERMISSIONS);
                }
            } else if ("prov".equals(cmd) || "providers".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_PROVIDERS);
            } else if ("m".equals(cmd) || "messages".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_MESSAGES);
            } else if ("v".equals(cmd) || "verifiers".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_VERIFIERS);
            } else if ("dv".equals(cmd) || "domain-verifier".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_DOMAIN_VERIFIER);
            } else if ("version".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_VERSION);
            } else if ("k".equals(cmd) || "keysets".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_KEYSETS);
            } else if ("installs".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_INSTALLS);
            } else if ("frozen".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_FROZEN);
            } else if ("volumes".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_VOLUMES);
            } else if ("dexopt".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_DEXOPT);
            } else if ("compiler-stats".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_COMPILER_STATS);
            } else if ("changes".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_CHANGES);
            } else if ("service-permissions".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_SERVICE_PERMISSIONS);
            } else if ("known-packages".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_KNOWN_PACKAGES);
            } else if ("t".equals(cmd) || "timeouts".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_PER_UID_READ_TIMEOUTS);
            } else if ("snapshot".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_SNAPSHOT_STATISTICS);
                if (opti < args.length) {
                    if ("--full".equals(args[opti])) {
                        dumpState.setBrief(false);
                        opti++;
                    } else if ("--brief".equals(args[opti])) {
                        dumpState.setBrief(true);
                        opti++;
                    }
                }
            } else if ("protected-broadcasts".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_PROTECTED_BROADCASTS);
            } else if ("write".equals(cmd)) {
                synchronized (mPm.mLock) {
                    mPm.writeSettingsLPrTEMP();
                    pw.println("Settings written.");
                    return;
                }
            }
        }

        final String packageName = dumpState.getTargetPackageName();
        final boolean checkin = dumpState.isCheckIn();

        // Return if the package doesn't exist.
        if (packageName != null
                && mPm.getPackageStateInternal(packageName) == null
                && !mPm.mApexManager.isApexPackage(packageName)) {
            pw.println("Unable to find package: " + packageName);
            return;
        }

        if (checkin) {
            pw.println("vers,1");
        }

        // reader
        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_VERSION)
                && packageName == null) {
            mPm.dumpComputer(DumpState.DUMP_VERSION, fd, pw, dumpState);
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_KNOWN_PACKAGES)
                && packageName == null) {
            if (dumpState.onTitlePrinted()) {
                pw.println();
            }
            final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ", 120);
            ipw.println("Known Packages:");
            ipw.increaseIndent();
            for (int i = 0; i <= LAST_KNOWN_PACKAGE; i++) {
                final String knownPackage = PackageManagerInternal.knownPackageToString(i);
                ipw.print(knownPackage);
                ipw.println(":");
                final String[] pkgNames = mPm.getKnownPackageNamesInternal(i,
                        UserHandle.USER_SYSTEM);
                ipw.increaseIndent();
                if (ArrayUtils.isEmpty(pkgNames)) {
                    ipw.println("none");
                } else {
                    for (String name : pkgNames) {
                        ipw.println(name);
                    }
                }
                ipw.decreaseIndent();
            }
            ipw.decreaseIndent();
        }

        if (dumpState.isDumping(DumpState.DUMP_VERIFIERS)
                && packageName == null) {
            final String requiredVerifierPackage = mPm.mRequiredVerifierPackage;
            if (!checkin) {
                if (dumpState.onTitlePrinted()) {
                    pw.println();
                }
                pw.println("Verifiers:");
                pw.print("  Required: ");
                pw.print(requiredVerifierPackage);
                pw.print(" (uid=");
                pw.print(mPm.getPackageUid(requiredVerifierPackage, MATCH_DEBUG_TRIAGED_MISSING,
                        UserHandle.USER_SYSTEM));
                pw.println(")");
            } else if (requiredVerifierPackage != null) {
                pw.print("vrfy,"); pw.print(requiredVerifierPackage);
                pw.print(",");
                pw.println(mPm.getPackageUid(requiredVerifierPackage, MATCH_DEBUG_TRIAGED_MISSING,
                        UserHandle.USER_SYSTEM));
            }
        }

        if (dumpState.isDumping(DumpState.DUMP_DOMAIN_VERIFIER)
                && packageName == null) {
            final DomainVerificationProxy proxy = mPm.mDomainVerificationManager.getProxy();
            final ComponentName verifierComponent = proxy.getComponentName();
            if (verifierComponent != null) {
                String verifierPackageName = verifierComponent.getPackageName();
                if (!checkin) {
                    if (dumpState.onTitlePrinted()) {
                        pw.println();
                    }
                    pw.println("Domain Verifier:");
                    pw.print("  Using: ");
                    pw.print(verifierPackageName);
                    pw.print(" (uid=");
                    pw.print(mPm.getPackageUid(verifierPackageName, MATCH_DEBUG_TRIAGED_MISSING,
                            UserHandle.USER_SYSTEM));
                    pw.println(")");
                } else if (verifierPackageName != null) {
                    pw.print("dv,"); pw.print(verifierPackageName);
                    pw.print(",");
                    pw.println(mPm.getPackageUid(verifierPackageName, MATCH_DEBUG_TRIAGED_MISSING,
                            UserHandle.USER_SYSTEM));
                }
            } else {
                pw.println();
                pw.println("No Domain Verifier available!");
            }
        }

        if (dumpState.isDumping(DumpState.DUMP_LIBS)
                && packageName == null) {
            mPm.dumpComputer(DumpState.DUMP_LIBS, fd, pw, dumpState);
        }

        if (dumpState.isDumping(DumpState.DUMP_FEATURES)
                && packageName == null) {
            if (dumpState.onTitlePrinted()) {
                pw.println();
            }
            if (!checkin) {
                pw.println("Features:");
            }

            synchronized (mPm.mAvailableFeatures) {
                for (FeatureInfo feat : mPm.mAvailableFeatures.values()) {
                    if (!checkin) {
                        pw.print("  ");
                        pw.print(feat.name);
                        if (feat.version > 0) {
                            pw.print(" version=");
                            pw.print(feat.version);
                        }
                        pw.println();
                    } else {
                        pw.print("feat,");
                        pw.print(feat.name);
                        pw.print(",");
                        pw.println(feat.version);
                    }
                }
            }
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_ACTIVITY_RESOLVERS)) {
            synchronized (mPm.mLock) {
                mPm.mComponentResolver.dumpActivityResolvers(pw, dumpState, packageName);
            }
        }
        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_RECEIVER_RESOLVERS)) {
            synchronized (mPm.mLock) {
                mPm.mComponentResolver.dumpReceiverResolvers(pw, dumpState, packageName);
            }
        }
        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_SERVICE_RESOLVERS)) {
            synchronized (mPm.mLock) {
                mPm.mComponentResolver.dumpServiceResolvers(pw, dumpState, packageName);
            }
        }
        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_CONTENT_RESOLVERS)) {
            synchronized (mPm.mLock) {
                mPm.mComponentResolver.dumpProviderResolvers(pw, dumpState, packageName);
            }
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_PREFERRED)) {
            mPm.dumpComputer(DumpState.DUMP_PREFERRED, fd, pw, dumpState);
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_PREFERRED_XML)
                && packageName == null) {
            mPm.dumpComputer(DumpState.DUMP_PREFERRED_XML, fd, pw, dumpState);
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_DOMAIN_PREFERRED)) {
            mPm.dumpComputer(DumpState.DUMP_DOMAIN_PREFERRED, fd, pw, dumpState);
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_PERMISSIONS)) {
            synchronized (mPm.mLock) {
                mPm.mSettings.dumpPermissions(pw, packageName, permissionNames, dumpState);
            }
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_PROVIDERS)) {
            synchronized (mPm.mLock) {
                mPm.mComponentResolver.dumpContentProviders(pw, dumpState, packageName);
            }
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_KEYSETS)) {
            synchronized (mPm.mLock) {
                mPm.mSettings.getKeySetManagerService().dumpLPr(pw, packageName, dumpState);
            }
        }

        if (dumpState.isDumping(DumpState.DUMP_PACKAGES)) {
            // This cannot be moved to ComputerEngine since some variables of the collections
            // in PackageUserState such as suspendParams, disabledComponents and enabledComponents
            // do not have a copy.
            synchronized (mPm.mLock) {
                mPm.mSettings.dumpPackagesLPr(pw, packageName, permissionNames, dumpState, checkin);
            }
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_QUERIES)) {
            mPm.dumpComputer(DumpState.DUMP_QUERIES, fd, pw, dumpState);
        }

        if (dumpState.isDumping(DumpState.DUMP_SHARED_USERS)) {
            // This cannot be moved to ComputerEngine since the set of packages in the
            // SharedUserSetting do not have a copy.
            synchronized (mPm.mLock) {
                mPm.mSettings.dumpSharedUsersLPr(pw, packageName, permissionNames, dumpState,
                        checkin);
            }
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_CHANGES)
                && packageName == null) {
            if (dumpState.onTitlePrinted()) {
                pw.println();
            }
            pw.println("Package Changes:");
            synchronized (mPm.mLock) {
                pw.print("  Sequence number="); pw.println(mPm.mChangedPackagesSequenceNumber);
                final int numChangedPackages = mPm.mChangedPackages.size();
                for (int i = 0; i < numChangedPackages; i++) {
                    final SparseArray<String> changes = mPm.mChangedPackages.valueAt(i);
                    pw.print("  User "); pw.print(mPm.mChangedPackages.keyAt(i)); pw.println(":");
                    final int numChanges = changes.size();
                    if (numChanges == 0) {
                        pw.print("    "); pw.println("No packages changed");
                    } else {
                        for (int j = 0; j < numChanges; j++) {
                            final String pkgName = changes.valueAt(j);
                            final int sequenceNumber = changes.keyAt(j);
                            pw.print("    ");
                            pw.print("seq=");
                            pw.print(sequenceNumber);
                            pw.print(", package=");
                            pw.println(pkgName);
                        }
                    }
                }
            }
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_FROZEN)
                && packageName == null) {
            // XXX should handle packageName != null by dumping only install data that
            // the given package is involved with.
            if (dumpState.onTitlePrinted()) {
                pw.println();
            }
            final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ", 120);
            ipw.println();
            ipw.println("Frozen packages:");
            ipw.increaseIndent();
            synchronized (mPm.mLock) {
                if (mPm.mFrozenPackages.size() == 0) {
                    ipw.println("(none)");
                } else {
                    for (int i = 0; i < mPm.mFrozenPackages.size(); i++) {
                        ipw.println(mPm.mFrozenPackages.valueAt(i));
                    }
                }
            }
            ipw.decreaseIndent();
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_VOLUMES)
                && packageName == null) {
            if (dumpState.onTitlePrinted()) {
                pw.println();
            }
            final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ", 120);
            ipw.println();
            ipw.println("Loaded volumes:");
            ipw.increaseIndent();
            synchronized (mPm.mLoadedVolumes) {
                if (mPm.mLoadedVolumes.size() == 0) {
                    ipw.println("(none)");
                } else {
                    for (int i = 0; i < mPm.mLoadedVolumes.size(); i++) {
                        ipw.println(mPm.mLoadedVolumes.valueAt(i));
                    }
                }
            }
            ipw.decreaseIndent();
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_SERVICE_PERMISSIONS)
                && packageName == null) {
            synchronized (mPm.mLock) {
                mPm.mComponentResolver.dumpServicePermissions(pw, dumpState);
            }
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_DEXOPT)) {
            mPm.dumpComputer(DumpState.DUMP_DEXOPT, fd, pw, dumpState);
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_COMPILER_STATS)) {
            mPm.dumpComputer(DumpState.DUMP_COMPILER_STATS, fd, pw, dumpState);
        }

        if (dumpState.isDumping(DumpState.DUMP_MESSAGES)
                && packageName == null) {
            if (!checkin) {
                if (dumpState.onTitlePrinted()) {
                    pw.println();
                }
                synchronized (mPm.mLock) {
                    mPm.mSettings.dumpReadMessagesLPr(pw, dumpState);
                }
                pw.println();
                pw.println("Package warning messages:");
                dumpCriticalInfo(pw, null);
            } else {
                dumpCriticalInfo(pw, "msg,");
            }
        }

        // PackageInstaller should be called outside of mPackages lock
        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_INSTALLS)
                && packageName == null) {
            // XXX should handle packageName != null by dumping only install data that
            // the given package is involved with.
            if (dumpState.onTitlePrinted()) {
                pw.println();
            }
            mPm.mInstallerService.dump(new IndentingPrintWriter(pw, "  ", 120));
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_APEX)
                && (packageName == null || mPm.mApexManager.isApexPackage(packageName))) {
            mPm.mApexManager.dump(pw, packageName);
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_PER_UID_READ_TIMEOUTS)
                && packageName == null) {
            if (dumpState.onTitlePrinted()) {
                pw.println();
            }
            pw.println("Per UID read timeouts:");
            pw.println("    Default timeouts flag: " + PackageManagerService.getDefaultTimeouts());
            pw.println("    Known digesters list flag: "
                    + PackageManagerService.getKnownDigestersList());

            PerUidReadTimeouts[] items = mPm.getPerUidReadTimeouts();
            pw.println("    Timeouts (" + items.length + "):");
            for (PerUidReadTimeouts item : items) {
                pw.print("        (");
                pw.print("uid=" + item.uid + ", ");
                pw.print("minTimeUs=" + item.minTimeUs + ", ");
                pw.print("minPendingTimeUs=" + item.minPendingTimeUs + ", ");
                pw.print("maxPendingTimeUs=" + item.maxPendingTimeUs);
                pw.println(")");
            }
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_SNAPSHOT_STATISTICS)
                && packageName == null) {
            if (dumpState.onTitlePrinted()) {
                pw.println();
            }
            pw.println("Snapshot statistics");
            mPm.dumpSnapshotStats(pw, dumpState.isBrief());
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_PROTECTED_BROADCASTS)
                && packageName == null) {
            if (dumpState.onTitlePrinted()) {
                pw.println();
            }
            pw.println("Protected broadcast actions:");
            synchronized (mPm.mProtectedBroadcasts) {
                for (int i = 0; i < mPm.mProtectedBroadcasts.size(); i++) {
                    pw.print("  ");
                    pw.println(mPm.mProtectedBroadcasts.valueAt(i));
                }
            }

        }
    }

    private void printHelp(PrintWriter pw) {
        pw.println("Package manager dump options:");
        pw.println("  [-h] [-f] [--checkin] [--all-components] [cmd] ...");
        pw.println("    --checkin: dump for a checkin");
        pw.println("    -f: print details of intent filters");
        pw.println("    -h: print this help");
        pw.println("    --all-components: include all component names in package dump");
        pw.println("  cmd may be one of:");
        pw.println("    apex: list active APEXes and APEX session state");
        pw.println("    l[ibraries]: list known shared libraries");
        pw.println("    f[eatures]: list device features");
        pw.println("    k[eysets]: print known keysets");
        pw.println("    r[esolvers] [activity|service|receiver|content]: dump intent resolvers");
        pw.println("    perm[issions]: dump permissions");
        pw.println("    permission [name ...]: dump declaration and use of given permission");
        pw.println("    pref[erred]: print preferred package settings");
        pw.println("    preferred-xml [--full]: print preferred package settings as xml");
        pw.println("    prov[iders]: dump content providers");
        pw.println("    p[ackages]: dump installed packages");
        pw.println("    q[ueries]: dump app queryability calculations");
        pw.println("    s[hared-users]: dump shared user IDs");
        pw.println("    m[essages]: print collected runtime messages");
        pw.println("    v[erifiers]: print package verifier info");
        pw.println("    d[omain-preferred-apps]: print domains preferred apps");
        pw.println("    i[ntent-filter-verifiers]|ifv: print intent filter verifier info");
        pw.println("    t[imeouts]: print read timeouts for known digesters");
        pw.println("    version: print database version info");
        pw.println("    write: write current settings now");
        pw.println("    installs: details about install sessions");
        pw.println("    check-permission <permission> <package> [<user>]: does pkg hold perm?");
        pw.println("    dexopt: dump dexopt state");
        pw.println("    compiler-stats: dump compiler statistics");
        pw.println("    service-permissions: dump permissions required by services");
        pw.println("    snapshot: dump snapshot statistics");
        pw.println("    protected-broadcasts: print list of protected broadcast actions");
        pw.println("    known-packages: dump known packages");
        pw.println("    <package.name>: info about given package");
    }

    private void dumpProto(FileDescriptor fd) {
        final ProtoOutputStream proto = new ProtoOutputStream(fd);

        synchronized (mPm.mLock) {
            final long requiredVerifierPackageToken =
                    proto.start(PackageServiceDumpProto.REQUIRED_VERIFIER_PACKAGE);
            proto.write(PackageServiceDumpProto.PackageShortProto.NAME,
                    mPm.mRequiredVerifierPackage);
            proto.write(
                    PackageServiceDumpProto.PackageShortProto.UID,
                    mPm.getPackageUid(
                            mPm.mRequiredVerifierPackage,
                            MATCH_DEBUG_TRIAGED_MISSING,
                            UserHandle.USER_SYSTEM));
            proto.end(requiredVerifierPackageToken);

            DomainVerificationProxy proxy = mPm.mDomainVerificationManager.getProxy();
            ComponentName verifierComponent = proxy.getComponentName();
            if (verifierComponent != null) {
                String verifierPackageName = verifierComponent.getPackageName();
                final long verifierPackageToken =
                        proto.start(PackageServiceDumpProto.VERIFIER_PACKAGE);
                proto.write(PackageServiceDumpProto.PackageShortProto.NAME, verifierPackageName);
                proto.write(
                        PackageServiceDumpProto.PackageShortProto.UID,
                        mPm.getPackageUid(
                                verifierPackageName,
                                MATCH_DEBUG_TRIAGED_MISSING,
                                UserHandle.USER_SYSTEM));
                proto.end(verifierPackageToken);
            }

            dumpSharedLibrariesProto(proto);
            dumpFeaturesProto(proto);
            mPm.mSettings.dumpPackagesProto(proto);
            mPm.mSettings.dumpSharedUsersProto(proto);
            dumpCriticalInfo(proto);
        }
        proto.flush();
    }

    private void dumpFeaturesProto(ProtoOutputStream proto) {
        synchronized (mPm.mAvailableFeatures) {
            final int count = mPm.mAvailableFeatures.size();
            for (int i = 0; i < count; i++) {
                mPm.mAvailableFeatures.valueAt(i).dumpDebug(proto,
                        PackageServiceDumpProto.FEATURES);
            }
        }
    }

    private void dumpSharedLibrariesProto(ProtoOutputStream proto) {
        final int count = mPm.mSharedLibraries.size();
        for (int i = 0; i < count; i++) {
            final String libName = mPm.mSharedLibraries.keyAt(i);
            WatchedLongSparseArray<SharedLibraryInfo> versionedLib =
                    mPm.mSharedLibraries.get(libName);
            if (versionedLib == null) {
                continue;
            }
            final int versionCount = versionedLib.size();
            for (int j = 0; j < versionCount; j++) {
                final SharedLibraryInfo libraryInfo = versionedLib.valueAt(j);
                final long sharedLibraryToken =
                        proto.start(PackageServiceDumpProto.SHARED_LIBRARIES);
                proto.write(PackageServiceDumpProto.SharedLibraryProto.NAME, libraryInfo.getName());
                final boolean isJar = (libraryInfo.getPath() != null);
                proto.write(PackageServiceDumpProto.SharedLibraryProto.IS_JAR, isJar);
                if (isJar) {
                    proto.write(PackageServiceDumpProto.SharedLibraryProto.PATH,
                            libraryInfo.getPath());
                } else {
                    proto.write(PackageServiceDumpProto.SharedLibraryProto.APK,
                            libraryInfo.getPackageName());
                }
                proto.end(sharedLibraryToken);
            }
        }
    }
}
