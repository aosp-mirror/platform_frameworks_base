/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.appwidget;

import static android.content.Context.KEYGUARD_SERVICE;
import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.res.Resources.ID_NULL;
import static android.provider.DeviceConfig.NAMESPACE_SYSTEMUI;

import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.AppOpsManagerInternal;
import android.app.BroadcastOptions;
import android.app.IApplicationThread;
import android.app.IServiceConnection;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.admin.DevicePolicyManagerInternal.OnCrossProfileWidgetProvidersChangeListener;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManagerInternal;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetManagerInternal;
import android.appwidget.AppWidgetProviderInfo;
import android.appwidget.PendingHostUpdate;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.Intent.FilterComparison;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.ShortcutServiceInternal;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.Point;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.service.appwidget.AppWidgetServiceDumpProto;
import android.service.appwidget.WidgetProto;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.AttributeSet;
import android.util.IntArray;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.SparseLongArray;
import android.util.TypedValue;
import android.util.Xml;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.View;
import android.widget.RemoteViews;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.SuspendedAppActivity;
import com.android.internal.app.UnlaunchableAppActivity;
import com.android.internal.appwidget.IAppWidgetHost;
import com.android.internal.appwidget.IAppWidgetService;
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.widget.IRemoteViewsFactory;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.WidgetBackupProvider;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

