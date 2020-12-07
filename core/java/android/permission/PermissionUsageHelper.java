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

import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Process;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.ArrayMap;
import android.util.Pair;

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

    /** How long after an access to show it as "recent" */
    private static final String RECENT_ACCESS_TIME_MS = "recent_acccess_time_ms";

    /** How long after an access to show it as "running" */
    private static final String RUNNING_ACCESS_TIME_MS = "running_acccess_time_ms";

    private static final long DEFAULT_RUNNING_TIME_MS = 5000L;
    private static final long DEFAULT_RECENT_TIME_MS = 30000L;

    private static boolean shouldShowIndicators() {
        return true;
        // TODO ntmyren: remove true set when device config is configured correctly
        //DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_PRIVACY,
        //PROPERTY_CAMERA_MIC_ICONS_ENABLED, true);
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
            OPSTR_PHONE_CALL_CAMERA,
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
    private Map<UserHandle, Context> mUserContexts;
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
        mUserContexts = Map.of(Process.myUserHandle(), mContext);
    }

    private Context getUserContext(UserHandle user) {
        if (!(mUserContexts.containsKey(user))) {
            mUserContexts.put(user, mContext.createContextAsUser(user, 0));
        }
        return mUserContexts.get(user);
    }

    /**
     * @see PermissionManager.getIndicatorAppOpUsageData
     */
    public List<PermGroupUsage> getOpUsageData(boolean isMicMuted) {
        if (!shouldShowIndicators()) {
            return null;
        }

        List<String> ops = CAMERA_OPS;
        if (shouldShowLocationIndicator()) {
            ops.addAll(LOCATION_OPS);
        }
        if (!isMicMuted) {
            ops.addAll(MIC_OPS);
        }

        Map<String, List<OpUsage>> rawUsages = getOpUsages(ops);
        Map<PackageAttribution, CharSequence> packagesWithAttributionLabels =
                getTrustedAttributions(rawUsages.get(MICROPHONE));

        List<PermGroupUsage> usages = new ArrayList<>();
        List<String> usedPermGroups = new ArrayList<>(rawUsages.keySet());
        for (int permGroupNum = 0; permGroupNum < usedPermGroups.size(); permGroupNum++) {
            boolean isPhone = false;
            String permGroup = usedPermGroups.get(permGroupNum);
            if (permGroup.equals(OPSTR_PHONE_CALL_MICROPHONE)) {
                isPhone = true;
                permGroup = MICROPHONE;
            } else if (permGroup.equals(OPSTR_PHONE_CALL_CAMERA)) {
                isPhone = true;
                permGroup = CAMERA;
            }

            int numUsages = rawUsages.get(permGroup).size();
            for (int usageNum = 0; usageNum < numUsages; usageNum++) {
                OpUsage usage = rawUsages.get(permGroup).get(usageNum);
                usages.add(new PermGroupUsage(usage.packageName, usage.uid, permGroup,
                        usage.isRunning, isPhone,
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
                    if (lastAccessTime < recentThreshold) {
                        continue;
                    }
                    if (!isUserSensitive(packageName, user, op)
                            && !isLocationProvider(packageName, user)) {
                        continue;
                    }

                    boolean isRunning = attrOpEntry.isRunning()
                            || lastAccessTime >= runningThreshold;

                    OpUsage proxyUsage = null;
                    AppOpsManager.OpEventProxyInfo proxy = attrOpEntry.getLastProxyInfo(opFlags);
                    if (proxy != null && proxy.getPackageName() != null) {
                        proxyUsage = new OpUsage(proxy.getPackageName(), proxy.getAttributionTag(),
                                uid, lastAccessTime, isRunning, null);
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

    // TODO ntmyren: create JavaDoc and copy merging of proxy chains and trusted labels from
    //  "usages" livedata in ReviewOngoingUsageLiveData
    private Map<PackageAttribution, CharSequence> getTrustedAttributions(List<OpUsage> usages) {
        ArrayMap<PackageAttribution, CharSequence> attributions = new ArrayMap<>();
        if (usages == null) {
            return attributions;
        }
        Set<List<OpUsage>> proxyChains = getProxyChains(usages);
        Map<Pair<String, UserHandle>, CharSequence> trustedLabels = getTrustedAttributionLabels();


        return attributions;
    }

    // TODO ntmyren: create JavaDoc and copy proxyChainsLiveData from ReviewOngoingUsageLiveData
    private Set<List<OpUsage>> getProxyChains(List<OpUsage> usages) {
        Map<PackageAttribution, List<OpUsage>> inProgressChains = new ArrayMap<>();
        List<OpUsage> remainingUsages = new ArrayList<>(usages);
        // find all one-link chains (that is, all proxied apps whose proxy is not included in
        // the usage list)
        for (int usageNum = 0; usageNum < usages.size(); usageNum++) {
            OpUsage usage = usages.get(usageNum);
            PackageAttribution usageAttr = usage.toPackageAttr();
            if (usage.proxy == null) {
                continue;
            }
            PackageAttribution proxyAttr = usage.proxy.toPackageAttr();
            boolean proxyExists = false;
            for (int otherUsageNum = 0; otherUsageNum < usages.size(); otherUsageNum++) {
                if (usages.get(otherUsageNum).toPackageAttr().equals(proxyAttr)) {
                    proxyExists = true;
                    break;
                }
            }

            if (!proxyExists) {
                inProgressChains.put(usageAttr, List.of(usage));
                remainingUsages.remove(usage);
            }
        }

        // find all possible starting points for chains
        for (int i = 0; i < usages.size(); i++) {
            OpUsage usage = usages.get(i);
        }

            /*
            // find all possible starting points for chains
            for (usage in remainingProxyChainUsages.toList()) {
                // if this usage has no proxy, but proxies another usage, it is the start of a chain
                val usageAttr = getPackageAttr(usage)
                if (usage.proxyAccess == null && remainingProxyChainUsages.any {
                    it.proxyAccess != null && getPackageAttr(it.proxyAccess) == usageAttr
                }) {
                    inProgressChains[usageAttr] = mutableListOf(usage)
                }

                // if this usage is a chain start, or no usage have this usage as a proxy, remove it
                if (usage.proxyAccess == null) {
                    remainingProxyChainUsages.remove(usage)
                }
            }

             */

        return null;
    }

    // TODO ntmyren: create JavaDoc and copy trustedAttrsLiveData from ReviewOngoingUsageLiveData
    private Map<Pair<String, UserHandle>, CharSequence> getTrustedAttributionLabels() {
        return new ArrayMap<>();
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
    }
}
