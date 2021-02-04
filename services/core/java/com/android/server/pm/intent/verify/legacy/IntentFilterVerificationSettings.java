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

package com.android.server.pm.intent.verify.legacy;

import static android.content.pm.PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS;
import static android.content.pm.PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.IntentFilterVerificationInfo;
import android.content.pm.ParceledListSlice;
import android.os.Binder;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseIntArray;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.XmlUtils;
import com.android.server.SystemConfig;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.PackageSetting;
import com.android.server.pm.Settings;
import com.android.server.pm.UserManagerService;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.utils.WatchedArrayMap;
import com.android.server.utils.WatchedSparseIntArray;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class IntentFilterVerificationSettings {

    private final Context mContext;
    private final IntentVerifierProxy.PackageManagerServiceConnection mConnection;
    private final UserManagerService mUserManagerService;

    // Set of restored intent-filter verification states
    final ArrayMap<String, IntentFilterVerificationInfo> mRestoredIntentFilterVerifications =
            new ArrayMap<>();

    public IntentFilterVerificationSettings(Context context,
            UserManagerService userManagerService,
            IntentVerifierProxy.PackageManagerServiceConnection connection) {
        mContext = context;
        mConnection = connection;
        mUserManagerService = userManagerService;
    }

    public int getIntentVerificationStatus(@NonNull String packageName, int userId) {
        final int callingUid = Binder.getCallingUid();
        if (UserHandle.getUserId(callingUid) != userId) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    "getIntentVerificationStatus" + userId);
        }
        if (mConnection.getInstantAppPackageName(callingUid) != null) {
            return INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED;
        }
        return mConnection.lockReturn(() -> {
            final PackageSetting ps = mConnection.getPackageSettingLPr(packageName);
            if (ps == null
                    || mConnection.shouldFilterApplicationLocked(
                    ps, callingUid, UserHandle.getUserId(callingUid))) {
                return INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED;
            }
            return getIntentFilterVerificationStatusLPr(packageName, userId);
        });
    }

    public boolean updateIntentVerificationStatus(@NonNull String packageName, int status,
            int userId) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);

        boolean result = mConnection.lockReturn(() -> {
            final PackageSetting ps = mConnection.getPackageSettingLPr(packageName);
            if (mConnection.shouldFilterApplicationLocked(
                    ps, Binder.getCallingUid(), UserHandle.getCallingUserId())) {
                return false;
            }
            return updateIntentFilterVerificationStatusLPw(packageName, status, userId);
        });
        if (result) {
            mConnection.scheduleWritePackageRestrictionsLocked(userId);
        }
        return result;
    }

    @NonNull
    public ParceledListSlice<IntentFilterVerificationInfo> getIntentFilterVerifications(
            @NonNull String packageName) {
        final int callingUid = Binder.getCallingUid();
        if (mConnection.getInstantAppPackageName(callingUid) != null) {
            return ParceledListSlice.emptyList();
        }
        return mConnection.lockReturn(() -> {
            final PackageSetting ps = mConnection.getPackageSettingLPr(packageName);
            if (mConnection.shouldFilterApplicationLocked(ps, callingUid,
                    UserHandle.getUserId(callingUid))) {
                return ParceledListSlice.emptyList();
            }
            return new ParceledListSlice<>(getIntentFilterVerificationsLPr(packageName));
        });
    }


    /** This method takes a specific user id as well as UserHandle.USER_ALL. */
    public void clearIntentFilterVerificationsLocked(int userId,
            WatchedArrayMap<String, AndroidPackage> packages) {
        final int packageCount = packages.size();
        for (int i = 0; i < packageCount; i++) {
            AndroidPackage pkg = packages.valueAt(i);
            clearIntentFilterVerificationsLocked(pkg.getPackageName(), userId, true);
        }
    }

    /** This method takes a specific user id as well as UserHandle.USER_ALL. */
    public void clearIntentFilterVerificationsLocked(String packageName, int userId,
            boolean alsoResetStatus) {
        if (SystemConfig.getInstance().getLinkedApps().contains(packageName)) {
            // Nope, need to preserve the system configuration approval for this app
            return;
        }

        if (userId == UserHandle.USER_ALL) {
            if (removeIntentFilterVerificationLPw(packageName, mUserManagerService.getUserIds())) {
                for (int oneUserId : mUserManagerService.getUserIds()) {
                    mConnection.scheduleWritePackageRestrictionsLocked(oneUserId);
                }
            }
        } else {
            if (removeIntentFilterVerificationLPw(packageName, userId, alsoResetStatus)) {
                mConnection.scheduleWritePackageRestrictionsLocked(userId);
            }
        }
    }

    @Nullable
    public IntentFilterVerificationInfo createIntentFilterVerificationIfNeededLPw(
            String packageName, ArraySet<String> domains) {
        PackageSetting pkgSetting = mConnection.getPackageSettingLPr(packageName);
        if (pkgSetting == null) {
            mConnection.warnLog("No package known: " + packageName);
            return null;
        }
        return updatePackageSetting(pkgSetting, domains);
    }

    public IntentFilterVerificationInfo updatePackageSetting(@NonNull PackageSetting pkgSetting,
            ArraySet<String> domains) {
        String pkgName = pkgSetting.name;
        IntentFilterVerificationInfo ivi = null;//pkgSetting.getIntentFilterVerificationInfo();
        if (ivi == null) {
            ivi = new IntentFilterVerificationInfo(pkgName, domains);
            // pkgSetting.setIntentFilterVerificationInfo(ivi);
            mConnection.debugLog("Creating new IntentFilterVerificationInfo for pkg: " + pkgName);
        } else {
            ivi.setDomains(domains);
            mConnection.debugLog(
                    "Setting domains to existing IntentFilterVerificationInfo for pkg: " +
                            pkgName + " and with domains: " + ivi.getDomainsString());
        }
        return ivi;
    }

    public int getIntentFilterVerificationStatusLPr(@NonNull String packageName,
            @UserIdInt int userId) {
        PackageSetting pkgSetting = mConnection.getPackageSettingLPr(packageName);
        if (pkgSetting == null) {
            mConnection.warnLog("No package known: " + packageName);
            return INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED;
        }
        return 0;//(int) (pkgSetting.getDomainVerificationStatusForUser(userId) >> 32);
    }

    @Nullable
    public IntentFilterVerificationInfo getIntentFilterVerificationLPr(
            @NonNull String packageName) {
        PackageSetting ps = mConnection.getPackageSettingLPr(packageName);
        if (ps == null) {
            mConnection.warnLog("No package known: " + packageName);
            return null;
        }
        return null;//ps.getIntentFilterVerificationInfo();
    }

    boolean updateIntentFilterVerificationStatusLPw(String packageName, final int status,
            int userId) {
        // Update the status for the current package
        PackageSetting current = mConnection.getPackageSettingLPr(packageName);
        if (current == null) {
            mConnection.warnLog("No package known: " + packageName);
            return false;
        }

        final int alwaysGeneration;
        if (status == INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS) {
//            WatchedSparseIntArray nextAppLinkGeneration = mConnection.getNextAppLinkGeneration();
//            alwaysGeneration = nextAppLinkGeneration.get(userId) + 1;
//            nextAppLinkGeneration.put(userId, alwaysGeneration);
        } else {
            alwaysGeneration = 0;
        }

//        current.setDomainVerificationStatusForUser(status, alwaysGeneration, userId);
        return true;
    }

    private boolean removeIntentFilterVerificationLPw(String packageName, int userId,
            boolean alsoResetStatus) {
        PackageSetting ps = mConnection.getPackageSettingLPr(packageName);
        if (ps == null) {
            mConnection.warnLog("No package known: " + packageName);
            return false;
        }
        if (alsoResetStatus) {
//            ps.clearDomainVerificationStatusForUser(userId);
        }
        return true;
    }

    private boolean removeIntentFilterVerificationLPw(String packageName, int[] userIds) {
        boolean result = false;
        for (int userId : userIds) {
            result |= removeIntentFilterVerificationLPw(packageName, userId, true);
        }
        return result;
    }

    private List<IntentFilterVerificationInfo> getIntentFilterVerificationsLPr(
            String packageName) {
        if (packageName == null) {
            return Collections.emptyList();
        }
        ArrayList<IntentFilterVerificationInfo> result = new ArrayList<>();
        for (PackageSetting ps : mConnection.getPackageSettingsLPr().values()) {
            IntentFilterVerificationInfo ivi = null;//ps.getIntentFilterVerificationInfo();
            if (ivi == null || TextUtils.isEmpty(ivi.getPackageName()) ||
                    !ivi.getPackageName().equalsIgnoreCase(packageName)) {
                continue;
            }
            result.add(ivi);
        }
        return result;
    }

    // Specifically for backup/restore
    public void writeAllDomainVerificationsLPr(TypedXmlSerializer serializer, int userId,
            @NonNull Map<String, PackageSetting> pkgSettings)
            throws IllegalArgumentException, IllegalStateException, IOException {
        serializer.startTag(null, Settings.TAG_ALL_INTENT_FILTER_VERIFICATION);
        for (PackageSetting value : pkgSettings.values()) {
            IntentFilterVerificationInfo ivi = null;//value.getIntentFilterVerificationInfo();
            if (ivi != null) {
                writeDomainVerificationsLPr(serializer, ivi);
            }
        }
        serializer.endTag(null, Settings.TAG_ALL_INTENT_FILTER_VERIFICATION);
    }

    public void writeDomainVerificationsLPr(TypedXmlSerializer serializer,
            IntentFilterVerificationInfo verificationInfo)
            throws IllegalArgumentException, IllegalStateException, IOException {
        if (verificationInfo != null && verificationInfo.getPackageName() != null) {
            serializer.startTag(null, Settings.TAG_DOMAIN_VERIFICATION);
            verificationInfo.writeToXml(serializer);
            mConnection.debugLog("Wrote domain verification for package: "
                    + verificationInfo.getPackageName());
            serializer.endTag(null, Settings.TAG_DOMAIN_VERIFICATION);
        }
    }

    // Specifically for backup/restore
    public void readAllDomainVerificationsLPr(TypedXmlPullParser parser, int userId)
            throws XmlPullParserException, IOException {
        mRestoredIntentFilterVerifications.clear();

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals(Settings.TAG_DOMAIN_VERIFICATION)) {
                IntentFilterVerificationInfo ivi = new IntentFilterVerificationInfo(parser);
                final String pkgName = ivi.getPackageName();
                final PackageSetting ps = mConnection.getPackageSettingLPr(pkgName);
                if (ps != null) {
                    // known/existing package; update in place
                    // TODO: Removed, commented out to allow compile, awaiting removal of entire
                    //  class
                    // ps.setIntentFilterVerificationInfo(ivi);
                    mConnection.debugLog("Restored IVI for existing app " + pkgName
                            + " status=" + ivi.getStatusString());
                } else {
                    mRestoredIntentFilterVerifications.put(pkgName, ivi);
                    mConnection.debugLog("Restored IVI for pending app " + pkgName
                            + " status=" + ivi.getStatusString());
                }
            } else {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Unknown element under <all-intent-filter-verification>: "
                                + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    public IntentFilterVerificationInfo getRestoredIntentFilterVerificationInfo(
            @NonNull String packageName) {
        IntentFilterVerificationInfo info = mRestoredIntentFilterVerifications.remove(packageName);
        if (info != null) {
            mConnection.infoLog(
                    "Applying restored IVI for " + packageName + " : " + info.getStatusString());
        }

        return info;
    }

    public void readRestoredIntentFilterVerifications(@NonNull TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            final String tagName = parser.getName();
            if (tagName.equals(Settings.TAG_DOMAIN_VERIFICATION)) {
                IntentFilterVerificationInfo ivi = new IntentFilterVerificationInfo(parser);
                mConnection.infoLog("Restored IVI for " + ivi.getPackageName()
                        + " status=" + ivi.getStatusString());
                mRestoredIntentFilterVerifications.put(ivi.getPackageName(), ivi);
            } else {
                mConnection.warnLog("Unknown element: " + tagName);
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    public void writeRestoredIntentFilterVerifications(@NonNull TypedXmlSerializer serializer)
            throws IOException {
        final int numIVIs = mRestoredIntentFilterVerifications.size();
        if (numIVIs > 0) {
            mConnection.infoLog("Writing restored-ivi entries to packages.xml");
            serializer.startTag(null, "restored-ivi");
            for (int i = 0; i < numIVIs; i++) {
                IntentFilterVerificationInfo ivi = mRestoredIntentFilterVerifications.valueAt(i);
                writeDomainVerificationsLPr(serializer, ivi);
            }
            serializer.endTag(null, "restored-ivi");
        } else {
            mConnection.infoLog("  no restored IVI entries to write");
        }
    }

    @NonNull
    public IntentFilterVerificationInfo readDomainVerificationLPw(
            @NonNull TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        return new IntentFilterVerificationInfo(parser);
    }
}