class AppWidgetServiceImpl extends IAppWidgetService.Stub implements WidgetBackupProvider,
        OnCrossProfileWidgetProvidersChangeListener {
    private static final String TAG = "AppWidgetServiceImpl";

    private static final boolean DEBUG = false;
    static final boolean DEBUG_PROVIDER_INFO_CACHE = true;

    private static final String OLD_KEYGUARD_HOST_PACKAGE = "android";
    private static final String NEW_KEYGUARD_HOST_PACKAGE = "com.android.keyguard";
    private static final int KEYGUARD_HOST_ID = 0x4b455947;

    private static final String STATE_FILENAME = "appwidgets.xml";

    private static final int MIN_UPDATE_PERIOD = DEBUG ? 0 : 30 * 60 * 1000; // 30 minutes

    private static final int TAG_UNDEFINED = -1;

    private static final int UNKNOWN_UID = -1;

    private static final int UNKNOWN_USER_ID = -10;

    // Bump if the stored widgets need to be upgraded.
    private static final int CURRENT_VERSION = 1;

    // Every widget update request is associated which an increasing sequence number. This is
    // used to verify which request has successfully been received by the host.
    private static final AtomicLong UPDATE_COUNTER = new AtomicLong();

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);

            if (DEBUG) {
                Slog.i(TAG, "Received broadcast: " + action + " on user " + userId);
            }

            switch (action) {
                case Intent.ACTION_MANAGED_PROFILE_AVAILABLE:
                case Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE:
                    synchronized (mLock) {
                        reloadWidgetsMaskedState(userId);
                    }
                    break;
                case Intent.ACTION_PACKAGES_SUSPENDED:
                    onPackageBroadcastReceived(intent, getSendingUserId());
                    updateWidgetPackageSuspensionMaskedState(intent, true, getSendingUserId());
                    break;
                case Intent.ACTION_PACKAGES_UNSUSPENDED:
                    onPackageBroadcastReceived(intent, getSendingUserId());
                    updateWidgetPackageSuspensionMaskedState(intent, false, getSendingUserId());
                    break;
                default:
                    onPackageBroadcastReceived(intent, getSendingUserId());
                    break;
            }
        }
    };

    // Manages persistent references to RemoteViewsServices from different App Widgets.
    private final HashMap<Pair<Integer, FilterComparison>, HashSet<Integer>>
            mRemoteViewsServicesAppWidgets = new HashMap<>();

    private final Object mLock = new Object();

    private final ArrayList<Widget> mWidgets = new ArrayList<>();
    private final ArrayList<Host> mHosts = new ArrayList<>();
    private final ArrayList<Provider> mProviders = new ArrayList<>();

    private final ArraySet<Pair<Integer, String>> mPackagesWithBindWidgetPermission =
            new ArraySet<>();

    private final SparseBooleanArray mLoadedUserIds = new SparseBooleanArray();

    private final Object mWidgetPackagesLock = new Object();
    private final SparseArray<ArraySet<String>> mWidgetPackages = new SparseArray<>();

    private BackupRestoreController mBackupRestoreController;

    private final Context mContext;

    private IPackageManager mPackageManager;
    private AlarmManager mAlarmManager;
    private UserManager mUserManager;
    private AppOpsManager mAppOpsManager;
    private KeyguardManager mKeyguardManager;
    private DevicePolicyManagerInternal mDevicePolicyManagerInternal;
    private PackageManagerInternal mPackageManagerInternal;
    private ActivityManagerInternal mActivityManagerInternal;
    private AppOpsManagerInternal mAppOpsManagerInternal;
    private UsageStatsManagerInternal mUsageStatsManagerInternal;

    private SecurityPolicy mSecurityPolicy;

    private Handler mSaveStateHandler;
    private Handler mCallbackHandler;

    private final SparseIntArray mNextAppWidgetIds = new SparseIntArray();

    private boolean mSafeMode;
    private int mMaxWidgetBitmapMemory;
    private boolean mIsProviderInfoPersisted;
    private boolean mIsCombinedBroadcastEnabled;

    // Mark widget lifecycle broadcasts as 'interactive'
    private Bundle mInteractiveBroadcast;

    AppWidgetServiceImpl(Context context) {
        mContext = context;
    }

    @RequiresPermission(android.Manifest.permission.READ_DEVICE_CONFIG)
    public void onStart() {
        mPackageManager = AppGlobals.getPackageManager();
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        mAppOpsManager = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        mKeyguardManager = (KeyguardManager) mContext.getSystemService(KEYGUARD_SERVICE);
        mDevicePolicyManagerInternal = LocalServices.getService(DevicePolicyManagerInternal.class);
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        mSaveStateHandler = BackgroundThread.getHandler();
        final ServiceThread serviceThread = new ServiceThread(TAG,
                android.os.Process.THREAD_PRIORITY_FOREGROUND, false /* allowIo */);
        serviceThread.start();
        mCallbackHandler = new CallbackHandler(serviceThread.getLooper());
        mBackupRestoreController = new BackupRestoreController();
        mSecurityPolicy = new SecurityPolicy();
        mIsProviderInfoPersisted = !ActivityManager.isLowRamDeviceStatic()
                && DeviceConfig.getBoolean(NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.PERSISTS_WIDGET_PROVIDER_INFO, true);
        mIsCombinedBroadcastEnabled = DeviceConfig.getBoolean(NAMESPACE_SYSTEMUI,
            SystemUiDeviceConfigFlags.COMBINED_BROADCAST_ENABLED, true);
        if (DEBUG_PROVIDER_INFO_CACHE && !mIsProviderInfoPersisted) {
            Slog.d(TAG, "App widget provider info will not be persisted on this device");
        }

        BroadcastOptions opts = BroadcastOptions.makeBasic();
        opts.setBackgroundActivityStartsAllowed(false);
        opts.setInteractive(true);
        mInteractiveBroadcast = opts.toBundle();

        computeMaximumWidgetBitmapMemory();
        registerBroadcastReceiver();
        registerOnCrossProfileProvidersChangedListener();

        LocalServices.addService(AppWidgetManagerInternal.class, new AppWidgetManagerLocal());
    }

    void systemServicesReady() {
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
        mAppOpsManagerInternal = LocalServices.getService(AppOpsManagerInternal.class);
        mUsageStatsManagerInternal = LocalServices.getService(UsageStatsManagerInternal.class);
    }

    private void computeMaximumWidgetBitmapMemory() {
        Display display = mContext.getDisplayNoVerify();
        Point size = new Point();
        display.getRealSize(size);
        // Cap memory usage at 1.5 times the size of the display
        // 1.5 * 4 bytes/pixel * w * h ==> 6 * w * h
        mMaxWidgetBitmapMemory = 6 * size.x * size.y;
    }

    private void registerBroadcastReceiver() {
        // Register for broadcasts about package install, etc., so we can
        // update the provider list.
        IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addDataScheme("package");
        mContext.registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL,
                packageFilter, null, mCallbackHandler);

        // Register for events related to sdcard installation.
        IntentFilter sdFilter = new IntentFilter();
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        mContext.registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL,
                sdFilter, null, mCallbackHandler);

        IntentFilter offModeFilter = new IntentFilter();
        offModeFilter.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE);
        offModeFilter.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        mContext.registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL,
                offModeFilter, null, mCallbackHandler);

        IntentFilter suspendPackageFilter = new IntentFilter();
        suspendPackageFilter.addAction(Intent.ACTION_PACKAGES_SUSPENDED);
        suspendPackageFilter.addAction(Intent.ACTION_PACKAGES_UNSUSPENDED);
        mContext.registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL,
                suspendPackageFilter, null, mCallbackHandler);
    }

    private void registerOnCrossProfileProvidersChangedListener() {
        // The device policy is an optional component.
        if (mDevicePolicyManagerInternal != null) {
            mDevicePolicyManagerInternal.addOnCrossProfileWidgetProvidersChangeListener(this);
        }
    }

    public void setSafeMode(boolean safeMode) {
        mSafeMode = safeMode;
    }

    private void onPackageBroadcastReceived(Intent intent, int userId) {
        final String action = intent.getAction();
        boolean added = false;
        boolean changed = false;
        boolean componentsModified = false;

        final String pkgList[];
        switch (action) {
            case Intent.ACTION_PACKAGES_SUSPENDED:
            case Intent.ACTION_PACKAGES_UNSUSPENDED:
                pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                changed = true;
                break;
            case Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE:
                added = true;
                // Follow through
            case Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE:
                pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                break;
            default: {
                Uri uri = intent.getData();
                if (uri == null) {
                    return;
                }
                String pkgName = uri.getSchemeSpecificPart();
                if (pkgName == null) {
                    return;
                }
                pkgList = new String[] { pkgName };
                added = Intent.ACTION_PACKAGE_ADDED.equals(action);
                changed = Intent.ACTION_PACKAGE_CHANGED.equals(action);
            }
        }
        if (pkgList == null || pkgList.length == 0) {
            return;
        }

        synchronized (mLock) {
            if (!mUserManager.isUserUnlockingOrUnlocked(userId) ||
                    isProfileWithLockedParent(userId)) {
                return;
            }
            ensureGroupStateLoadedLocked(userId, /* enforceUserUnlockingOrUnlocked */ false);

            Bundle extras = intent.getExtras();

            if (added || changed) {
                final boolean newPackageAdded = added && (extras == null
                        || !extras.getBoolean(Intent.EXTRA_REPLACING, false));

                for (String pkgName : pkgList) {
                    // Fix up the providers - add/remove/update.
                    componentsModified |= updateProvidersForPackageLocked(pkgName, userId, null);

                    // ... and see if these are hosts we've been awaiting.
                    // NOTE: We are backing up and restoring only the owner.
                    // TODO: http://b/22388012
                    if (newPackageAdded && userId == UserHandle.USER_SYSTEM) {
                        final int uid = getUidForPackage(pkgName, userId);
                        if (uid >= 0 ) {
                            resolveHostUidLocked(pkgName, uid);
                        }
                    }
                }
            } else {
                // If the package is being updated, we'll receive a PACKAGE_ADDED
                // shortly, otherwise it is removed permanently.
                final boolean packageRemovedPermanently = (extras == null
                        || !extras.getBoolean(Intent.EXTRA_REPLACING, false));

                if (packageRemovedPermanently) {
                    for (String pkgName : pkgList) {
                        componentsModified |= removeHostsAndProvidersForPackageLocked(
                                pkgName, userId);
                    }
                }
            }

            if (componentsModified) {
                saveGroupStateAsync(userId);

                // If the set of providers has been modified, notify each active AppWidgetHost
                scheduleNotifyGroupHostsForProvidersChangedLocked(userId);
                // Possibly notify any new components of widget id changes
                mBackupRestoreController.widgetComponentsChanged(userId);
            }
        }
    }

    /**
     * Reload all widgets' masked state for the given user and its associated profiles, including
     * due to user not being available and package suspension.
     * userId must be the group parent.
     */
    void reloadWidgetsMaskedStateForGroup(int userId) {
        if (!mUserManager.isUserUnlockingOrUnlocked(userId)) {
            return;
        }
        synchronized (mLock) {
            reloadWidgetsMaskedState(userId);
            int[] profileIds = mUserManager.getEnabledProfileIds(userId);
            for (int profileId : profileIds) {
                reloadWidgetsMaskedState(profileId);
            }
        }
    }

    private void reloadWidgetsMaskedState(int userId) {
        final long identity = Binder.clearCallingIdentity();
        try {
            UserInfo user  = mUserManager.getUserInfo(userId);

            boolean lockedProfile = !mUserManager.isUserUnlockingOrUnlocked(userId);
            boolean quietProfile = user.isQuietModeEnabled();
            final int N = mProviders.size();
            for (int i = 0; i < N; i++) {
                Provider provider = mProviders.get(i);
                int providerUserId = provider.getUserId();
                if (providerUserId != userId) {
                    continue;
                }

                boolean changed = provider.setMaskedByLockedProfileLocked(lockedProfile);
                changed |= provider.setMaskedByQuietProfileLocked(quietProfile);
                try {
                    boolean suspended;
                    try {
                        suspended = mPackageManager.isPackageSuspendedForUser(
                                provider.id.componentName.getPackageName(), provider.getUserId());
                    } catch (IllegalArgumentException ex) {
                        // Package not found.
                        suspended = false;
                    }
                    changed |= provider.setMaskedBySuspendedPackageLocked(suspended);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to query application info", e);
                }
                if (changed) {
                    if (provider.isMaskedLocked()) {
                        maskWidgetsViewsLocked(provider, null);
                    } else {
                        unmaskWidgetsViewsLocked(provider);
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Incrementally update the masked state due to package suspension state.
     */
    private void updateWidgetPackageSuspensionMaskedState(Intent intent, boolean suspended,
            int profileId) {
        String[] packagesArray = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
        if (packagesArray == null) {
            return;
        }
        Set<String> packages = new ArraySet<>(Arrays.asList(packagesArray));
        synchronized (mLock) {
            final int N = mProviders.size();
            for (int i = 0; i < N; i++) {
                Provider provider = mProviders.get(i);
                int providerUserId = provider.getUserId();
                if (providerUserId != profileId
                        || !packages.contains(provider.id.componentName.getPackageName())) {
                    continue;
                }
                if (provider.setMaskedBySuspendedPackageLocked(suspended)) {
                    if (provider.isMaskedLocked()) {
                        maskWidgetsViewsLocked(provider, null);
                    } else {
                        unmaskWidgetsViewsLocked(provider);
                    }
                }
            }
        }
    }

    /**
     * Mask the target widget belonging to the specified provider, or all active widgets
     * of the provider if target widget == null.
     */
    private void maskWidgetsViewsLocked(Provider provider, Widget targetWidget) {
        final int widgetCount = provider.widgets.size();
        if (widgetCount == 0) {
            return;
        }
        RemoteViews views = new RemoteViews(mContext.getPackageName(),
                R.layout.work_widget_mask_view);
        ApplicationInfo appInfo = provider.info.providerInfo.applicationInfo;
        final int appUserId = provider.getUserId();
        boolean showBadge;

        final long identity = Binder.clearCallingIdentity();
        try {
            final Intent onClickIntent;

            if (provider.maskedByQuietProfile) {
                showBadge = true;
                onClickIntent = UnlaunchableAppActivity.createInQuietModeDialogIntent(appUserId);
            } else if (provider.maskedBySuspendedPackage) {
                showBadge = mUserManager.hasBadge(appUserId);
                final String suspendingPackage = mPackageManagerInternal.getSuspendingPackage(
                        appInfo.packageName, appUserId);
                // TODO(b/281839596): don't rely on platform always meaning suspended by admin.
                if (PLATFORM_PACKAGE_NAME.equals(suspendingPackage)) {
                    onClickIntent = mDevicePolicyManagerInternal.createShowAdminSupportIntent(
                            appUserId, true);
                } else {
                    final SuspendDialogInfo dialogInfo =
                            mPackageManagerInternal.getSuspendedDialogInfo(
                                    appInfo.packageName, suspendingPackage, appUserId);
                    // onUnsuspend is null because we don't want to start any activity on
                    // unsuspending from a suspended widget.
                    onClickIntent = SuspendedAppActivity.createSuspendedAppInterceptIntent(
                            appInfo.packageName, suspendingPackage, dialogInfo, null, null,
                            appUserId);
                }
            } else /* provider.maskedByLockedProfile */ {
                showBadge = true;
                onClickIntent = mKeyguardManager
                        .createConfirmDeviceCredentialIntent(null, null, appUserId);
                if (onClickIntent != null) {
                    onClickIntent.setFlags(
                            FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                }
            }

            Icon icon = appInfo.icon != 0
                    ? Icon.createWithResource(appInfo.packageName, appInfo.icon)
                    : Icon.createWithResource(mContext, android.R.drawable.sym_def_app_icon);
            views.setImageViewIcon(R.id.work_widget_app_icon, icon);
            if (!showBadge) {
                views.setViewVisibility(R.id.work_widget_badge_icon, View.INVISIBLE);
            }

            for (int j = 0; j < widgetCount; j++) {
                Widget widget = provider.widgets.get(j);
                if (targetWidget != null && targetWidget != widget) continue;
                if (onClickIntent != null) {
                    views.setOnClickPendingIntent(android.R.id.background,
                            PendingIntent.getActivity(mContext, widget.appWidgetId, onClickIntent,
                                    PendingIntent.FLAG_UPDATE_CURRENT
                                       | PendingIntent.FLAG_IMMUTABLE));
                }
                if (widget.replaceWithMaskedViewsLocked(views)) {
                    scheduleNotifyUpdateAppWidgetLocked(widget, widget.getEffectiveViewsLocked());
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void unmaskWidgetsViewsLocked(Provider provider) {
        final int widgetCount = provider.widgets.size();
        for (int j = 0; j < widgetCount; j++) {
            Widget widget = provider.widgets.get(j);
            if (widget.clearMaskedViewsLocked()) {
                scheduleNotifyUpdateAppWidgetLocked(widget, widget.getEffectiveViewsLocked());
            }
        }
    }

    private void resolveHostUidLocked(String pkg, int uid) {
        final int N = mHosts.size();
        for (int i = 0; i < N; i++) {
            Host host = mHosts.get(i);
            if (host.id.uid == UNKNOWN_UID && pkg.equals(host.id.packageName)) {
                if (DEBUG) {
                    Slog.i(TAG, "host " + host.id + " resolved to uid " + uid);
                }
                host.id = new HostId(uid, host.id.hostId, host.id.packageName);
                return;
            }
        }
    }

    @GuardedBy("mLock")
    private void ensureGroupStateLoadedLocked(int userId) {
        ensureGroupStateLoadedLocked(userId, /* enforceUserUnlockingOrUnlocked */ true );
    }

    @GuardedBy("mLock")
    private void ensureGroupStateLoadedLocked(int userId, boolean enforceUserUnlockingOrUnlocked) {
        if (enforceUserUnlockingOrUnlocked && !isUserRunningAndUnlocked(userId)) {
            throw new IllegalStateException(
                    "User " + userId + " must be unlocked for widgets to be available");
        }
        if (enforceUserUnlockingOrUnlocked && isProfileWithLockedParent(userId)) {
            throw new IllegalStateException(
                    "Profile " + userId + " must have unlocked parent");
        }
        final int[] profileIds = mSecurityPolicy.getEnabledGroupProfileIds(userId);

        IntArray newIds = new IntArray(1);
        for (int profileId : profileIds) {
            if (!mLoadedUserIds.get(profileId)) {
                mLoadedUserIds.put(profileId, true);
                newIds.add(profileId);
            }
        }
        if (newIds.size() <= 0) {
            return;
        }
        final int[] newProfileIds = newIds.toArray();
        clearProvidersAndHostsTagsLocked();

        loadGroupWidgetProvidersLocked(newProfileIds);
        loadGroupStateLocked(newProfileIds);
    }

    private boolean isUserRunningAndUnlocked(@UserIdInt int userId) {
        return mUserManager.isUserUnlockingOrUnlocked(userId);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        synchronized (mLock) {
            if (args.length > 0 && "--proto".equals(args[0])) {
                dumpProto(fd);
            } else {
                dumpInternalLocked(pw);
            }
        }
    }

    private void dumpProto(FileDescriptor fd) {
        Slog.i(TAG, "dump proto for " + mWidgets.size() + " widgets");

        ProtoOutputStream proto = new ProtoOutputStream(fd);
        int N = mWidgets.size();
        for (int i=0; i < N; i++) {
            dumpProtoWidget(proto, mWidgets.get(i));
        }
        proto.flush();
    }

    private void dumpProtoWidget(ProtoOutputStream proto, Widget widget) {
        if (widget.host == null || widget.provider == null) {
            Slog.d(TAG, "skip dumping widget because host or provider is null: widget.host="
                + widget.host + " widget.provider="  + widget.provider);
            return;
        }
        long token = proto.start(AppWidgetServiceDumpProto.WIDGETS);
        proto.write(WidgetProto.IS_CROSS_PROFILE,
            widget.host.getUserId() != widget.provider.getUserId());
        proto.write(WidgetProto.IS_HOST_STOPPED, widget.host.callbacks == null);
        proto.write(WidgetProto.HOST_PACKAGE, widget.host.id.packageName);
        proto.write(WidgetProto.PROVIDER_PACKAGE, widget.provider.id.componentName.getPackageName());
        proto.write(WidgetProto.PROVIDER_CLASS, widget.provider.id.componentName.getClassName());
        if (widget.options != null) {
            proto.write(WidgetProto.RESTORE_COMPLETED,
                    widget.options.getBoolean(AppWidgetManager.OPTION_APPWIDGET_RESTORE_COMPLETED));
            proto.write(WidgetProto.MIN_WIDTH,
                widget.options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0));
            proto.write(WidgetProto.MIN_HEIGHT,
                widget.options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0));
            proto.write(WidgetProto.MAX_WIDTH,
                widget.options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0));
            proto.write(WidgetProto.MAX_HEIGHT,
                widget.options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0));
        }
        proto.end(token);
    }

    private void dumpInternalLocked(PrintWriter pw) {
        int N = mProviders.size();
        pw.println("Providers:");
        for (int i = 0; i < N; i++) {
            dumpProviderLocked(mProviders.get(i), i, pw);
        }

        N = mWidgets.size();
        pw.println(" ");
        pw.println("Widgets:");
        for (int i = 0; i < N; i++) {
            dumpWidget(mWidgets.get(i), i, pw);
        }

        N = mHosts.size();
        pw.println(" ");
        pw.println("Hosts:");
        for (int i = 0; i < N; i++) {
            dumpHost(mHosts.get(i), i, pw);
        }

        N = mPackagesWithBindWidgetPermission.size();
        pw.println(" ");
        pw.println("Grants:");
        for (int i = 0; i < N; i++) {
            Pair<Integer, String> grant = mPackagesWithBindWidgetPermission.valueAt(i);
            dumpGrant(grant, i, pw);
        }
    }

    @Override
    public ParceledListSlice<PendingHostUpdate> startListening(IAppWidgetHost callbacks,
            String callingPackage, int hostId, int[] appWidgetIds) {
        final int userId = UserHandle.getCallingUserId();
        if (DEBUG) {
            Slog.i(TAG, "startListening() " + userId);
        }

        // Make sure the package runs under the caller uid.
        mSecurityPolicy.enforceCallFromPackage(callingPackage);

        synchronized (mLock) {
            // Instant apps cannot host app widgets.
            if (mSecurityPolicy.isInstantAppLocked(callingPackage, userId)) {
                Slog.w(TAG, "Instant package " + callingPackage + " cannot host app widgets");
                return ParceledListSlice.emptyList();
            }

            ensureGroupStateLoadedLocked(userId);

            // NOTE: The lookup is enforcing security across users by making
            // sure the caller can only access hosts it owns.
            HostId id = new HostId(Binder.getCallingUid(), hostId, callingPackage);
            Host host = lookupOrAddHostLocked(id);
            host.callbacks = callbacks;

            long updateSequenceNo = UPDATE_COUNTER.incrementAndGet();
            int N = appWidgetIds.length;
            ArrayList<PendingHostUpdate> outUpdates = new ArrayList<>(N);
            LongSparseArray<PendingHostUpdate> updatesMap = new LongSparseArray<>();
            for (int i = 0; i < N; i++) {
                updatesMap.clear();
                host.getPendingUpdatesForIdLocked(mContext, appWidgetIds[i], updatesMap);
                // We key the updates based on request id, so that the values are sorted in the
                // order they were received.
                int m = updatesMap.size();
                for (int j = 0; j < m; j++) {
                    outUpdates.add(updatesMap.valueAt(j));
                }
            }
            // Reset the update counter once all the updates have been calculated
            host.lastWidgetUpdateSequenceNo = updateSequenceNo;
            return new ParceledListSlice<>(outUpdates);
        }
    }

    @Override
    public void stopListening(String callingPackage, int hostId) {
        final int userId = UserHandle.getCallingUserId();

        if (DEBUG) {
            Slog.i(TAG, "stopListening() " + userId);
        }

        // Make sure the package runs under the caller uid.
        mSecurityPolicy.enforceCallFromPackage(callingPackage);

        synchronized (mLock) {
            ensureGroupStateLoadedLocked(userId, /* enforceUserUnlockingOrUnlocked */ false);

            // NOTE: The lookup is enforcing security across users by making
            // sure the caller can only access hosts it owns.
            HostId id = new HostId(Binder.getCallingUid(), hostId, callingPackage);
            Host host = lookupHostLocked(id);

            if (host != null) {
                host.callbacks = null;
                pruneHostLocked(host);
                mAppOpsManagerInternal.updateAppWidgetVisibility(host.getWidgetUidsIfBound(),
                        false);
            }
        }
    }

    @Override
    public int allocateAppWidgetId(String callingPackage, int hostId) {
        final int userId = UserHandle.getCallingUserId();

        if (DEBUG) {
            Slog.i(TAG, "allocateAppWidgetId() " + userId);
        }

        // Make sure the package runs under the caller uid.
        mSecurityPolicy.enforceCallFromPackage(callingPackage);

        synchronized (mLock) {
            // Instant apps cannot host app widgets.
            if (mSecurityPolicy.isInstantAppLocked(callingPackage, userId)) {
                Slog.w(TAG, "Instant package " + callingPackage + " cannot host app widgets");
                return AppWidgetManager.INVALID_APPWIDGET_ID;
            }

            ensureGroupStateLoadedLocked(userId);

            if (mNextAppWidgetIds.indexOfKey(userId) < 0) {
                mNextAppWidgetIds.put(userId, AppWidgetManager.INVALID_APPWIDGET_ID + 1);
            }

            final int appWidgetId = incrementAndGetAppWidgetIdLocked(userId);

            // NOTE: The lookup is enforcing security across users by making
            // sure the caller can only access hosts it owns.
            HostId id = new HostId(Binder.getCallingUid(), hostId, callingPackage);
            Host host = lookupOrAddHostLocked(id);

            Widget widget = new Widget();
            widget.appWidgetId = appWidgetId;
            widget.host = host;

            host.widgets.add(widget);
            addWidgetLocked(widget);

            saveGroupStateAsync(userId);

            if (DEBUG) {
                Slog.i(TAG, "Allocated widget id " + appWidgetId
                        + " for host " + host.id);
            }

            return appWidgetId;
        }
    }

    @Override
    public void setAppWidgetHidden(String callingPackage, int hostId) {
        final int userId = UserHandle.getCallingUserId();

        if (DEBUG) {
            Slog.i(TAG, "setAppWidgetHidden() " + userId);
        }

        mSecurityPolicy.enforceCallFromPackage(callingPackage);

        synchronized (mLock) {
            ensureGroupStateLoadedLocked(userId, /* enforceUserUnlockingOrUnlocked */false);

            HostId id = new HostId(Binder.getCallingUid(), hostId, callingPackage);
            Host host = lookupHostLocked(id);

            if (host != null) {
                mAppOpsManagerInternal.updateAppWidgetVisibility(host.getWidgetUidsIfBound(),
                        false);
            }
        }
    }

    @Override
    public void deleteAppWidgetId(String callingPackage, int appWidgetId) {
        final int userId = UserHandle.getCallingUserId();

        if (DEBUG) {
            Slog.i(TAG, "deleteAppWidgetId() " + userId);
        }

        // Make sure the package runs under the caller uid.
        mSecurityPolicy.enforceCallFromPackage(callingPackage);

        synchronized (mLock) {
            ensureGroupStateLoadedLocked(userId);

            // NOTE: The lookup is enforcing security across users by making
            // sure the caller can only access widgets it hosts or provides.
            Widget widget = lookupWidgetLocked(appWidgetId,
                    Binder.getCallingUid(), callingPackage);

            if (widget == null) {
                return;
            }

            deleteAppWidgetLocked(widget);

            saveGroupStateAsync(userId);

            if (DEBUG) {
                Slog.i(TAG, "Deleted widget id " + appWidgetId
                        + " for host " + widget.host.id);
            }
        }
    }

    @Override
    public boolean hasBindAppWidgetPermission(String packageName, int grantId) {
        if (DEBUG) {
            Slog.i(TAG, "hasBindAppWidgetPermission() " + UserHandle.getCallingUserId());
        }

        // A special permission is required for managing allowlisting.
        mSecurityPolicy.enforceModifyAppWidgetBindPermissions(packageName);

        synchronized (mLock) {
            // The grants are stored in user state wich gets the grant.
            ensureGroupStateLoadedLocked(grantId);

            final int packageUid = getUidForPackage(packageName, grantId);
            if (packageUid < 0) {
                return false;
            }

            Pair<Integer, String> packageId = Pair.create(grantId, packageName);
            return mPackagesWithBindWidgetPermission.contains(packageId);
        }
    }

    @Override
    public void setBindAppWidgetPermission(String packageName, int grantId,
            boolean grantPermission) {
        if (DEBUG) {
            Slog.i(TAG, "setBindAppWidgetPermission() " + UserHandle.getCallingUserId());
        }

        // A special permission is required for managing allowlisting.
        mSecurityPolicy.enforceModifyAppWidgetBindPermissions(packageName);

        synchronized (mLock) {
            // The grants are stored in user state wich gets the grant.
            ensureGroupStateLoadedLocked(grantId);

            final int packageUid = getUidForPackage(packageName, grantId);
            if (packageUid < 0) {
                return;
            }

            Pair<Integer, String> packageId = Pair.create(grantId, packageName);
            if (grantPermission) {
                mPackagesWithBindWidgetPermission.add(packageId);
            } else {
                mPackagesWithBindWidgetPermission.remove(packageId);
            }

            saveGroupStateAsync(grantId);
        }
    }

    @Override
    public IntentSender createAppWidgetConfigIntentSender(String callingPackage, int appWidgetId,
            final int intentFlags) {
        final int userId = UserHandle.getCallingUserId();

        if (DEBUG) {
            Slog.i(TAG, "createAppWidgetConfigIntentSender() " + userId);
        }

        // Make sure the package runs under the caller uid.
        mSecurityPolicy.enforceCallFromPackage(callingPackage);

        synchronized (mLock) {
            ensureGroupStateLoadedLocked(userId);

            // NOTE: The lookup is enforcing security across users by making
            // sure the caller can only access widgets it hosts or provides.
            Widget widget = lookupWidgetLocked(appWidgetId,
                    Binder.getCallingUid(), callingPackage);

            if (widget == null) {
                throw new IllegalArgumentException("Bad widget id " + appWidgetId);
            }

            Provider provider = widget.provider;
            if (provider == null) {
                throw new IllegalArgumentException("Widget not bound " + appWidgetId);
            }

            // Make sure only safe flags can be passed it.
            final int secureFlags = intentFlags & ~Intent.IMMUTABLE_FLAGS;

            Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            intent.setComponent(provider.getInfoLocked(mContext).configure);
            intent.setFlags(secureFlags);

            final ActivityOptions options =
                    ActivityOptions.makeBasic().setPendingIntentCreatorBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED);

            // All right, create the sender.
            final long identity = Binder.clearCallingIdentity();
            try {
                return PendingIntent.getActivityAsUser(
                        mContext, 0, intent, PendingIntent.FLAG_ONE_SHOT
                                | PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT,
                                options.toBundle(), new UserHandle(provider.getUserId()))
                        .getIntentSender();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public boolean bindAppWidgetId(String callingPackage, int appWidgetId,
            int providerProfileId, ComponentName providerComponent, Bundle options) {
        final int userId = UserHandle.getCallingUserId();

        if (DEBUG) {
            Slog.i(TAG, "bindAppWidgetId() " + userId);
        }

        // Make sure the package runs under the caller uid.
        mSecurityPolicy.enforceCallFromPackage(callingPackage);

        // Check that if a cross-profile binding is attempted, it is allowed.
        if (!mSecurityPolicy.isEnabledGroupProfile(providerProfileId)) {
            return false;
        }

        // If the provider is not under the calling user, make sure this
        // provider is allowlisted for access from the parent.
        if (!mSecurityPolicy.isProviderInCallerOrInProfileAndWhitelListed(
                providerComponent.getPackageName(), providerProfileId)) {
            return false;
        }

        synchronized (mLock) {
            ensureGroupStateLoadedLocked(userId);

            // A special permission or allowlisting is required to bind widgets.
            if (!mSecurityPolicy.hasCallerBindPermissionOrBindWhiteListedLocked(
                    callingPackage)) {
                return false;
            }

            // NOTE: The lookup is enforcing security across users by making
            // sure the caller can only access widgets it hosts or provides.
            Widget widget = lookupWidgetLocked(appWidgetId,
                    Binder.getCallingUid(), callingPackage);

            if (widget == null) {
                Slog.e(TAG, "Bad widget id " + appWidgetId);
                return false;
            }

            if (widget.provider != null) {
                Slog.e(TAG, "Widget id " + appWidgetId
                        + " already bound to: " + widget.provider.id);
                return false;
            }

            final int providerUid = getUidForPackage(providerComponent.getPackageName(),
                    providerProfileId);
            if (providerUid < 0) {
                Slog.e(TAG, "Package " + providerComponent.getPackageName() + " not installed "
                        + " for profile " + providerProfileId);
                return false;
            }

            // NOTE: The lookup is enforcing security across users by making
            // sure the provider is in the already vetted user profile.
            ProviderId providerId = new ProviderId(providerUid, providerComponent);
            Provider provider = lookupProviderLocked(providerId);

            if (provider == null) {
                Slog.e(TAG, "No widget provider " + providerComponent + " for profile "
                        + providerProfileId);
                return false;
            }

            if (provider.zombie) {
                Slog.e(TAG, "Can't bind to a 3rd party provider in"
                        + " safe mode " + provider);
                return false;
            }

            widget.provider = provider;
            widget.options = (options != null) ? cloneIfLocalBinder(options) : new Bundle();

            // We need to provide a default value for the widget category if it is not specified
            if (!widget.options.containsKey(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY)) {
                widget.options.putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                        AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN);
            }

            provider.widgets.add(widget);

            onWidgetProviderAddedOrChangedLocked(widget);

            final int widgetCount = provider.widgets.size();
            if (widgetCount == 1) {
                // If we are binding the very first widget from a provider, we will send
                // a combined broadcast or 2 separate broadcasts to tell the provider that
                // it's ready, and we need them to provide the update now.
                sendEnableAndUpdateIntentLocked(provider, new int[]{appWidgetId});
            } else {
                // For any widget other then the first one, we just send update intent
                // as we normally would.
                sendUpdateIntentLocked(provider, new int[]{appWidgetId}, true);
            }

            // Schedule the future updates.
            registerForBroadcastsLocked(provider, getWidgetIds(provider.widgets));

            saveGroupStateAsync(userId);
            Slog.i(TAG, "Bound widget " + appWidgetId + " to provider " + provider.id);
        }

        return true;
    }

    @Override
    public int[] getAppWidgetIds(ComponentName componentName) {
        final int userId = UserHandle.getCallingUserId();

        if (DEBUG) {
            Slog.i(TAG, "getAppWidgetIds() " + userId);
        }

        // Make sure the package runs under the caller uid.
        mSecurityPolicy.enforceCallFromPackage(componentName.getPackageName());

        synchronized (mLock) {
            ensureGroupStateLoadedLocked(userId);

            // NOTE: The lookup is enforcing security across users by making
            // sure the caller can access only its providers.
            ProviderId providerId = new ProviderId(Binder.getCallingUid(), componentName);
            Provider provider = lookupProviderLocked(providerId);

            if (provider != null) {
                return getWidgetIds(provider.widgets);
            }

            return new int[0];
        }
    }

    @Override
    public int[] getAppWidgetIdsForHost(String callingPackage, int hostId) {
        final int userId = UserHandle.getCallingUserId();

        if (DEBUG) {
            Slog.i(TAG, "getAppWidgetIdsForHost() " + userId);
        }

        // Make sure the package runs under the caller uid.
        mSecurityPolicy.enforceCallFromPackage(callingPackage);

        synchronized (mLock) {
            ensureGroupStateLoadedLocked(userId);

            // NOTE: The lookup is enforcing security across users by making
            // sure the caller can only access its hosts.
            HostId id = new HostId(Binder.getCallingUid(), hostId, callingPackage);
            Host host = lookupHostLocked(id);

            if (host != null) {
                return getWidgetIds(host.widgets);
            }

            return new int[0];
        }
    }

    @Override
    public boolean bindRemoteViewsService(String callingPackage, int appWidgetId, Intent intent,
            IApplicationThread caller, IBinder activtiyToken, IServiceConnection connection,
            long flags) {
        final int userId = UserHandle.getCallingUserId();
        if (DEBUG) {
            Slog.i(TAG, "bindRemoteViewsService() " + userId);
        }

        synchronized (mLock) {
            ensureGroupStateLoadedLocked(userId);

            // NOTE: The lookup is enforcing security across users by making
            // sure the caller can only access widgets it hosts or provides.
            Widget widget = lookupWidgetLocked(appWidgetId,
                    Binder.getCallingUid(), callingPackage);

            if (widget == null) {
                throw new IllegalArgumentException("Bad widget id");
            }

            // Make sure the widget has a provider.
            if (widget.provider == null) {
                throw new IllegalArgumentException("No provider for widget "
                        + appWidgetId);
            }

            ComponentName componentName = intent.getComponent();

            // Ensure that the service belongs to the same package as the provider.
            // But this is not enough as they may be under different users - see below...
            String providerPackage = widget.provider.id.componentName.getPackageName();
            String servicePackage = componentName.getPackageName();
            if (!servicePackage.equals(providerPackage)) {
                throw new SecurityException("The taget service not in the same package"
                        + " as the widget provider");
            }

            // Make sure this service exists under the same user as the provider and
            // requires a permission which allows only the system to bind to it.
            mSecurityPolicy.enforceServiceExistsAndRequiresBindRemoteViewsPermission(
                    componentName, widget.provider.getUserId());

            // Good to go - the service package is correct, it exists for the correct
            // user, and requires the bind permission.

            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                // Ask ActivityManager to bind it. Notice that we are binding the service with the
                // caller app instead of DevicePolicyManagerService.
                if (ActivityManager.getService().bindService(
                        caller, activtiyToken, intent,
                        intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                        connection, flags & (Context.BIND_AUTO_CREATE
                                | Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE),
                        mContext.getOpPackageName(), widget.provider.getUserId()) != 0) {

                    // Add it to the mapping of RemoteViewsService to appWidgetIds so that we
                    // can determine when we can call back to the RemoteViewsService later to
                    // destroy associated factories.
                    incrementAppWidgetServiceRefCount(appWidgetId,
                            Pair.create(widget.provider.id.uid, new FilterComparison(intent)));
                    return true;
                }
            } catch (RemoteException ex) {
                // Same process, should not happen.
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
        }

        // Failed to bind.
        return false;
    }

    @Override
    public void deleteHost(String callingPackage, int hostId) {
        final int userId = UserHandle.getCallingUserId();

        if (DEBUG) {
            Slog.i(TAG, "deleteHost() " + userId);
        }

        // Make sure the package runs under the caller uid.
        mSecurityPolicy.enforceCallFromPackage(callingPackage);

        synchronized (mLock) {
            ensureGroupStateLoadedLocked(userId);

            // NOTE: The lookup is enforcing security across users by making
            // sure the caller can only access hosts in its uid and package.
            HostId id = new HostId(Binder.getCallingUid(), hostId, callingPackage);
            Host host = lookupHostLocked(id);

            if (host == null) {
                return;
            }

            deleteHostLocked(host);

            saveGroupStateAsync(userId);

            if (DEBUG) {
                Slog.i(TAG, "Deleted host " + host.id);
            }
        }
    }

    @Override
    public void deleteAllHosts() {
        final int userId = UserHandle.getCallingUserId();

        if (DEBUG) {
            Slog.i(TAG, "deleteAllHosts() " + userId);
        }

        synchronized (mLock) {
            ensureGroupStateLoadedLocked(userId);

            boolean changed = false;

            final int N = mHosts.size();
            for (int i = N - 1; i >= 0; i--) {
                Host host = mHosts.get(i);

                // Delete only hosts in the calling uid.
                if (host.id.uid == Binder.getCallingUid()) {
                    deleteHostLocked(host);
                    changed = true;

                    if (DEBUG) {
                        Slog.i(TAG, "Deleted host " + host.id);
                    }
                }
            }

            if (changed) {
                saveGroupStateAsync(userId);
            }
        }
    }

    @Override
    public AppWidgetProviderInfo getAppWidgetInfo(String callingPackage, int appWidgetId) {
        final int userId = UserHandle.getCallingUserId();

        if (DEBUG) {
            Slog.i(TAG, "getAppWidgetInfo() " + userId);
        }

        // Make sure the package runs under the caller uid.
        mSecurityPolicy.enforceCallFromPackage(callingPackage);

        synchronized (mLock) {
            ensureGroupStateLoadedLocked(userId);

            // NOTE: The lookup is enforcing security across users by making
            // sure the caller can only access widgets it hosts or provides.
            Widget widget = lookupWidgetLocked(appWidgetId,
                    Binder.getCallingUid(), callingPackage);

            if (widget != null && widget.provider != null && !widget.provider.zombie) {
                return cloneIfLocalBinder(widget.provider.getInfoLocked(mContext));
            }

            return null;
        }
    }

    @Override
    public RemoteViews getAppWidgetViews(String callingPackage, int appWidgetId) {
        final int userId = UserHandle.getCallingUserId();

        if (DEBUG) {
            Slog.i(TAG, "getAppWidgetViews() " + userId);
        }

        // Make sure the package runs under the caller uid.
        mSecurityPolicy.enforceCallFromPackage(callingPackage);

        synchronized (mLock) {
            ensureGroupStateLoadedLocked(userId);

            // NOTE: The lookup is enforcing security across users by making
            // sure the caller can only access widgets it hosts or provides.
            Widget widget = lookupWidgetLocked(appWidgetId,
                    Binder.getCallingUid(), callingPackage);

            if (widget != null) {
                return cloneIfLocalBinder(widget.getEffectiveViewsLocked());
            }

            return null;
        }
    }

    @Override
    public void updateAppWidgetOptions(String callingPackage, int appWidgetId, Bundle options) {
        final int userId = UserHandle.getCallingUserId();

        if (DEBUG) {
            Slog.i(TAG, "updateAppWidgetOptions() " + userId);
        }

        // Make sure the package runs under the caller uid.
        mSecurityPolicy.enforceCallFromPackage(callingPackage);

        synchronized (mLock) {
            ensureGroupStateLoadedLocked(userId);

            // NOTE: The lookup is enforcing security across users by making
            // sure the caller can only access widgets it hosts or provides.
            Widget widget = lookupWidgetLocked(appWidgetId,
                    Binder.getCallingUid(), callingPackage);

            if (widget == null) {
                return;
            }

            // Merge the options.
            widget.options.putAll(options);

            // Send the broacast to notify the provider that options changed.
            sendOptionsChangedIntentLocked(widget);

            saveGroupStateAsync(userId);
        }
    }

    @Override
    public Bundle getAppWidgetOptions(String callingPackage, int appWidgetId) {
        final int userId = UserHandle.getCallingUserId();

        if (DEBUG) {
            Slog.i(TAG, "getAppWidgetOptions() " + userId);
        }

        // Make sure the package runs under the caller uid.
        mSecurityPolicy.enforceCallFromPackage(callingPackage);

        synchronized (mLock) {
            ensureGroupStateLoadedLocked(userId);

            // NOTE: The lookup is enforcing security across users by making
            // sure the caller can only access widgets it hosts or provides.
            Widget widget = lookupWidgetLocked(appWidgetId,
                    Binder.getCallingUid(), callingPackage);

            if (widget != null && widget.options != null) {
                return cloneIfLocalBinder(widget.options);
            }

            return Bundle.EMPTY;
        }
    }

    @Override
    public void updateAppWidgetIds(String callingPackage, int[] appWidgetIds,
            RemoteViews views) {
        if (DEBUG) {
            Slog.i(TAG, "updateAppWidgetIds() " + UserHandle.getCallingUserId());
        }

        updateAppWidgetIds(callingPackage, appWidgetIds, views, false);
    }

    @Override
    public void partiallyUpdateAppWidgetIds(String callingPackage, int[] appWidgetIds,
            RemoteViews views) {
        if (DEBUG) {
            Slog.i(TAG, "partiallyUpdateAppWidgetIds() " + UserHandle.getCallingUserId());
        }

        updateAppWidgetIds(callingPackage, appWidgetIds, views, true);
    }

    @Override
    public void notifyProviderInheritance(@Nullable final ComponentName[] componentNames) {
        final int userId = UserHandle.getCallingUserId();
        if (DEBUG) {
            Slog.i(TAG, "notifyProviderInheritance() " + userId);
        }

        if (componentNames == null) {
            return;
        }

        for (ComponentName componentName : componentNames) {
            if (componentName == null) {
                return;
            }
            mSecurityPolicy.enforceCallFromPackage(componentName.getPackageName());
        }
        synchronized (mLock) {
            ensureGroupStateLoadedLocked(userId);

            for (ComponentName componentName : componentNames) {
                final ProviderId providerId = new ProviderId(Binder.getCallingUid(), componentName);
                final Provider provider = lookupProviderLocked(providerId);

                if (provider == null || provider.info == null) {
                    return;
                }

                provider.info.isExtendedFromAppWidgetProvider = true;
            }
            saveGroupStateAsync(userId);
        }
    }

    @Override
    public void notifyAppWidgetViewDataChanged(String callingPackage, int[] appWidgetIds,
            int viewId) {
        final int userId = UserHandle.getCallingUserId();

        if (DEBUG) {
            Slog.i(TAG, "notifyAppWidgetViewDataChanged() " + userId);
        }

        // Make sure the package runs under the caller uid.
        mSecurityPolicy.enforceCallFromPackage(callingPackage);

        if (appWidgetIds == null || appWidgetIds.length == 0) {
            return;
        }

        synchronized (mLock) {
            ensureGroupStateLoadedLocked(userId);

            final int N = appWidgetIds.length;
            for (int i = 0; i < N; i++) {
                final int appWidgetId = appWidgetIds[i];

                // NOTE: The lookup is enforcing security across users by making
                // sure the caller can only access widgets it hosts or provides.
                Widget widget = lookupWidgetLocked(appWidgetId,
                        Binder.getCallingUid(), callingPackage);

                if (widget != null) {
                    scheduleNotifyAppWidgetViewDataChanged(widget, viewId);
                }
            }
        }
    }

    @Override
    public void updateAppWidgetProvider(ComponentName componentName, RemoteViews views) {
        final int userId = UserHandle.getCallingUserId();

        if (DEBUG) {
            Slog.i(TAG, "updateAppWidgetProvider() " + userId);
        }

        // Make sure the package runs under the caller uid.
        mSecurityPolicy.enforceCallFromPackage(componentName.getPackageName());

        synchronized (mLock) {
            ensureGroupStateLoadedLocked(userId);

            // NOTE: The lookup is enforcing security across users by making
            // sure the caller can access only its providers.
            ProviderId providerId = new ProviderId(Binder.getCallingUid(), componentName);
            Provider provider = lookupProviderLocked(providerId);

            if (provider == null) {
                Slog.w(TAG, "Provider doesn't exist " + providerId);
                return;
            }

            ArrayList<Widget> instances = provider.widgets;
            final int N = instances.size();
            for (int i = 0; i < N; i++) {
                Widget widget = instances.get(i);
                updateAppWidgetInstanceLocked(widget, views, false);
            }
        }
    }

    @Override
    public void updateAppWidgetProviderInfo(ComponentName componentName, String metadataKey) {
        final int userId = UserHandle.getCallingUserId();
        if (DEBUG) {
            Slog.i(TAG, "updateAppWidgetProvider() " + userId);
        }

        // Make sure the package runs under the caller uid.
        mSecurityPolicy.enforceCallFromPackage(componentName.getPackageName());

        synchronized (mLock) {
            ensureGroupStateLoadedLocked(userId);

            // NOTE: The lookup is enforcing security across users by making
            // sure the caller can access only its providers.
            ProviderId providerId = new ProviderId(Binder.getCallingUid(), componentName);
            Provider provider = lookupProviderLocked(providerId);
            if (provider == null) {
                throw new IllegalArgumentException(
                        componentName + " is not a valid AppWidget provider");
            }
            if (Objects.equals(provider.infoTag, metadataKey)) {
                // No change
                return;
            }

            String keyToUse = metadataKey == null
                    ? AppWidgetManager.META_DATA_APPWIDGET_PROVIDER : metadataKey;
            AppWidgetProviderInfo info = parseAppWidgetProviderInfo(mContext, providerId,
                    provider.getPartialInfoLocked().providerInfo, keyToUse);
            if (info == null) {
                throw new IllegalArgumentException("Unable to parse " + keyToUse
                        + " meta-data to a valid AppWidget provider");
            }

            provider.setInfoLocked(info);
            provider.infoTag = metadataKey;

            // Update all widgets for this provider
            final int N = provider.widgets.size();
            for (int i = 0; i < N; i++) {
                Widget widget = provider.widgets.get(i);
                scheduleNotifyProviderChangedLocked(widget);
                updateAppWidgetInstanceLocked(widget, widget.views, false /* isPartialUpdate */);
            }

            saveGroupStateAsync(userId);
            scheduleNotifyGroupHostsForProvidersChangedLocked(userId);
        }
    }

    @Override
    public boolean isRequestPinAppWidgetSupported() {
        synchronized (mLock) {
            if (mSecurityPolicy.isCallerInstantAppLocked()) {
                Slog.w(TAG, "Instant uid " + Binder.getCallingUid()
                        + " query information about app widgets");
                return false;
            }
        }
        return LocalServices.getService(ShortcutServiceInternal.class)
                .isRequestPinItemSupported(UserHandle.getCallingUserId(),
                        LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET);
    }

    @Override
    public boolean requestPinAppWidget(String callingPackage, ComponentName componentName,
            Bundle extras, IntentSender resultSender) {
        final int callingUid = Binder.getCallingUid();
        final int userId = UserHandle.getUserId(callingUid);

        if (DEBUG) {
            Slog.i(TAG, "requestPinAppWidget() " + userId);
        }

        final AppWidgetProviderInfo info;

        synchronized (mLock) {
            ensureGroupStateLoadedLocked(userId);

            // Look for the widget associated with the caller.
            Provider provider = lookupProviderLocked(new ProviderId(callingUid, componentName));
            if (provider == null || provider.zombie) {
                return false;
            }
            info = provider.getInfoLocked(mContext);
            if ((info.widgetCategory & AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) == 0) {
                return false;
            }
        }

        return LocalServices.getService(ShortcutServiceInternal.class)
                .requestPinAppWidget(callingPackage, info, extras, resultSender, userId);
    }

    @Override
    public ParceledListSlice<AppWidgetProviderInfo> getInstalledProvidersForProfile(int categoryFilter,
            int profileId, String packageName) {
        final int userId = UserHandle.getCallingUserId();
        final int callingUid = Binder.getCallingUid();

        if (DEBUG) {
            Slog.i(TAG, "getInstalledProvidersForProfiles() " + userId);
        }

        // Ensure the profile is in the group and enabled.
        if (!mSecurityPolicy.isEnabledGroupProfile(profileId)) {
            return null;
        }

        synchronized (mLock) {
            if (mSecurityPolicy.isCallerInstantAppLocked()) {
                Slog.w(TAG, "Instant uid " + callingUid
                        + " cannot access widget providers");
                return ParceledListSlice.emptyList();
            }

            ensureGroupStateLoadedLocked(userId);

            ArrayList<AppWidgetProviderInfo> result = new ArrayList<AppWidgetProviderInfo>();

            final int providerCount = mProviders.size();
            for (int i = 0; i < providerCount; i++) {
                Provider provider = mProviders.get(i);
                final String providerPackageName = provider.id.componentName.getPackageName();

                // Ignore an invalid provider or one that isn't in the given package, if any.
                boolean inPackage = packageName == null || providerPackageName.equals(packageName);
                if (provider.zombie || !inPackage) {
                    continue;
                }

                // Ignore the ones not matching the filter.
                AppWidgetProviderInfo info = provider.getInfoLocked(mContext);
                if ((info.widgetCategory & categoryFilter) == 0) {
                    continue;
                }

                // Add providers only for the requested profile that are allowlisted.
                final int providerProfileId = info.getProfile().getIdentifier();
                if (providerProfileId == profileId
                        && mSecurityPolicy.isProviderInCallerOrInProfileAndWhitelListed(
                        providerPackageName, providerProfileId)
                        && !mPackageManagerInternal.filterAppAccess(providerPackageName, callingUid,
                        profileId)) {
                    result.add(cloneIfLocalBinder(info));
                }
            }

            return new ParceledListSlice<AppWidgetProviderInfo>(result);
        }
    }

    private void updateAppWidgetIds(String callingPackage, int[] appWidgetIds,
            RemoteViews views, boolean partially) {
        final int userId = UserHandle.getCallingUserId();

        if (appWidgetIds == null || appWidgetIds.length == 0) {
            return;
        }

        // Make sure the package runs under the caller uid.
        mSecurityPolicy.enforceCallFromPackage(callingPackage);
        synchronized (mLock) {
            ensureGroupStateLoadedLocked(userId);

            final int N = appWidgetIds.length;
            for (int i = 0; i < N; i++) {
                final int appWidgetId = appWidgetIds[i];

                // NOTE: The lookup is enforcing security across users by making
                // sure the caller can only access widgets it hosts or provides.
                Widget widget = lookupWidgetLocked(appWidgetId,
                        Binder.getCallingUid(), callingPackage);

                if (widget != null) {
                    updateAppWidgetInstanceLocked(widget, views, partially);
                }
            }
        }
    }

    private int incrementAndGetAppWidgetIdLocked(int userId) {
        final int appWidgetId = peekNextAppWidgetIdLocked(userId) + 1;
        mNextAppWidgetIds.put(userId, appWidgetId);
        return appWidgetId;
    }

    private void setMinAppWidgetIdLocked(int userId, int minWidgetId) {
        final int nextAppWidgetId = peekNextAppWidgetIdLocked(userId);
        if (nextAppWidgetId < minWidgetId) {
            mNextAppWidgetIds.put(userId, minWidgetId);
        }
    }

    private int peekNextAppWidgetIdLocked(int userId) {
        if (mNextAppWidgetIds.indexOfKey(userId) < 0) {
            return AppWidgetManager.INVALID_APPWIDGET_ID + 1;
        } else {
            return mNextAppWidgetIds.get(userId);
        }
    }

    private Host lookupOrAddHostLocked(HostId id) {
        Host host = lookupHostLocked(id);
        if (host != null) {
            return host;
        }

        host = new Host();
        host.id = id;
        mHosts.add(host);

        return host;
    }

    private void deleteHostLocked(Host host) {
        final int N = host.widgets.size();
        for (int i = N - 1; i >= 0; i--) {
            Widget widget = host.widgets.remove(i);
            deleteAppWidgetLocked(widget);
        }
        mHosts.remove(host);

        // it's gone or going away, abruptly drop the callback connection
        host.callbacks = null;
    }

    private void deleteAppWidgetLocked(Widget widget) {
        // We first unbind all services that are bound to this id
        // Check if we need to destroy any services (if no other app widgets are
        // referencing the same service)
        decrementAppWidgetServiceRefCount(widget);

        Host host = widget.host;
        host.widgets.remove(widget);
        pruneHostLocked(host);

        removeWidgetLocked(widget);

        Provider provider = widget.provider;
        if (provider != null) {
            provider.widgets.remove(widget);
            if (!provider.zombie) {
                // send the broacast saying that this appWidgetId has been deleted
                sendDeletedIntentLocked(widget);

                if (provider.widgets.isEmpty()) {
                    // cancel the future updates
                    cancelBroadcastsLocked(provider);

                    // send the broacast saying that the provider is not in use any more
                    sendDisabledIntentLocked(provider);
                }
            }
        }
    }

    private void cancelBroadcastsLocked(Provider provider) {
        if (DEBUG) {
            Slog.i(TAG, "cancelBroadcastsLocked() for " + provider);
        }
        if (provider.broadcast != null) {
            final PendingIntent broadcast = provider.broadcast;
            mSaveStateHandler.post(() -> {
                    mAlarmManager.cancel(broadcast);
                    broadcast.cancel();
            });
            provider.broadcast = null;
        }
    }

    // Destroys the cached factory on the RemoteViewsService's side related to the specified intent
    private void destroyRemoteViewsService(final Intent intent, Widget widget) {
        final ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                final IRemoteViewsFactory cb = IRemoteViewsFactory.Stub.asInterface(service);
                try {
                    cb.onDestroy(intent);
                } catch (RemoteException re) {
                    Slog.e(TAG, "Error calling remove view factory", re);
                }
                mContext.unbindService(this);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // Do nothing
            }
        };

        // Bind to the service and remove the static intent->factory mapping in the
        // RemoteViewsService.
        final long token = Binder.clearCallingIdentity();
        try {
            mContext.bindServiceAsUser(intent, conn,
                    Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE,
                    widget.provider.id.getProfile());
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // Adds to the ref-count for a given RemoteViewsService intent
    private void incrementAppWidgetServiceRefCount(int appWidgetId,
            Pair<Integer, FilterComparison> serviceId) {
        final HashSet<Integer> appWidgetIds;
        if (mRemoteViewsServicesAppWidgets.containsKey(serviceId)) {
            appWidgetIds = mRemoteViewsServicesAppWidgets.get(serviceId);
        } else {
            appWidgetIds = new HashSet<>();
            mRemoteViewsServicesAppWidgets.put(serviceId, appWidgetIds);
        }
        appWidgetIds.add(appWidgetId);
    }

    // Subtracts from the ref-count for a given RemoteViewsService intent, prompting a delete if
    // the ref-count reaches zero.
    private void decrementAppWidgetServiceRefCount(Widget widget) {
        Iterator<Pair<Integer, FilterComparison>> it = mRemoteViewsServicesAppWidgets
                .keySet().iterator();
        while (it.hasNext()) {
            final Pair<Integer, FilterComparison> key = it.next();
            final HashSet<Integer> ids = mRemoteViewsServicesAppWidgets.get(key);
            if (ids.remove(widget.appWidgetId)) {
                // If we have removed the last app widget referencing this service, then we
                // should destroy it and remove it from this set
                if (ids.isEmpty()) {
                    destroyRemoteViewsService(key.second.getIntent(), widget);
                    it.remove();
                }
            }
        }
    }

    private void saveGroupStateAsync(int groupId) {
        mSaveStateHandler.post(new SaveStateRunnable(groupId));
    }

    private void updateAppWidgetInstanceLocked(Widget widget, RemoteViews views,
            boolean isPartialUpdate) {
        if (widget != null && widget.provider != null
                && !widget.provider.zombie && !widget.host.zombie) {

            if (isPartialUpdate && widget.views != null) {
                // For a partial update, we merge the new RemoteViews with the old.
                widget.views.mergeRemoteViews(views);
            } else {
                // For a full update we replace the RemoteViews completely.
                widget.views = views;
            }
            int memoryUsage;
            if ((UserHandle.getAppId(Binder.getCallingUid()) != Process.SYSTEM_UID) &&
                    (widget.views != null) &&
                    ((memoryUsage = widget.views.estimateMemoryUsage()) > mMaxWidgetBitmapMemory)) {
                widget.views = null;
                throw new IllegalArgumentException("RemoteViews for widget update exceeds"
                        + " maximum bitmap memory usage (used: " + memoryUsage
                        + ", max: " + mMaxWidgetBitmapMemory + ")");
            }
            scheduleNotifyUpdateAppWidgetLocked(widget, widget.getEffectiveViewsLocked());
        }
    }
    private void scheduleNotifyAppWidgetViewDataChanged(Widget widget, int viewId) {
        if (viewId == ID_VIEWS_UPDATE || viewId == ID_PROVIDER_CHANGED) {
            // A view id should never collide with these constants but a developer can call this
            // method with a wrong id. In that case, ignore the call.
            return;
        }
        long requestId = UPDATE_COUNTER.incrementAndGet();
        if (widget != null) {
            widget.updateSequenceNos.put(viewId, requestId);
        }
        if (widget == null || widget.host == null || widget.host.zombie
                || widget.host.callbacks == null || widget.provider == null
                || widget.provider.zombie) {
            return;
        }

        SomeArgs args = SomeArgs.obtain();
        args.arg1 = widget.host;
        args.arg2 = widget.host.callbacks;
        args.arg3 = requestId;
        args.argi1 = widget.appWidgetId;
        args.argi2 = viewId;

        mCallbackHandler.obtainMessage(
                CallbackHandler.MSG_NOTIFY_VIEW_DATA_CHANGED,
                args).sendToTarget();
    }


    private void handleNotifyAppWidgetViewDataChanged(Host host, IAppWidgetHost callbacks,
            int appWidgetId, int viewId, long requestId) {
        try {
            callbacks.viewDataChanged(appWidgetId, viewId);
            host.lastWidgetUpdateSequenceNo = requestId;
        } catch (RemoteException re) {
            // It failed; remove the callback. No need to prune because
            // we know that this host is still referenced by this instance.
            callbacks = null;
        }

        // If the host is unavailable, then we call the associated
        // RemoteViewsFactory.onDataSetChanged() directly
        synchronized (mLock) {
            if (callbacks == null) {
                host.callbacks = null;

                Set<Pair<Integer, FilterComparison>> keys = mRemoteViewsServicesAppWidgets.keySet();
                for (Pair<Integer, FilterComparison> key : keys) {
                    if (mRemoteViewsServicesAppWidgets.get(key).contains(appWidgetId)) {
                        final ServiceConnection connection = new ServiceConnection() {
                            @Override
                            public void onServiceConnected(ComponentName name, IBinder service) {
                                IRemoteViewsFactory cb = IRemoteViewsFactory.Stub
                                        .asInterface(service);
                                try {
                                    cb.onDataSetChangedAsync();
                                } catch (RemoteException e) {
                                    Slog.e(TAG, "Error calling onDataSetChangedAsync()", e);
                                }
                                mContext.unbindService(this);
                            }

                            @Override
                            public void onServiceDisconnected(android.content.ComponentName name) {
                                // Do nothing
                            }
                        };

                        final int userId = UserHandle.getUserId(key.first);
                        Intent intent = key.second.getIntent();

                        // Bind to the service and call onDataSetChanged()
                        bindService(intent, connection, new UserHandle(userId));
                    }
                }
            }
        }
    }

    private void scheduleNotifyUpdateAppWidgetLocked(Widget widget, RemoteViews updateViews) {
        long requestId = UPDATE_COUNTER.incrementAndGet();
        if (widget != null) {
            if (widget.trackingUpdate) {
                // This is the first update, end the trace
                widget.trackingUpdate = false;
                Log.i(TAG, "Widget update received " + widget.toString());
                Trace.asyncTraceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                        "appwidget update-intent " + widget.provider.id.toString(),
                        widget.appWidgetId);
            }
            widget.updateSequenceNos.put(ID_VIEWS_UPDATE, requestId);
        }
        if (widget == null || widget.provider == null || widget.provider.zombie
                || widget.host.callbacks == null || widget.host.zombie) {
            return;
        }
        if (updateViews != null) {
            updateViews = new RemoteViews(updateViews);
            updateViews.setProviderInstanceId(requestId);
        }

        SomeArgs args = SomeArgs.obtain();
        args.arg1 = widget.host;
        args.arg2 = widget.host.callbacks;
        args.arg3 = updateViews;
        args.arg4 = requestId;
        args.argi1 = widget.appWidgetId;

        mCallbackHandler.obtainMessage(
                CallbackHandler.MSG_NOTIFY_UPDATE_APP_WIDGET,
                args).sendToTarget();
    }

    private void handleNotifyUpdateAppWidget(Host host, IAppWidgetHost callbacks,
            int appWidgetId, RemoteViews views, long requestId) {
        try {
            callbacks.updateAppWidget(appWidgetId, views);
            host.lastWidgetUpdateSequenceNo = requestId;
        } catch (RemoteException re) {
            synchronized (mLock) {
                Slog.e(TAG, "Widget host dead: " + host.id, re);
                host.callbacks = null;
            }
        }
    }

    @GuardedBy("mLock")
    private void scheduleNotifyProviderChangedLocked(Widget widget) {
        long requestId = UPDATE_COUNTER.incrementAndGet();
        if (widget != null) {
            // When the provider changes, reset everything else.
            widget.updateSequenceNos.clear();
            widget.updateSequenceNos.append(ID_PROVIDER_CHANGED, requestId);
        }
        if (widget == null || widget.provider == null || widget.provider.zombie
                || widget.host.callbacks == null || widget.host.zombie) {
            return;
        }

        SomeArgs args = SomeArgs.obtain();
        args.arg1 = widget.host;
        args.arg2 = widget.host.callbacks;
        args.arg3 = widget.provider.getInfoLocked(mContext);
        args.arg4 = requestId;
        args.argi1 = widget.appWidgetId;

        mCallbackHandler.obtainMessage(
                CallbackHandler.MSG_NOTIFY_PROVIDER_CHANGED,
                args).sendToTarget();
    }

    private void handleNotifyProviderChanged(Host host, IAppWidgetHost callbacks,
            int appWidgetId, AppWidgetProviderInfo info, long requestId) {
        try {
            callbacks.providerChanged(appWidgetId, info);
            host.lastWidgetUpdateSequenceNo = requestId;
        } catch (RemoteException re) {
            synchronized (mLock){
                Slog.e(TAG, "Widget host dead: " + host.id, re);
                host.callbacks = null;
            }
        }
    }

    private void scheduleNotifyAppWidgetRemovedLocked(Widget widget) {
        long requestId = UPDATE_COUNTER.incrementAndGet();
        if (widget != null) {
            if (widget.trackingUpdate) {
                // Widget is being removed without any update, end the trace
                widget.trackingUpdate = false;
                Log.i(TAG, "Widget removed " + widget.toString());
                Trace.asyncTraceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                        "appwidget update-intent " + widget.provider.id.toString(),
                        widget.appWidgetId);
            }

            widget.updateSequenceNos.clear();
        }
        if (widget == null || widget.provider == null || widget.provider.zombie
                || widget.host.callbacks == null || widget.host.zombie) {
            return;
        }

        SomeArgs args = SomeArgs.obtain();
        args.arg1 = widget.host;
        args.arg2 = widget.host.callbacks;
        args.arg3 = requestId;
        args.argi1 = widget.appWidgetId;

        mCallbackHandler.obtainMessage(
            CallbackHandler.MSG_NOTIFY_APP_WIDGET_REMOVED,
            args).sendToTarget();
    }

    private void handleNotifyAppWidgetRemoved(Host host, IAppWidgetHost callbacks, int appWidgetId,
            long requestId) {
        try {
            callbacks.appWidgetRemoved(appWidgetId);
            host.lastWidgetUpdateSequenceNo = requestId;
        } catch (RemoteException re) {
            synchronized (mLock) {
                Slog.e(TAG, "Widget host dead: " + host.id, re);
                host.callbacks = null;
            }
        }
    }

    private void scheduleNotifyGroupHostsForProvidersChangedLocked(int userId) {
        final int[] profileIds = mSecurityPolicy.getEnabledGroupProfileIds(userId);

        final int N = mHosts.size();
        for (int i = N - 1; i >= 0; i--) {
            Host host = mHosts.get(i);

            boolean hostInGroup = false;
            final int M = profileIds.length;
            for (int j = 0; j < M; j++) {
                final int profileId = profileIds[j];
                if (host.getUserId() == profileId) {
                    hostInGroup = true;
                    break;
                }
            }

            if (!hostInGroup) {
                continue;
            }

            if (host == null || host.zombie || host.callbacks == null) {
                continue;
            }

            SomeArgs args = SomeArgs.obtain();
            args.arg1 = host;
            args.arg2 = host.callbacks;

            mCallbackHandler.obtainMessage(
                    CallbackHandler.MSG_NOTIFY_PROVIDERS_CHANGED,
                    args).sendToTarget();
        }
    }

    private void handleNotifyProvidersChanged(Host host, IAppWidgetHost callbacks) {
        try {
            callbacks.providersChanged();
        } catch (RemoteException re) {
            synchronized (mLock) {
                Slog.e(TAG, "Widget host dead: " + host.id, re);
                host.callbacks = null;
            }
        }
    }

    private static boolean isLocalBinder() {
        return Process.myPid() == Binder.getCallingPid();
    }

    private static RemoteViews cloneIfLocalBinder(RemoteViews rv) {
        if (isLocalBinder() && rv != null) {
            return rv.clone();
        }
        return rv;
    }

    private static AppWidgetProviderInfo cloneIfLocalBinder(AppWidgetProviderInfo info) {
        if (isLocalBinder() && info != null) {
            return info.clone();
        }
        return info;
    }

    private static Bundle cloneIfLocalBinder(Bundle bundle) {
        // Note: this is only a shallow copy. For now this will be fine, but it could be problematic
        // if we start adding objects to the options. Further, it would only be an issue if keyguard
        // used such options.
        if (isLocalBinder() && bundle != null) {
            return (Bundle) bundle.clone();
        }
        return bundle;
    }

    private Widget lookupWidgetLocked(int appWidgetId, int uid, String packageName) {
        final int N = mWidgets.size();
        for (int i = 0; i < N; i++) {
            Widget widget = mWidgets.get(i);
            if (widget.appWidgetId == appWidgetId
                    && mSecurityPolicy.canAccessAppWidget(widget, uid, packageName)) {
                return widget;
            }
        }
        return null;
    }

    private Provider lookupProviderLocked(ProviderId id) {
        final int N = mProviders.size();
        for (int i = 0; i < N; i++) {
            Provider provider = mProviders.get(i);
            if (provider.id.equals(id)) {
                return provider;
            }
        }
        return null;
    }

    private Host lookupHostLocked(HostId hostId) {
        final int N = mHosts.size();
        for (int i = 0; i < N; i++) {
            Host host = mHosts.get(i);
            if (host.id.equals(hostId)) {
                return host;
            }
        }
        return null;
    }

    private void pruneHostLocked(Host host) {
        if (host.widgets.size() == 0 && host.callbacks == null) {
            if (DEBUG) {
                Slog.i(TAG, "Pruning host " + host.id);
            }
            mHosts.remove(host);
        }
    }

    @GuardedBy("mLock")
    private void loadGroupWidgetProvidersLocked(int[] profileIds) {
        List<ResolveInfo> allReceivers = null;
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);

        final int profileCount = profileIds.length;
        for (int i = 0; i < profileCount; i++) {
            final int profileId = profileIds[i];

            List<ResolveInfo> receivers = queryIntentReceivers(intent, profileId);
            if (receivers != null && !receivers.isEmpty()) {
                if (allReceivers == null) {
                    allReceivers = new ArrayList<>();
                }
                allReceivers.addAll(receivers);
            }
        }

        final int N = (allReceivers == null) ? 0 : allReceivers.size();
        for (int i = 0; i < N; i++) {
            ResolveInfo receiver = allReceivers.get(i);
            addProviderLocked(receiver);
        }
    }

    private boolean addProviderLocked(ResolveInfo ri) {
        if ((ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0) {
            return false;
        }

        ComponentName componentName = new ComponentName(ri.activityInfo.packageName,
                ri.activityInfo.name);
        ProviderId providerId = new ProviderId(ri.activityInfo.applicationInfo.uid, componentName);

        // we might have an inactive entry for this provider already due to
        // a preceding restore operation.  if so, fix it up in place; otherwise
        // just add this new one.
        Provider existing = lookupProviderLocked(providerId);

        // If the provider was not found it may be because it was restored and
        // we did not know its UID so let us find if there is such one.
        if (existing == null) {
            ProviderId restoredProviderId = new ProviderId(UNKNOWN_UID, componentName);
            existing = lookupProviderLocked(restoredProviderId);
        }

        AppWidgetProviderInfo info = createPartialProviderInfo(providerId, ri, existing);
        if (info != null) {
            if (existing != null) {
                if (existing.zombie && !mSafeMode) {
                    // it's a placeholder that was set up during an app restore
                    existing.id = providerId;
                    existing.zombie = false;
                    existing.setPartialInfoLocked(info);
                    if (DEBUG) {
                        Slog.i(TAG, "Provider placeholder now reified: " + existing);
                    }
                }
            } else {
                Provider provider = new Provider();
                provider.id = providerId;
                provider.setPartialInfoLocked(info);
                mProviders.add(provider);
            }
            return true;
        }

        return false;
    }

    // Remove widgets for provider that are hosted in userId.
    private void deleteWidgetsLocked(Provider provider, int userId) {
        final int N = provider.widgets.size();
        for (int i = N - 1; i >= 0; i--) {
            Widget widget = provider.widgets.get(i);
            if (userId == UserHandle.USER_ALL
                    || userId == widget.host.getUserId()) {
                provider.widgets.remove(i);
                // Call back with empty RemoteViews
                updateAppWidgetInstanceLocked(widget, null, false);
                // clear out references to this appWidgetId
                widget.host.widgets.remove(widget);
                removeWidgetLocked(widget);
                widget.provider = null;
                pruneHostLocked(widget.host);
                widget.host = null;
            }
        }
    }

    private void deleteProviderLocked(Provider provider) {
        deleteWidgetsLocked(provider, UserHandle.USER_ALL);
        mProviders.remove(provider);

        // no need to send the DISABLE broadcast, since the receiver is gone anyway
        cancelBroadcastsLocked(provider);
    }

    private void sendEnableAndUpdateIntentLocked(@NonNull Provider p, int[] appWidgetIds) {
        final boolean canSendCombinedBroadcast = mIsCombinedBroadcastEnabled && p.info != null
                && p.info.isExtendedFromAppWidgetProvider;
        if (!canSendCombinedBroadcast) {
            // If this function is called by mistake, send two separate broadcasts instead
            sendEnableIntentLocked(p);
            sendUpdateIntentLocked(p, appWidgetIds, true);
            return;
        }

        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_ENABLE_AND_UPDATE);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds);
        intent.setComponent(p.id.componentName);
        // Placing a widget is something users expect to be UX-responsive, so mark this
        // broadcast as interactive
        sendBroadcastAsUser(intent, p.id.getProfile(), true);
    }

    private void sendEnableIntentLocked(Provider p) {
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_ENABLED);
        intent.setComponent(p.id.componentName);
        // Enabling the widget is something users expect to be UX-responsive, so mark this
        // broadcast as interactive
        sendBroadcastAsUser(intent, p.id.getProfile(), true);
    }

    private void sendUpdateIntentLocked(Provider provider, int[] appWidgetIds,
            boolean interactive) {
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds);
        intent.setComponent(provider.id.componentName);
        sendBroadcastAsUser(intent, provider.id.getProfile(), interactive);
    }

    private void sendDeletedIntentLocked(Widget widget) {
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_DELETED);
        intent.setComponent(widget.provider.id.componentName);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widget.appWidgetId);
        // Cleanup after deletion isn't an interactive UX case
        sendBroadcastAsUser(intent, widget.provider.id.getProfile(), false);
    }

    private void sendDisabledIntentLocked(Provider provider) {
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_DISABLED);
        intent.setComponent(provider.id.componentName);
        // Cleanup after disable isn't an interactive UX case
        sendBroadcastAsUser(intent, provider.id.getProfile(), false);
    }

    public void sendOptionsChangedIntentLocked(Widget widget) {
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED);
        intent.setComponent(widget.provider.id.componentName);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widget.appWidgetId);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, widget.options);
        // The user's changed the options, so seeing them take effect promptly is
        // an interactive UX expectation
        sendBroadcastAsUser(intent, widget.provider.id.getProfile(), true);
    }

    @GuardedBy("mLock")
    private void registerForBroadcastsLocked(Provider provider, int[] appWidgetIds) {
        AppWidgetProviderInfo info = provider.getInfoLocked(mContext);
        if (info.updatePeriodMillis > 0) {
            // if this is the first instance, set the alarm. otherwise,
            // rely on the fact that we've already set it and that
            // PendingIntent.getBroadcast will update the extras.
            boolean alreadyRegistered = provider.broadcast != null;
            Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds);
            intent.setComponent(info.provider);
            final long token = Binder.clearCallingIdentity();
            try {
                // Broadcast alarms sent by system are immutable
                provider.broadcast = PendingIntent.getBroadcastAsUser(mContext, 1, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                        info.getProfile());
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            if (!alreadyRegistered) {
                // Set the alarm outside of our locks; we've latched the first-time
                // invariant and established the PendingIntent safely.
                final long period = Math.max(info.updatePeriodMillis, MIN_UPDATE_PERIOD);
                final PendingIntent broadcast = provider.broadcast;
                mSaveStateHandler.post(() ->
                    mAlarmManager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            SystemClock.elapsedRealtime() + period, period, broadcast)
                );
            }
        }
    }

    private static int[] getWidgetIds(ArrayList<Widget> widgets) {
        int instancesSize = widgets.size();
        int appWidgetIds[] = new int[instancesSize];
        for (int i = 0; i < instancesSize; i++) {
            appWidgetIds[i] = widgets.get(i).appWidgetId;
        }
        return appWidgetIds;
    }

    private static void dumpProviderLocked(Provider provider, int index, PrintWriter pw) {
        AppWidgetProviderInfo info = provider.getPartialInfoLocked();
        pw.print("  ["); pw.print(index); pw.print("] provider ");
        pw.println(provider.id);
        pw.print("    min=("); pw.print(info.minWidth);
        pw.print("x"); pw.print(info.minHeight);
        pw.print(")   minResize=("); pw.print(info.minResizeWidth);
        pw.print("x"); pw.print(info.minResizeHeight);
        pw.print(") updatePeriodMillis=");
        pw.print(info.updatePeriodMillis);
        pw.print(" resizeMode=");
        pw.print(info.resizeMode);
        pw.print(" widgetCategory=");
        pw.print(info.widgetCategory);
        pw.print(" autoAdvanceViewId=");
        pw.print(info.autoAdvanceViewId);
        pw.print(" initialLayout=#");
        pw.print(Integer.toHexString(info.initialLayout));
        pw.print(" initialKeyguardLayout=#");
        pw.print(Integer.toHexString(info.initialKeyguardLayout));
        pw.print("   zombie="); pw.println(provider.zombie);
    }

    private static void dumpHost(Host host, int index, PrintWriter pw) {
        pw.print("  ["); pw.print(index); pw.print("] hostId=");
        pw.println(host.id);
        pw.print("    callbacks="); pw.println(host.callbacks);
        pw.print("    widgets.size="); pw.print(host.widgets.size());
        pw.print(" zombie="); pw.println(host.zombie);
    }

    private static void dumpGrant(Pair<Integer, String> grant, int index, PrintWriter pw) {
        pw.print("  ["); pw.print(index); pw.print(']');
        pw.print(" user="); pw.print(grant.first);
        pw.print(" package="); pw.println(grant.second);
    }

    private static void dumpWidget(Widget widget, int index, PrintWriter pw) {
        pw.print("  ["); pw.print(index); pw.print("] id=");
        pw.println(widget.appWidgetId);
        pw.print("    host=");
        pw.println(widget.host.id);
        if (widget.provider != null) {
            pw.print("    provider="); pw.println(widget.provider.id);
        }
        if (widget.host != null) {
            pw.print("    host.callbacks="); pw.println(widget.host.callbacks);
        }
        if (widget.views != null) {
            pw.print("    views="); pw.println(widget.views);
        }
    }

    private static void serializeProvider(
            @NonNull final TypedXmlSerializer out, @NonNull final Provider p) throws IOException {
        Objects.requireNonNull(out);
        Objects.requireNonNull(p);
        serializeProviderInner(out, p, false /* persistsProviderInfo */);
    }

    private static void serializeProviderWithProviderInfo(
            @NonNull final TypedXmlSerializer out, @NonNull final Provider p) throws IOException {
        Objects.requireNonNull(out);
        Objects.requireNonNull(p);
        serializeProviderInner(out, p, true /* persistsProviderInfo */);
    }

    private static void serializeProviderInner(@NonNull final TypedXmlSerializer out,
            @NonNull final Provider p, final boolean persistsProviderInfo) throws IOException {
        Objects.requireNonNull(out);
        Objects.requireNonNull(p);
        out.startTag(null, "p");
        out.attribute(null, "pkg", p.id.componentName.getPackageName());
        out.attribute(null, "cl", p.id.componentName.getClassName());
        out.attributeIntHex(null, "tag", p.tag);
        if (!TextUtils.isEmpty(p.infoTag)) {
            out.attribute(null, "info_tag", p.infoTag);
        }
        if (DEBUG_PROVIDER_INFO_CACHE && persistsProviderInfo && !p.mInfoParsed) {
            Slog.d(TAG, "Provider info from " + p.id.componentName + " won't be persisted.");
        }
        if (persistsProviderInfo && p.mInfoParsed) {
            AppWidgetXmlUtil.writeAppWidgetProviderInfoLocked(out, p.info);
        }
        out.endTag(null, "p");
    }

    private static void serializeHost(TypedXmlSerializer out, Host host) throws IOException {
        out.startTag(null, "h");
        out.attribute(null, "pkg", host.id.packageName);
        out.attributeIntHex(null, "id", host.id.hostId);
        out.attributeIntHex(null, "tag", host.tag);
        out.endTag(null, "h");
    }

    private static void serializeAppWidget(TypedXmlSerializer out, Widget widget,
            boolean saveRestoreCompleted) throws IOException {
        out.startTag(null, "g");
        out.attributeIntHex(null, "id", widget.appWidgetId);
        out.attributeIntHex(null, "rid", widget.restoredId);
        out.attributeIntHex(null, "h", widget.host.tag);
        if (widget.provider != null) {
            out.attributeIntHex(null, "p", widget.provider.tag);
        }
        if (widget.options != null) {
            int minWidth = widget.options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
            int minHeight = widget.options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT);
            int maxWidth = widget.options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH);
            int maxHeight = widget.options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
            out.attributeIntHex(null, "min_width", (minWidth > 0) ? minWidth : 0);
            out.attributeIntHex(null, "min_height", (minHeight > 0) ? minHeight : 0);
            out.attributeIntHex(null, "max_width", (maxWidth > 0) ? maxWidth : 0);
            out.attributeIntHex(null, "max_height", (maxHeight > 0) ? maxHeight : 0);
            out.attributeIntHex(null, "host_category", widget.options.getInt(
                    AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY));
            if (saveRestoreCompleted) {
                boolean restoreCompleted = widget.options.getBoolean(
                        AppWidgetManager.OPTION_APPWIDGET_RESTORE_COMPLETED);
                out.attributeBoolean(null, "restore_completed", restoreCompleted);
            }
        }
        out.endTag(null, "g");
    }

    private static Bundle parseWidgetIdOptions(TypedXmlPullParser parser) {
        Bundle options = new Bundle();
        boolean restoreCompleted = parser.getAttributeBoolean(null, "restore_completed", false);
        if (restoreCompleted) {
            options.putBoolean(AppWidgetManager.OPTION_APPWIDGET_RESTORE_COMPLETED, true);
        }
        int minWidth = parser.getAttributeIntHex(null, "min_width", -1);
        if (minWidth != -1) {
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, minWidth);
        }
        int minHeight = parser.getAttributeIntHex(null, "min_height", -1);
        if (minHeight != -1) {
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, minHeight);
        }
        int maxWidth = parser.getAttributeIntHex(null, "max_width", -1);
        if (maxWidth != -1) {
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, maxWidth);
        }
        int maxHeight = parser.getAttributeIntHex(null, "max_height", -1);
        if (maxHeight != -1) {
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, maxHeight);
        }
        int category = parser.getAttributeIntHex(null, "host_category",
                AppWidgetProviderInfo.WIDGET_CATEGORY_UNKNOWN);
        if (category != AppWidgetProviderInfo.WIDGET_CATEGORY_UNKNOWN) {
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, category);
        }
        return options;
    }

    @Override
    public List<String> getWidgetParticipants(int userId) {
        return mBackupRestoreController.getWidgetParticipants(userId);
    }

    @Override
    public byte[] getWidgetState(String packageName, int userId) {
        return mBackupRestoreController.getWidgetState(packageName, userId);
    }

    @Override
    public void systemRestoreStarting(int userId) {
        mBackupRestoreController.systemRestoreStarting(userId);
    }

    @Override
    public void restoreWidgetState(String packageName, byte[] restoredState, int userId) {
        mBackupRestoreController.restoreWidgetState(packageName, restoredState, userId);
    }

    @Override
    public void systemRestoreFinished(int userId) {
        mBackupRestoreController.systemRestoreFinished(userId);
    }

    @SuppressWarnings("deprecation")
    private AppWidgetProviderInfo createPartialProviderInfo(ProviderId providerId, ResolveInfo ri,
            Provider provider) {
        boolean hasXmlDefinition = false;
        Bundle metaData = ri.activityInfo.metaData;
        if (metaData == null) {
            return null;
        }

        if (provider != null && !TextUtils.isEmpty(provider.infoTag)) {
            hasXmlDefinition = metaData.getInt(provider.infoTag) != 0;
        }
        hasXmlDefinition |= metaData.getInt(AppWidgetManager.META_DATA_APPWIDGET_PROVIDER) != 0;

        if (hasXmlDefinition) {
            AppWidgetProviderInfo info = new AppWidgetProviderInfo();
            info.provider = providerId.componentName;
            info.providerInfo = ri.activityInfo;
            return info;
        }
        return null;
    }

    private static AppWidgetProviderInfo parseAppWidgetProviderInfo(Context context,
            ProviderId providerId, ActivityInfo activityInfo, String metadataKey) {
        final PackageManager pm = context.getPackageManager();
        try (XmlResourceParser parser = activityInfo.loadXmlMetaData(pm, metadataKey)) {
            if (parser == null) {
                Slog.w(TAG, "No " + metadataKey + " meta-data for AppWidget provider '"
                        + providerId + '\'');
                return null;
            }

            AttributeSet attrs = Xml.asAttributeSet(parser);

            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
                // drain whitespace, comments, etc.
            }

            String nodeName = parser.getName();
            if (!"appwidget-provider".equals(nodeName)) {
                Slog.w(TAG, "Meta-data does not start with appwidget-provider tag for"
                        + " AppWidget provider " + providerId.componentName
                        + " for user " + providerId.uid);
                return null;
            }

            AppWidgetProviderInfo info = new AppWidgetProviderInfo();
            info.provider = providerId.componentName;
            info.providerInfo = activityInfo;

            final Resources resources;
            final long identity = Binder.clearCallingIdentity();
            try {
                final int userId = UserHandle.getUserId(providerId.uid);
                final ApplicationInfo app = pm.getApplicationInfoAsUser(activityInfo.packageName,
                        0, userId);
                resources = pm.getResourcesForApplication(app);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }

            TypedArray sa = resources.obtainAttributes(attrs,
                    com.android.internal.R.styleable.AppWidgetProviderInfo);

            // These dimensions has to be resolved in the application's context.
            // We simply send back the raw complex data, which will be
            // converted to dp in {@link AppWidgetManager#getAppWidgetInfo}.
            TypedValue value = sa
                    .peekValue(com.android.internal.R.styleable.AppWidgetProviderInfo_minWidth);
            info.minWidth = value != null ? value.data : 0;
            value = sa.peekValue(com.android.internal.R.styleable.AppWidgetProviderInfo_minHeight);
            info.minHeight = value != null ? value.data : 0;

            value = sa.peekValue(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_minResizeWidth);
            info.minResizeWidth = value != null ? value.data : info.minWidth;
            value = sa.peekValue(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_minResizeHeight);
            info.minResizeHeight = value != null ? value.data : info.minHeight;

            value = sa.peekValue(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_maxResizeWidth);
            info.maxResizeWidth = value != null ? value.data : 0;
            value = sa.peekValue(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_maxResizeHeight);
            info.maxResizeHeight = value != null ? value.data : 0;

            info.targetCellWidth = sa.getInt(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_targetCellWidth, 0);
            info.targetCellHeight = sa.getInt(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_targetCellHeight, 0);

            info.updatePeriodMillis = sa.getInt(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_updatePeriodMillis, 0);
            info.initialLayout = sa.getResourceId(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_initialLayout, ID_NULL);
            info.initialKeyguardLayout = sa.getResourceId(com.android.internal.R.styleable.
                    AppWidgetProviderInfo_initialKeyguardLayout, ID_NULL);

            String className = sa
                    .getString(com.android.internal.R.styleable.AppWidgetProviderInfo_configure);
            if (className != null) {
                info.configure = new ComponentName(providerId.componentName.getPackageName(),
                        className);
            }
            info.label = activityInfo.loadLabel(pm).toString();
            info.icon = activityInfo.getIconResource();
            info.previewImage = sa.getResourceId(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_previewImage, ID_NULL);
            info.previewLayout = sa.getResourceId(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_previewLayout, ID_NULL);
            info.autoAdvanceViewId = sa.getResourceId(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_autoAdvanceViewId,
                    View.NO_ID);
            info.resizeMode = sa.getInt(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_resizeMode,
                    AppWidgetProviderInfo.RESIZE_NONE);
            info.widgetCategory = sa.getInt(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_widgetCategory,
                    AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN);
            info.widgetFeatures = sa.getInt(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_widgetFeatures, 0);
            info.descriptionRes = sa.getResourceId(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_description, ID_NULL);
            sa.recycle();
            return info;
        } catch (IOException | PackageManager.NameNotFoundException | XmlPullParserException e) {
            // Ok to catch Exception here, because anything going wrong because
            // of what a client process passes to us should not be fatal for the
            // system process.
            Slog.w(TAG, "XML parsing failed for AppWidget provider "
                    + providerId.componentName + " for user " + providerId.uid, e);
            return null;
        }
    }

    private int getUidForPackage(String packageName, int userId) {
        PackageInfo pkgInfo = null;

        final long identity = Binder.clearCallingIdentity();
        try {
            pkgInfo = mPackageManager.getPackageInfo(packageName, 0, userId);
        } catch (RemoteException re) {
            // Shouldn't happen, local call
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        if (pkgInfo == null || pkgInfo.applicationInfo == null) {
            return -1;
        }

        return pkgInfo.applicationInfo.uid;
    }

    private ActivityInfo getProviderInfo(ComponentName componentName, int userId) {
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        intent.setComponent(componentName);

        List<ResolveInfo> receivers = queryIntentReceivers(intent, userId);
        // We are setting component, so there is only one or none.
        if (!receivers.isEmpty()) {
            return receivers.get(0).activityInfo;
        }

        return null;
    }

    private List<ResolveInfo> queryIntentReceivers(Intent intent, int userId) {
        final long identity = Binder.clearCallingIdentity();
        try {
            int flags = PackageManager.GET_META_DATA;

            // We really need packages to be around and parsed to know if they
            // provide widgets.
            flags |= PackageManager.MATCH_DEBUG_TRIAGED_MISSING;

            // Widget hosts that are non-crypto aware may be hosting widgets
            // from a profile that is still locked, so let them see those
            // widgets.
            if (isProfileWithUnlockedParent(userId)) {
                flags |= PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
            }

            // Widgets referencing shared libraries need to have their
            // dependencies loaded.
            flags |= PackageManager.GET_SHARED_LIBRARY_FILES;

            return mPackageManager.queryIntentReceivers(intent,
                    intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                    flags, userId).getList();
        } catch (RemoteException re) {
            return Collections.emptyList();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * This does not use the usual onUserUnlocked() listener mechanism because it is
     * invoked at a choreographed point in the middle of the user unlock sequence,
     * before the boot-completed broadcast is issued and the listeners notified.
     */
    void handleUserUnlocked(int userId) {
        if (isProfileWithLockedParent(userId)) {
            return;
        }
        if (!mUserManager.isUserUnlockingOrUnlocked(userId)) {
            Slog.w(TAG, "User " + userId + " is no longer unlocked - exiting");
            return;
        }
        long time = SystemClock.elapsedRealtime();
        synchronized (mLock) {
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "appwidget ensure");
            ensureGroupStateLoadedLocked(userId);
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "appwidget reload");
            reloadWidgetsMaskedStateForGroup(mSecurityPolicy.getGroupParent(userId));
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);

            final int N = mProviders.size();
            for (int i = 0; i < N; i++) {
                Provider provider = mProviders.get(i);

                // Send broadcast only to the providers of the user.
                if (provider.getUserId() != userId) {
                    continue;
                }

                if (provider.widgets.size() > 0) {
                    Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                            "appwidget init " + provider.id.componentName.getPackageName());
                    provider.widgets.forEach(widget -> {
                        widget.trackingUpdate = true;
                        Trace.asyncTraceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                                "appwidget update-intent " + provider.id.toString(),
                                widget.appWidgetId);
                        Log.i(TAG, "Widget update scheduled on unlock " + widget.toString());
                    });
                    int[] appWidgetIds = getWidgetIds(provider.widgets);
                    sendEnableAndUpdateIntentLocked(provider, appWidgetIds);
                    registerForBroadcastsLocked(provider, appWidgetIds);
                    Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                }
            }
        }
        Slog.i(TAG, "Processing of handleUserUnlocked u" + userId + " took "
                + (SystemClock.elapsedRealtime() - time) + " ms");
    }

    // only call from initialization -- it assumes that the data structures are all empty
    @GuardedBy("mLock")
    private void loadGroupStateLocked(int[] profileIds) {
        // We can bind the widgets to host and providers only after
        // reading the host and providers for all users since a widget
        // can have a host and a provider in different users.
        List<LoadedWidgetState> loadedWidgets = new ArrayList<>();

        int version = 0;

        final int profileIdCount = profileIds.length;
        for (int i = 0; i < profileIdCount; i++) {
            final int profileId = profileIds[i];

            // No file written for this user - nothing to do.
            AtomicFile file = getSavedStateFile(profileId);
            try (FileInputStream stream = file.openRead()) {
                version = readProfileStateFromFileLocked(stream, profileId, loadedWidgets);
            } catch (IOException e) {
                Slog.w(TAG, "Failed to read state: " + e);
            }
        }

        if (version >= 0) {
            // Hooke'm up...
            bindLoadedWidgetsLocked(loadedWidgets);

            // upgrade the database if needed
            performUpgradeLocked(version);
        } else {
            // failed reading, clean up
            Slog.w(TAG, "Failed to read state, clearing widgets and hosts.");
            clearWidgetsLocked();
            mHosts.clear();
            final int N = mProviders.size();
            for (int i = 0; i < N; i++) {
                mProviders.get(i).widgets.clear();
            }
        }
    }

    private void bindLoadedWidgetsLocked(List<LoadedWidgetState> loadedWidgets) {
        final int loadedWidgetCount = loadedWidgets.size();
        for (int i = loadedWidgetCount - 1; i >= 0; i--) {
            LoadedWidgetState loadedWidget = loadedWidgets.remove(i);
            Widget widget = loadedWidget.widget;

            widget.provider = findProviderByTag(loadedWidget.providerTag);
            if (widget.provider == null) {
                // This provider is gone. We just let the host figure out
                // that this happened when it fails to load it.
                continue;
            }

            widget.host = findHostByTag(loadedWidget.hostTag);
            if (widget.host == null) {
                // This host is gone.
                continue;
            }

            widget.provider.widgets.add(widget);
            widget.host.widgets.add(widget);
            addWidgetLocked(widget);
        }
    }

    private Provider findProviderByTag(int tag) {
        if (tag < 0) {
            return null;
        }
        final int providerCount = mProviders.size();
        for (int i = 0; i < providerCount; i++) {
            Provider provider = mProviders.get(i);
            if (provider.tag == tag) {
                return provider;
            }
        }
        return null;
    }

    private Host findHostByTag(int tag) {
        if (tag < 0) {
            return null;
        }
        final int hostCount = mHosts.size();
        for (int i = 0; i < hostCount; i++) {
            Host host = mHosts.get(i);
            if (host.tag == tag) {
                return host;
            }
        }
        return null;
    }

    /**
     * Adds the widget to mWidgets and tracks the package name in mWidgetPackages.
     */
    void addWidgetLocked(Widget widget) {
        mWidgets.add(widget);

        onWidgetProviderAddedOrChangedLocked(widget);
    }

    /**
     * Checks if the provider is assigned and updates the mWidgetPackages to track packages
     * that have bound widgets.
     */
    void onWidgetProviderAddedOrChangedLocked(Widget widget) {
        if (widget.provider == null) return;

        int userId = widget.provider.getUserId();
        synchronized (mWidgetPackagesLock) {
            ArraySet<String> packages = mWidgetPackages.get(userId);
            if (packages == null) {
                mWidgetPackages.put(userId, packages = new ArraySet<String>());
            }
            packages.add(widget.provider.id.componentName.getPackageName());
        }

        // If we are adding a widget it might be for a provider that
        // is currently masked, if so mask the widget.
        if (widget.provider.isMaskedLocked()) {
            maskWidgetsViewsLocked(widget.provider, widget);
        } else {
            widget.clearMaskedViewsLocked();
        }
    }

    /**
     * Removes a widget from mWidgets and updates the cache of bound widget provider packages.
     * If there are other widgets with the same package, leaves it in the cache, otherwise it
     * removes the associated package from the cache.
     */
    void removeWidgetLocked(Widget widget) {
        mWidgets.remove(widget);
        onWidgetRemovedLocked(widget);
        scheduleNotifyAppWidgetRemovedLocked(widget);
    }

    private void onWidgetRemovedLocked(Widget widget) {
        if (widget.provider == null) return;

        final int userId = widget.provider.getUserId();
        final String packageName = widget.provider.id.componentName.getPackageName();
        synchronized (mWidgetPackagesLock) {
            ArraySet<String> packages = mWidgetPackages.get(userId);
            if (packages == null) {
                return;
            }
            // Check if there is any other widget with the same package name.
            // Remove packageName if none.
            final int N = mWidgets.size();
            for (int i = 0; i < N; i++) {
                Widget w = mWidgets.get(i);
                if (w.provider == null) continue;
                if (w.provider.getUserId() == userId
                        && packageName.equals(w.provider.id.componentName.getPackageName())) {
                    return;
                }
            }
            packages.remove(packageName);
        }
    }

    /**
     * Clears all widgets and associated cache of packages with bound widgets.
     */
    void clearWidgetsLocked() {
        mWidgets.clear();

        onWidgetsClearedLocked();
    }

    private void onWidgetsClearedLocked() {
        synchronized (mWidgetPackagesLock) {
            mWidgetPackages.clear();
        }
    }

    @Override
    public boolean isBoundWidgetPackage(String packageName, int userId) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Only the system process can call this");
        }
        synchronized (mWidgetPackagesLock) {
            final ArraySet<String> packages = mWidgetPackages.get(userId);
            if (packages != null) {
                return packages.contains(packageName);
            }
        }
        return false;
    }

    @GuardedBy("mLock")
    private void saveStateLocked(int userId) {
        tagProvidersAndHosts();

        final int[] profileIds = mSecurityPolicy.getEnabledGroupProfileIds(userId);

        final int profileCount = profileIds.length;
        for (int i = 0; i < profileCount; i++) {
            final int profileId = profileIds[i];

            AtomicFile file = getSavedStateFile(profileId);
            FileOutputStream stream;
            try {
                stream = file.startWrite();
                if (writeProfileStateToFileLocked(stream, profileId)) {
                    file.finishWrite(stream);
                } else {
                    file.failWrite(stream);
                    Slog.w(TAG, "Failed to save state, restoring backup.");
                }
            } catch (IOException e) {
                Slog.w(TAG, "Failed open state file for write: " + e);
            }
        }
    }

    private void tagProvidersAndHosts() {
        final int providerCount = mProviders.size();
        for (int i = 0; i < providerCount; i++) {
            Provider provider = mProviders.get(i);
            provider.tag = i;
        }

        final int hostCount = mHosts.size();
        for (int i = 0; i < hostCount; i++) {
            Host host = mHosts.get(i);
            host.tag = i;
        }
    }

    private void clearProvidersAndHostsTagsLocked() {
        final int providerCount = mProviders.size();
        for (int i = 0; i < providerCount; i++) {
            Provider provider = mProviders.get(i);
            provider.tag = TAG_UNDEFINED;
        }

        final int hostCount = mHosts.size();
        for (int i = 0; i < hostCount; i++) {
            Host host = mHosts.get(i);
            host.tag = TAG_UNDEFINED;
        }
    }

    @GuardedBy("mLock")
    private boolean writeProfileStateToFileLocked(FileOutputStream stream, int userId) {
        int N;

        try {
            TypedXmlSerializer out = Xml.resolveSerializer(stream);
            out.startDocument(null, true);
            out.startTag(null, "gs");
            out.attributeInt(null, "version", CURRENT_VERSION);

            N = mProviders.size();
            for (int i = 0; i < N; i++) {
                Provider provider = mProviders.get(i);
                // Save only providers for the user.
                if (provider.getUserId() != userId) {
                    continue;
                }
                if (mIsProviderInfoPersisted) {
                    serializeProviderWithProviderInfo(out, provider);
                } else if (provider.shouldBePersisted()) {
                    serializeProvider(out, provider);
                }
            }

            N = mHosts.size();
            for (int i = 0; i < N; i++) {
                Host host = mHosts.get(i);
                // Save only hosts for the user.
                if (host.getUserId() != userId) {
                    continue;
                }
                serializeHost(out, host);
            }

            N = mWidgets.size();
            for (int i = 0; i < N; i++) {
                Widget widget = mWidgets.get(i);
                // Save only widgets hosted by the user.
                if (widget.host.getUserId() != userId) {
                    continue;
                }
                serializeAppWidget(out, widget, true);
            }

            Iterator<Pair<Integer, String>> it = mPackagesWithBindWidgetPermission.iterator();
            while (it.hasNext()) {
                Pair<Integer, String> binding = it.next();
                // Save only white listings for the user.
                if (binding.first != userId) {
                    continue;
                }
                out.startTag(null, "b");
                out.attribute(null, "packageName", binding.second);
                out.endTag(null, "b");
            }

            out.endTag(null, "gs");
            out.endDocument();
            return true;
        } catch (IOException e) {
            Slog.w(TAG, "Failed to write state: " + e);
            return false;
        }
    }

    @GuardedBy("mLock")
    private int readProfileStateFromFileLocked(FileInputStream stream, int userId,
            List<LoadedWidgetState> outLoadedWidgets) {
        int version = -1;
        try {
            TypedXmlPullParser parser = Xml.resolvePullParser(stream);

            int legacyProviderIndex = -1;
            int legacyHostIndex = -1;
            int type;
            do {
                type = parser.next();
                if (type == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("gs".equals(tag)) {
                        version = parser.getAttributeInt(null, "version", 0);
                    } else if ("p".equals(tag)) {
                        legacyProviderIndex++;
                        // TODO: do we need to check that this package has the same signature
                        // as before?
                        String pkg = parser.getAttributeValue(null, "pkg");
                        String cl = parser.getAttributeValue(null, "cl");

                        pkg = getCanonicalPackageName(pkg, cl, userId);
                        if (pkg == null) {
                            continue;
                        }

                        final int uid = getUidForPackage(pkg, userId);
                        if (uid < 0) {
                            continue;
                        }

                        ComponentName componentName = new ComponentName(pkg, cl);

                        ActivityInfo providerInfo = getProviderInfo(componentName, userId);
                        if (providerInfo == null) {
                            continue;
                        }

                        ProviderId providerId = new ProviderId(uid, componentName);
                        Provider provider = lookupProviderLocked(providerId);

                        if (provider == null && mSafeMode) {
                            // if we're in safe mode, make a temporary one
                            AppWidgetProviderInfo info = new AppWidgetProviderInfo();
                            info.provider = providerId.componentName;
                            info.providerInfo = providerInfo;

                            provider = new Provider();
                            provider.setPartialInfoLocked(info);
                            provider.zombie = true;
                            provider.id = providerId;
                            mProviders.add(provider);
                        } else if (mIsProviderInfoPersisted) {
                            final AppWidgetProviderInfo info =
                                    AppWidgetXmlUtil.readAppWidgetProviderInfoLocked(parser);
                            if (DEBUG_PROVIDER_INFO_CACHE && info == null) {
                                Slog.d(TAG, "Unable to load widget provider info from xml for "
                                        + providerId.componentName);
                            }
                            if (info != null) {
                                info.provider = providerId.componentName;
                                info.providerInfo = providerInfo;
                                provider.setInfoLocked(info);
                            }
                        }

                        final int providerTag = parser.getAttributeIntHex(null, "tag",
                                legacyProviderIndex);
                        provider.tag = providerTag;
                        provider.infoTag = parser.getAttributeValue(null, "info_tag");
                    } else if ("h".equals(tag)) {
                        legacyHostIndex++;
                        Host host = new Host();
                        // TODO: do we need to check that this package has the same signature
                        // as before?
                        String pkg = parser.getAttributeValue(null, "pkg");

                        final int uid = getUidForPackage(pkg, userId);
                        if (uid < 0) {
                            host.zombie = true;
                        }

                        if (!host.zombie || mSafeMode) {
                            // In safe mode, we don't discard the hosts we don't recognize
                            // so that they're not pruned from our list. Otherwise, we do.
                            final int hostId = parser.getAttributeIntHex(null, "id");
                            final int hostTag = parser.getAttributeIntHex(null, "tag",
                                    legacyHostIndex);

                            host.tag = hostTag;
                            host.id = new HostId(uid, hostId, pkg);
                            mHosts.add(host);
                        }
                    } else if ("b".equals(tag)) {
                        String packageName = parser.getAttributeValue(null, "packageName");
                        final int uid = getUidForPackage(packageName, userId);
                        if (uid >= 0) {
                            Pair<Integer, String> packageId = Pair.create(userId, packageName);
                            mPackagesWithBindWidgetPermission.add(packageId);
                        }
                    } else if ("g".equals(tag)) {
                        Widget widget = new Widget();
                        widget.appWidgetId = parser.getAttributeIntHex(null, "id");
                        setMinAppWidgetIdLocked(userId, widget.appWidgetId + 1);

                        // restored ID is allowed to be absent
                        widget.restoredId = parser.getAttributeIntHex(null, "rid", 0);
                        widget.options = parseWidgetIdOptions(parser);

                        final int hostTag = parser.getAttributeIntHex(null, "h");
                        String providerString = parser.getAttributeValue(null, "p");
                        final int providerTag = (providerString != null)
                                ? parser.getAttributeIntHex(null, "p") : TAG_UNDEFINED;

                        // We can match widgets with hosts and providers only after hosts
                        // and providers for all users have been loaded since the widget
                        // host and provider can be in different user profiles.
                        LoadedWidgetState loadedWidgets = new LoadedWidgetState(widget,
                                hostTag, providerTag);
                        outLoadedWidgets.add(loadedWidgets);
                    }
                }
            } while (type != XmlPullParser.END_DOCUMENT);
        } catch (NullPointerException
                | NumberFormatException
                | XmlPullParserException
                | IOException
                | IndexOutOfBoundsException e) {
            Slog.w(TAG, "failed parsing " + e);
            return -1;
        }

        return version;
    }

    private void performUpgradeLocked(int fromVersion) {
        if (fromVersion < CURRENT_VERSION) {
            Slog.v(TAG, "Upgrading widget database from " + fromVersion + " to "
                    + CURRENT_VERSION);
        }

        int version = fromVersion;

        // Update 1: keyguard moved from package "android" to "com.android.keyguard"
        if (version == 0) {
            HostId oldHostId = new HostId(Process.myUid(),
                    KEYGUARD_HOST_ID, OLD_KEYGUARD_HOST_PACKAGE);

            Host host = lookupHostLocked(oldHostId);
            if (host != null) {
                final int uid = getUidForPackage(NEW_KEYGUARD_HOST_PACKAGE,
                        UserHandle.USER_SYSTEM);
                if (uid >= 0) {
                    host.id = new HostId(uid, KEYGUARD_HOST_ID, NEW_KEYGUARD_HOST_PACKAGE);
                }
            }

            version = 1;
        }

        if (version != CURRENT_VERSION) {
            throw new IllegalStateException("Failed to upgrade widget database");
        }
    }

    private static File getStateFile(int userId) {
        return new File(Environment.getUserSystemDirectory(userId), STATE_FILENAME);
    }

    private static AtomicFile getSavedStateFile(int userId) {
        File dir = Environment.getUserSystemDirectory(userId);
        File settingsFile = getStateFile(userId);
        if (!settingsFile.exists() && userId == UserHandle.USER_SYSTEM) {
            if (!dir.exists()) {
                dir.mkdirs();
            }
            // Migrate old data
            File oldFile = new File("/data/system/" + STATE_FILENAME);
            // Method doesn't throw an exception on failure. Ignore any errors
            // in moving the file (like non-existence)
            oldFile.renameTo(settingsFile);
        }
        return new AtomicFile(settingsFile);
    }

    void onUserStopped(int userId) {
        synchronized (mLock) {
            boolean crossProfileWidgetsChanged = false;

            // Remove widgets that have both host and provider in the user.
            final int widgetCount = mWidgets.size();
            for (int i = widgetCount - 1; i >= 0; i--) {
                Widget widget = mWidgets.get(i);

                final boolean hostInUser = widget.host.getUserId() == userId;
                final boolean hasProvider = widget.provider != null;
                final boolean providerInUser = hasProvider && widget.provider.getUserId() == userId;

                // If both host and provider are in the user, just drop the widgets
                // as we do not want to make host callbacks and provider broadcasts
                // as the host and the provider will be killed.
                if (hostInUser && (!hasProvider || providerInUser)) {
                    removeWidgetLocked(widget);
                    widget.host.widgets.remove(widget);
                    widget.host = null;
                    if (hasProvider) {
                        widget.provider.widgets.remove(widget);
                        widget.provider = null;
                    }
                }
            }

            // Remove hosts and notify providers in other profiles.
            final int hostCount = mHosts.size();
            for (int i = hostCount - 1; i >= 0; i--) {
                Host host = mHosts.get(i);
                if (host.getUserId() == userId) {
                    crossProfileWidgetsChanged |= !host.widgets.isEmpty();
                    deleteHostLocked(host);
                }
            }

            // Leave the providers present as hosts will show the widgets
            // masked while the user is stopped.

            // Remove grants for this user.
            final int grantCount = mPackagesWithBindWidgetPermission.size();
            for (int i = grantCount - 1; i >= 0; i--) {
                Pair<Integer, String> packageId = mPackagesWithBindWidgetPermission.valueAt(i);
                if (packageId.first == userId) {
                    mPackagesWithBindWidgetPermission.removeAt(i);
                }
            }

            // Take a note we no longer have state for this user.
            final int userIndex = mLoadedUserIds.indexOfKey(userId);
            if (userIndex >= 0) {
                mLoadedUserIds.removeAt(userIndex);
            }

            // Remove the widget id counter.
            final int nextIdIndex = mNextAppWidgetIds.indexOfKey(userId);
            if (nextIdIndex >= 0) {
                mNextAppWidgetIds.removeAt(nextIdIndex);
            }

            // Save state if removing a profile changed the group state.
            // Nothing will be saved if the group parent was removed.
            if (crossProfileWidgetsChanged) {
                saveGroupStateAsync(userId);
            }
        }
    }

    private void applyResourceOverlaysToWidgetsLocked(Set<String> packageNames, int userId,
            boolean updateFrameworkRes) {
        for (int i = 0, N = mProviders.size(); i < N; i++) {
            Provider provider = mProviders.get(i);
            if (provider.getUserId() != userId) {
                continue;
            }

            final String packageName = provider.id.componentName.getPackageName();
            if (!updateFrameworkRes && !packageNames.contains(packageName)) {
                continue;
            }

            ApplicationInfo newAppInfo = null;
            try {
                newAppInfo = mPackageManager.getApplicationInfo(packageName,
                        PackageManager.GET_SHARED_LIBRARY_FILES, userId);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to retrieve app info for " + packageName
                        + " userId=" + userId, e);
            }
            if (newAppInfo == null || provider.info == null
                    || provider.info.providerInfo == null) {
                continue;
            }
            ApplicationInfo oldAppInfo = provider.info.providerInfo.applicationInfo;
            if (oldAppInfo == null || !newAppInfo.sourceDir.equals(oldAppInfo.sourceDir)) {
                // Overlay paths are generated against a particular version of an application.
                // The overlays paths of a newly upgraded application are incompatible with the
                // old version of the application.
                continue;
            }

            // Isolate the changes relating to RROs. The app info must be copied to prevent
            // affecting other parts of system server that may have cached this app info.
            oldAppInfo = new ApplicationInfo(oldAppInfo);
            oldAppInfo.overlayPaths = newAppInfo.overlayPaths == null
                    ? null : newAppInfo.overlayPaths.clone();
            oldAppInfo.resourceDirs = newAppInfo.resourceDirs == null
                    ? null : newAppInfo.resourceDirs.clone();
            provider.info.providerInfo.applicationInfo = oldAppInfo;

            for (int j = 0, M = provider.widgets.size(); j < M; j++) {
                Widget widget = provider.widgets.get(j);
                if (widget.views != null) {
                    widget.views.updateAppInfo(oldAppInfo);
                }
                if (widget.maskedViews != null) {
                    widget.maskedViews.updateAppInfo(oldAppInfo);
                }
            }
        }
    }

    /**
     * Updates all providers with the specified package names, and records any providers that were
     * pruned.
     *
     * @return whether any providers were updated
     */
    @GuardedBy("mLock")
    private boolean updateProvidersForPackageLocked(String packageName, int userId,
            Set<ProviderId> removedProviders) {
        boolean providersUpdated = false;

        HashSet<ProviderId> keep = new HashSet<>();
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        intent.setPackage(packageName);
        List<ResolveInfo> broadcastReceivers = queryIntentReceivers(intent, userId);

        // add the missing ones and collect which ones to keep
        int N = broadcastReceivers == null ? 0 : broadcastReceivers.size();
        for (int i = 0; i < N; i++) {
            ResolveInfo ri = broadcastReceivers.get(i);
            ActivityInfo ai = ri.activityInfo;

            if ((ai.applicationInfo.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0) {
                continue;
            }

            if (packageName.equals(ai.packageName)) {
                ProviderId providerId = new ProviderId(ai.applicationInfo.uid,
                        new ComponentName(ai.packageName, ai.name));

                Provider provider = lookupProviderLocked(providerId);
                if (provider == null) {
                    if (addProviderLocked(ri)) {
                        keep.add(providerId);
                        providersUpdated = true;
                    }
                } else {
                    AppWidgetProviderInfo info =
                            createPartialProviderInfo(providerId, ri, provider);
                    if (info != null) {
                        keep.add(providerId);
                        // Use the new AppWidgetProviderInfo.
                        provider.setPartialInfoLocked(info);
                        // If it's enabled
                        final int M = provider.widgets.size();
                        if (M > 0) {
                            int[] appWidgetIds = getWidgetIds(provider.widgets);
                            // Reschedule for the new updatePeriodMillis (don't worry about handling
                            // it specially if updatePeriodMillis didn't change because we just sent
                            // an update, and the next one will be updatePeriodMillis from now).
                            cancelBroadcastsLocked(provider);
                            registerForBroadcastsLocked(provider, appWidgetIds);
                            // If it's currently showing, call back with the new
                            // AppWidgetProviderInfo.
                            for (int j = 0; j < M; j++) {
                                Widget widget = provider.widgets.get(j);
                                widget.views = null;
                                scheduleNotifyProviderChangedLocked(widget);
                            }
                            // Now that we've told the host, push out an update.
                            sendUpdateIntentLocked(provider, appWidgetIds, false);
                        }
                    }
                    providersUpdated = true;
                }
            }
        }

        // prune the ones we don't want to keep
        N = mProviders.size();
        for (int i = N - 1; i >= 0; i--) {
            Provider provider = mProviders.get(i);
            if (packageName.equals(provider.id.componentName.getPackageName())
                    && provider.getUserId() == userId
                    && !keep.contains(provider.id)) {
                if (removedProviders != null) {
                    removedProviders.add(provider.id);
                }
                deleteProviderLocked(provider);
                providersUpdated = true;
            }
        }

        return providersUpdated;
    }

    // Remove widgets for provider in userId that are hosted in parentUserId
    private void removeWidgetsForPackageLocked(String pkgName, int userId, int parentUserId) {
        final int N = mProviders.size();
        for (int i = 0; i < N; ++i) {
            Provider provider = mProviders.get(i);
            if (pkgName.equals(provider.id.componentName.getPackageName())
                    && provider.getUserId() == userId
                    && provider.widgets.size() > 0) {
                deleteWidgetsLocked(provider, parentUserId);
            }
        }
    }

    private boolean removeProvidersForPackageLocked(String pkgName, int userId) {
        boolean removed = false;

        final int N = mProviders.size();
        for (int i = N - 1; i >= 0; i--) {
            Provider provider = mProviders.get(i);
            if (pkgName.equals(provider.id.componentName.getPackageName())
                    && provider.getUserId() == userId) {
                deleteProviderLocked(provider);
                removed = true;
            }
        }
        return removed;
    }

    private boolean removeHostsAndProvidersForPackageLocked(String pkgName, int userId) {
        boolean removed = removeProvidersForPackageLocked(pkgName, userId);

        // Delete the hosts for this package too
        // By now, we have removed any AppWidgets that were in any hosts here,
        // so we don't need to worry about sending DISABLE broadcasts to them.
        final int N = mHosts.size();
        for (int i = N - 1; i >= 0; i--) {
            Host host = mHosts.get(i);
            if (pkgName.equals(host.id.packageName)
                    && host.getUserId() == userId) {
                deleteHostLocked(host);
                removed = true;
            }
        }

        return removed;
    }

    private String getCanonicalPackageName(String packageName, String className, int userId) {
        final long identity = Binder.clearCallingIdentity();
        try {
            try {
                AppGlobals.getPackageManager().getReceiverInfo(new ComponentName(packageName,
                        className), 0, userId);
                return packageName;
            } catch (RemoteException re) {
                String[] packageNames = mContext.getPackageManager()
                        .currentToCanonicalPackageNames(new String[]{packageName});
                if (packageNames != null && packageNames.length > 0) {
                    return packageNames[0];
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return null;
    }

    /**
     * Sends a widget lifecycle broadcast within the specified user.  If {@code isInteractive}
     * is specified as {@code true}, the broadcast dispatch mechanism will be told that it
     * is related to a UX flow with user-visible expectations about timely dispatch.  This
     * should only be used for broadcast flows that do have such expectations.
     */
    private void sendBroadcastAsUser(Intent intent, UserHandle userHandle, boolean isInteractive) {
        final long identity = Binder.clearCallingIdentity();
        try {
            mContext.sendBroadcastAsUser(intent, userHandle, null,
                    isInteractive ? mInteractiveBroadcast : null);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void bindService(Intent intent, ServiceConnection connection,
            UserHandle userHandle) {
        final long token = Binder.clearCallingIdentity();
        try {
            mContext.bindServiceAsUser(intent, connection,
                    Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE,
                    userHandle);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void unbindService(ServiceConnection connection) {
        final long token = Binder.clearCallingIdentity();
        try {
            mContext.unbindService(connection);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void onCrossProfileWidgetProvidersChanged(int userId, List<String> packages) {
        final int parentId = mSecurityPolicy.getProfileParent(userId);
        // We care only if the allowlisted package is in a profile of
        // the group parent as only the parent can add widgets from the
        // profile and not the other way around.
        if (parentId != userId) {
            synchronized (mLock) {
                boolean providersChanged = false;

                ArraySet<String> previousPackages = new ArraySet<String>();
                final int providerCount = mProviders.size();
                for (int i = 0; i < providerCount; ++i) {
                    Provider provider = mProviders.get(i);
                    if (provider.getUserId() == userId) {
                        previousPackages.add(provider.id.componentName.getPackageName());
                    }
                }

                final int packageCount = packages.size();
                for (int i = 0; i < packageCount; i++) {
                    String packageName = packages.get(i);
                    previousPackages.remove(packageName);
                    providersChanged |= updateProvidersForPackageLocked(packageName,
                            userId, null);
                }

                // Remove widgets from hosts in parent user for packages not in the allowlist
                final int removedCount = previousPackages.size();
                for (int i = 0; i < removedCount; ++i) {
                    removeWidgetsForPackageLocked(previousPackages.valueAt(i),
                            userId, parentId);
                }

                if (providersChanged || removedCount > 0) {
                    saveGroupStateAsync(userId);
                    scheduleNotifyGroupHostsForProvidersChangedLocked(userId);
                }
            }
        }
    }

    private boolean isProfileWithLockedParent(int userId) {
        final long token = Binder.clearCallingIdentity();
        try {
            UserInfo userInfo = mUserManager.getUserInfo(userId);
            if (userInfo != null && userInfo.isProfile()) {
                UserInfo parentInfo = mUserManager.getProfileParent(userId);
                if (parentInfo != null
                        && !isUserRunningAndUnlocked(parentInfo.getUserHandle().getIdentifier())) {
                    return true;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return false;
    }

    private boolean isProfileWithUnlockedParent(int userId) {
        UserInfo userInfo = mUserManager.getUserInfo(userId);
        if (userInfo != null && userInfo.isProfile()) {
            UserInfo parentInfo = mUserManager.getProfileParent(userId);
            if (parentInfo != null
                    && mUserManager.isUserUnlockingOrUnlocked(parentInfo.getUserHandle())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Note an app widget is tapped on. If a app widget is tapped, the underlying app is treated as
     * foreground so the app can get while-in-use permission.
     *
     * @param callingPackage calling app's packageName.
     * @param appWidgetId App widget id.
     */
    @Override
    public void noteAppWidgetTapped(String callingPackage, int appWidgetId) {
        mSecurityPolicy.enforceCallFromPackage(callingPackage);
        final int callingUid = Binder.getCallingUid();
        final long ident = Binder.clearCallingIdentity();
        try {
            // The launcher must be at TOP.
            final int procState = mActivityManagerInternal.getUidProcessState(callingUid);
            if (procState > ActivityManager.PROCESS_STATE_TOP) {
                return;
            }
            synchronized (mLock) {
                final Widget widget = lookupWidgetLocked(appWidgetId, callingUid, callingPackage);
                if (widget == null) {
                    return;
                }
                final ProviderId providerId = widget.provider.id;
                final String packageName = providerId.componentName.getPackageName();
                if (packageName == null) {
                    return;
                }
                final SparseArray<String> uid2PackageName = new SparseArray<String>();
                uid2PackageName.put(providerId.uid, packageName);
                mAppOpsManagerInternal.updateAppWidgetVisibility(uid2PackageName, true);
                mUsageStatsManagerInternal.reportEvent(packageName,
                        UserHandle.getUserId(providerId.uid), UsageEvents.Event.USER_INTERACTION);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private final class CallbackHandler extends Handler {
        public static final int MSG_NOTIFY_UPDATE_APP_WIDGET = 1;
        public static final int MSG_NOTIFY_PROVIDER_CHANGED = 2;
        public static final int MSG_NOTIFY_PROVIDERS_CHANGED = 3;
        public static final int MSG_NOTIFY_VIEW_DATA_CHANGED = 4;
        public static final int MSG_NOTIFY_APP_WIDGET_REMOVED = 5;

        public CallbackHandler(Looper looper) {
            super(looper, null, false);
        }

        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case MSG_NOTIFY_UPDATE_APP_WIDGET: {
                    SomeArgs args = (SomeArgs) message.obj;
                    Host host = (Host) args.arg1;
                    IAppWidgetHost callbacks = (IAppWidgetHost) args.arg2;
                    RemoteViews views = (RemoteViews) args.arg3;
                    long requestId = (Long) args.arg4;
                    final int appWidgetId = args.argi1;
                    args.recycle();

                    handleNotifyUpdateAppWidget(host, callbacks, appWidgetId, views, requestId);
                } break;

                case MSG_NOTIFY_PROVIDER_CHANGED: {
                    SomeArgs args = (SomeArgs) message.obj;
                    Host host = (Host) args.arg1;
                    IAppWidgetHost callbacks = (IAppWidgetHost) args.arg2;
                    AppWidgetProviderInfo info = (AppWidgetProviderInfo)args.arg3;
                    long requestId = (Long) args.arg4;
                    final int appWidgetId = args.argi1;
                    args.recycle();

                    handleNotifyProviderChanged(host, callbacks, appWidgetId, info, requestId);
                } break;

                case MSG_NOTIFY_APP_WIDGET_REMOVED: {
                    SomeArgs args = (SomeArgs) message.obj;
                    Host host = (Host) args.arg1;
                    IAppWidgetHost callbacks = (IAppWidgetHost) args.arg2;
                    long requestId = (Long) args.arg3;
                    final int appWidgetId = args.argi1;
                    args.recycle();
                    handleNotifyAppWidgetRemoved(host, callbacks, appWidgetId, requestId);
                } break;

                case MSG_NOTIFY_PROVIDERS_CHANGED: {
                    SomeArgs args = (SomeArgs) message.obj;
                    Host host = (Host) args.arg1;
                    IAppWidgetHost callbacks = (IAppWidgetHost) args.arg2;
                    args.recycle();

                    handleNotifyProvidersChanged(host, callbacks);
                } break;

                case MSG_NOTIFY_VIEW_DATA_CHANGED: {
                    SomeArgs args = (SomeArgs) message.obj;
                    Host host = (Host) args.arg1;
                    IAppWidgetHost callbacks = (IAppWidgetHost) args.arg2;
                    long requestId = (Long) args.arg3;
                    final int appWidgetId = args.argi1;
                    final int viewId = args.argi2;
                    args.recycle();

                    handleNotifyAppWidgetViewDataChanged(host, callbacks, appWidgetId, viewId,
                            requestId);
                } break;
            }
        }
    }

    private final class SecurityPolicy {

        public boolean isEnabledGroupProfile(int profileId) {
            final int parentId = UserHandle.getCallingUserId();
            return isParentOrProfile(parentId, profileId) && isProfileEnabled(profileId);
        }

        public int[] getEnabledGroupProfileIds(int userId) {
            final int parentId = getGroupParent(userId);

            final long identity = Binder.clearCallingIdentity();
            try {
                return mUserManager.getEnabledProfileIds(parentId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        public void enforceServiceExistsAndRequiresBindRemoteViewsPermission(
                ComponentName componentName, int userId) {
            final long identity = Binder.clearCallingIdentity();
            try {
                ServiceInfo serviceInfo = mPackageManager.getServiceInfo(componentName,
                        PackageManager.GET_PERMISSIONS, userId);
                if (serviceInfo == null) {
                    throw new SecurityException("Service " + componentName
                            + " not installed for user " + userId);
                }
                if (!android.Manifest.permission.BIND_REMOTEVIEWS.equals(serviceInfo.permission)) {
                    throw new SecurityException("Service " + componentName
                            + " in user " + userId + "does not require "
                            + android.Manifest.permission.BIND_REMOTEVIEWS);
                }
            } catch (RemoteException re) {
                // Local call - shouldn't happen.
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        public void enforceModifyAppWidgetBindPermissions(String packageName) {
            mContext.enforceCallingPermission(
                    android.Manifest.permission.MODIFY_APPWIDGET_BIND_PERMISSIONS,
                    "hasBindAppWidgetPermission packageName=" + packageName);
        }

        public boolean isCallerInstantAppLocked() {
            final int callingUid =  Binder.getCallingUid();
            final long identity = Binder.clearCallingIdentity();
            try {
                final String[] uidPackages = mPackageManager.getPackagesForUid(callingUid);
                if (!ArrayUtils.isEmpty(uidPackages)) {
                    return mPackageManager.isInstantApp(uidPackages[0],
                            UserHandle.getUserId(callingUid));
                }
            } catch (RemoteException e) {
                /* ignore - same process */
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            return false;
        }

        public boolean isInstantAppLocked(String packageName, int userId) {
            final long identity = Binder.clearCallingIdentity();
            try {
                return mPackageManager.isInstantApp(packageName, userId);
            } catch (RemoteException e) {
                /* ignore - same process */
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            return false;
        }

        public void enforceCallFromPackage(String packageName) {
            mAppOpsManager.checkPackage(Binder.getCallingUid(), packageName);
        }

        public boolean hasCallerBindPermissionOrBindWhiteListedLocked(String packageName) {
            try {
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.BIND_APPWIDGET, null);
            } catch (SecurityException se) {
                if (!isCallerBindAppWidgetWhiteListedLocked(packageName)) {
                    return false;
                }
            }
            return true;
        }

        private boolean isCallerBindAppWidgetWhiteListedLocked(String packageName) {
            final int userId = UserHandle.getCallingUserId();
            final int packageUid = getUidForPackage(packageName, userId);
            if (packageUid < 0) {
                throw new IllegalArgumentException("No package " + packageName
                        + " for user " + userId);
            }
            synchronized (mLock) {
                ensureGroupStateLoadedLocked(userId);

                Pair<Integer, String> packageId = Pair.create(userId, packageName);
                if (mPackagesWithBindWidgetPermission.contains(packageId)) {
                    return true;
                }
            }

            return false;
        }

        public boolean canAccessAppWidget(Widget widget, int uid, String packageName) {
            if (isHostInPackageForUid(widget.host, uid, packageName)) {
                // Apps hosting the AppWidget have access to it.
                return true;
            }
            if (isProviderInPackageForUid(widget.provider, uid, packageName)) {
                // Apps providing the AppWidget have access to it.
                return true;
            }
            if (isHostAccessingProvider(widget.host, widget.provider, uid, packageName)) {
                // Apps hosting the AppWidget get to bind to a remote view service in the provider.
                return true;
            }
            final int userId = UserHandle.getUserId(uid);
            if ((widget.host.getUserId() == userId || (widget.provider != null
                    && widget.provider.getUserId() == userId))
                && mContext.checkCallingPermission(android.Manifest.permission.BIND_APPWIDGET)
                    == PackageManager.PERMISSION_GRANTED) {
                // Apps that run in the same user as either the host or the provider and
                // have the bind widget permission have access to the widget.
                return true;
            }
            return false;
        }

        private boolean isParentOrProfile(int parentId, int profileId) {
            if (parentId == profileId) {
                return true;
            }
            return getProfileParent(profileId) == parentId;
        }

        public boolean isProviderInCallerOrInProfileAndWhitelListed(String packageName,
                int profileId) {
            final int callerId = UserHandle.getCallingUserId();
            if (profileId == callerId) {
                return true;
            }
            final int parentId = getProfileParent(profileId);
            if (parentId != callerId) {
                return false;
            }
            return isProviderWhiteListed(packageName, profileId);
        }

        public boolean isProviderWhiteListed(String packageName, int profileId) {
            // If the policy manager is not available on the device we deny it all.
            if (mDevicePolicyManagerInternal == null) {
                return false;
            }

            List<String> crossProfilePackages = mDevicePolicyManagerInternal
                    .getCrossProfileWidgetProviders(profileId);

            return crossProfilePackages.contains(packageName);
        }

        public int getProfileParent(int profileId) {
            final long identity = Binder.clearCallingIdentity();
            try {
                UserInfo parent = mUserManager.getProfileParent(profileId);
                if (parent != null) {
                    return parent.getUserHandle().getIdentifier();
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            return UNKNOWN_USER_ID;
        }

        public int getGroupParent(int profileId) {
            final int parentId = mSecurityPolicy.getProfileParent(profileId);
            return (parentId != UNKNOWN_USER_ID) ? parentId : profileId;
        }

        public boolean isHostInPackageForUid(Host host, int uid, String packageName) {
            return host.id.uid == uid && host.id.packageName.equals(packageName);
        }

        public boolean isProviderInPackageForUid(Provider provider, int uid,
                String packageName) {
            // Packages providing the AppWidget have access to it.
            return provider != null && provider.id.uid == uid
                    && provider.id.componentName.getPackageName().equals(packageName);
        }

        public boolean isHostAccessingProvider(Host host, Provider provider, int uid,
                String packageName) {
            // The host creates a package context to bind to remote views service in the provider.
            return host.id.uid == uid && provider != null
                    && provider.id.componentName.getPackageName().equals(packageName);
        }

        private boolean isProfileEnabled(int profileId) {
            final long identity = Binder.clearCallingIdentity();
            try {
                UserInfo userInfo = mUserManager.getUserInfo(profileId);
                if (userInfo == null || !userInfo.isEnabled()) {
                    return false;
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            return true;
        }
    }

    private static final class Provider {

        ProviderId id;
        AppWidgetProviderInfo info;
        ArrayList<Widget> widgets = new ArrayList<>();
        PendingIntent broadcast;
        String infoTag;

        boolean zombie; // if we're in safe mode, don't prune this just because nobody references it

        boolean maskedByLockedProfile;
        boolean maskedByQuietProfile;
        boolean maskedBySuspendedPackage;

        boolean mInfoParsed = false;

        int tag = TAG_UNDEFINED; // for use while saving state (the index)

        public int getUserId() {
            return UserHandle.getUserId(id.uid);
        }

        public boolean isInPackageForUser(String packageName, int userId) {
            return getUserId() == userId
                    && id.componentName.getPackageName().equals(packageName);
        }

        // is there an instance of this provider hosted by the given app?
        public boolean hostedByPackageForUser(String packageName, int userId) {
            final int N = widgets.size();
            for (int i = 0; i < N; i++) {
                Widget widget = widgets.get(i);
                if (packageName.equals(widget.host.id.packageName)
                        && widget.host.getUserId() == userId) {
                    return true;
                }
            }
            return false;
        }

        @GuardedBy("mLock")
        public AppWidgetProviderInfo getInfoLocked(Context context) {
            if (!mInfoParsed) {
                // parse
                if (!zombie) {
                    AppWidgetProviderInfo newInfo = null;
                    if (!TextUtils.isEmpty(infoTag)) {
                        newInfo = parseAppWidgetProviderInfo(
                                context, id, info.providerInfo, infoTag);
                    }
                    if (newInfo == null) {
                        newInfo = parseAppWidgetProviderInfo(context, id, info.providerInfo,
                                AppWidgetManager.META_DATA_APPWIDGET_PROVIDER);
                    }
                    if (newInfo != null) {
                        info = newInfo;
                    }
                }
                mInfoParsed = true;
            }
            return info;
        }

        /**
         * Returns the last updated AppWidgetProviderInfo for this provider. This info may not
         * be completely parsed and only contain placeHolder information like
         * {@link AppWidgetProviderInfo#providerInfo}
         */
        @GuardedBy("mLock")
        public AppWidgetProviderInfo getPartialInfoLocked() {
            return info;
        }

        @GuardedBy("mLock")
        public void setPartialInfoLocked(AppWidgetProviderInfo info) {
            this.info = info;
            mInfoParsed = false;
        }

        @GuardedBy("mLock")
        public void setInfoLocked(AppWidgetProviderInfo info) {
            this.info = info;
            mInfoParsed = true;
        }

        @Override
        public String toString() {
            return "Provider{" + id + (zombie ? " Z" : "") + '}';
        }

        // returns true if it's different from previous state.
        public boolean setMaskedByQuietProfileLocked(boolean masked) {
            boolean oldState = maskedByQuietProfile;
            maskedByQuietProfile = masked;
            return masked != oldState;
        }

        // returns true if it's different from previous state.
        public boolean setMaskedByLockedProfileLocked(boolean masked) {
            boolean oldState = maskedByLockedProfile;
            maskedByLockedProfile = masked;
            return masked != oldState;
        }

        // returns true if it's different from previous state.
        public boolean setMaskedBySuspendedPackageLocked(boolean masked) {
            boolean oldState = maskedBySuspendedPackage;
            maskedBySuspendedPackage = masked;
            return masked != oldState;
        }

        public boolean isMaskedLocked() {
            return maskedByQuietProfile || maskedByLockedProfile || maskedBySuspendedPackage;
        }

        public boolean shouldBePersisted() {
            return !widgets.isEmpty() || !TextUtils.isEmpty(infoTag);
        }
    }

    private static final class ProviderId {
        final int uid;
        final ComponentName componentName;

        private ProviderId(int uid, ComponentName componentName) {
            this.uid = uid;
            this.componentName = componentName;
        }

        public UserHandle getProfile() {
            return UserHandle.getUserHandleForUid(uid);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            ProviderId other = (ProviderId) obj;
            if (uid != other.uid)  {
                return false;
            }
            if (componentName == null) {
                if (other.componentName != null) {
                    return false;
                }
            } else if (!componentName.equals(other.componentName)) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            int result = uid;
            result = 31 * result + ((componentName != null)
                    ? componentName.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "ProviderId{user:" + UserHandle.getUserId(uid) + ", app:"
                    + UserHandle.getAppId(uid) + ", cmp:" + componentName + '}';
        }
    }

    private static final class Host {
        HostId id;
        ArrayList<Widget> widgets = new ArrayList<>();
        IAppWidgetHost callbacks;
        boolean zombie; // if we're in safe mode, don't prune this just because nobody references it

        private static final boolean DEBUG = true;

        private static final String TAG = "AppWidgetServiceHost";

        int tag = TAG_UNDEFINED; // for use while saving state (the index)
        // Sequence no for the last update successfully sent. This is updated whenever a
        // widget update is successfully sent to the host callbacks. As all new/undelivered updates
        // will have sequenceNo greater than this, all those updates will be sent when the host
        // callbacks are attached again.
        long lastWidgetUpdateSequenceNo;

        public int getUserId() {
            return UserHandle.getUserId(id.uid);
        }

        public boolean isInPackageForUser(String packageName, int userId) {
            return getUserId() == userId && id.packageName.equals(packageName);
        }

        private boolean hostsPackageForUser(String pkg, int userId) {
            final int N = widgets.size();
            for (int i = 0; i < N; i++) {
                Provider provider = widgets.get(i).provider;
                if (provider != null && provider.getUserId() == userId
                        && pkg.equals(provider.id.componentName.getPackageName())) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Adds all pending updates in {@param outUpdates} keys by the update time.
         */
        @GuardedBy("mLock")
        public void getPendingUpdatesForIdLocked(Context context, int appWidgetId,
                LongSparseArray<PendingHostUpdate> outUpdates) {
            long updateSequenceNo = lastWidgetUpdateSequenceNo;
            int N = widgets.size();
            for (int i = 0; i < N; i++) {
                Widget widget = widgets.get(i);
                if (widget.appWidgetId == appWidgetId) {
                    for (int j = widget.updateSequenceNos.size() - 1; j >= 0; j--) {
                        long requestId = widget.updateSequenceNos.valueAt(j);
                        if (requestId <= updateSequenceNo) {
                            continue;
                        }
                        int id = widget.updateSequenceNos.keyAt(j);
                        final PendingHostUpdate update;
                        switch (id) {
                            case ID_PROVIDER_CHANGED:
                                update = PendingHostUpdate.providerChanged(
                                        appWidgetId, widget.provider.getInfoLocked(context));
                                break;
                            case ID_VIEWS_UPDATE:
                                update = PendingHostUpdate.updateAppWidget(appWidgetId,
                                        cloneIfLocalBinder(widget.getEffectiveViewsLocked()));
                                break;
                            default:
                                update = PendingHostUpdate.viewDataChanged(appWidgetId, id);
                        }
                        outUpdates.put(requestId, update);
                    }
                    return;
                }
            }
            outUpdates.put(lastWidgetUpdateSequenceNo,
                    PendingHostUpdate.appWidgetRemoved(appWidgetId));
        }

        public SparseArray<String> getWidgetUidsIfBound() {
            final SparseArray<String> uids = new SparseArray<>();
            for (int i = widgets.size() - 1; i >= 0; i--) {
                final Widget widget = widgets.get(i);
                if (widget.provider == null) {
                    if (DEBUG) {
                        Slog.d(TAG, "Widget with no provider " + widget.toString());
                    }
                    continue;
                }
                final ProviderId providerId = widget.provider.id;
                uids.put(providerId.uid, providerId.componentName.getPackageName());
            }
            return uids;
        }

        @Override
        public String toString() {
            return "Host{" + id + (zombie ? " Z" : "") + '}';
        }
    }

    private static final class HostId {
        final int uid;
        final int hostId;
        final String packageName;

        public HostId(int uid, int hostId, String packageName) {
            this.uid = uid;
            this.hostId = hostId;
            this.packageName = packageName;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            HostId other = (HostId) obj;
            if (uid != other.uid)  {
                return false;
            }
            if (hostId != other.hostId) {
                return false;
            }
            if (packageName == null) {
                if (other.packageName != null) {
                    return false;
                }
            } else if (!packageName.equals(other.packageName)) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            int result = uid;
            result = 31 * result + hostId;
            result = 31 * result + ((packageName != null)
                    ? packageName.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "HostId{user:" + UserHandle.getUserId(uid) + ", app:"
                    + UserHandle.getAppId(uid) + ", hostId:" + hostId
                    + ", pkg:" + packageName + '}';
        }
    }

    // These can be any constants that would not collide with a resource id.
    private static final int ID_VIEWS_UPDATE = 0;
    private static final int ID_PROVIDER_CHANGED = 1;

    private static final class Widget {
        int appWidgetId;
        int restoredId;  // tracking & remapping any restored state
        Provider provider;
        RemoteViews views;
        RemoteViews maskedViews;
        Bundle options;
        Host host;
        // Map of request type to updateSequenceNo.
        SparseLongArray updateSequenceNos = new SparseLongArray(2);
        boolean trackingUpdate = false;

        @Override
        public String toString() {
            return "AppWidgetId{" + appWidgetId + ':' + host + ':' + provider + '}';
        }

        private boolean replaceWithMaskedViewsLocked(RemoteViews views) {
            maskedViews = views;
            return true;
        }

        private boolean clearMaskedViewsLocked() {
            if (maskedViews != null) {
                maskedViews = null;
                return true;
            } else {
                return false;
            }
        }

        public RemoteViews getEffectiveViewsLocked() {
            return maskedViews != null ? maskedViews : views;
        }
    }

    private class LoadedWidgetState {
        final Widget widget;
        final int hostTag;
        final int providerTag;

        public LoadedWidgetState(Widget widget, int hostTag, int providerTag) {
            this.widget = widget;
            this.hostTag = hostTag;
            this.providerTag = providerTag;
        }
    }

    private final class SaveStateRunnable implements Runnable {
        final int mUserId;

        public SaveStateRunnable(int userId) {
            mUserId = userId;
        }

        @Override
        public void run() {
            synchronized (mLock) {
                // No need to enforce unlocked state when there is no caller. User can be in the
                // stopping state or removed by the time the message is processed
                ensureGroupStateLoadedLocked(mUserId, false /* enforceUserUnlockingOrUnlocked */ );
                saveStateLocked(mUserId);
            }
        }
    }

    /**
     * This class encapsulates the backup and restore logic for a user group state.
     */
    private final class BackupRestoreController {
        private static final String TAG = "BackupRestoreController";

        private static final boolean DEBUG = true;

        // Version of backed-up widget state.
        private static final int WIDGET_STATE_VERSION = 2;

        // We need to make sure to wipe the pre-restore widget state only once for
        // a given package.  Keep track of what we've done so far here; the list is
        // cleared at the start of every system restore pass, but preserved through
        // any install-time restore operations.
        private final SparseArray<Set<String>> mPrunedAppsPerUser = new SparseArray<>();

        private final HashMap<Provider, ArrayList<RestoreUpdateRecord>> mUpdatesByProvider =
                new HashMap<>();
        private final HashMap<Host, ArrayList<RestoreUpdateRecord>> mUpdatesByHost =
                new HashMap<>();

        @GuardedBy("mLock")
        private boolean mHasSystemRestoreFinished;

        public List<String> getWidgetParticipants(int userId) {
            if (DEBUG) {
                Slog.i(TAG, "Getting widget participants for user: " + userId);
            }

            HashSet<String> packages = new HashSet<>();
            synchronized (mLock) {
                final int N = mWidgets.size();
                for (int i = 0; i < N; i++) {
                    Widget widget = mWidgets.get(i);

                    // Skip cross-user widgets.
                    if (!isProviderAndHostInUser(widget, userId)) {
                        continue;
                    }

                    packages.add(widget.host.id.packageName);
                    Provider provider = widget.provider;
                    if (provider != null) {
                        packages.add(provider.id.componentName.getPackageName());
                    }
                }
            }
            return new ArrayList<>(packages);
        }

        public byte[] getWidgetState(String backedupPackage, int userId) {
            if (DEBUG) {
                Slog.i(TAG, "Getting widget state for user: " + userId);
            }

            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            synchronized (mLock) {
                // Preflight: if this app neither hosts nor provides any live widgets
                // we have no work to do.
                if (!packageNeedsWidgetBackupLocked(backedupPackage, userId)) {
                    return null;
                }

                try {
                    TypedXmlSerializer out = Xml.newFastSerializer();
                    out.setOutput(stream, StandardCharsets.UTF_8.name());
                    out.startDocument(null, true);
                    out.startTag(null, "ws");      // widget state
                    out.attributeInt(null, "version", WIDGET_STATE_VERSION);
                    out.attribute(null, "pkg", backedupPackage);

                    // Remember all the providers that are currently hosted or published
                    // by this package: that is, all of the entities related to this app
                    // which will need to be told about id remapping.
                    int index = 0;
                    int N = mProviders.size();
                    for (int i = 0; i < N; i++) {
                        Provider provider = mProviders.get(i);

                        if (provider.shouldBePersisted()
                                && (provider.isInPackageForUser(backedupPackage, userId)
                                || provider.hostedByPackageForUser(backedupPackage, userId))) {
                            provider.tag = index;
                            serializeProvider(out, provider);
                            index++;
                        }
                    }

                    N = mHosts.size();
                    index = 0;
                    for (int i = 0; i < N; i++) {
                        Host host = mHosts.get(i);

                        if (!host.widgets.isEmpty()
                                && (host.isInPackageForUser(backedupPackage, userId)
                                || host.hostsPackageForUser(backedupPackage, userId))) {
                            host.tag = index;
                            serializeHost(out, host);
                            index++;
                        }
                    }

                    // All widget instances involving this package,
                    // either as host or as provider
                    N = mWidgets.size();
                    for (int i = 0; i < N; i++) {
                        Widget widget = mWidgets.get(i);

                        Provider provider = widget.provider;
                        if (widget.host.isInPackageForUser(backedupPackage, userId)
                                || (provider != null
                                &&  provider.isInPackageForUser(backedupPackage, userId))) {
                            serializeAppWidget(out, widget, false);
                        }
                    }

                    out.endTag(null, "ws");
                    out.endDocument();
                } catch (IOException e) {
                    Slog.w(TAG, "Unable to save widget state for " + backedupPackage);
                    return null;
                }
            }

            return stream.toByteArray();
        }

        public void systemRestoreStarting(int userId) {
            if (DEBUG) {
                Slog.i(TAG, "System restore starting for user: " + userId);
            }

            synchronized (mLock) {
                mHasSystemRestoreFinished = false;
                // We're starting a new "system" restore operation, so any widget restore
                // state that we see from here on is intended to replace the current
                // widget configuration of any/all of the affected apps.
                getPrunedAppsLocked(userId).clear();
                mUpdatesByProvider.clear();
                mUpdatesByHost.clear();
            }
        }

        public void restoreWidgetState(String packageName, byte[] restoredState, int userId) {
            if (DEBUG) {
                Slog.i(TAG, "Restoring widget state for user:" + userId
                        + " package: " + packageName);
            }

            ByteArrayInputStream stream = new ByteArrayInputStream(restoredState);
            try {
                // Providers mentioned in the widget dataset by ordinal
                ArrayList<Provider> restoredProviders = new ArrayList<>();

                // Hosts mentioned in the widget dataset by ordinal
                ArrayList<Host> restoredHosts = new ArrayList<>();

                TypedXmlPullParser parser = Xml.newFastPullParser();
                parser.setInput(stream, StandardCharsets.UTF_8.name());

                synchronized (mLock) {
                    int type;
                    do {
                        type = parser.next();
                        if (type == XmlPullParser.START_TAG) {
                            final String tag = parser.getName();
                            if ("ws".equals(tag)) {
                                final int versionNumber = parser.getAttributeInt(null, "version");
                                if (versionNumber > WIDGET_STATE_VERSION) {
                                    Slog.w(TAG, "Unable to process state version " + versionNumber);
                                    return;
                                }

                                // TODO: fix up w.r.t. canonical vs current package names
                                String pkg = parser.getAttributeValue(null, "pkg");
                                if (!packageName.equals(pkg)) {
                                    Slog.w(TAG, "Package mismatch in ws");
                                    return;
                                }
                            } else if ("p".equals(tag)) {
                                String pkg = parser.getAttributeValue(null, "pkg");
                                String cl = parser.getAttributeValue(null, "cl");

                                // hostedProviders index will match 'p' attribute in widget's
                                // entry in the xml file being restored
                                // If there's no live entry for this provider, add an inactive one
                                // so that widget IDs referring to them can be properly allocated

                                // Backup and resotre only for the parent profile.
                                ComponentName componentName = new ComponentName(pkg, cl);

                                Provider p = findProviderLocked(componentName, userId);
                                if (p == null) {
                                    AppWidgetProviderInfo info = new AppWidgetProviderInfo();
                                    info.provider = componentName;

                                    p = new Provider();
                                    p.id = new ProviderId(UNKNOWN_UID, componentName);
                                    p.setPartialInfoLocked(info);
                                    p.zombie = true;
                                    mProviders.add(p);
                                }
                                if (DEBUG) {
                                    Slog.i(TAG, "   provider " + p.id);
                                }
                                restoredProviders.add(p);
                            } else if ("h".equals(tag)) {
                                // The host app may not yet exist on the device.  If it's here we
                                // just use the existing Host entry, otherwise we create a
                                // placeholder whose uid will be fixed up at PACKAGE_ADDED time.
                                String pkg = parser.getAttributeValue(null, "pkg");

                                final int uid = getUidForPackage(pkg, userId);
                                final int hostId = parser.getAttributeIntHex(null, "id");

                                HostId id = new HostId(uid, hostId, pkg);
                                Host h = lookupOrAddHostLocked(id);
                                restoredHosts.add(h);

                                if (DEBUG) {
                                    Slog.i(TAG, "   host[" + restoredHosts.size()
                                            + "]: {" + h.id + "}");
                                }
                            } else if ("g".equals(tag)) {
                                int restoredId = parser.getAttributeIntHex(null, "id");
                                int hostIndex = parser.getAttributeIntHex(null, "h");
                                Host host = restoredHosts.get(hostIndex);
                                Provider p = null;
                                int which = parser.getAttributeIntHex(null, "p", -1);
                                if (which != -1) {
                                    // could have been null if the app had allocated an id
                                    // but not yet established a binding under that id
                                    p = restoredProviders.get(which);
                                }

                                // We'll be restoring widget state for both the host and
                                // provider sides of this widget ID, so make sure we are
                                // beginning from a clean slate on both fronts.
                                pruneWidgetStateLocked(host.id.packageName, userId);
                                if (p != null) {
                                    pruneWidgetStateLocked(p.id.componentName.getPackageName(),
                                            userId);
                                }

                                // Have we heard about this ancestral widget instance before?
                                Widget id = findRestoredWidgetLocked(restoredId, host, p);
                                if (id == null) {
                                    id = new Widget();
                                    id.appWidgetId = incrementAndGetAppWidgetIdLocked(userId);
                                    id.restoredId = restoredId;
                                    id.options = parseWidgetIdOptions(parser);
                                    id.host = host;
                                    id.host.widgets.add(id);
                                    id.provider = p;
                                    if (id.provider != null) {
                                        id.provider.widgets.add(id);
                                    }
                                    if (DEBUG) {
                                        Slog.i(TAG, "New restored id " + restoredId
                                                + " now " + id);
                                    }
                                    addWidgetLocked(id);
                                }
                                if (id.provider != null
                                        && id.provider.getPartialInfoLocked() != null) {
                                    stashProviderRestoreUpdateLocked(id.provider,
                                            restoredId, id.appWidgetId);
                                } else {
                                    Slog.w(TAG, "Missing provider for restored widget " + id);
                                }
                                stashHostRestoreUpdateLocked(id.host, restoredId, id.appWidgetId);

                                if (DEBUG) {
                                    Slog.i(TAG, "   instance: " + restoredId
                                            + " -> " + id.appWidgetId
                                            + " :: p=" + id.provider);
                                }
                            }
                        }
                    } while (type != XmlPullParser.END_DOCUMENT);

                    // We've updated our own bookkeeping.  We'll need to notify the hosts and
                    // providers about the changes, but we can't do that yet because the restore
                    // target is not necessarily fully live at this moment.  Set aside the
                    // information for now; the backup manager will call us once more at the
                    // end of the process when all of the targets are in a known state, and we
                    // will update at that point.
                }
            } catch (XmlPullParserException | IOException e) {
                Slog.w(TAG, "Unable to restore widget state for " + packageName);
            } finally {
                saveGroupStateAsync(userId);
            }
        }

        // Called once following the conclusion of a system restore operation.  This is when we
        // send out updates to apps involved in widget-state restore telling them about
        // the new widget ID space.  Apps that are not yet installed will be notifed when they are.
        public void systemRestoreFinished(int userId) {
            if (DEBUG) {
                Slog.i(TAG, "systemRestoreFinished for " + userId);
            }
            synchronized (mLock) {
                mHasSystemRestoreFinished = true;
                maybeSendWidgetRestoreBroadcastsLocked(userId);
            }
        }

        // Called when widget components (hosts or providers) are added or changed.  If system
        // restore has completed, we use this opportunity to tell the apps to update to the new
        // widget ID space.  If system restore is still in progress, we delay the updates until
        // the end, to allow all participants to restore their state before updating widget IDs.
        public void widgetComponentsChanged(int userId) {
            synchronized (mLock) {
                if (mHasSystemRestoreFinished) {
                    maybeSendWidgetRestoreBroadcastsLocked(userId);
                }
            }
        }

        // Called following the conclusion of a restore operation and when widget components
        // are added or changed.  This is when we send out updates to apps involved in widget-state
        // restore telling them about the new widget ID space.
        @GuardedBy("mLock")
        private void maybeSendWidgetRestoreBroadcastsLocked(int userId) {
            if (DEBUG) {
                Slog.i(TAG, "maybeSendWidgetRestoreBroadcasts for " + userId);
            }

            final UserHandle userHandle = new UserHandle(userId);
            // Build the providers' broadcasts and send them off
            Set<Map.Entry<Provider, ArrayList<RestoreUpdateRecord>>> providerEntries
                    = mUpdatesByProvider.entrySet();
            for (Map.Entry<Provider, ArrayList<RestoreUpdateRecord>> e : providerEntries) {
                // For each provider there's a list of affected IDs
                Provider provider = e.getKey();
                if (provider.zombie) {
                    // Provider not installed, we can't send them broadcasts yet.
                    // We'll be called again when the provider is installed.
                    continue;
                }
                ArrayList<RestoreUpdateRecord> updates = e.getValue();
                final int pending = countPendingUpdates(updates);
                if (DEBUG) {
                    Slog.i(TAG, "Provider " + provider + " pending: " + pending);
                }
                if (pending > 0) {
                    int[] oldIds = new int[pending];
                    int[] newIds = new int[pending];
                    final int N = updates.size();
                    int nextPending = 0;
                    for (int i = 0; i < N; i++) {
                        RestoreUpdateRecord r = updates.get(i);
                        if (!r.notified) {
                            r.notified = true;
                            oldIds[nextPending] = r.oldId;
                            newIds[nextPending] = r.newId;
                            nextPending++;
                            if (DEBUG) {
                                Slog.i(TAG, "   " + r.oldId + " => " + r.newId);
                            }
                        }
                    }
                    sendWidgetRestoreBroadcastLocked(
                            AppWidgetManager.ACTION_APPWIDGET_RESTORED,
                            provider, null, oldIds, newIds, userHandle);
                }
            }

            // same thing per host
            Set<Map.Entry<Host, ArrayList<RestoreUpdateRecord>>> hostEntries
                    = mUpdatesByHost.entrySet();
            for (Map.Entry<Host, ArrayList<RestoreUpdateRecord>> e : hostEntries) {
                Host host = e.getKey();
                if (host.id.uid != UNKNOWN_UID) {
                    ArrayList<RestoreUpdateRecord> updates = e.getValue();
                    final int pending = countPendingUpdates(updates);
                    if (DEBUG) {
                        Slog.i(TAG, "Host " + host + " pending: " + pending);
                    }
                    if (pending > 0) {
                        int[] oldIds = new int[pending];
                        int[] newIds = new int[pending];
                        final int N = updates.size();
                        int nextPending = 0;
                        for (int i = 0; i < N; i++) {
                            RestoreUpdateRecord r = updates.get(i);
                            if (!r.notified) {
                                r.notified = true;
                                oldIds[nextPending] = r.oldId;
                                newIds[nextPending] = r.newId;
                                nextPending++;
                                if (DEBUG) {
                                    Slog.i(TAG, "   " + r.oldId + " => " + r.newId);
                                }
                            }
                        }
                        sendWidgetRestoreBroadcastLocked(
                                AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED,
                                null, host, oldIds, newIds, userHandle);
                    }
                }
            }
        }

        private Provider findProviderLocked(ComponentName componentName, int userId) {
            final int providerCount = mProviders.size();
            for (int i = 0; i < providerCount; i++) {
                Provider provider = mProviders.get(i);
                if (provider.getUserId() == userId
                        && provider.id.componentName.equals(componentName)) {
                    return provider;
                }
            }
            return null;
        }

        private Widget findRestoredWidgetLocked(int restoredId, Host host, Provider p) {
            if (DEBUG) {
                Slog.i(TAG, "Find restored widget: id=" + restoredId
                        + " host=" + host + " provider=" + p);
            }

            if (p == null || host == null) {
                return null;
            }

            final int N = mWidgets.size();
            for (int i = 0; i < N; i++) {
                Widget widget = mWidgets.get(i);
                if (widget.restoredId == restoredId
                        && widget.host.id.equals(host.id)
                        && widget.provider.id.equals(p.id)) {
                    if (DEBUG) {
                        Slog.i(TAG, "   Found at " + i + " : " + widget);
                    }
                    return widget;
                }
            }
            return null;
        }

        private boolean packageNeedsWidgetBackupLocked(String packageName, int userId) {
            int N = mWidgets.size();
            for (int i = 0; i < N; i++) {
                Widget widget = mWidgets.get(i);

                // Skip cross-user widgets.
                if (!isProviderAndHostInUser(widget, userId)) {
                    continue;
                }

                if (widget.host.isInPackageForUser(packageName, userId)) {
                    // this package is hosting widgets, so it knows widget IDs.
                    return true;
                }

                Provider provider = widget.provider;
                if (provider != null && provider.isInPackageForUser(packageName, userId)) {
                    // someone is hosting this app's widgets, so it knows widget IDs.
                    return true;
                }
            }
            return false;
        }

        private void stashProviderRestoreUpdateLocked(Provider provider, int oldId, int newId) {
            ArrayList<RestoreUpdateRecord> r = mUpdatesByProvider.get(provider);
            if (r == null) {
                r = new ArrayList<>();
                mUpdatesByProvider.put(provider, r);
            } else {
                // don't duplicate
                if (alreadyStashed(r, oldId, newId)) {
                    if (DEBUG) {
                        Slog.i(TAG, "ID remap " + oldId + " -> " + newId
                                + " already stashed for " + provider);
                    }
                    return;
                }
            }
            r.add(new RestoreUpdateRecord(oldId, newId));
        }

        private boolean alreadyStashed(ArrayList<RestoreUpdateRecord> stash,
                final int oldId, final int newId) {
            final int N = stash.size();
            for (int i = 0; i < N; i++) {
                RestoreUpdateRecord r = stash.get(i);
                if (r.oldId == oldId && r.newId == newId) {
                    return true;
                }
            }
            return false;
        }

        private void stashHostRestoreUpdateLocked(Host host, int oldId, int newId) {
            ArrayList<RestoreUpdateRecord> r = mUpdatesByHost.get(host);
            if (r == null) {
                r = new ArrayList<>();
                mUpdatesByHost.put(host, r);
            } else {
                if (alreadyStashed(r, oldId, newId)) {
                    if (DEBUG) {
                        Slog.i(TAG, "ID remap " + oldId + " -> " + newId
                                + " already stashed for " + host);
                    }
                    return;
                }
            }
            r.add(new RestoreUpdateRecord(oldId, newId));
        }

        private void sendWidgetRestoreBroadcastLocked(String action, Provider provider,
                Host host, int[] oldIds, int[] newIds, UserHandle userHandle) {
            // Users expect restore to emplace widgets properly ASAP, so flag these as
            // being interactive broadcast dispatches
            Intent intent = new Intent(action);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS, oldIds);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, newIds);
            if (provider != null) {
                intent.setComponent(provider.id.componentName);
                sendBroadcastAsUser(intent, userHandle, true);
            }
            if (host != null) {
                intent.setComponent(null);
                intent.setPackage(host.id.packageName);
                intent.putExtra(AppWidgetManager.EXTRA_HOST_ID, host.id.hostId);
                sendBroadcastAsUser(intent, userHandle, true);
            }
        }

        // We're restoring widget state for 'pkg', so we start by wiping (a) all widget
        // instances that are hosted by that app, and (b) all instances in other hosts
        // for which 'pkg' is the provider.  We assume that we'll be restoring all of
        // these hosts & providers, so will be reconstructing a correct live state.
        @GuardedBy("mLock")
        private void pruneWidgetStateLocked(String pkg, int userId) {
            final Set<String> prunedApps = getPrunedAppsLocked(userId);
            if (!prunedApps.contains(pkg)) {
                if (DEBUG) {
                    Slog.i(TAG, "pruning widget state for restoring package " + pkg);
                }
                for (int i = mWidgets.size() - 1; i >= 0; i--) {
                    Widget widget = mWidgets.get(i);

                    Host host = widget.host;
                    Provider provider = widget.provider;

                    if (host.hostsPackageForUser(pkg, userId)
                            || (provider != null && provider.isInPackageForUser(pkg, userId))) {
                        // 'pkg' is either the host or the provider for this instances,
                        // so we tear it down in anticipation of it (possibly) being
                        // reconstructed due to the restore
                        host.widgets.remove(widget);
                        provider.widgets.remove(widget);
                        // Check if we need to destroy any services (if no other app widgets are
                        // referencing the same service)
                        decrementAppWidgetServiceRefCount(widget);
                        removeWidgetLocked(widget);
                    }
                }
                prunedApps.add(pkg);
            } else {
                if (DEBUG) {
                    Slog.i(TAG, "already pruned " + pkg + ", continuing normally");
                }
            }
        }

        @GuardedBy("mLock")
        @NonNull
        private Set<String> getPrunedAppsLocked(int userId) {
            if (!mPrunedAppsPerUser.contains(userId)) {
                mPrunedAppsPerUser.set(userId, new ArraySet<>());
            }
            return mPrunedAppsPerUser.get(userId);
        }

        private boolean isProviderAndHostInUser(Widget widget, int userId) {
            // Backup only widgets hosted or provided by the owner profile.
            return widget.host.getUserId() == userId && (widget.provider == null
                    || widget.provider.getUserId() == userId);
        }

        private int countPendingUpdates(ArrayList<RestoreUpdateRecord> updates) {
            int pending = 0;
            final int N = updates.size();
            for (int i = 0; i < N; i++) {
                RestoreUpdateRecord r = updates.get(i);
                if (!r.notified) {
                    pending++;
                }
            }
            return pending;
        }

        // Accumulate a list of updates that affect the given provider for a final
        // coalesced notification broadcast once restore is over.
        private class RestoreUpdateRecord {
            public int oldId;
            public int newId;
            public boolean notified;

            public RestoreUpdateRecord(int theOldId, int theNewId) {
                oldId = theOldId;
                newId = theNewId;
                notified = false;
            }
        }
    }

    private class AppWidgetManagerLocal extends AppWidgetManagerInternal {
        @Override
        public ArraySet<String> getHostedWidgetPackages(int uid) {
            synchronized (mLock) {
                ArraySet<String> widgetPackages = null;
                final int widgetCount = mWidgets.size();
                for (int i = 0; i < widgetCount; i++) {
                    final Widget widget = mWidgets.get(i);
                    if  (widget.host.id.uid == uid && widget.provider != null) {
                        if (widgetPackages == null) {
                            widgetPackages = new ArraySet<>();
                        }
                        widgetPackages.add(widget.provider.id.componentName.getPackageName());
                    }
                }
                return widgetPackages;
            }
        }

        @Override
        public void unlockUser(int userId) {
            handleUserUnlocked(userId);
        }

        @Override
        public void applyResourceOverlaysToWidgets(Set<String> packageNames, int userId,
                boolean updateFrameworkRes) {
            synchronized (mLock) {
                applyResourceOverlaysToWidgetsLocked(new HashSet<>(packageNames), userId,
                        updateFrameworkRes);
            }
        }
    }
}
