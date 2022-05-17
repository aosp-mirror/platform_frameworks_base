/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.companion.datatransfer.permbackup;

import static android.content.pm.PackageManager.FLAG_PERMISSION_POLICY_FIXED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;
import static android.content.pm.PackageManager.GET_PERMISSIONS;
import static android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.END_TAG;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.UserHandle;
import android.permission.PermissionManager;
import android.permission.PermissionManager.SplitPermissionInfo;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.server.companion.datatransfer.permbackup.model.AppPermissionGroup;
import com.android.server.companion.datatransfer.permbackup.model.AppPermissions;
import com.android.server.companion.datatransfer.permbackup.model.Permission;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper for creating and restoring permission backups.
 */
public class BackupHelper {
    private static final String LOG_TAG = BackupHelper.class.getSimpleName();

    private static final String TAG_PERMISSION_BACKUP = "perm-grant-backup";
    private static final String ATTR_PLATFORM_VERSION = "version";

    private static final String TAG_ALL_GRANTS = "rt-grants";

    private static final String TAG_GRANT = "grant";
    private static final String ATTR_PACKAGE_NAME = "pkg";
    private static final String ATTR_HAS_MULTIPLE_SIGNERS = "multi-signers";

    private static final String TAG_SIGNATURE = "sig";
    private static final String ATTR_SIGNATURE_VALUE = "v";

    private static final String TAG_PERMISSION = "perm";
    private static final String ATTR_PERMISSION_NAME = "name";
    private static final String ATTR_IS_GRANTED = "g";
    private static final String ATTR_USER_SET = "set";
    private static final String ATTR_USER_FIXED = "fixed";
    private static final String ATTR_WAS_REVIEWED = "was-reviewed";

    /** Flags of permissions to <u>not</u> back up */
    private static final int SYSTEM_RUNTIME_GRANT_MASK = FLAG_PERMISSION_POLICY_FIXED
            | FLAG_PERMISSION_SYSTEM_FIXED;

    /** Make sure only one user can change the delayed permissions at a time */
    private static final Object sLock = new Object();

    private final Context mContext;

    /**
     * Create a new backup utils for a user.
     *
     * @param context A context to use
     * @param user The user that is backed up / restored
     */
    public BackupHelper(@NonNull Context context, @NonNull UserHandle user) {
        try {
            mContext = context.createPackageContextAsUser(context.getPackageName(), 0, user);
        } catch (PackageManager.NameNotFoundException doesNotHappen) {
            throw new IllegalStateException();
        }
    }

    /**
     * Forward parser and skip everything up to the end of the current tag.
     *
     * @param parser The parser to forward
     */
    private static void skipToEndOfTag(@NonNull XmlPullParser parser)
            throws IOException, XmlPullParserException {
        int numOpenTags = 1;
        while (numOpenTags > 0) {
            switch (parser.next()) {
                case START_TAG:
                    numOpenTags++;
                    break;
                case END_TAG:
                    numOpenTags--;
                    break;
                default:
                    // ignore
            }
        }
    }

    /**
     * Forward parser to a given direct sub-tag.
     *
     * @param parser The parser to forward
     * @param tag The tag to search for
     */
    private void skipToTag(@NonNull XmlPullParser parser, @NonNull String tag)
            throws IOException, XmlPullParserException {
        int type;
        do {
            type = parser.next();

            switch (type) {
                case START_TAG:
                    if (!parser.getName().equals(tag)) {
                        skipToEndOfTag(parser);
                    }

                    return;
            }
        } while (type != END_DOCUMENT);
    }

