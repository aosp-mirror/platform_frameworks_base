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

package android.permission;

import static android.Manifest.permission_group.CAMERA;
import static android.Manifest.permission_group.LOCATION;
import static android.Manifest.permission_group.MICROPHONE;
import static android.app.AppOpsManager.OPSTR_CAMERA;
import static android.app.AppOpsManager.OPSTR_COARSE_LOCATION;
import static android.app.AppOpsManager.OPSTR_FINE_LOCATION;
import static android.app.AppOpsManager.OPSTR_PHONE_CALL_CAMERA;
import static android.app.AppOpsManager.OPSTR_PHONE_CALL_MICROPHONE;
import static android.app.AppOpsManager.OPSTR_RECORD_AUDIO;
import static android.app.AppOpsManager.OP_FLAGS_ALL_TRUSTED;
import static android.app.AppOpsManager.opToPermission;
import static android.content.pm.PackageManager.FLAG_PERMISSION_USER_SENSITIVE_WHEN_GRANTED;
import static android.media.AudioSystem.MODE_IN_COMMUNICATION;
import static android.telephony.TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS;

import android.Manifest;
import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.Attribution;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.icu.text.ListFormatter;
import android.location.LocationManager;
import android.media.AudioManager;
import android.os.Process;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A helper which gets all apps which have used microphone, camera, and possible location
 * permissions within a certain timeframe, as well as possible special attributions, and if the
 * usage is a phone call.
 *
 * @hide
 */
public class PermissionUsageHelper {

    /** Whether to show the mic and camera icons.  */
    private static final String PROPERTY_CAMERA_MIC_ICONS_ENABLED = "camera_mic_icons_enabled";

    /** Whether to show the location indicators. */
    private static final String PROPERTY_LOCATION_INDICATORS_ENABLED =
            "location_indicators_enabled";

    /** Whether to show the Permissions Hub.  */
    private static final String PROPERTY_PERMISSIONS_HUB_2_ENABLED = "permissions_hub_2_enabled";

    /** How long after an access to show it as "recent" */
    private static final String RECENT_ACCESS_TIME_MS = "recent_acccess_time_ms";

    /** How long after an access to show it as "running" */
    private static final String RUNNING_ACCESS_TIME_MS = "running_acccess_time_ms";

    /** The name of the expected voice IME subtype */
    private static final String VOICE_IME_SUBTYPE = "voice";

    private static final String SYSTEM_PKG = "android";

    private static final long DEFAULT_RUNNING_TIME_MS = 5000L;
    private static final long DEFAULT_RECENT_TIME_MS = 15000L;

