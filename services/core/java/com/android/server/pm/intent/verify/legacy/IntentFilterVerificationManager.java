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
import static android.content.pm.PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK;
import static android.content.pm.PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED;
import static android.content.pm.PackageManager.MATCH_DEBUG_TRIAGED_MISSING;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.IntentFilterVerificationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.parsing.component.ParsedActivity;
import android.content.pm.parsing.component.ParsedIntentInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.StringBuilderPrinter;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.server.SystemConfig;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.PackageSetting;
import com.android.server.pm.UserManagerService;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.utils.WatchedArrayMap;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class IntentFilterVerificationManager {

    private final Context mContext;
    private final Handler mHandler;
    private final IntentVerifierProxy.PackageManagerServiceConnection mConnection;
    private final SystemConfig mSystemConfig;
    private final IntentFilterVerificationSettings mSettings;

    private final IntentVerifierProxy mVerifier;

    private int mIntentFilterVerificationToken = 0;
    private boolean mHasVerifier;

    private final SparseArray<IntentFilterVerificationState> mStates = new SparseArray<>();

    public IntentFilterVerificationManager(Context context, Handler handler,
            IntentVerifierProxy.PackageManagerServiceConnection connection,
            SystemConfig systemConfig, UserManagerService userManager) {
        mContext = context;
        mHandler = handler;
        mConnection = connection;
        mSystemConfig = systemConfig;
        mSettings = new IntentFilterVerificationSettings(mContext, userManager, connection);
        mVerifier = new IntentVerifierProxy(mContext, connection);
    }

    public void setVerifierComponent(@Nullable ComponentName componentName) {
        mVerifier.setComponent(componentName);
        mHasVerifier = componentName != null;
    }

    @Nullable
    public ComponentName getVerifierComponent() {
        return mVerifier.getComponent();
    }

    public void startIntentFilterVerifications(int userId, boolean replacing, AndroidPackage pkg) {
        if (!mHasVerifier) {
            mConnection.warnLog("No IntentFilter verification will not be done as "
                    + "there is no IntentFilterVerifier available!");
            return;
        }

        final int verifierUid = mConnection.getPackageUid(
                mVerifier.getComponent().getPackageName(),
                MATCH_DEBUG_TRIAGED_MISSING,
                (userId == UserHandle.USER_ALL) ? UserHandle.USER_SYSTEM : userId);

        Message msg = mHandler.obtainMessage(
                PackageManagerService.START_INTENT_FILTER_VERIFICATIONS);
        msg.obj = new IntentFilterVerificationParams(
                pkg.getPackageName(),
                pkg.isHasDomainUrls(),
                pkg.getActivities(),
                replacing,
                userId,
                verifierUid
        );
        mHandler.sendMessage(msg);
    }

    public void verifyIntentFiltersIfNeeded(IntentFilterVerificationParams params) {
        if (!mHasVerifier) {
            return;
        }

        int userId = params.userId;
        int verifierUid = params.verifierUid;
        boolean replacing = params.replacing;
        String packageName = params.packageName;
        boolean hasDomainUrls = params.hasDomainUrls;
        List<ParsedActivity> activities = params.activities;


        int size = activities.size();
        if (size == 0) {
            mConnection.debugLog("No activity, so no need to verify any IntentFilter!");
            return;
        }

        if (!hasDomainUrls) {
            mConnection.debugLog("No domain URLs, so no need to verify any IntentFilter!");
            return;
        }

        mConnection.debugLog("Checking for userId:" + userId
                + " if any IntentFilter from the " + size
                + " Activities needs verification ...");

        boolean runVerify = mConnection.lockReturn(() -> {
            int count = 0;
            boolean handlesWebUris = false;
            ArraySet<String> domains = new ArraySet<>();
            final boolean previouslyVerified;
            boolean hostSetExpanded = false;
            boolean needToRunVerify = false;

            // If this is a new install and we see that we've already run verification for this
            // package, we have nothing to do: it means the state was restored from backup.
            IntentFilterVerificationInfo ivi =
                    mSettings.getIntentFilterVerificationLPr(packageName);
            previouslyVerified = (ivi != null);
            if (!replacing && previouslyVerified) {
                mConnection.infoLog("Package " + packageName + " already verified: status="
                        + ivi.getStatusString());
                return false;
            }

            mConnection.infoLog("    Previous verified hosts: "
                    + (ivi == null ? "[none]" : ivi.getDomainsString()));

            // If any filters need to be verified, then all need to be.  In addition, we need to
            // know whether an updating app has any web navigation intent filters, to re-
            // examine handling policy even if not re-verifying.
            final boolean needsVerification = needsNetworkVerificationLPr(packageName);

            mConnection.infoLog("    needsVerification = " + needsVerification);
            StringBuilder builder = new StringBuilder();
            StringBuilderPrinter printer = new StringBuilderPrinter(builder);
            for (ParsedActivity a : activities) {
                mConnection.infoLog("    activity = " + a.getClassName());
                for (ParsedIntentInfo filter : a.getIntents()) {
                    builder.setLength(0);
                    filter.dump(printer, "");
                    mConnection.infoLog("    filter = " + builder.toString());
                    mConnection.infoLog("    handlesWebUris = " + filter.handlesWebUris(true));
                    mConnection.infoLog("    needsVerification = " + filter.needsVerification());
                    if (filter.handlesWebUris(true)) {
                        handlesWebUris = true;
                    }
                    if (needsVerification && filter.needsVerification()) {
                        mConnection.debugLog("autoVerify requested, processing all filters");
                        needToRunVerify = true;
                        // It's safe to break out here because filter.needsVerification()
                        // can only be true if filter.handlesWebUris(true) returned true, so
                        // we've already noted that.
                        break;
                    }
                }
            }

            mConnection.infoLog("    needToRunVerify = " + needToRunVerify);
            mConnection.infoLog("    previouslyVerified = " + previouslyVerified);
            // Compare the new set of recognized hosts if the app is either requesting
            // autoVerify or has previously used autoVerify but no longer does.
            if (needToRunVerify || previouslyVerified) {
                final int verificationId = mIntentFilterVerificationToken++;
                for (ParsedActivity a : activities) {
                    for (ParsedIntentInfo filter : a.getIntents()) {
                        // Run verification against hosts mentioned in any web-nav intent filter,
                        // even if the filter matches non-web schemes as well
                        if (filter.handlesWebUris(false /*onlyWebSchemes*/)) {
                            mConnection.debugLog("Verification needed for IntentFilter:"
                                    + filter.toString());
                            mVerifier.addOneIntentFilterVerification(verifierUid, userId,
                                    verificationId, filter, packageName, mStates);
                            domains.addAll(filter.getHostsList());
                            count++;
                        }
                    }
                }
            }

            mConnection.infoLog("    Update published hosts: " + domains.toString());

            // If we've previously verified this same host set (or a subset), we can trust that
            // a current ALWAYS policy is still applicable.  If this is the case, we're done.
            // (If we aren't in ALWAYS, we want to reverify to allow for apps that had failing
            // hosts in their intent filters, then pushed a new apk that removed them and now
            // passes.)
            //
            // Cases:
            //   + still autoVerify (needToRunVerify):
            //      - preserve current state if all of: unexpanded, in always
            //      - otherwise rerun as usual (fall through)
            //   + no longer autoVerify (alreadyVerified && !needToRunVerify)
            //      - wipe verification history always
            //      - preserve current state if all of: unexpanded, in always
            hostSetExpanded = !previouslyVerified
                    || (ivi != null && !ivi.getDomains().containsAll(domains));
            final int currentPolicy =
                    mSettings.getIntentFilterVerificationStatusLPr(packageName, userId);
            final boolean keepCurState = !hostSetExpanded
                    && currentPolicy == INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS;

            if (needToRunVerify && keepCurState) {
                mConnection.infoLog("Host set not expanding + ALWAYS -> no need to reverify");
                ivi.setDomains(domains);
                mConnection.scheduleWriteSettingsLocked();
                return false;
            } else if (previouslyVerified && !needToRunVerify) {
                // Prior autoVerify state but not requesting it now.  Clear autoVerify history,
                // and preserve the always policy iff the host set is not expanding.
                mSettings.clearIntentFilterVerificationsLocked(packageName, userId, !keepCurState);
                return false;
            }

            if (needToRunVerify && count > 0) {
                // app requested autoVerify and has at least one matching intent filter
                mConnection.debugLog("Starting " + count
                        + " IntentFilter verification" + (count > 1 ? "s" : "")
                        + " for userId:" + userId);
                return true;
            } else {
                mConnection.debugLog("No web filters or no new host policy for " + packageName);
                return false;
            }
        });

        if (runVerify) {
            mVerifier.startVerifications(userId, mStates);
        }
    }

    private boolean needsNetworkVerificationLPr(String packageName) {
        IntentFilterVerificationInfo ivi = mSettings.getIntentFilterVerificationLPr(
                packageName);
        if (ivi == null) {
            return true;
        }
        int status = ivi.getStatus();
        switch (status) {
            case INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED:
            case INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS:
            case INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK:
                return true;

            default:
                // Nothing to do
                return false;
        }
    }

    public void queueVerifyResult(int id, int verificationCode, List<String> failedDomains) {
        if (!mHasVerifier) {
            return;
        }

        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.INTENT_FILTER_VERIFICATION_AGENT,
                "Only intentfilter verification agents can verify applications");

        final Message msg = mHandler.obtainMessage(PackageManagerService.INTENT_FILTER_VERIFIED);
        final IntentFilterVerificationResponse
                response = new IntentFilterVerificationResponse(
                Binder.getCallingUid(), verificationCode, failedDomains);
        msg.arg1 = id;
        msg.obj = response;
        mHandler.sendMessage(msg);
    }

    public void onFilterVerified(Message msg) {
        if (!mHasVerifier) {
            return;
        }

        final int verificationId = msg.arg1;

        final IntentFilterVerificationState state = mStates.get(verificationId);
        if (state == null) {
            mConnection.warnLog("Invalid IntentFilter verification token "
                    + verificationId + " received");
            return;
        }

        final int userId = state.getUserId();

        mConnection.debugLog("Processing IntentFilter verification with token:"
                + verificationId + " and userId:" + userId);

        final IntentFilterVerificationResponse
                response =
                (IntentFilterVerificationResponse) msg.obj;

        state.setVerifierResponse(response.callerUid, response.code);

        mConnection.debugLog("IntentFilter verification with token:" + verificationId
                + " and userId:" + userId
                + " is settings verifier response with response code:"
                + response.code);

        if (response.code == PackageManager.INTENT_FILTER_VERIFICATION_FAILURE) {
            mConnection.debugLog("Domains failing verification: "
                    + response.getFailedDomainsString());
        }

        if (state.isVerificationComplete()) {
            receiveVerificationResponse(verificationId);
        } else {
            mConnection.debugLog("IntentFilter verification with token:" + verificationId
                    + " was not said to be complete");
        }
    }

    public void receiveVerificationResponse(int verificationId) {
        IntentFilterVerificationState ivs = mStates.get(verificationId);

        final boolean verified = ivs.isVerified();

        ArrayList<ParsedIntentInfo> filters = ivs.getFilters();
        final int count = filters.size();
        mConnection.debugLog("Received verification response " + verificationId
                + " for " + count + " filters, verified=" + verified);

        for (int n = 0; n < count; n++) {
            ParsedIntentInfo filter = filters.get(n);
            filter.setVerified(verified);

            mConnection.debugLog("IntentFilter " + filter.toString()
                    + " verified with result:" + verified + " and hosts:"
                    + ivs.getHostsString());
        }

        mStates.remove(verificationId);

        final String packageName = ivs.getPackageName();
        IntentFilterVerificationInfo ivi = mSettings.getIntentFilterVerificationLPr(packageName);
        if (ivi == null) {
            mConnection.warnLog("IntentFilterVerificationInfo not found for verificationId:"
                    + verificationId + " packageName:" + packageName);
            return;
        }

        mConnection.lock(() -> {
            if (verified) {
                ivi.setStatus(INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS);
            } else {
                ivi.setStatus(INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK);
            }
            mConnection.scheduleWriteSettingsLocked();

            updateUser(packageName, ivs.getUserId(), verified);
        });
    }

    private void updateUser(String packageName, @UserIdInt int userId, boolean verified) {
        if (userId == UserHandle.USER_ALL) {
            mConnection.infoLog("autoVerify ignored when installing for all users");
            return;
        }

        final int userStatus = mSettings.getIntentFilterVerificationStatusLPr(packageName, userId);

        int updatedStatus = INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED;
        boolean needUpdate = false;

        // In a success case, we promote from undefined or ASK to ALWAYS.  This
        // supports a flow where the app fails validation but then ships an updated
        // APK that passes, and therefore deserves to be in ALWAYS.
        //
        // If validation failed, the undefined state winds up in the basic ASK behavior,
        // but apps that previously passed and became ALWAYS are *demoted* out of
        // that state, since they would not deserve the ALWAYS behavior in case of a
        // clean install.
        switch (userStatus) {
            case INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS:
                if (!verified) {
                    // Don't demote if sysconfig says 'always'
                    SystemConfig systemConfig = SystemConfig.getInstance();
                    ArraySet<String> packages = systemConfig.getLinkedApps();
                    if (!packages.contains(packageName)) {
                        // updatedStatus is already UNDEFINED
                        needUpdate = true;

                        mConnection.debugLog(
                                "Formerly validated but now failing; demoting");
                    } else {
                        mConnection.debugLog("Updating bundled package " + packageName
                                + " failed autoVerify, but sysconfig supersedes");
                        // leave needUpdate == false here intentionally
                    }
                }
                break;

            case INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED:
                // Stay in 'undefined' on verification failure
                if (verified) {
                    updatedStatus = INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS;
                }
                needUpdate = true;
                mConnection.debugLog("Applying update; old=" + userStatus
                        + " new=" + updatedStatus);
                break;

            case INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK:
                // Keep in 'ask' on failure
                if (verified) {
                    updatedStatus = INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS;
                    needUpdate = true;
                }
                break;


            // Nothing to do
        }

        if (needUpdate) {
            mSettings.updateIntentFilterVerificationStatusLPw(packageName, updatedStatus, userId);
            mConnection.scheduleWritePackageRestrictionsLocked(userId);
        }
    }

    public void primeDomainVerificationsLPw(int userId, Map<String, AndroidPackage> packages) {
        if (!mHasVerifier) {
            return;
        }

        mConnection.debugLog("Priming domain verifications in user " + userId);

        ArraySet<String> packageNames = mSystemConfig.getLinkedApps();

        for (int pkgNameIndex = 0; pkgNameIndex < packageNames.size(); pkgNameIndex++) {
            String packageName = packageNames.valueAt(pkgNameIndex);
            AndroidPackage pkg = packages.get(packageName);
            if (pkg == null) {
                mConnection.warnLog("Unknown package " + packageName + " in sysconfig <app-link>");
                continue;
            } else if (!pkg.isSystem()) {
                mConnection.warnLog("Non-system app '" + packageName + "' in sysconfig <app-link>");
                continue;
            }
            ArraySet<String> domains = null;
            List<ParsedActivity> activities = pkg.getActivities();
            for (int activityIndex = 0; activityIndex < activities.size(); activityIndex++) {
                List<ParsedIntentInfo> intentInfos = activities.get(activityIndex).getIntents();
                for (int infoIndex = 0; infoIndex < intentInfos.size(); infoIndex++) {
                    ParsedIntentInfo intentInfo = intentInfos.get(infoIndex);
                    if (IntentVerifyUtils.hasValidDomains(intentInfo)) {
                        domains = ArrayUtils.addAll(domains, intentInfo.getHostsList());
                    }
                }
            }

            if (CollectionUtils.isEmpty(domains)) {
                mConnection.warnLog("Sysconfig <app-link> package '" + packageName
                        + "' does not handle web links");
                continue;
            }

            mConnection.verboseLog("      + " + packageName);
            // 'Undefined' in the global IntentFilterVerificationInfo, i.e. the usual
            // state w.r.t. the formal app-linkage "no verification attempted" state;
            // and then 'always' in the per-user state actually used for intent resolution.
            final IntentFilterVerificationInfo ivi;
            ivi = mSettings.createIntentFilterVerificationIfNeededLPw(packageName, domains);
            ivi.setStatus(INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED);
            mSettings.updateIntentFilterVerificationStatusLPw(packageName,
                    INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS, userId);
        }

        mConnection.scheduleWritePackageRestrictionsLocked(userId);
        mConnection.scheduleWriteSettingsLocked();
    }

    public IntentFilterVerificationInfo updatePackageSetting(@NonNull PackageSetting pkgSetting,
            ArraySet<String> domainSet) {
        return mSettings.updatePackageSetting(pkgSetting, domainSet);
    }

    @NonNull
    public ParceledListSlice<IntentFilterVerificationInfo> getIntentFilterVerifications(
            @NonNull String packageName) {
        return mSettings.getIntentFilterVerifications(packageName);
    }

    public int getIntentVerificationStatus(@NonNull String packageName, int userId) {
        return mSettings.getIntentVerificationStatus(packageName, userId);
    }

    public boolean updateIntentVerificationStatus(@NonNull String packageName, int status,
            int userId) {
        return mSettings.updateIntentVerificationStatus(packageName, status, userId);
    }

    public void clearIntentFilterVerificationsLocked(@NonNull String packageName, int userId,
            boolean alsoResetStatus) {
        mSettings.clearIntentFilterVerificationsLocked(packageName, userId, alsoResetStatus);
    }

    public void clearIntentFilterVerificationsLocked(int userId,
            WatchedArrayMap<String, AndroidPackage> packages) {
        mSettings.clearIntentFilterVerificationsLocked(userId, packages);
    }

    public void writeAllDomainVerificationsLPr(TypedXmlSerializer serializer, int userId,
            @NonNull Map<String, PackageSetting> pkgSettings) throws IOException {
        mSettings.writeAllDomainVerificationsLPr(serializer, userId, pkgSettings);
    }

    public void readAllDomainVerificationsLPr(TypedXmlPullParser parser, @UserIdInt int userId)
            throws IOException, XmlPullParserException {
        mSettings.readAllDomainVerificationsLPr(parser, userId);
    }

    public void writeDomainVerificationsLPr(@NonNull TypedXmlSerializer serializer,
            @NonNull IntentFilterVerificationInfo info) throws IOException {
        mSettings.writeDomainVerificationsLPr(serializer, info);
    }

    @Nullable
    public IntentFilterVerificationInfo getRestoredIntentFilterVerificationInfo(
            @NonNull String packageName) {
        return mSettings.getRestoredIntentFilterVerificationInfo(packageName);
    }

    public void readRestoredIntentFilterVerifications(@NonNull TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        mSettings.readRestoredIntentFilterVerifications(parser);
    }

    public void writeRestoredIntentFilterVerifications(@NonNull TypedXmlSerializer serializer)
            throws IOException {
        mSettings.writeRestoredIntentFilterVerifications(serializer);
    }

    @NonNull
    public IntentFilterVerificationInfo readDomainVerificationLPw(
            @NonNull TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        return mSettings.readDomainVerificationLPw(parser);
    }
}