    /**
     * Read a XML file and return the packages stored in it.
     *
     * @param parser The file to read
     *
     * @return The packages in this file
     */
    private @NonNull ArrayList<BackupPackageState> parseFromXml(@NonNull XmlPullParser parser)
            throws IOException, XmlPullParserException {
        ArrayList<BackupPackageState> pkgStates = new ArrayList<>();

        skipToTag(parser, TAG_PERMISSION_BACKUP);

        int backupPlatformVersion;
        try {
            backupPlatformVersion = Integer.parseInt(
                    parser.getAttributeValue(null, ATTR_PLATFORM_VERSION));
        } catch (NumberFormatException ignored) {
            // Platforms P and before did not store the platform version
            backupPlatformVersion = Build.VERSION_CODES.P;
        }

        skipToTag(parser, TAG_ALL_GRANTS);

        if (parser.getEventType() != START_TAG && !parser.getName().equals(TAG_ALL_GRANTS)) {
            throw new XmlPullParserException("Could not find " + TAG_PERMISSION_BACKUP + " > "
                    + TAG_ALL_GRANTS);
        }

        // Read packages to restore from xml
        int type;
        do {
            type = parser.next();

            switch (type) {
                case START_TAG:
                    switch (parser.getName()) {
                        case TAG_GRANT:
                            try {
                                pkgStates.add(BackupPackageState.parseFromXml(parser, mContext,
                                        backupPlatformVersion));
                            } catch (XmlPullParserException e) {
                                Log.e(LOG_TAG, "Could not parse permissions ", e);
                                skipToEndOfTag(parser);
                            }
                            break;
                        default:
                            // ignore tag
                            Log.w(LOG_TAG, "Found unexpected tag " + parser.getName()
                                    + " during restore");
                            skipToEndOfTag(parser);
                    }
            }
        } while (type != END_DOCUMENT);

        return pkgStates;
    }

    /**
     * Try to restore the permission state from XML.
     *
     * @param parser The xml to read
     */
    public void restoreState(@NonNull XmlPullParser parser) throws IOException,
            XmlPullParserException {
        ArrayList<BackupPackageState> pkgStates = parseFromXml(parser);

        ArrayList<BackupPackageState> packagesToRestoreLater = new ArrayList<>();
        int numPkgStates = pkgStates.size();
        if (numPkgStates > 0) {
            // Try to restore packages
            for (int i = 0; i < numPkgStates; i++) {
                BackupPackageState pkgState = pkgStates.get(i);

                PackageInfo pkgInfo;
                try {
                    pkgInfo = mContext.getPackageManager().getPackageInfo(pkgState.mPackageName,
                            GET_PERMISSIONS | GET_SIGNING_CERTIFICATES);
                } catch (PackageManager.NameNotFoundException ignored) {
                    packagesToRestoreLater.add(pkgState);
                    continue;
                }

                pkgState.restore(mContext, pkgInfo);
            }
        }

//        synchronized (sLock) {
//            writeDelayedStorePkgsLocked(packagesToRestoreLater);
//        }
    }

    /**
     * Write a xml file for the given packages.
     *
     * @param serializer The file to write to
     * @param pkgs The packages to write
     */
    private static void writePkgsAsXml(@NonNull XmlSerializer serializer,
            @NonNull ArrayList<BackupPackageState> pkgs) throws IOException {
        serializer.startDocument(null, true);

        serializer.startTag(null, TAG_PERMISSION_BACKUP);

//        if (SDK_INT >= Build.VERSION_CODES.Q) {
        // STOPSHIP: Remove compatibility code once Q SDK level is declared
        serializer.attribute(null, ATTR_PLATFORM_VERSION,
                Integer.valueOf(Build.VERSION_CODES.Q).toString());
//        } else {
//            serializer.attribute(null, ATTR_PLATFORM_VERSION,
//                    Integer.valueOf(SDK_INT).toString());
//        }

        serializer.startTag(null, TAG_ALL_GRANTS);

        int numPkgs = pkgs.size();
        for (int i = 0; i < numPkgs; i++) {
            BackupPackageState packageState = pkgs.get(i);

            if (packageState != null) {
                packageState.writeAsXml(serializer);
            }
        }

        serializer.endTag(null, TAG_ALL_GRANTS);
        serializer.endTag(null, TAG_PERMISSION_BACKUP);

        serializer.endDocument();
    }

    /**
     * Write the state of all packages as XML.
     *
     * @param serializer The xml to write to
     */
    public void writeState(@NonNull XmlSerializer serializer) throws IOException {
        List<PackageInfo> pkgs = mContext.getPackageManager().getInstalledPackages(
                GET_PERMISSIONS | GET_SIGNING_CERTIFICATES);
        ArrayList<BackupPackageState> backupPkgs = new ArrayList<>();

        int numPkgs = pkgs.size();
        for (int i = 0; i < numPkgs; i++) {
            BackupPackageState packageState = BackupPackageState.fromAppPermissions(mContext,
                    pkgs.get(i));

            if (packageState != null) {
                backupPkgs.add(packageState);
            }
        }

        writePkgsAsXml(serializer, backupPkgs);
    }