    private static boolean shouldShowPermissionsHub() {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_PRIVACY,
                PROPERTY_PERMISSIONS_HUB_2_ENABLED, false);
    }

    private static boolean shouldShowIndicators() {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_PRIVACY,
                PROPERTY_CAMERA_MIC_ICONS_ENABLED, true) || shouldShowPermissionsHub();
    }

    private static boolean shouldShowLocationIndicator() {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_PRIVACY,
                PROPERTY_LOCATION_INDICATORS_ENABLED, false);
    }

    private static long getRecentThreshold(Long now) {
        return now - DeviceConfig.getLong(DeviceConfig.NAMESPACE_PRIVACY,
                RECENT_ACCESS_TIME_MS, DEFAULT_RECENT_TIME_MS);
    }

    private static long getRunningThreshold(Long now) {
        return now - DeviceConfig.getLong(DeviceConfig.NAMESPACE_PRIVACY,
                RUNNING_ACCESS_TIME_MS, DEFAULT_RUNNING_TIME_MS);
    }

    private static final List<String> LOCATION_OPS = List.of(
            OPSTR_COARSE_LOCATION,
            OPSTR_FINE_LOCATION
    );

    private static final List<String> MIC_OPS = List.of(
            OPSTR_PHONE_CALL_MICROPHONE,
            OPSTR_RECORD_AUDIO
    );

    private static final List<String> CAMERA_OPS = List.of(
            OPSTR_PHONE_CALL_CAMERA,
            OPSTR_CAMERA
    );

    private static @NonNull String getGroupForOp(String op) {
        switch(op) {
            case OPSTR_RECORD_AUDIO:
                return MICROPHONE;
            case OPSTR_CAMERA:
                return CAMERA;
            case OPSTR_PHONE_CALL_MICROPHONE:
            case OPSTR_PHONE_CALL_CAMERA:
                return op;
            case OPSTR_COARSE_LOCATION:
            case OPSTR_FINE_LOCATION:
                return LOCATION;
            default:
                throw new IllegalArgumentException("Unknown app op: " + op);
        }
    }

    private Context mContext;
    private ArrayMap<UserHandle, Context> mUserContexts;
    private PackageManager mPkgManager;
    private AppOpsManager mAppOpsManager;

    /**
     * Constructor for PermissionUsageHelper
     * @param context The context from which to derive the package information
     */
    public PermissionUsageHelper(Context context) {
        mContext = context;
        mPkgManager = context.getPackageManager();
        mAppOpsManager = context.getSystemService(AppOpsManager.class);
        mUserContexts = new ArrayMap<>();
        mUserContexts.put(Process.myUserHandle(), mContext);
    }

    private Context getUserContext(UserHandle user) {
        if (!(mUserContexts.containsKey(user))) {
            mUserContexts.put(user, mContext.createContextAsUser(user, 0));
        }
        return mUserContexts.get(user);
    }

    // TODO ntmyren: Replace this with better check if this moves beyond teamfood
    private boolean isAppPredictor(String packageName, UserHandle user) {
        return shouldShowPermissionsHub() && getUserContext(user).getPackageManager()
                .checkPermission(Manifest.permission.MANAGE_APP_PREDICTIONS, packageName)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isSpeechRecognizerUsage(String op, String packageName) {
        if (!OPSTR_RECORD_AUDIO.equals(op)) {
            return false;
        }

        return packageName.equals(
                mContext.getString(R.string.config_systemSpeechRecognizer));
    }

    /**
     * @see PermissionManager.getIndicatorAppOpUsageData
     */
    public List<PermGroupUsage> getOpUsageData(boolean isMicMuted) {
        List<PermGroupUsage> usages = new ArrayList<>();

        if (!shouldShowIndicators()) {
            return usages;
        }

        List<String> ops = new ArrayList<>(CAMERA_OPS);
        if (shouldShowLocationIndicator()) {
            ops.addAll(LOCATION_OPS);
        }
        if (!isMicMuted) {
            ops.addAll(MIC_OPS);
        }

        Map<String, List<OpUsage>> rawUsages = getOpUsages(ops);
        Set<List<PackageAttribution>> proxyChains = getProxyChains(rawUsages.get(MICROPHONE));
        Map<PackageAttribution, CharSequence> packagesWithAttributionLabels =
                getTrustedAttributions(rawUsages.get(MICROPHONE), proxyChains);

        ArrayList<String> usedPermGroups = new ArrayList<>(rawUsages.keySet());

        // If we have a phone call, and a carrier privileged app using microphone, hide the
        // phone call.
        AudioManager audioManager = mContext.getSystemService(AudioManager.class);
        boolean hasPhoneCall = usedPermGroups.contains(OPSTR_PHONE_CALL_CAMERA)
                || usedPermGroups.contains(OPSTR_PHONE_CALL_MICROPHONE);
        if (hasPhoneCall && usedPermGroups.contains(MICROPHONE) && audioManager.getMode()
                == MODE_IN_COMMUNICATION) {
            TelephonyManager telephonyManager =
                    mContext.getSystemService(TelephonyManager.class);
            List<OpUsage> permUsages = rawUsages.get(MICROPHONE);
            for (int usageNum = 0; usageNum < permUsages.size(); usageNum++) {
                if (telephonyManager.checkCarrierPrivilegesForPackage(
                        permUsages.get(usageNum).packageName)
                        == CARRIER_PRIVILEGE_STATUS_HAS_ACCESS) {
                    usedPermGroups.remove(OPSTR_PHONE_CALL_CAMERA);
                    usedPermGroups.remove(OPSTR_PHONE_CALL_MICROPHONE);
                }
            }
        }

        for (int permGroupNum = 0; permGroupNum < usedPermGroups.size(); permGroupNum++) {
            boolean isPhone = false;
            String permGroup = usedPermGroups.get(permGroupNum);

            Map<PackageAttribution, CharSequence> pkgAttrLabels = packagesWithAttributionLabels;
            Set<List<PackageAttribution>> proxies = proxyChains;
            if (!MICROPHONE.equals(permGroup)) {
                pkgAttrLabels = new ArrayMap<>();
                proxies = new ArraySet<>();
            }

            List<OpUsage> permUsages = removeDuplicatesAndProxies(rawUsages.get(permGroup),
                    pkgAttrLabels.keySet(), proxies);

            if (permGroup.equals(OPSTR_PHONE_CALL_MICROPHONE)) {
                isPhone = true;
                permGroup = MICROPHONE;
            } else if (permGroup.equals(OPSTR_PHONE_CALL_CAMERA)) {
                isPhone = true;
                permGroup = CAMERA;
            }

            for (int usageNum = 0; usageNum < permUsages.size(); usageNum++) {
                OpUsage usage = permUsages.get(usageNum);
                usages.add(new PermGroupUsage(usage.packageName, usage.uid, permGroup,
                        usage.lastAccessTime, usage.isRunning, isPhone,
                        packagesWithAttributionLabels.get(usage.toPackageAttr())));
            }
        }

        return usages;
    }

    /**
     * Get the raw usages from the system, and then parse out the ones that are not recent enough,
     * determine which permission group each belongs in, and removes duplicates (if the same app
     * uses multiple permissions of the same group). Stores the package name, attribution tag, user,
     * running/recent info, if the usage is a phone call, per permission group.
     *
     * @param opNames a list of op names to get usage for
     *
     * @return A map of permission group -> list of usages that are recent or running
     */
    private Map<String, List<OpUsage>> getOpUsages(List<String> opNames) {
        List<AppOpsManager.PackageOps> ops;
        try {
            ops = mAppOpsManager.getPackagesForOps(opNames.toArray(new String[opNames.size()]));
        } catch (NullPointerException e) {
            // older builds might not support all the app-ops requested
            return Collections.emptyMap();
        }

        long now = System.currentTimeMillis();
        long recentThreshold = getRecentThreshold(now);
        long runningThreshold = getRunningThreshold(now);
        int opFlags = OP_FLAGS_ALL_TRUSTED;
        Map<String, Map<PackageAttribution, OpUsage>> usages = new ArrayMap<>();

        int numPkgOps = ops.size();
        for (int pkgOpNum = 0; pkgOpNum < numPkgOps; pkgOpNum++) {
            AppOpsManager.PackageOps pkgOps = ops.get(pkgOpNum);
            int uid = pkgOps.getUid();
            UserHandle user = UserHandle.getUserHandleForUid(uid);
            String packageName = pkgOps.getPackageName();

            int numOpEntries = pkgOps.getOps().size();
            for (int opEntryNum = 0; opEntryNum < numOpEntries; opEntryNum++) {
                AppOpsManager.OpEntry opEntry = pkgOps.getOps().get(opEntryNum);
                String op = opEntry.getOpStr();
                List<String> attributionTags =
                        new ArrayList<>(opEntry.getAttributedOpEntries().keySet());

                int numAttrEntries = opEntry.getAttributedOpEntries().size();
                for (int attrOpEntryNum = 0; attrOpEntryNum < numAttrEntries; attrOpEntryNum++) {
                    String attributionTag = attributionTags.get(attrOpEntryNum);
                    AppOpsManager.AttributedOpEntry attrOpEntry =
                            opEntry.getAttributedOpEntries().get(attributionTag);

                    long lastAccessTime = attrOpEntry.getLastAccessTime(opFlags);
                    if (lastAccessTime < recentThreshold && !attrOpEntry.isRunning()) {
                        continue;
                    }

                    if (packageName.equals(SYSTEM_PKG)
                            || (!shouldShowPermissionsHub()
                            && !isUserSensitive(packageName, user, op)
                            && !isLocationProvider(packageName, user)
                            && !isSpeechRecognizerUsage(op, packageName))) {
                        continue;
                    }

                    boolean isRunning = attrOpEntry.isRunning()
                            || lastAccessTime >= runningThreshold;

                    OpUsage proxyUsage = null;
                    AppOpsManager.OpEventProxyInfo proxy = attrOpEntry.getLastProxyInfo(opFlags);
                    if (proxy != null && proxy.getPackageName() != null) {
                        proxyUsage = new OpUsage(proxy.getPackageName(), proxy.getAttributionTag(),
                                proxy.getUid(), lastAccessTime, isRunning, null);
                    }

                    String permGroupName = getGroupForOp(op);
                    OpUsage usage = new OpUsage(packageName, attributionTag, uid,
                            lastAccessTime, isRunning, proxyUsage);

                    PackageAttribution packageAttr = usage.toPackageAttr();
                    if (!usages.containsKey(permGroupName)) {
                        ArrayMap<PackageAttribution, OpUsage> map = new ArrayMap<>();
                        map.put(packageAttr, usage);
                        usages.put(permGroupName, map);
                    } else {
                        Map<PackageAttribution, OpUsage> permGroupUsages =
                                usages.get(permGroupName);
                        if (!permGroupUsages.containsKey(packageAttr)) {
                            permGroupUsages.put(packageAttr, usage);
                        } else if (usage.lastAccessTime
                                > permGroupUsages.get(packageAttr).lastAccessTime) {
                            permGroupUsages.put(packageAttr, usage);
                        }
                    }
                }
            }
        }

        Map<String, List<OpUsage>> flattenedUsages = new ArrayMap<>();
        List<String> permGroups = new ArrayList<>(usages.keySet());
        for (int i = 0; i < permGroups.size(); i++) {
            String permGroupName = permGroups.get(i);
            flattenedUsages.put(permGroupName, new ArrayList<>(usages.get(permGroupName).values()));
        }
        return flattenedUsages;
    }

    /**
     * Take the list of all usages, figure out any proxy chains, get all possible special
     * attribution labels, and figure out which usages need to show a special label, if any.
     *
     * @param usages The raw permission usages
     *
     * @return A map of package + attribution (in the form of a PackageAttribution object) to
     * trusted attribution label, if there is one
     */
    private ArrayMap<PackageAttribution, CharSequence> getTrustedAttributions(
            List<OpUsage> usages, Set<List<PackageAttribution>> proxyChains) {
        ArrayMap<PackageAttribution, CharSequence> attributions = new ArrayMap<>();
        if (usages == null) {
            return attributions;
        }

        Map<PackageAttribution, CharSequence> trustedLabels =
                getTrustedAttributionLabels(usages);

        for (List<PackageAttribution> chain : proxyChains) {
            // If this chain is empty, or has only one link, then do not show any special labels
            if (chain.size() <= 1) {
                continue;
            }

            // If the last link in the chain is not user sensitive, do not show it.
            boolean lastLinkIsUserSensitive = false;
            for (int i = 0; i < usages.size(); i++) {
                PackageAttribution lastLink = chain.get(chain.size() - 1);
                if (lastLink.equals(usages.get(i).toPackageAttr())) {
                    lastLinkIsUserSensitive = true;
                    break;
                }
            }
            if (!lastLinkIsUserSensitive) {
                continue;
            }

            List<CharSequence> labels = new ArrayList<>();
            for (int i = 0; i < chain.size(); i++) {
                // If this is the last link in the proxy chain, assign it the series of labels
                // Else, if it has a special label, add that label
                // Else, if there are no other apps in the remaining part of the chain which
                // have the same package name, add the app label
                // If it is not the last link in the chain, remove its attribution
                PackageAttribution attr = chain.get(i);
                CharSequence trustedLabel = trustedLabels.get(attr);
                if (i == chain.size() - 1) {
                    attributions.put(attr, formatLabelList(labels));
                } else if (trustedLabel != null && !labels.contains(trustedLabel)) {
                    labels.add(trustedLabel);
                    trustedLabels.remove(attr);
                } else {
                    boolean remainingChainHasPackage = false;
                    for (int attrNum = i + 1; attrNum < chain.size() - 1; attrNum++) {
                        if (chain.get(i).packageName.equals(attr.packageName)) {
                            remainingChainHasPackage = true;
                            break;
                        }
                    }
                    if (!remainingChainHasPackage) {
                        try {
                            ApplicationInfo appInfo = mPkgManager.getApplicationInfoAsUser(
                                    attr.packageName, 0, attr.getUser());
                            CharSequence appLabel = appInfo.loadLabel(
                                    getUserContext(attr.getUser()).getPackageManager());
                            labels.add(appLabel);
                        } catch (PackageManager.NameNotFoundException e) {
                            // Do nothing
                        }
                    }
                }
            }
        }

        for (PackageAttribution attr : trustedLabels.keySet()) {
            attributions.put(attr, trustedLabels.get(attr));
        }

        return attributions;
    }

    private CharSequence formatLabelList(List<CharSequence> labels) {
        return ListFormatter.getInstance().format(labels);
    }

    /**
     * Get all chains of proxy usages. A proxy chain is defined as one usage at the root, then
     * further proxy usages, where the app and attribution tag of the proxy in the proxy usage
     * matches the previous usage in the chain.
     *
     * @param usages The permission usages
     *
     * @return A set of lists of package attributions. One list represents a chain of proxy usages,
     * with the start of the chain (the usage without a proxy) at position 0, and each usage down
     * the chain has the previous one listed as a proxy usage.
     */
    private Set<List<PackageAttribution>> getProxyChains(List<OpUsage> usages) {
        if (usages == null) {
            return new ArraySet<>();
        }

        ArrayMap<PackageAttribution, ArrayList<PackageAttribution>> proxyChains = new ArrayMap<>();
        // map of usages that still need to be removed, or added to a chain
        ArrayMap<PackageAttribution, OpUsage> remainingUsages = new ArrayMap<>();
        // map of usage.proxy -> usage, telling us if a usage is a proxy
        ArrayMap<PackageAttribution, PackageAttribution> proxies = new ArrayMap<>();
        for (int i = 0; i < usages.size(); i++) {
            OpUsage usage = usages.get(i);
            remainingUsages.put(usage.toPackageAttr(), usage);
            if (usage.proxy != null) {
                proxies.put(usage.proxy.toPackageAttr(), usage.toPackageAttr());
            }
        }

        // find all possible end points for chains
        List<PackageAttribution> keys = new ArrayList<>(remainingUsages.keySet());
        for (int usageNum = 0; usageNum < remainingUsages.size(); usageNum++) {
            OpUsage usage = remainingUsages.get(keys.get(usageNum));
            if (usage == null) {
                continue;
            }
            PackageAttribution usageAttr = usage.toPackageAttr();
            // If this usage has a proxy, but is not a proxy, it is the end of a chain.
            // If it has no proxy, and isn't a proxy, remove it.
            if (!proxies.containsKey(usageAttr) && usage.proxy != null) {
                ArrayList<PackageAttribution> proxyList = new ArrayList<>();
                proxyList.add(usageAttr);
                proxyChains.put(usageAttr, proxyList);
            } else if (!proxies.containsKey(usageAttr) && usage.proxy == null) {
                remainingUsages.remove(keys.get(usageNum));
            }
        }

        // assemble the chains in reverse order, then invert them
        for (int numStart = 0; numStart < proxyChains.size(); numStart++) {
            PackageAttribution currPackageAttr = proxyChains.keyAt(numStart);
            ArrayList<PackageAttribution> proxyChain = proxyChains.get(currPackageAttr);
            OpUsage currentUsage = remainingUsages.get(currPackageAttr);
            if (currentUsage == null || proxyChain == null) {
                continue;
            }
            while (currentUsage.proxy != null) {
                currPackageAttr = currentUsage.proxy.toPackageAttr();
                currentUsage = remainingUsages.get(currPackageAttr);

                boolean invalidState = false;
                for (int chainNum = 0; chainNum < proxyChain.size(); chainNum++) {
                    if (currentUsage == null || proxyChain.get(chainNum).equals(currPackageAttr)) {
                        // either our current value is not in the usage list, or we have a cycle
                        invalidState = true;
                        break;
                    }
                }

                if (invalidState) {
                    break;
                }

                proxyChain.add(currPackageAttr);
            }
            // invert the lists, so the element without a proxy is first on the list
            Collections.reverse(proxyChain);
        }

        return new ArraySet<>(proxyChains.values());
    }

    /**
     * Gets all trusted proxied voice IME and voice recognition microphone uses, and get the
     * label needed to display with it, as well as information about the proxy whose label is being
     * shown, if applicable.
     *
     * @param usages The permission usages
     *
     * @return A map of package attribution -> the attribution label for that package attribution,
     * if applicable
     */
    private Map<PackageAttribution, CharSequence> getTrustedAttributionLabels(
            List<OpUsage> usages) {
        List<UserHandle> users = new ArrayList<>();
        for (int i = 0; i < usages.size(); i++) {
            UserHandle user = UserHandle.getUserHandleForUid(usages.get(i).uid);
            if (!users.contains(user)) {
                users.add(user);
            }
        }

        Map<PackageAttribution, CharSequence> trustedLabels = new ArrayMap<>();
        for (int userNum = 0; userNum < users.size(); userNum++) {
            UserHandle user = users.get(userNum);
            Context userContext = mContext.createContextAsUser(user, 0);

            // Get all voice IME labels
            Map<String, CharSequence> voiceInputs = new ArrayMap<>();
            List<InputMethodInfo> inputs = userContext.getSystemService(InputMethodManager.class)
                    .getEnabledInputMethodList();
            for (int inputNum = 0; inputNum < inputs.size(); inputNum++) {
                InputMethodInfo input = inputs.get(inputNum);
                for (int subtypeNum = 0; subtypeNum < input.getSubtypeCount(); subtypeNum++) {
                    if (VOICE_IME_SUBTYPE.equals(input.getSubtypeAt(subtypeNum).getMode())) {
                        voiceInputs.put(input.getPackageName(), input.getServiceInfo()
                                .loadUnsafeLabel(userContext.getPackageManager()));
                        break;
                    }
                }
            }

            // Get the currently selected recognizer from the secure setting
            String recognitionPackageName = Settings.Secure.getString(
                    userContext.getContentResolver(), Settings.Secure.VOICE_RECOGNITION_SERVICE);
            if (recognitionPackageName == null) {
                continue;
            }
            recognitionPackageName =
                    ComponentName.unflattenFromString(recognitionPackageName).getPackageName();
            Map<String, CharSequence> recognizers = new ArrayMap<>();
            List<ResolveInfo> availableRecognizers = mPkgManager.queryIntentServicesAsUser(
                    new Intent(RecognitionService.SERVICE_INTERFACE), PackageManager.GET_META_DATA,
                    user.getIdentifier());
            for (int recogNum = 0; recogNum < availableRecognizers.size(); recogNum++) {
                ResolveInfo info = availableRecognizers.get(recogNum);
                if (recognitionPackageName.equals(info.serviceInfo.packageName)) {
                    recognizers.put(recognitionPackageName, info.serviceInfo.loadUnsafeLabel(
                            userContext.getPackageManager()));
                }
            }

            Map<String, CharSequence> recognizerIntents = new ArrayMap<>();
            List<ResolveInfo> availableRecognizerIntents = mPkgManager.queryIntentActivitiesAsUser(
                    new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
                    PackageManager.GET_META_DATA, user);
            for (int recogNum = 0; recogNum < availableRecognizerIntents.size(); recogNum++) {
                ResolveInfo info = availableRecognizerIntents.get(recogNum);
                if (info.activityInfo == null) {
                    continue;
                }
                String pkgName = info.activityInfo.packageName;
                if (recognitionPackageName.equals(pkgName) && recognizers.containsKey(pkgName)) {
                    recognizerIntents.put(pkgName, recognizers.get(pkgName));
                }
            }
            for (int usageNum = 0; usageNum < usages.size(); usageNum++) {
                setTrustedAttrsForAccess(usages.get(usageNum), user, false, voiceInputs,
                        trustedLabels);
                setTrustedAttrsForAccess(usages.get(usageNum), user, false, recognizerIntents,
                        trustedLabels);
                setTrustedAttrsForAccess(usages.get(usageNum), user, true, recognizers,
                        trustedLabels);
            }
        }

        return trustedLabels;
    }

    private void setTrustedAttrsForAccess(OpUsage opUsage, UserHandle currUser, boolean getProxy,
            Map<String, CharSequence> trustedMap, Map<PackageAttribution, CharSequence> toSetMap) {
        OpUsage usage = opUsage;
        if (getProxy) {
            usage = opUsage.proxy;
        }

        if (usage == null || !usage.getUser().equals(currUser)
                || !trustedMap.containsKey(usage.packageName)) {
            return;
        }

        CharSequence label = getAttributionLabel(usage);
        if (trustedMap.get(usage.packageName).equals(label)) {
            toSetMap.put(opUsage.toPackageAttr(), label);
        }
    }

    private CharSequence getAttributionLabel(OpUsage usage) {
        if (usage.attributionTag == null) {
            return null;
        }

        PackageInfo pkgInfo;
        try {
            pkgInfo = mPkgManager.getPackageInfoAsUser(usage.packageName,
                    PackageManager.GET_ATTRIBUTIONS, usage.getUser().getIdentifier());
            if (pkgInfo.attributions == null || pkgInfo.attributions.length == 0) {
                return null;
            }
            for (int attrNum = 0; attrNum < pkgInfo.attributions.length; attrNum++) {
                Attribution attr = pkgInfo.attributions[attrNum];
                if (usage.attributionTag.equals(attr.getTag())) {
                    return mContext.createPackageContextAsUser(usage.packageName, 0,
                            usage.getUser()).getString(attr.getLabel());
                }
            }
            return null;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * If we have multiple usages of a
     * @param rawUsages The list of all usages that we wish to
     * @param specialAttributions A set of all usages that have a special label
     * @param proxies A list of proxy chains- all links but the last on the chain should be removed,
     *                if the last link has a special label
     * @return A list of usages without duplicates or proxy usages.
     */
    private List<OpUsage> removeDuplicatesAndProxies(List<OpUsage> rawUsages,
            Set<PackageAttribution> specialAttributions,
            Set<List<PackageAttribution>> proxies) {
        List<OpUsage> deDuped = new ArrayList<>();
        if (rawUsages == null) {
            return deDuped;
        }

        List<PackageAttribution> toRemoveProxies = new ArrayList<>();
        for (List<PackageAttribution> proxyList: proxies) {
            PackageAttribution lastLink = proxyList.get(proxyList.size() - 1);
            if (!specialAttributions.contains(lastLink)) {
                continue;
            }
            for (int proxyNum = 0; proxyNum < proxyList.size(); proxyNum++) {
                if (!proxyList.get(proxyNum).equals(lastLink)) {
                    toRemoveProxies.add(proxyList.get(proxyNum));
                }
            }
        }

        for (int usageNum = 0; usageNum < rawUsages.size(); usageNum++) {
            OpUsage usage = rawUsages.get(usageNum);

            // If this attribution is a proxy, remove it
            if (toRemoveProxies.contains(usage.toPackageAttr())) {
                continue;
            }

            // If this attribution has a special attribution, do not remove it
            if (specialAttributions.contains(usage.toPackageAttr())) {
                deDuped.add(usage);
                continue;
            }


            // Search the rest of the list for usages with the same UID. If this is the most recent
            // usage for that uid, keep it. Otherwise, remove it
            boolean isMostRecentForUid = true;
            for (int otherUsageNum = 0; otherUsageNum < rawUsages.size(); otherUsageNum++) {
                // Do not compare this usage to itself
                if (otherUsageNum == usageNum) {
                    continue;
                }

                OpUsage otherUsage = rawUsages.get(otherUsageNum);
                if (otherUsage.uid == usage.uid) {
                    if (otherUsage.isRunning && !usage.isRunning) {
                        isMostRecentForUid = false;
                    } else if (usage.isRunning
                            && otherUsage.lastAccessTime >= usage.lastAccessTime) {
                        isMostRecentForUid = false;
                    } else if (otherUsage.lastAccessTime >= usage.lastAccessTime) {
                        isMostRecentForUid = false;
                    }

                    if (!isMostRecentForUid) {
                        break;
                    }
                }
            }

            if (isMostRecentForUid) {
                deDuped.add(usage);
            }
        }

        return deDuped;
    }

    private boolean isUserSensitive(String packageName, UserHandle user, String op) {
        if (op.equals(OPSTR_PHONE_CALL_CAMERA) || op.equals(OPSTR_PHONE_CALL_MICROPHONE)) {
            return true;
        }

        if (opToPermission(op) == null) {
            return false;
        }

        int permFlags = mPkgManager.getPermissionFlags(opToPermission(op), packageName, user);
        return (permFlags & FLAG_PERMISSION_USER_SENSITIVE_WHEN_GRANTED) != 0;
    }

    private boolean isLocationProvider(String packageName, UserHandle user) {
        return getUserContext(user)
                .getSystemService(LocationManager.class).isProviderPackage(packageName);
    }

    /**
     * Represents the usage of an App op by a particular package and attribution
     */
    private static class OpUsage {

        public final String packageName;
        public final String attributionTag;
        public final int uid;
        public final long lastAccessTime;
        public final OpUsage proxy;
        public final boolean isRunning;

        OpUsage(String packageName, String attributionTag, int uid, long lastAccessTime,
                boolean isRunning, OpUsage proxy) {
            this.isRunning = isRunning;
            this.packageName = packageName;
            this.attributionTag = attributionTag;
            this.uid = uid;
            this.lastAccessTime = lastAccessTime;
            this.proxy = proxy;
        }

        public PackageAttribution toPackageAttr() {
            return new PackageAttribution(packageName, attributionTag, uid);
        }

        public UserHandle getUser() {
            return UserHandle.getUserHandleForUid(uid);
        }
    }

    /**
     * A unique identifier for one package attribution, made up of attribution tag, package name
     * and user
     */
    private static class PackageAttribution {
        public final String packageName;
        public final String attributionTag;
        public final int uid;

        PackageAttribution(String packageName, String attributionTag, int uid) {
            this.packageName = packageName;
            this.attributionTag = attributionTag;
            this.uid = uid;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof PackageAttribution)) {
                return false;
            }
            PackageAttribution other = (PackageAttribution) obj;
            return Objects.equals(packageName, other.packageName) && Objects.equals(attributionTag,
                    other.attributionTag) && Objects.equals(uid, other.uid);
        }

        @Override
        public int hashCode() {
            return Objects.hash(packageName, attributionTag, uid);
        }

        public UserHandle getUser() {
            return UserHandle.getUserHandleForUid(uid);
        }
    }
}
