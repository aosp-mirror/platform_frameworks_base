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

package com.android.server.policy;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.app.AppOpsManager.AttributionFlags;
import android.app.AppOpsManagerInternal;
import android.app.SyncNotedAppOp;
import android.app.role.RoleManager;
import android.content.AttributionSource;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.location.LocationManagerInternal;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PackageTagsList;
import android.os.Process;
import android.os.UserHandle;
import android.service.voice.VoiceInteractionManagerInternal;
import android.service.voice.VoiceInteractionManagerInternal.HotwordDetectionServiceIdentity;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.function.HeptFunction;
import com.android.internal.util.function.HexFunction;
import com.android.internal.util.function.QuadFunction;
import com.android.internal.util.function.QuintConsumer;
import com.android.internal.util.function.QuintFunction;
import com.android.internal.util.function.UndecFunction;
import com.android.server.LocalServices;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class defines policy for special behaviors around app ops.
 */
public final class AppOpsPolicy implements AppOpsManagerInternal.CheckOpsDelegate {
    private static final String LOG_TAG = AppOpsPolicy.class.getName();

    private static final String ACTIVITY_RECOGNITION_TAGS =
            "android:activity_recognition_allow_listed_tags";
    private static final String ACTIVITY_RECOGNITION_TAGS_SEPARATOR = ";";

    @NonNull
    private final Object mLock = new Object();

    @NonNull
    private final IBinder mToken = new Binder();

    @NonNull
    private final Context mContext;

    @NonNull
    private final RoleManager mRoleManager;

    @NonNull
    private final VoiceInteractionManagerInternal mVoiceInteractionManagerInternal;

    /**
     * Whether this device allows only the HotwordDetectionService to use
     * OP_RECORD_AUDIO_HOTWORD which doesn't incur the privacy indicator.
     */
    private final boolean mIsHotwordDetectionServiceRequired;

    /**
     * The locking policy around the location tags is a bit special. Since we want to
     * avoid grabbing the lock on every op note we are taking the approach where the
     * read and write are being done via a thread-safe data structure such that the
     * lookup/insert are single thread-safe calls. When we update the cached state we
     * use a lock to ensure the update's lookup and store calls are done atomically,
     * so multiple writers would not interleave. The tradeoff is we make is that the
     * concurrent data structure would use boxing/unboxing of integers but this is
     * preferred to locking.
     */
    @GuardedBy("mLock - writes only - see above")
    @NonNull
    private final ConcurrentHashMap<Integer, PackageTagsList> mLocationTags =
            new ConcurrentHashMap<>();

    // location tags can vary per uid - but we merge all tags under an app id into the final data
    // structure above
    @GuardedBy("mLock")
    private final SparseArray<PackageTagsList> mPerUidLocationTags = new SparseArray<>();

    // activity recognition currently only grabs tags from the APK manifest. we know that the
    // manifest is the same for all users, so there's no need to track variations in tags across
    // different users. if that logic ever changes, this might need to behave more like location
    // tags above.
    @GuardedBy("mLock - writes only - see above")
    @NonNull
    private final ConcurrentHashMap<Integer, PackageTagsList> mActivityRecognitionTags =
            new ConcurrentHashMap<>();