    /**
     * State that needs to be backed up for a permission.
     */
    private static class BackupPermissionState {
        private final @NonNull String mPermissionName;
        private final boolean mIsGranted;
        private final boolean mIsUserSet;
        private final boolean mIsUserFixed;
        private final boolean mWasReviewed;

        private BackupPermissionState(@NonNull String permissionName, boolean isGranted,
                boolean isUserSet, boolean isUserFixed, boolean wasReviewed) {
            mPermissionName = permissionName;
            mIsGranted = isGranted;
            mIsUserSet = isUserSet;
            mIsUserFixed = isUserFixed;
            mWasReviewed = wasReviewed;
        }

        /**
         * Parse a package state from XML.
         *
         * @param parser The data to read
         * @param context a context to use
         * @param backupPlatformVersion The platform version the backup was created on
         *
         * @return The state
         */
        static @NonNull List<BackupPermissionState> parseFromXml(@NonNull XmlPullParser parser,
                @NonNull Context context, int backupPlatformVersion)
                throws XmlPullParserException {
            String permName = parser.getAttributeValue(null, ATTR_PERMISSION_NAME);
            if (permName == null) {
                throw new XmlPullParserException("Found " + TAG_PERMISSION + " without "
                        + ATTR_PERMISSION_NAME);
            }

            ArrayList<String> expandedPermissions = new ArrayList<>();
            expandedPermissions.add(permName);

            List<SplitPermissionInfo> splitPerms = context.getSystemService(
                    PermissionManager.class).getSplitPermissions();

            // Expand the properties to permissions that were split between the platform version the
            // backup was taken and the current version.
            int numSplitPerms = splitPerms.size();
            for (int i = 0; i < numSplitPerms; i++) {
                SplitPermissionInfo splitPerm = splitPerms.get(i);
                if (backupPlatformVersion < splitPerm.getTargetSdk()
                        && permName.equals(splitPerm.getSplitPermission())) {
                    expandedPermissions.addAll(splitPerm.getNewPermissions());
                }
            }

            ArrayList<BackupPermissionState> parsedPermissions = new ArrayList<>(
                    expandedPermissions.size());
            int numExpandedPerms = expandedPermissions.size();
            for (int i = 0; i < numExpandedPerms; i++) {
                parsedPermissions.add(new BackupPermissionState(expandedPermissions.get(i),
                        "true".equals(parser.getAttributeValue(null, ATTR_IS_GRANTED)),
                        "true".equals(parser.getAttributeValue(null, ATTR_USER_SET)),
                        "true".equals(parser.getAttributeValue(null, ATTR_USER_FIXED)),
                        "true".equals(parser.getAttributeValue(null, ATTR_WAS_REVIEWED))));
            }

            return parsedPermissions;
        }

        /**
         * Is the permission granted, also considering the app-op.
         *
         * <p>This does not consider the review-required state of the permission.
         *
         * @param perm The permission that might be granted
         *
         * @return {@code true} iff the permission and app-op is granted
         */
        private static boolean isPermGrantedIncludingAppOp(@NonNull Permission perm) {
            return perm.isGranted() && (!perm.affectsAppOp() || perm.isAppOpAllowed());
        }

        /**
         * Get the state of a permission to back up.
         *
         * @param perm The permission to back up
         * @param appSupportsRuntimePermissions If the app supports runtimePermissions
         *
         * @return The state to back up or {@code null} if the permission does not need to be
         * backed up.
         */
        private static @Nullable BackupPermissionState fromPermission(@NonNull Permission perm,
                boolean appSupportsRuntimePermissions) {
            int grantFlags = perm.getFlags();

            if ((grantFlags & SYSTEM_RUNTIME_GRANT_MASK) != 0) {
                return null;
            }

            if (!perm.isUserSet() && perm.isGrantedByDefault()) {
                return null;
            }

            boolean permissionWasReviewed;
            boolean isNotInDefaultGrantState;
            if (appSupportsRuntimePermissions) {
                isNotInDefaultGrantState = isPermGrantedIncludingAppOp(perm);
                permissionWasReviewed = false;
            } else {
                isNotInDefaultGrantState = !isPermGrantedIncludingAppOp(perm);
                permissionWasReviewed = !perm.isReviewRequired();
            }

//            if (isNotInDefaultGrantState || perm.isUserSet() || perm.isUserFixed()
//                    || permissionWasReviewed) {
//                return new BackupPermissionState(perm.getName(),
//                        isPermGrantedIncludingAppOp(perm),
//                        perm.isUserSet(), perm.isUserFixed(), permissionWasReviewed);
//            } else {
//                return null;
//            }
            if (perm.isUserSet() && isPermGrantedIncludingAppOp(perm)) {
                return new BackupPermissionState(perm.getName(), /* isGranted */ true,
                        /* isUserSet */ true, perm.isUserFixed(), permissionWasReviewed);
            } else {
                return null;
            }
        }

        /**
         * Get the states of all permissions of a group to back up.
         *
         * @param group The group of the permissions to back up
         *
         * @return The state to back up. Empty list if no permissions in the group need to be backed
         * up
         */
        static @NonNull ArrayList<BackupPermissionState> fromPermissionGroup(
                @NonNull AppPermissionGroup group) {
            ArrayList<BackupPermissionState> permissionsToRestore = new ArrayList<>();
            List<Permission> perms = group.getPermissions();

            boolean appSupportsRuntimePermissions =
                    group.getApp().applicationInfo.targetSdkVersion >= Build.VERSION_CODES.M;

            int numPerms = perms.size();
            for (int i = 0; i < numPerms; i++) {
                BackupPermissionState permState = fromPermission(perms.get(i),
                        appSupportsRuntimePermissions);
                if (permState != null) {
                    permissionsToRestore.add(permState);
                }
            }

            return permissionsToRestore;
        }

        /**
         * Write this state as XML.
         *
         * @param serializer The file to write to
         */
        void writeAsXml(@NonNull XmlSerializer serializer) throws IOException {
            serializer.startTag(null, TAG_PERMISSION);

            serializer.attribute(null, ATTR_PERMISSION_NAME, mPermissionName);

            if (mIsGranted) {
                serializer.attribute(null, ATTR_IS_GRANTED, "true");
            }

            if (mIsUserSet) {
                serializer.attribute(null, ATTR_USER_SET, "true");
            }

            if (mIsUserFixed) {
                serializer.attribute(null, ATTR_USER_FIXED, "true");
            }

            if (mWasReviewed) {
                serializer.attribute(null, ATTR_WAS_REVIEWED, "true");
            }

            serializer.endTag(null, TAG_PERMISSION);
        }

        /**
         * Restore this permission state.
         *
         * @param appPerms The {@link AppPermissions} to restore the state to
         * @param restoreBackgroundPerms if {@code true} only restore background permissions,
         *                               if {@code false} do not restore background permissions
         */
        void restore(@NonNull AppPermissions appPerms, boolean restoreBackgroundPerms) {
            AppPermissionGroup group = appPerms.getGroupForPermission(mPermissionName);
            if (group == null) {
                Log.w(LOG_TAG, "Could not find group for " + mPermissionName + " in "
                        + appPerms.getPackageInfo().packageName);
                return;
            }

            if (restoreBackgroundPerms != group.isBackgroundGroup()) {
                return;
            }

            Permission perm = group.getPermission(mPermissionName);
            if (mWasReviewed) {
                perm.unsetReviewRequired();
            }

            // Don't grant or revoke fixed permission groups
            if (group.isSystemFixed() || group.isPolicyFixed()) {
                return;
            }

            if (!perm.isUserSet()) {
                if (mIsGranted) {
                    group.grantRuntimePermissions(false, mIsUserFixed,
                            new String[]{mPermissionName});
                } else {
                    group.revokeRuntimePermissions(mIsUserFixed,
                            new String[]{mPermissionName});
                }

                perm.setUserSet(mIsUserSet);
            }
        }
    }

    /**
     * State that needs to be backed up for a package.
     */
    private static class BackupPackageState {
        final @NonNull String mPackageName;
        final boolean mHasMultipleSigners;
        @NonNull Signature[] mSignatures;
        private final @NonNull ArrayList<BackupPermissionState> mPermissionsToRestore;

        private BackupPackageState(@NonNull String packageName, boolean hasMultipleSigners,
                @NonNull Signature[] signatures,
                @NonNull ArrayList<BackupPermissionState> permissionsToRestore) {
            mPackageName = packageName;
            mHasMultipleSigners = hasMultipleSigners;
            mSignatures = signatures;
            mPermissionsToRestore = permissionsToRestore;
        }