    public AppOpsPolicy(@NonNull Context context) {
        mContext = context;
        mRoleManager = mContext.getSystemService(RoleManager.class);
        mVoiceInteractionManagerInternal = LocalServices.getService(
                VoiceInteractionManagerInternal.class);
        mIsHotwordDetectionServiceRequired = isHotwordDetectionServiceRequired(
                mContext.getPackageManager());

        final LocationManagerInternal locationManagerInternal = LocalServices.getService(
                LocationManagerInternal.class);
        locationManagerInternal.setLocationPackageTagsListener(
                (uid, packageTagsList) -> {
                    synchronized (mLock) {
                        if (packageTagsList.isEmpty()) {
                            mPerUidLocationTags.remove(uid);
                        } else {
                            mPerUidLocationTags.set(uid, packageTagsList);
                        }

                        int appId = UserHandle.getAppId(uid);
                        PackageTagsList.Builder appIdTags = new PackageTagsList.Builder(1);
                        int size = mPerUidLocationTags.size();
                        for (int i = 0; i < size; i++) {
                            if (UserHandle.getAppId(mPerUidLocationTags.keyAt(i)) == appId) {
                                appIdTags.add(mPerUidLocationTags.valueAt(i));
                            }
                        }

                        updateAllowListedTagsForPackageLocked(appId, appIdTags.build(),
                                mLocationTags);
                    }
                });

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        intentFilter.addDataScheme("package");

        context.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final Uri uri = intent.getData();
                if (uri == null) {
                    return;
                }
                final String packageName = uri.getSchemeSpecificPart();
                if (TextUtils.isEmpty(packageName)) {
                    return;
                }
                final List<String> activityRecognizers = mRoleManager.getRoleHolders(
                        RoleManager.ROLE_SYSTEM_ACTIVITY_RECOGNIZER);
                if (activityRecognizers.contains(packageName)) {
                    updateActivityRecognizerTags(packageName);
                }
            }
        }, UserHandle.SYSTEM, intentFilter, null, null);

        mRoleManager.addOnRoleHoldersChangedListenerAsUser(context.getMainExecutor(),
                (String roleName, UserHandle user) -> {
            if (RoleManager.ROLE_SYSTEM_ACTIVITY_RECOGNIZER.equals(roleName)) {
                initializeActivityRecognizersTags();
            }
        }, UserHandle.SYSTEM);

        initializeActivityRecognizersTags();

        // Restrict phone call ops if the TelecomService will not start (conditioned on having
        // FEATURE_MICROPHONE, FEATURE_TELECOM, or FEATURE_TELEPHONY).
        PackageManager pm = mContext.getPackageManager();
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
                && !pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
                && !pm.hasSystemFeature(PackageManager.FEATURE_TELECOM)) {
            AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);
            appOps.setUserRestrictionForUser(AppOpsManager.OP_PHONE_CALL_MICROPHONE, true, mToken,
                    null, UserHandle.USER_ALL);
            appOps.setUserRestrictionForUser(AppOpsManager.OP_PHONE_CALL_CAMERA, true, mToken,
                    null, UserHandle.USER_ALL);
        }
    }

    private static boolean isHotwordDetectionServiceRequired(PackageManager pm) {
        // Usage of the HotwordDetectionService won't be enforced until a later release.
        return false;
    }

    @Override
    public int checkOperation(int code, int uid, String packageName,
            @Nullable String attributionTag, boolean raw,
            QuintFunction<Integer, Integer, String, String, Boolean, Integer> superImpl) {
        return superImpl.apply(code, resolveUid(code, uid), packageName, attributionTag, raw);
    }

    @Override
    public int checkAudioOperation(int code, int usage, int uid, String packageName,
            QuadFunction<Integer, Integer, Integer, String, Integer> superImpl) {
        return superImpl.apply(code, usage, uid, packageName);
    }

    @Override
    public SyncNotedAppOp noteOperation(int code, int uid, @Nullable String packageName,
            @Nullable String attributionTag, boolean shouldCollectAsyncNotedOp, @Nullable
            String message, boolean shouldCollectMessage, @NonNull HeptFunction<Integer, Integer,
                    String, String, Boolean, String, Boolean, SyncNotedAppOp> superImpl) {
        return superImpl.apply(resolveDatasourceOp(code, uid, packageName, attributionTag),
                resolveUid(code, uid), packageName, attributionTag, shouldCollectAsyncNotedOp,
                message, shouldCollectMessage);
    }

    @Override
    public SyncNotedAppOp noteProxyOperation(int code, @NonNull AttributionSource attributionSource,
            boolean shouldCollectAsyncNotedOp, @Nullable String message,
            boolean shouldCollectMessage, boolean skipProxyOperation, @NonNull HexFunction<Integer,
                    AttributionSource, Boolean, String, Boolean, Boolean,
                    SyncNotedAppOp> superImpl) {
        return superImpl.apply(resolveDatasourceOp(code, attributionSource.getUid(),
                attributionSource.getPackageName(), attributionSource.getAttributionTag()),
                attributionSource, shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                skipProxyOperation);
    }

    @Override
    public SyncNotedAppOp startOperation(IBinder token, int code, int uid,
            @Nullable String packageName, @Nullable String attributionTag,
            boolean startIfModeDefault, boolean shouldCollectAsyncNotedOp, String message,
            boolean shouldCollectMessage, @AttributionFlags int attributionFlags,
            int attributionChainId, @NonNull UndecFunction<IBinder, Integer, Integer, String,
                    String, Boolean, Boolean, String, Boolean, Integer, Integer,
            SyncNotedAppOp> superImpl) {
        return superImpl.apply(token, resolveDatasourceOp(code, uid, packageName, attributionTag),
                resolveUid(code, uid), packageName, attributionTag, startIfModeDefault,
                shouldCollectAsyncNotedOp, message, shouldCollectMessage, attributionFlags,
                attributionChainId);
    }

    @Override
    public SyncNotedAppOp startProxyOperation(@NonNull IBinder clientId, int code,
            @NonNull AttributionSource attributionSource, boolean startIfModeDefault,
            boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage,
            boolean skipProxyOperation, @AttributionFlags int proxyAttributionFlags,
            @AttributionFlags int proxiedAttributionFlags, int attributionChainId,
            @NonNull UndecFunction<IBinder, Integer, AttributionSource, Boolean, Boolean, String,
                    Boolean, Boolean, Integer, Integer, Integer, SyncNotedAppOp> superImpl) {
        return superImpl.apply(clientId, resolveDatasourceOp(code, attributionSource.getUid(),
                attributionSource.getPackageName(), attributionSource.getAttributionTag()),
                attributionSource, startIfModeDefault, shouldCollectAsyncNotedOp, message,
                shouldCollectMessage, skipProxyOperation, proxyAttributionFlags,
                proxiedAttributionFlags, attributionChainId);
    }

    @Override
    public void finishOperation(IBinder clientId, int code, int uid, String packageName,
            String attributionTag,
            @NonNull QuintConsumer<IBinder, Integer, Integer, String, String> superImpl) {
        superImpl.accept(clientId, resolveDatasourceOp(code, uid, packageName, attributionTag),
                resolveUid(code, uid), packageName, attributionTag);
    }

    @Override
    public void finishProxyOperation(@NonNull IBinder clientId, int code,
            @NonNull AttributionSource attributionSource, boolean skipProxyOperation,
            @NonNull QuadFunction<IBinder, Integer, AttributionSource, Boolean, Void> superImpl) {
        superImpl.apply(clientId, resolveDatasourceOp(code, attributionSource.getUid(),
                attributionSource.getPackageName(), attributionSource.getAttributionTag()),
                attributionSource, skipProxyOperation);
    }

    /**
     * Write location and activity recognition tags to console.
     * See also {@code adb shell dumpsys appops}.
     */
    public void dumpTags(PrintWriter writer) {
        if (!mLocationTags.isEmpty()) {
            writer.println("  AppOps policy location tags:");
            writeTags(mLocationTags, writer);
            writer.println();
        }
        if (!mActivityRecognitionTags.isEmpty()) {
            writer.println("  AppOps policy activity recognition tags:");
            writeTags(mActivityRecognitionTags, writer);
            writer.println();
        }
    }

    private void writeTags(Map<Integer, PackageTagsList> tags, PrintWriter writer) {
        int counter = 0;
        for (Map.Entry<Integer, PackageTagsList> tagEntry : tags.entrySet()) {
            writer.print("    #"); writer.print(counter++); writer.print(": ");
            writer.print(tagEntry.getKey().toString()); writer.print("=");
            tagEntry.getValue().dump(writer);
        }
    }


    private int resolveDatasourceOp(int code, int uid, @NonNull String packageName,
            @Nullable String attributionTag) {
        code = resolveRecordAudioOp(code, uid);
        if (attributionTag == null) {
            return code;
        }
        int resolvedCode = resolveLocationOp(code);
        if (resolvedCode != code) {
            if (isDatasourceAttributionTag(uid, packageName, attributionTag,
                    mLocationTags)) {
                return resolvedCode;
            }
        } else {
            resolvedCode = resolveArOp(code);
            if (resolvedCode != code) {
                if (isDatasourceAttributionTag(uid, packageName, attributionTag,
                        mActivityRecognitionTags)) {
                    return resolvedCode;
                }
            }
        }
        return code;
    }

    private void initializeActivityRecognizersTags() {
        final List<String> activityRecognizers = mRoleManager.getRoleHolders(
                RoleManager.ROLE_SYSTEM_ACTIVITY_RECOGNIZER);
        final int recognizerCount = activityRecognizers.size();
        if (recognizerCount > 0) {
            for (int i = 0; i < recognizerCount; i++) {
                final String activityRecognizer = activityRecognizers.get(i);
                updateActivityRecognizerTags(activityRecognizer);
            }
        } else {
            clearActivityRecognitionTags();
        }
    }

    private void clearActivityRecognitionTags() {
        synchronized (mLock) {
            mActivityRecognitionTags.clear();
        }
    }

    private void updateActivityRecognizerTags(@NonNull String activityRecognizer) {
        final int flags = PackageManager.GET_SERVICES
                | PackageManager.GET_META_DATA
                | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
                | PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;

        final Intent intent = new Intent(Intent.ACTION_ACTIVITY_RECOGNIZER);
        intent.setPackage(activityRecognizer);
        final ResolveInfo resolvedService = mContext.getPackageManager()
                .resolveServiceAsUser(intent, flags, UserHandle.USER_SYSTEM);
        if (resolvedService == null || resolvedService.serviceInfo == null) {
            Log.w(LOG_TAG, "Service recognizer doesn't handle "
                    + Intent.ACTION_ACTIVITY_RECOGNIZER +  ", ignoring!");
            return;
        }
        final Bundle metaData = resolvedService.serviceInfo.metaData;
        if (metaData == null) {
            return;
        }
        final String tagsList = metaData.getString(ACTIVITY_RECOGNITION_TAGS);
        if (!TextUtils.isEmpty(tagsList)) {
            PackageTagsList packageTagsList = new PackageTagsList.Builder(1).add(
                    resolvedService.serviceInfo.packageName,
                    Arrays.asList(tagsList.split(ACTIVITY_RECOGNITION_TAGS_SEPARATOR))).build();
            synchronized (mLock) {
                updateAllowListedTagsForPackageLocked(
                        UserHandle.getAppId(resolvedService.serviceInfo.applicationInfo.uid),
                        packageTagsList,
                        mActivityRecognitionTags);
            }
        }
    }

    private static void updateAllowListedTagsForPackageLocked(int appId,
            PackageTagsList packageTagsList,
            ConcurrentHashMap<Integer, PackageTagsList> datastore) {
        datastore.put(appId, packageTagsList);
    }

    private static boolean isDatasourceAttributionTag(int uid, @NonNull String packageName,
            @NonNull String attributionTag, @NonNull Map<Integer, PackageTagsList> mappedOps) {
        // Only a single lookup from the underlying concurrent data structure
        final PackageTagsList appIdTags = mappedOps.get(UserHandle.getAppId(uid));
        return appIdTags != null && appIdTags.contains(packageName, attributionTag);
    }

    private static int resolveLocationOp(int code) {
        switch (code) {
            case AppOpsManager.OP_FINE_LOCATION:
                return AppOpsManager.OP_FINE_LOCATION_SOURCE;
            case AppOpsManager.OP_COARSE_LOCATION:
                return AppOpsManager.OP_COARSE_LOCATION_SOURCE;
        }
        return code;
    }

    private static int resolveArOp(int code) {
        if (code == AppOpsManager.OP_ACTIVITY_RECOGNITION) {
            return AppOpsManager.OP_ACTIVITY_RECOGNITION_SOURCE;
        }
        return code;
    }

    private int resolveRecordAudioOp(int code, int uid) {
        if (code == AppOpsManager.OP_RECORD_AUDIO_HOTWORD) {
            if (!mIsHotwordDetectionServiceRequired) {
                return code;
            }
            // Only the HotwordDetectionService can use the RECORD_AUDIO_HOTWORD op which doesn't
            // incur the privacy indicator. Downgrade to standard RECORD_AUDIO for other processes.
            final HotwordDetectionServiceIdentity hotwordDetectionServiceIdentity =
                    mVoiceInteractionManagerInternal.getHotwordDetectionServiceIdentity();
            if (hotwordDetectionServiceIdentity != null
                    && uid == hotwordDetectionServiceIdentity.getIsolatedUid()) {
                return code;
            }
            return AppOpsManager.OP_RECORD_AUDIO;
        }
        return code;
    }

    private int resolveUid(int code, int uid) {
        // The HotwordDetectionService is an isolated service, which ordinarily cannot hold
        // permissions. So we allow it to assume the owning package identity for certain
        // operations.
        // Note: The package name coming from the audio server is already the one for the owning
        // package, so we don't need to modify it.
        if (Process.isIsolated(uid) // simple check which fails-fast for the common case
                && (code == AppOpsManager.OP_RECORD_AUDIO
                || code == AppOpsManager.OP_RECORD_AUDIO_HOTWORD)) {
            final HotwordDetectionServiceIdentity hotwordDetectionServiceIdentity =
                    mVoiceInteractionManagerInternal.getHotwordDetectionServiceIdentity();
            if (hotwordDetectionServiceIdentity != null
                    && uid == hotwordDetectionServiceIdentity.getIsolatedUid()) {
                uid = hotwordDetectionServiceIdentity.getOwnerUid();
            }
        }
        return uid;
    }
}