        /**
         * Parse a package state from XML.
         *
         * @param parser The data to read
         * @param context a context to use
         * @param backupPlatformVersion The platform version the backup was created on
         *
         * @return The state
         */
        static @NonNull BackupPackageState parseFromXml(@NonNull XmlPullParser parser,
                @NonNull Context context, int backupPlatformVersion)
                throws IOException, XmlPullParserException {
            String packageName = parser.getAttributeValue(null, ATTR_PACKAGE_NAME);
            if (packageName == null) {
                throw new XmlPullParserException("Found " + TAG_GRANT + " without "
                        + ATTR_PACKAGE_NAME);
            }

            boolean hasMultipleSigners = Boolean.parseBoolean(
                    parser.getAttributeValue(null, ATTR_HAS_MULTIPLE_SIGNERS));
            ArrayList<Signature> signatureList = new ArrayList<>();

            ArrayList<BackupPermissionState> permissionsToRestore = new ArrayList<>();

            while (true) {
                switch (parser.next()) {
                    case START_TAG:
                        switch (parser.getName()) {
                            case TAG_PERMISSION:
                                try {
                                    permissionsToRestore.addAll(
                                            BackupPermissionState.parseFromXml(parser, context,
                                                    backupPlatformVersion));
                                } catch (XmlPullParserException e) {
                                    Log.e(LOG_TAG, "Could not parse permission for "
                                            + packageName, e);
                                }

                                skipToEndOfTag(parser);
                                break;
                            case TAG_SIGNATURE:
                                signatureList.add(new Signature(
                                        parser.getAttributeValue(null, ATTR_SIGNATURE_VALUE)));
                                skipToEndOfTag(parser);
                                break;
                            default:
                                // ignore tag
                                Log.w(LOG_TAG, "Found unexpected tag " + parser.getName()
                                        + " while restoring " + packageName);
                                skipToEndOfTag(parser);
                        }

                        break;
                    case END_TAG:
                        Signature[] signatures = new Signature[signatureList.size()];
                        for (int i = 0; i < signatureList.size(); i++) {
                            signatures[i] = signatureList.get(i);
                        }
                        return new BackupPackageState(packageName, hasMultipleSigners, signatures,
                                permissionsToRestore);
                    case END_DOCUMENT:
                        throw new XmlPullParserException("Could not parse state for "
                                + packageName);
                }
            }
        }

        /**
         * Get the state of a package to back up.
         *
         * @param context A context to use
         * @param pkgInfo The package to back up.
         *
         * @return The state to back up or {@code null} if no permission of the package need to be
         * backed up.
         */
        static @Nullable BackupPackageState fromAppPermissions(@NonNull Context context,
                @NonNull PackageInfo pkgInfo) {
            AppPermissions appPerms = new AppPermissions(context, pkgInfo, false, null);

            ArrayList<BackupPermissionState> permissionsToRestore = new ArrayList<>();
            List<AppPermissionGroup> groups = appPerms.getPermissionGroups();

            // Check if the package has signatures
            SigningInfo signingInfo = pkgInfo.signingInfo;
            Signature[] signatures;
            boolean hasMultipleSigners;
            if (signingInfo.hasMultipleSigners()) {
                hasMultipleSigners = true;
                signatures = signingInfo.getApkContentsSigners();
            } else {
                hasMultipleSigners = false;
                signatures = signingInfo.getSigningCertificateHistory();
            }
            if (signatures == null) {
                Slog.d(LOG_TAG, "Skipping " + pkgInfo.packageName + ", it's unsigned.");
                return null;
            }

            int numGroups = groups.size();
            for (int groupNum = 0; groupNum < numGroups; groupNum++) {
                AppPermissionGroup group = groups.get(groupNum);

                permissionsToRestore.addAll(BackupPermissionState.fromPermissionGroup(group));

                // Background permissions are in a subgroup that is not part of
                // {@link AppPermission#getPermissionGroups}. Hence add it explicitly here.
                if (group.getBackgroundPermissions() != null) {
                    permissionsToRestore.addAll(BackupPermissionState.fromPermissionGroup(
                            group.getBackgroundPermissions()));
                }
            }

            if (permissionsToRestore.size() == 0) {
                return null;
            }

            return new BackupPackageState(pkgInfo.packageName, hasMultipleSigners, signatures,
                    permissionsToRestore);
        }

        /**
         * Write this state as XML.
         *
         * @param serializer The file to write to
         */
        void writeAsXml(@NonNull XmlSerializer serializer) throws IOException {
            if (mPermissionsToRestore.size() == 0) {
                return;
            }

            serializer.startTag(null, TAG_GRANT);
            serializer.attribute(null, ATTR_PACKAGE_NAME, mPackageName);

            // Add signing info
            serializer.attribute(null, ATTR_HAS_MULTIPLE_SIGNERS,
                    String.valueOf(mHasMultipleSigners));
            for (Signature signature : mSignatures) {
                serializer.startTag(null, TAG_SIGNATURE);
                serializer.attribute(null, ATTR_SIGNATURE_VALUE, signature.toCharsString());
                serializer.endTag(null, TAG_SIGNATURE);
            }

            int numPerms = mPermissionsToRestore.size();
            for (int i = 0; i < numPerms; i++) {
                mPermissionsToRestore.get(i).writeAsXml(serializer);
            }

            serializer.endTag(null, TAG_GRANT);
        }

        /**
         * Restore this package state.
         *
         * @param context A context to use
         * @param pkgInfo The package to restore.
         */
        void restore(@NonNull Context context, @NonNull PackageInfo pkgInfo) {
            Slog.e(LOG_TAG, "Restoring permissions for package [" + mPackageName + "]");

            // Verify signature info
            try {
                if (mHasMultipleSigners && pkgInfo.signingInfo.hasMultipleSigners()) {
                    // If both packages are signed by multi signers, check if two signature sets are
                    // effectively matched.
                    if (!Signature.areEffectiveMatch(mSignatures,
                            pkgInfo.signingInfo.getApkContentsSigners())) {
                        Slog.e(LOG_TAG, "Multi-signers signatures don't match for package ["
                                + mPackageName + "], skipped.");
                        return;
                    }
                } else if (!mHasMultipleSigners && !pkgInfo.signingInfo.hasMultipleSigners()) {
                    // If both packages are not signed by multi signers, check if two signature sets
                    // have overlaps.
                    Signature[] signatures = pkgInfo.signingInfo.getSigningCertificateHistory();
                    if (signatures == null) {
                        Slog.e(LOG_TAG, "The dest package is unsigned.");
                        return;
                    }
                    boolean isMatched = false;
                    for (int i = 0; i < mSignatures.length; i++) {
                        for (int j = 0; j < signatures.length; j++) {
                            isMatched = Signature.areEffectiveMatch(mSignatures[i], signatures[j]);
                        }
                    }
                    if (!isMatched) {
                        Slog.e(LOG_TAG, "Single signer signatures don't match for package ["
                                + mPackageName + "], skipped.");
                        return;
                    }
                } else {
                    Slog.e(LOG_TAG, "Number of signers don't match.");
                    return;
                }
            } catch (CertificateException ce) {
                Slog.e(LOG_TAG, "Either the source or the dest package's bounced cert length "
                        + "looks fishy, skipped package [" + pkgInfo.packageName + "]");
            }

            AppPermissions appPerms = new AppPermissions(context, pkgInfo, false, true, null);

            ArraySet<String> affectedPermissions = new ArraySet<>();
            // Restore background permissions after foreground permissions as for pre-M apps bg
            // granted and fg revoked cannot be expressed.
            int numPerms = mPermissionsToRestore.size();
            for (int i = 0; i < numPerms; i++) {
                mPermissionsToRestore.get(i).restore(appPerms, false);
                affectedPermissions.add(mPermissionsToRestore.get(i).mPermissionName);
            }
            for (int i = 0; i < numPerms; i++) {
                mPermissionsToRestore.get(i).restore(appPerms, true);
            }

            int numGroups = appPerms.getPermissionGroups().size();
            for (int i = 0; i < numGroups; i++) {
                AppPermissionGroup group = appPerms.getPermissionGroups().get(i);

                // Only denied groups can be user fixed
                if (group.areRuntimePermissionsGranted()) {
                    group.setUserFixed(false);
                }

                AppPermissionGroup bgGroup = group.getBackgroundPermissions();
                if (bgGroup != null) {
                    // Only denied groups can be user fixed
                    if (bgGroup.areRuntimePermissionsGranted()) {
                        bgGroup.setUserFixed(false);
                    }
                }
            }

            appPerms.persistChanges(true, affectedPermissions);
        }
    }
}
