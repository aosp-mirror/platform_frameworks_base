/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.am;

import static android.Manifest.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST;
import static android.Manifest.permission.START_ACTIVITIES_FROM_BACKGROUND;
import static android.Manifest.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND;
import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;
import static android.app.ActivityManagerInternal.ALLOW_FULL_ONLY;
import static android.app.ActivityManagerInternal.ALLOW_NON_FULL;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_FINISH_RECEIVER;
import static android.app.AppOpsManager.OP_NONE;
import static android.content.pm.PackageManager.MATCH_DEBUG_TRIAGED_MISSING;
import static android.os.Process.BLUETOOTH_UID;
import static android.os.Process.FIRST_APPLICATION_UID;
import static android.os.Process.NETWORK_STACK_UID;
import static android.os.Process.NFC_UID;
import static android.os.Process.PHONE_UID;
import static android.os.Process.ROOT_UID;
import static android.os.Process.SE_UID;
import static android.os.Process.SHELL_UID;
import static android.os.Process.SYSTEM_UID;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BACKGROUND_CHECK;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BROADCAST;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BROADCAST_LIGHT;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_BROADCAST;
import static com.android.server.am.ActivityManagerService.CLEAR_DNS_CACHE_MSG;
import static com.android.server.am.ActivityManagerService.HANDLE_TRUST_STORAGE_UPDATE_MSG;
import static com.android.server.am.ActivityManagerService.STOCK_PM_FLAGS;
import static com.android.server.am.ActivityManagerService.TAG;
import static com.android.server.am.ActivityManagerService.UPDATE_HTTP_PROXY_MSG;
import static com.android.server.am.ActivityManagerService.UPDATE_TIME_PREFERENCE_MSG;
import static com.android.server.am.ActivityManagerService.UPDATE_TIME_ZONE;
import static com.android.server.am.ActivityManagerService.checkComponentPermission;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.ApplicationExitInfo;
import android.app.ApplicationThreadConstants;
import android.app.BackgroundStartPrivileges;
import android.app.BroadcastOptions;
import android.app.BroadcastStickyCache;
import android.app.IApplicationThread;
import android.app.compat.CompatChanges;
import android.appwidget.AppWidgetManager;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.media.audiofx.AudioEffect;
import android.net.ConnectivityManager;
import android.net.Proxy;
import android.net.Uri;
import android.os.Binder;
import android.os.BinderProxy;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.text.style.SuggestionSpan;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.IntentResolver;
import com.android.server.LocalManagerRegistry;
import com.android.server.LocalServices;
import com.android.server.SystemConfig;
import com.android.server.pm.Computer;
import com.android.server.pm.SaferIntentUtils;
import com.android.server.pm.UserManagerInternal;
import com.android.server.pm.snapshot.PackageDataSnapshot;
import com.android.server.sdksandbox.SdkSandboxManagerLocal;
import com.android.server.utils.Slogf;

import dalvik.annotation.optimization.NeverCompile;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

class BroadcastController {
    private static final String TAG_BROADCAST = TAG + POSTFIX_BROADCAST;

    /**
     * It is now required for apps to explicitly set either
     * {@link android.content.Context#RECEIVER_EXPORTED} or
     * {@link android.content.Context#RECEIVER_NOT_EXPORTED} when registering a receiver for an
     * unprotected broadcast in code.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private static final long DYNAMIC_RECEIVER_EXPLICIT_EXPORT_REQUIRED = 161145287L;

    // Maximum number of receivers an app can register.
    private static final int MAX_RECEIVERS_ALLOWED_PER_APP = 1000;

    @NonNull
    private final Context mContext;
    @NonNull
    private final ActivityManagerService mService;
    @NonNull
    private BroadcastQueue mBroadcastQueue;

    @GuardedBy("mService")
    BroadcastStats mLastBroadcastStats;

    @GuardedBy("mService")
    BroadcastStats mCurBroadcastStats;

    /**
     * Broadcast actions that will always be deliverable to unlaunched/background apps
     */
    @GuardedBy("mService")
    private ArraySet<String> mBackgroundLaunchBroadcasts;

    /**
     * State of all active sticky broadcasts per user.  Keys are the action of the
     * sticky Intent, values are an ArrayList of all broadcasted intents with
     * that action (which should usually be one).  The SparseArray is keyed
     * by the user ID the sticky is for, and can include UserHandle.USER_ALL
     * for stickies that are sent to all users.
     */
    @GuardedBy("mStickyBroadcasts")
    final SparseArray<ArrayMap<String, ArrayList<StickyBroadcast>>> mStickyBroadcasts =
            new SparseArray<>();

    /**
     * Keeps track of all IIntentReceivers that have been registered for broadcasts.
     * Hash keys are the receiver IBinder, hash value is a ReceiverList.
     */
    @GuardedBy("mService")
    final HashMap<IBinder, ReceiverList> mRegisteredReceivers = new HashMap<>();

    /**
     * Resolver for broadcast intents to registered receivers.
     * Holds BroadcastFilter (subclass of IntentFilter).
     */
    final IntentResolver<BroadcastFilter, BroadcastFilter> mReceiverResolver =
            new IntentResolver<>() {
        @Override
        protected boolean allowFilterResult(
                BroadcastFilter filter, List<BroadcastFilter> dest) {
            IBinder target = filter.receiverList.receiver.asBinder();
            for (int i = dest.size() - 1; i >= 0; i--) {
                if (dest.get(i).receiverList.receiver.asBinder() == target) {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected BroadcastFilter newResult(@NonNull Computer computer, BroadcastFilter filter,
                int match, int userId, long customFlags) {
            if (userId == UserHandle.USER_ALL || filter.owningUserId == UserHandle.USER_ALL
                    || userId == filter.owningUserId) {
                return super.newResult(computer, filter, match, userId, customFlags);
            }
            return null;
        }

        @Override
        protected IntentFilter getIntentFilter(@NonNull BroadcastFilter input) {
            return input;
        }

        @Override
        protected BroadcastFilter[] newArray(int size) {
            return new BroadcastFilter[size];
        }

        @Override
        protected boolean isPackageForFilter(String packageName, BroadcastFilter filter) {
            return packageName.equals(filter.packageName);
        }
    };

    BroadcastController(Context context, ActivityManagerService service, BroadcastQueue queue) {
        mContext = context;
        mService = service;
        mBroadcastQueue = queue;
    }

    void setBroadcastQueueForTest(BroadcastQueue broadcastQueue) {
        mBroadcastQueue = broadcastQueue;
    }

    Intent registerReceiverWithFeature(IApplicationThread caller, String callerPackage,
            String callerFeatureId, String receiverId, IIntentReceiver receiver,
            IntentFilter filter, String permission, int userId, int flags) {
        traceRegistrationBegin(receiverId, receiver, filter, userId);
        try {
            return registerReceiverWithFeatureTraced(caller, callerPackage, callerFeatureId,
                    receiverId, receiver, filter, permission, userId, flags);
        } finally {
            traceRegistrationEnd();
        }
    }

    private static void traceRegistrationBegin(String receiverId, IIntentReceiver receiver,
            IntentFilter filter, int userId) {
        if (!Flags.traceReceiverRegistration()) {
            return;
        }
        if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
            final StringBuilder sb = new StringBuilder("registerReceiver: ");
            sb.append(Binder.getCallingUid()); sb.append('/');
            sb.append(receiverId == null ? "null" : receiverId); sb.append('/');
            final int actionsCount = filter.safeCountActions();
            if (actionsCount > 0) {
                for (int i = 0; i < actionsCount; ++i) {
                    sb.append(filter.getAction(i));
                    if (i != actionsCount - 1) sb.append(',');
                }
            } else {
                sb.append("null");
            }
            sb.append('/');
            sb.append('u'); sb.append(userId); sb.append('/');
            sb.append(receiver == null ? "null" : receiver.asBinder());
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, sb.toString());
        }
    }

    private static void traceRegistrationEnd() {
        if (!Flags.traceReceiverRegistration()) {
            return;
        }
        if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    private Intent registerReceiverWithFeatureTraced(IApplicationThread caller,
            String callerPackage, String callerFeatureId, String receiverId,
            IIntentReceiver receiver, IntentFilter filter, String permission,
            int userId, int flags) {
        mService.enforceNotIsolatedCaller("registerReceiver");
        ArrayList<StickyBroadcast> stickyBroadcasts = null;
        ProcessRecord callerApp = null;
        final boolean visibleToInstantApps =
                (flags & Context.RECEIVER_VISIBLE_TO_INSTANT_APPS) != 0;

        int callingUid;
        int callingPid;
        boolean instantApp;
        synchronized (mService.mProcLock) {
            callerApp = mService.getRecordForAppLOSP(caller);
            if (callerApp == null) {
                Slog.w(TAG, "registerReceiverWithFeature: no app for " + caller);
                return null;
            }
            if (callerApp.info.uid != SYSTEM_UID
                    && !callerApp.getPkgList().containsKey(callerPackage)
                    && !"android".equals(callerPackage)) {
                throw new SecurityException("Given caller package " + callerPackage
                        + " is not running in process " + callerApp);
            }
            callingUid = callerApp.info.uid;
            callingPid = callerApp.getPid();

            instantApp = isInstantApp(callerApp, callerPackage, callingUid);
        }
        userId = mService.mUserController.handleIncomingUser(callingPid, callingUid, userId, true,
                ALLOW_FULL_ONLY, "registerReceiver", callerPackage);

        // Warn if system internals are registering for important broadcasts
        // without also using a priority to ensure they process the event
        // before normal apps hear about it
        if (UserHandle.isCore(callingUid)) {
            final int priority = filter.getPriority();
            final boolean systemPriority = (priority >= IntentFilter.SYSTEM_HIGH_PRIORITY)
                    || (priority <= IntentFilter.SYSTEM_LOW_PRIORITY);
            if (!systemPriority) {
                final int N = filter.countActions();
                for (int i = 0; i < N; i++) {
                    // TODO: expand to additional important broadcasts over time
                    final String action = filter.getAction(i);
                    if (action.startsWith("android.intent.action.USER_")
                            || action.startsWith("android.intent.action.PACKAGE_")
                            || action.startsWith("android.intent.action.UID_")
                            || action.startsWith("android.intent.action.EXTERNAL_")
                            || action.startsWith("android.bluetooth.")
                            || action.equals(Intent.ACTION_SHUTDOWN)) {
                        if (DEBUG_BROADCAST) {
                            Slog.wtf(TAG,
                                    "System internals registering for " + filter.toLongString()
                                            + " with app priority; this will race with apps!",
                                    new Throwable());
                        }

                        // When undefined, assume that system internals need
                        // to hear about the event first; they can use
                        // SYSTEM_LOW_PRIORITY if they need to hear last
                        if (priority == 0) {
                            filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
                        }
                        break;
                    }
                }
            }
        }

        Iterator<String> actions = filter.actionsIterator();
        if (actions == null) {
            ArrayList<String> noAction = new ArrayList<String>(1);
            noAction.add(null);
            actions = noAction.iterator();
        }
        boolean onlyProtectedBroadcasts = true;

        // Collect stickies of users and check if broadcast is only registered for protected
        // broadcasts
        int[] userIds = { UserHandle.USER_ALL, UserHandle.getUserId(callingUid) };
        synchronized (mStickyBroadcasts) {
            while (actions.hasNext()) {
                String action = actions.next();
                for (int id : userIds) {
                    ArrayMap<String, ArrayList<StickyBroadcast>> stickies =
                            mStickyBroadcasts.get(id);
                    if (stickies != null) {
                        ArrayList<StickyBroadcast> broadcasts = stickies.get(action);
                        if (broadcasts != null) {
                            if (stickyBroadcasts == null) {
                                stickyBroadcasts = new ArrayList<>();
                            }
                            stickyBroadcasts.addAll(broadcasts);
                        }
                    }
                }
                if (onlyProtectedBroadcasts) {
                    try {
                        onlyProtectedBroadcasts &=
                                AppGlobals.getPackageManager().isProtectedBroadcast(action);
                    } catch (RemoteException e) {
                        onlyProtectedBroadcasts = false;
                        Slog.w(TAG, "Remote exception", e);
                    }
                }
            }
        }

        if (Process.isSdkSandboxUid(Binder.getCallingUid())) {
            SdkSandboxManagerLocal sdkSandboxManagerLocal =
                    LocalManagerRegistry.getManager(SdkSandboxManagerLocal.class);
            if (sdkSandboxManagerLocal == null) {
                throw new IllegalStateException("SdkSandboxManagerLocal not found when checking"
                        + " whether SDK sandbox uid can register to broadcast receivers.");
            }
            if (!sdkSandboxManagerLocal.canRegisterBroadcastReceiver(
                    /*IntentFilter=*/ filter, flags, onlyProtectedBroadcasts)) {
                throw new SecurityException("SDK sandbox not allowed to register receiver"
                        + " with the given IntentFilter: " + filter.toLongString());
            }
        }

        // If the change is enabled, but neither exported or not exported is set, we need to log
        // an error so the consumer can know to explicitly set the value for their flag.
        // If the caller is registering for a sticky broadcast with a null receiver, we won't
        // require a flag
        final boolean explicitExportStateDefined =
                (flags & (Context.RECEIVER_EXPORTED | Context.RECEIVER_NOT_EXPORTED)) != 0;
        if (((flags & Context.RECEIVER_EXPORTED) != 0) && (
                (flags & Context.RECEIVER_NOT_EXPORTED) != 0)) {
            throw new IllegalArgumentException(
                    "Receiver can't specify both RECEIVER_EXPORTED and RECEIVER_NOT_EXPORTED"
                            + "flag");
        }

        // Don't enforce the flag check if we're EITHER registering for only protected
        // broadcasts, or the receiver is null (a sticky broadcast). Sticky broadcasts should
        // not be used generally, so we will be marking them as exported by default
        boolean requireExplicitFlagForDynamicReceivers = CompatChanges.isChangeEnabled(
                DYNAMIC_RECEIVER_EXPLICIT_EXPORT_REQUIRED, callingUid);

        // A receiver that is visible to instant apps must also be exported.
        final boolean unexportedReceiverVisibleToInstantApps =
                ((flags & Context.RECEIVER_VISIBLE_TO_INSTANT_APPS) != 0) && (
                        (flags & Context.RECEIVER_NOT_EXPORTED) != 0);
        if (unexportedReceiverVisibleToInstantApps && requireExplicitFlagForDynamicReceivers) {
            throw new IllegalArgumentException(
                    "Receiver can't specify both RECEIVER_VISIBLE_TO_INSTANT_APPS and "
                            + "RECEIVER_NOT_EXPORTED flag");
        }

        if (!onlyProtectedBroadcasts) {
            if (receiver == null && !explicitExportStateDefined) {
                // sticky broadcast, no flag specified (flag isn't required)
                flags |= Context.RECEIVER_EXPORTED;
            } else if (requireExplicitFlagForDynamicReceivers && !explicitExportStateDefined) {
                throw new SecurityException(
                        callerPackage + ": One of RECEIVER_EXPORTED or "
                                + "RECEIVER_NOT_EXPORTED should be specified when a receiver "
                                + "isn't being registered exclusively for system broadcasts");
                // Assume default behavior-- flag check is not enforced
            } else if (!requireExplicitFlagForDynamicReceivers && (
                    (flags & Context.RECEIVER_NOT_EXPORTED) == 0)) {
                // Change is not enabled, assume exported unless otherwise specified.
                flags |= Context.RECEIVER_EXPORTED;
            }
        } else if ((flags & Context.RECEIVER_NOT_EXPORTED) == 0) {
            flags |= Context.RECEIVER_EXPORTED;
        }

        // Dynamic receivers are exported by default for versions prior to T
        final boolean exported = (flags & Context.RECEIVER_EXPORTED) != 0;

        ArrayList<StickyBroadcast> allSticky = null;
        if (stickyBroadcasts != null) {
            final ContentResolver resolver = mContext.getContentResolver();
            // Look for any matching sticky broadcasts...
            for (int i = 0, N = stickyBroadcasts.size(); i < N; i++) {
                final StickyBroadcast broadcast = stickyBroadcasts.get(i);
                Intent intent = broadcast.intent;
                // Don't provided intents that aren't available to instant apps.
                if (instantApp && (intent.getFlags() & Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS)
                        == 0) {
                    continue;
                }
                // If intent has scheme "content", it will need to access
                // provider that needs to lock mProviderMap in ActivityThread
                // and also it may need to wait application response, so we
                // cannot lock ActivityManagerService here.
                final int match;
                if (Flags.avoidResolvingType()) {
                    match = filter.match(intent.getAction(), broadcast.resolvedDataType,
                            intent.getScheme(), intent.getData(), intent.getCategories(),
                            TAG, false /* supportsWildcards */, null /* ignoreActions */,
                            intent.getExtras());
                } else {
                    match = filter.match(resolver, intent, true, TAG);
                }
                if (match >= 0) {
                    if (allSticky == null) {
                        allSticky = new ArrayList<>();
                    }
                    allSticky.add(broadcast);
                }
            }
        }

        // The first sticky in the list is returned directly back to the client.
        Intent sticky = allSticky != null ? allSticky.get(0).intent : null;
        if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Register receiver " + filter + ": " + sticky);
        if (receiver == null) {
            return sticky;
        }

        // SafetyNet logging for b/177931370. If any process other than system_server tries to
        // listen to this broadcast action, then log it.
        if (callingPid != Process.myPid()) {
            if (filter.hasAction("com.android.server.net.action.SNOOZE_WARNING")
                    || filter.hasAction("com.android.server.net.action.SNOOZE_RAPID")) {
                EventLog.writeEvent(0x534e4554, "177931370", callingUid, "");
            }
        }

        synchronized (mService) {
            IApplicationThread thread;
            if (callerApp != null && ((thread = callerApp.getThread()) == null
                    || thread.asBinder() != caller.asBinder())) {
                // Original caller already died
                return null;
            }
            ReceiverList rl = mRegisteredReceivers.get(receiver.asBinder());
            if (rl == null) {
                rl = new ReceiverList(mService, callerApp, callingPid, callingUid,
                        userId, receiver);
                if (rl.app != null) {
                    final int totalReceiversForApp = rl.app.mReceivers.numberOfReceivers();
                    if (totalReceiversForApp >= MAX_RECEIVERS_ALLOWED_PER_APP) {
                        throw new IllegalStateException("Too many receivers, total of "
                                + totalReceiversForApp + ", registered for pid: "
                                + rl.pid + ", callerPackage: " + callerPackage);
                    }
                    rl.app.mReceivers.addReceiver(rl);
                } else {
                    try {
                        receiver.asBinder().linkToDeath(rl, 0);
                    } catch (RemoteException e) {
                        return sticky;
                    }
                    rl.linkedToDeath = true;
                }
                mRegisteredReceivers.put(receiver.asBinder(), rl);
            } else if (rl.uid != callingUid) {
                throw new IllegalArgumentException(
                        "Receiver requested to register for uid " + callingUid
                                + " was previously registered for uid " + rl.uid
                                + " callerPackage is " + callerPackage);
            } else if (rl.pid != callingPid) {
                throw new IllegalArgumentException(
                        "Receiver requested to register for pid " + callingPid
                                + " was previously registered for pid " + rl.pid
                                + " callerPackage is " + callerPackage);
            } else if (rl.userId != userId) {
                throw new IllegalArgumentException(
                        "Receiver requested to register for user " + userId
                                + " was previously registered for user " + rl.userId
                                + " callerPackage is " + callerPackage);
            }
            BroadcastFilter bf = new BroadcastFilter(filter, rl, callerPackage, callerFeatureId,
                    receiverId, permission, callingUid, userId, instantApp, visibleToInstantApps,
                    exported);
            if (rl.containsFilter(filter)) {
                Slog.w(TAG, "Receiver with filter " + filter
                        + " already registered for pid " + rl.pid
                        + ", callerPackage is " + callerPackage);
            } else {
                rl.add(bf);
                if (!bf.debugCheck()) {
                    Slog.w(TAG, "==> For Dynamic broadcast");
                }
                mReceiverResolver.addFilter(mService.getPackageManagerInternal().snapshot(), bf);
            }

            // Enqueue broadcasts for all existing stickies that match
            // this filter.
            if (allSticky != null) {
                ArrayList receivers = new ArrayList();
                receivers.add(bf);
                sticky = null;

                final int stickyCount = allSticky.size();
                for (int i = 0; i < stickyCount; i++) {
                    final StickyBroadcast broadcast = allSticky.get(i);
                    final int originalStickyCallingUid = allSticky.get(i).originalCallingUid;
                    // TODO(b/281889567): consider using checkComponentPermission instead of
                    //  canAccessUnexportedComponents
                    if (sticky == null && (exported || originalStickyCallingUid == callingUid
                            || ActivityManager.canAccessUnexportedComponents(
                            originalStickyCallingUid))) {
                        sticky = broadcast.intent;
                    }
                    BroadcastQueue queue = mBroadcastQueue;
                    BroadcastRecord r = new BroadcastRecord(queue, broadcast.intent, null,
                            null, null, -1, -1, false, null, null, null, null, OP_NONE,
                            BroadcastOptions.makeWithDeferUntilActive(broadcast.deferUntilActive),
                            receivers, null, null, 0, null, null, false, true, true, -1,
                            originalStickyCallingUid, BackgroundStartPrivileges.NONE,
                            false /* only PRE_BOOT_COMPLETED should be exempt, no stickies */,
                            null /* filterExtrasForReceiver */,
                            broadcast.originalCallingAppProcessState);
                    queue.enqueueBroadcastLocked(r);
                }
            }

            return sticky;
        }
    }

    void unregisterReceiver(IIntentReceiver receiver) {
        traceUnregistrationBegin(receiver);
        try {
            unregisterReceiverTraced(receiver);
        } finally {
            traceUnregistrationEnd();
        }
    }

    private static void traceUnregistrationBegin(IIntentReceiver receiver) {
        if (!Flags.traceReceiverRegistration()) {
            return;
        }
        if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                    TextUtils.formatSimple("unregisterReceiver: %d/%s", Binder.getCallingUid(),
                            receiver == null ? "null" : receiver.asBinder()));
        }
    }

    private static void traceUnregistrationEnd() {
        if (!Flags.traceReceiverRegistration()) {
            return;
        }
        if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    private void unregisterReceiverTraced(IIntentReceiver receiver) {
        if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Unregister receiver: " + receiver);

        final long origId = Binder.clearCallingIdentity();
        try {
            boolean doTrim = false;
            synchronized (mService) {
                ReceiverList rl = mRegisteredReceivers.get(receiver.asBinder());
                if (rl != null) {
                    final BroadcastRecord r = rl.curBroadcast;
                    if (r != null) {
                        final boolean doNext = r.queue.finishReceiverLocked(
                                rl.app, r.resultCode, r.resultData, r.resultExtras,
                                r.resultAbort, false);
                        if (doNext) {
                            doTrim = true;
                        }
                    }
                    if (rl.app != null) {
                        rl.app.mReceivers.removeReceiver(rl);
                    }
                    removeReceiverLocked(rl);
                    if (rl.linkedToDeath) {
                        rl.linkedToDeath = false;
                        rl.receiver.asBinder().unlinkToDeath(rl, 0);
                    }
                }

                // If we actually concluded any broadcasts, we might now be able
                // to trim the recipients' apps from our working set
                if (doTrim) {
                    mService.trimApplicationsLocked(false, OOM_ADJ_REASON_FINISH_RECEIVER);
                    return;
                }
            }

        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    void removeReceiverLocked(ReceiverList rl) {
        mRegisteredReceivers.remove(rl.receiver.asBinder());
        for (int i = rl.size() - 1; i >= 0; i--) {
            mReceiverResolver.removeFilter(rl.get(i));
        }
    }

    int broadcastIntentWithFeature(IApplicationThread caller, String callingFeatureId,
            Intent intent, String resolvedType, IIntentReceiver resultTo,
            int resultCode, String resultData, Bundle resultExtras,
            String[] requiredPermissions, String[] excludedPermissions,
            String[] excludedPackages, int appOp, Bundle bOptions,
            boolean serialized, boolean sticky, int userId) {
        mService.enforceNotIsolatedCaller("broadcastIntent");

        int result;
        synchronized (mService) {
            intent = verifyBroadcastLocked(intent);

            final ProcessRecord callerApp = mService.getRecordForAppLOSP(caller);
            final int callingPid = Binder.getCallingPid();
            final int callingUid = Binder.getCallingUid();

            // We're delivering the result to the caller
            final ProcessRecord resultToApp = callerApp;

            // Permission regimes around sender-supplied broadcast options.
            enforceBroadcastOptionPermissionsInternal(bOptions, callingUid);

            final ComponentName cn = intent.getComponent();

            Trace.traceBegin(
                    Trace.TRACE_TAG_ACTIVITY_MANAGER,
                    "broadcastIntent:" + (cn != null ? cn.toString() : intent.getAction()));

            final long origId = Binder.clearCallingIdentity();
            try {
                result = broadcastIntentLocked(callerApp,
                        callerApp != null ? callerApp.info.packageName : null, callingFeatureId,
                        intent, resolvedType, resultToApp, resultTo, resultCode, resultData,
                        resultExtras, requiredPermissions, excludedPermissions, excludedPackages,
                        appOp, bOptions, serialized, sticky, callingPid, callingUid, callingUid,
                        callingPid, userId, BackgroundStartPrivileges.NONE, null, null);
            } finally {
                Binder.restoreCallingIdentity(origId);
                Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
            }
        }
        if (sticky && result == ActivityManager.BROADCAST_SUCCESS) {
            BroadcastStickyCache.incrementVersion(intent.getAction());
        }
        return result;
    }

    // Not the binder call surface
    int broadcastIntentInPackage(String packageName, @Nullable String featureId, int uid,
            int realCallingUid, int realCallingPid, Intent intent, String resolvedType,
            ProcessRecord resultToApp, IIntentReceiver resultTo, int resultCode,
            String resultData, Bundle resultExtras, String requiredPermission, Bundle bOptions,
            boolean serialized, boolean sticky, int userId,
            BackgroundStartPrivileges backgroundStartPrivileges,
            @Nullable int[] broadcastAllowList) {
        int result;
        synchronized (mService) {
            intent = verifyBroadcastLocked(intent);

            final long origId = Binder.clearCallingIdentity();
            String[] requiredPermissions = requiredPermission == null ? null
                    : new String[] {requiredPermission};
            try {
                result = broadcastIntentLocked(null, packageName, featureId, intent, resolvedType,
                        resultToApp, resultTo, resultCode, resultData, resultExtras,
                        requiredPermissions, null, null, OP_NONE, bOptions, serialized, sticky, -1,
                        uid, realCallingUid, realCallingPid, userId,
                        backgroundStartPrivileges, broadcastAllowList,
                        null /* filterExtrasForReceiver */);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
        if (sticky && result == ActivityManager.BROADCAST_SUCCESS) {
            BroadcastStickyCache.incrementVersion(intent.getAction());
        }
        return result;
    }

    @GuardedBy("mService")
    final int broadcastIntentLocked(ProcessRecord callerApp, String callerPackage,
            @Nullable String callerFeatureId, Intent intent, String resolvedType,
            ProcessRecord resultToApp, IIntentReceiver resultTo, int resultCode, String resultData,
            Bundle resultExtras, String[] requiredPermissions,
            String[] excludedPermissions, String[] excludedPackages, int appOp, Bundle bOptions,
            boolean ordered, boolean sticky, int callingPid, int callingUid,
            int realCallingUid, int realCallingPid, int userId,
            BackgroundStartPrivileges backgroundStartPrivileges,
            @Nullable int[] broadcastAllowList,
            @Nullable BiFunction<Integer, Bundle, Bundle> filterExtrasForReceiver) {
        final int cookie = traceBroadcastIntentBegin(intent, resultTo, ordered, sticky,
                callingUid, realCallingUid, userId);
        try {
            final BroadcastSentEventRecord broadcastSentEventRecord =
                    new BroadcastSentEventRecord();
            final int res = broadcastIntentLockedTraced(callerApp, callerPackage, callerFeatureId,
                    intent, resolvedType, resultToApp, resultTo, resultCode, resultData,
                    resultExtras, requiredPermissions, excludedPermissions, excludedPackages,
                    appOp, BroadcastOptions.fromBundleNullable(bOptions), ordered, sticky,
                    callingPid, callingUid, realCallingUid, realCallingPid, userId,
                    backgroundStartPrivileges, broadcastAllowList, filterExtrasForReceiver,
                    broadcastSentEventRecord);
            broadcastSentEventRecord.setResult(res);
            broadcastSentEventRecord.logToStatsd();
            return res;
        } finally {
            traceBroadcastIntentEnd(cookie);
        }
    }

    private static int traceBroadcastIntentBegin(Intent intent, IIntentReceiver resultTo,
            boolean ordered, boolean sticky, int callingUid, int realCallingUid, int userId) {
        if (!Flags.traceReceiverRegistration()) {
            return BroadcastQueue.traceBegin("broadcastIntentLockedTraced");
        }
        if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
            final StringBuilder sb = new StringBuilder("broadcastIntent: ");
            sb.append(callingUid); sb.append('/');
            final String action = intent.getAction();
            sb.append(action == null ? null : action); sb.append('/');
            sb.append("0x"); sb.append(Integer.toHexString(intent.getFlags())); sb.append('/');
            sb.append(ordered ? "O" : "_");
            sb.append(sticky ? "S" : "_");
            sb.append(resultTo != null ? "C" : "_");
            sb.append('/');
            sb.append('u'); sb.append(userId);
            if (callingUid != realCallingUid) {
                sb.append('/');
                sb.append("sender="); sb.append(realCallingUid);
            }
            return BroadcastQueue.traceBegin(sb.toString());
        }
        return 0;
    }

    private static void traceBroadcastIntentEnd(int cookie) {
        if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
            BroadcastQueue.traceEnd(cookie);
        }
    }

    @GuardedBy("mService")
    final int broadcastIntentLockedTraced(ProcessRecord callerApp, String callerPackage,
            @Nullable String callerFeatureId, Intent intent, String resolvedType,
            ProcessRecord resultToApp, IIntentReceiver resultTo, int resultCode, String resultData,
            Bundle resultExtras, String[] requiredPermissions,
            String[] excludedPermissions, String[] excludedPackages, int appOp,
            BroadcastOptions brOptions, boolean ordered, boolean sticky, int callingPid,
            int callingUid, int realCallingUid, int realCallingPid, int userId,
            BackgroundStartPrivileges backgroundStartPrivileges,
            @Nullable int[] broadcastAllowList,
            @Nullable BiFunction<Integer, Bundle, Bundle> filterExtrasForReceiver,
            @NonNull BroadcastSentEventRecord broadcastSentEventRecord) {
        // Ensure all internal loopers are registered for idle checks
        BroadcastLoopers.addMyLooper();

        if (Process.isSdkSandboxUid(realCallingUid)) {
            final SdkSandboxManagerLocal sdkSandboxManagerLocal = LocalManagerRegistry.getManager(
                    SdkSandboxManagerLocal.class);
            if (sdkSandboxManagerLocal == null) {
                throw new IllegalStateException("SdkSandboxManagerLocal not found when sending"
                        + " a broadcast from an SDK sandbox uid.");
            }
            if (!sdkSandboxManagerLocal.canSendBroadcast(intent)) {
                throw new SecurityException(
                        "Intent " + intent.getAction() + " may not be broadcast from an SDK sandbox"
                                + " uid. Given caller package " + callerPackage
                                + " (pid=" + callingPid + ", realCallingUid=" + realCallingUid
                                + ", callingUid= " + callingUid + ")");
            }
        }

        if ((resultTo != null) && (resultToApp == null)) {
            if (resultTo.asBinder() instanceof BinderProxy) {
                // Warn when requesting results without a way to deliver them
                Slog.wtf(TAG, "Sending broadcast " + intent.getAction()
                        + " with resultTo requires resultToApp", new Throwable());
            } else {
                // If not a BinderProxy above, then resultTo is an in-process
                // receiver, so splice in system_server process
                resultToApp = mService.getProcessRecordLocked("system", SYSTEM_UID);
            }
        }

        intent = new Intent(intent);
        broadcastSentEventRecord.setIntent(intent);
        broadcastSentEventRecord.setOriginalIntentFlags(intent.getFlags());
        broadcastSentEventRecord.setSenderUid(callingUid);
        broadcastSentEventRecord.setRealSenderUid(realCallingUid);
        broadcastSentEventRecord.setSticky(sticky);
        broadcastSentEventRecord.setOrdered(ordered);
        broadcastSentEventRecord.setResultRequested(resultTo != null);
        final int callerAppProcessState = getRealProcessStateLocked(callerApp, realCallingPid);
        broadcastSentEventRecord.setSenderProcState(callerAppProcessState);
        broadcastSentEventRecord.setSenderUidState(getRealUidStateLocked(callerApp,
                realCallingPid));

        final boolean callerInstantApp = isInstantApp(callerApp, callerPackage, callingUid);
        // Instant Apps cannot use FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS
        if (callerInstantApp) {
            intent.setFlags(intent.getFlags() & ~Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS);
        }

        if (userId == UserHandle.USER_ALL && broadcastAllowList != null) {
            Slog.e(TAG, "broadcastAllowList only applies when sending to individual users. "
                    + "Assuming restrictive whitelist.");
            broadcastAllowList = new int[]{};
        }

        // By default broadcasts do not go to stopped apps.
        intent.addFlags(Intent.FLAG_EXCLUDE_STOPPED_PACKAGES);

        // If we have not finished booting, don't allow this to launch new processes.
        if (!mService.mProcessesReady
                && (intent.getFlags() & Intent.FLAG_RECEIVER_BOOT_UPGRADE) == 0) {
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        }

        if (DEBUG_BROADCAST_LIGHT) {
            Slog.v(TAG_BROADCAST,
                    (sticky ? "Broadcast sticky: " : "Broadcast: ") + intent
                            + " ordered=" + ordered + " userid=" + userId
                            + " options=" + (brOptions == null ? "null" : brOptions.toBundle()));
        }
        if ((resultTo != null) && !ordered) {
            if (!UserHandle.isCore(callingUid)) {
                String msg = "Unauthorized unordered resultTo broadcast "
                        + intent + " sent from uid " + callingUid;
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }
        }

        userId = mService.mUserController.handleIncomingUser(callingPid, callingUid, userId, true,
                ALLOW_NON_FULL, "broadcast", callerPackage);

        // Make sure that the user who is receiving this broadcast or its parent is running.
        // If not, we will just skip it. Make an exception for shutdown broadcasts, upgrade steps.
        if (userId != UserHandle.USER_ALL && !mService.mUserController.isUserOrItsParentRunning(
                userId)) {
            if ((callingUid != SYSTEM_UID
                    || (intent.getFlags() & Intent.FLAG_RECEIVER_BOOT_UPGRADE) == 0)
                    && !Intent.ACTION_SHUTDOWN.equals(intent.getAction())) {
                Slog.w(TAG, "Skipping broadcast of " + intent
                        + ": user " + userId + " and its parent (if any) are stopped");
                scheduleCanceledResultTo(resultToApp, resultTo, intent, userId,
                        brOptions, callingUid, callerPackage);
                return ActivityManager.BROADCAST_FAILED_USER_STOPPED;
            }
        }

        final String action = intent.getAction();
        if (brOptions != null) {
            if (brOptions.getTemporaryAppAllowlistDuration() > 0) {
                // See if the caller is allowed to do this.  Note we are checking against
                // the actual real caller (not whoever provided the operation as say a
                // PendingIntent), because that who is actually supplied the arguments.
                if (checkComponentPermission(CHANGE_DEVICE_IDLE_TEMP_WHITELIST,
                        realCallingPid, realCallingUid, -1, true)
                        != PackageManager.PERMISSION_GRANTED
                        && checkComponentPermission(START_ACTIVITIES_FROM_BACKGROUND,
                        realCallingPid, realCallingUid, -1, true)
                        != PackageManager.PERMISSION_GRANTED
                        && checkComponentPermission(START_FOREGROUND_SERVICES_FROM_BACKGROUND,
                        realCallingPid, realCallingUid, -1, true)
                        != PackageManager.PERMISSION_GRANTED) {
                    String msg = "Permission Denial: " + intent.getAction()
                            + " broadcast from " + callerPackage + " (pid=" + callingPid
                            + ", uid=" + callingUid + ")"
                            + " requires "
                            + CHANGE_DEVICE_IDLE_TEMP_WHITELIST + " or "
                            + START_ACTIVITIES_FROM_BACKGROUND + " or "
                            + START_FOREGROUND_SERVICES_FROM_BACKGROUND;
                    Slog.w(TAG, msg);
                    throw new SecurityException(msg);
                }
            }
            if (brOptions.isDontSendToRestrictedApps()
                    && !mService.isUidActiveLOSP(callingUid)
                    && mService.isBackgroundRestrictedNoCheck(callingUid, callerPackage)) {
                Slog.i(TAG, "Not sending broadcast " + action + " - app " + callerPackage
                        + " has background restrictions");
                return ActivityManager.START_CANCELED;
            }
            if (brOptions.allowsBackgroundActivityStarts()) {
                // See if the caller is allowed to do this.  Note we are checking against
                // the actual real caller (not whoever provided the operation as say a
                // PendingIntent), because that who is actually supplied the arguments.
                if (checkComponentPermission(
                        android.Manifest.permission.START_ACTIVITIES_FROM_BACKGROUND,
                        realCallingPid, realCallingUid, -1, true)
                        != PackageManager.PERMISSION_GRANTED) {
                    String msg = "Permission Denial: " + intent.getAction()
                            + " broadcast from " + callerPackage + " (pid=" + callingPid
                            + ", uid=" + callingUid + ")"
                            + " requires "
                            + android.Manifest.permission.START_ACTIVITIES_FROM_BACKGROUND;
                    Slog.w(TAG, msg);
                    throw new SecurityException(msg);
                } else {
                    // We set the token to null since if it wasn't for it we'd allow anyway here
                    backgroundStartPrivileges = BackgroundStartPrivileges.ALLOW_BAL;
                }
            }

            if (brOptions.getIdForResponseEvent() > 0) {
                mService.enforcePermission(
                        android.Manifest.permission.ACCESS_BROADCAST_RESPONSE_STATS,
                        callingPid, callingUid, "recordResponseEventWhileInBackground");
            }
        }

        // Verify that protected broadcasts are only being sent by system code,
        // and that system code is only sending protected broadcasts.
        final boolean isProtectedBroadcast;
        try {
            isProtectedBroadcast = AppGlobals.getPackageManager().isProtectedBroadcast(action);
        } catch (RemoteException e) {
            Slog.w(TAG, "Remote exception", e);
            scheduleCanceledResultTo(resultToApp, resultTo, intent,
                    userId, brOptions, callingUid, callerPackage);
            return ActivityManager.BROADCAST_SUCCESS;
        }

        final boolean isCallerSystem;
        switch (UserHandle.getAppId(callingUid)) {
            case ROOT_UID:
            case SYSTEM_UID:
            case PHONE_UID:
            case BLUETOOTH_UID:
            case NFC_UID:
            case SE_UID:
            case NETWORK_STACK_UID:
                isCallerSystem = true;
                break;
            default:
                isCallerSystem = (callerApp != null) && callerApp.isPersistent();
                break;
        }

        // First line security check before anything else: stop non-system apps from
        // sending protected broadcasts.
        if (!isCallerSystem) {
            if (isProtectedBroadcast) {
                String msg = "Permission Denial: not allowed to send broadcast "
                        + action + " from pid="
                        + callingPid + ", uid=" + callingUid;
                Slog.w(TAG, msg);
                throw new SecurityException(msg);

            } else if (AppWidgetManager.ACTION_APPWIDGET_CONFIGURE.equals(action)
                    || AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)) {
                // Special case for compatibility: we don't want apps to send this,
                // but historically it has not been protected and apps may be using it
                // to poke their own app widget.  So, instead of making it protected,
                // just limit it to the caller.
                if (callerPackage == null) {
                    String msg = "Permission Denial: not allowed to send broadcast "
                            + action + " from unknown caller.";
                    Slog.w(TAG, msg);
                    throw new SecurityException(msg);
                } else if (intent.getComponent() != null) {
                    // They are good enough to send to an explicit component...  verify
                    // it is being sent to the calling app.
                    if (!intent.getComponent().getPackageName().equals(
                            callerPackage)) {
                        String msg = "Permission Denial: not allowed to send broadcast "
                                + action + " to "
                                + intent.getComponent().getPackageName() + " from "
                                + callerPackage;
                        Slog.w(TAG, msg);
                        throw new SecurityException(msg);
                    }
                } else {
                    // Limit broadcast to their own package.
                    intent.setPackage(callerPackage);
                }
            }
        }

        boolean timeoutExempt = false;

        if (action != null) {
            if (getBackgroundLaunchBroadcasts().contains(action)) {
                if (DEBUG_BACKGROUND_CHECK) {
                    Slog.i(TAG, "Broadcast action " + action + " forcing include-background");
                }
                intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
            }

            // TODO: b/329211459 - Remove this after background remote intent is fixed.
            if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH)
                    && getWearRemoteIntentAction().equals(action)) {
                final int callerProcState = callerApp != null
                        ? callerApp.getCurProcState()
                        : ActivityManager.PROCESS_STATE_NONEXISTENT;
                if (ActivityManager.RunningAppProcessInfo.procStateToImportance(callerProcState)
                        > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    return ActivityManager.START_CANCELED;
                }
            }

            switch (action) {
                case Intent.ACTION_MEDIA_SCANNER_SCAN_FILE:
                    UserManagerInternal umInternal = LocalServices.getService(
                            UserManagerInternal.class);
                    UserInfo userInfo = umInternal.getUserInfo(userId);
                    if (userInfo != null && userInfo.isCloneProfile()) {
                        userId = umInternal.getProfileParentId(userId);
                    }
                    break;
                case Intent.ACTION_UID_REMOVED:
                case Intent.ACTION_PACKAGE_REMOVED:
                case Intent.ACTION_PACKAGE_CHANGED:
                case Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE:
                case Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE:
                case Intent.ACTION_PACKAGES_SUSPENDED:
                case Intent.ACTION_PACKAGES_UNSUSPENDED:
                    // Handle special intents: if this broadcast is from the package
                    // manager about a package being removed, we need to remove all of
                    // its activities from the history stack.
                    if (checkComponentPermission(
                            android.Manifest.permission.BROADCAST_PACKAGE_REMOVED,
                            callingPid, callingUid, -1, true)
                            != PackageManager.PERMISSION_GRANTED) {
                        String msg = "Permission Denial: " + intent.getAction()
                                + " broadcast from " + callerPackage + " (pid=" + callingPid
                                + ", uid=" + callingUid + ")"
                                + " requires "
                                + android.Manifest.permission.BROADCAST_PACKAGE_REMOVED;
                        Slog.w(TAG, msg);
                        throw new SecurityException(msg);
                    }
                    switch (action) {
                        case Intent.ACTION_UID_REMOVED:
                            final int uid = getUidFromIntent(intent);
                            if (uid >= 0) {
                                mService.mBatteryStatsService.removeUid(uid);
                                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                                    mService.mAppOpsService.resetAllModes(UserHandle.getUserId(uid),
                                            intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME));
                                } else {
                                    mService.mAppOpsService.uidRemoved(uid);
                                    mService.mServices.onUidRemovedLocked(uid);
                                }
                            }
                            break;
                        case Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE:
                            // If resources are unavailable just force stop all those packages
                            // and flush the attribute cache as well.
                            String[] list = intent.getStringArrayExtra(
                                    Intent.EXTRA_CHANGED_PACKAGE_LIST);
                            if (list != null && list.length > 0) {
                                for (int i = 0; i < list.length; i++) {
                                    mService.forceStopPackageLocked(list[i], -1, false, true, true,
                                            false, false, false, userId, "storage unmount");
                                }
                                mService.mAtmInternal.cleanupRecentTasksForUser(
                                        UserHandle.USER_ALL);
                                sendPackageBroadcastLocked(
                                        ApplicationThreadConstants.EXTERNAL_STORAGE_UNAVAILABLE,
                                        list, userId);
                            }
                            break;
                        case Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE:
                            mService.mAtmInternal.cleanupRecentTasksForUser(UserHandle.USER_ALL);
                            break;
                        case Intent.ACTION_PACKAGE_REMOVED:
                        case Intent.ACTION_PACKAGE_CHANGED:
                            Uri data = intent.getData();
                            String ssp;
                            if (data != null && (ssp = data.getSchemeSpecificPart()) != null) {
                                boolean removed = Intent.ACTION_PACKAGE_REMOVED.equals(action);
                                final boolean replacing =
                                        intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                                final boolean killProcess =
                                        !intent.getBooleanExtra(Intent.EXTRA_DONT_KILL_APP, false);
                                final boolean fullUninstall = removed && !replacing;

                                if (removed) {
                                    if (killProcess) {
                                        mService.forceStopPackageLocked(ssp, UserHandle.getAppId(
                                                        intent.getIntExtra(Intent.EXTRA_UID, -1)),
                                                false, true, true, false, fullUninstall, false,
                                                userId, "pkg removed");
                                        mService.getPackageManagerInternal()
                                                .onPackageProcessKilledForUninstall(ssp);
                                    } else {
                                        // Kill any app zygotes always, since they can't fork new
                                        // processes with references to the old code
                                        mService.forceStopAppZygoteLocked(ssp, UserHandle.getAppId(
                                                        intent.getIntExtra(Intent.EXTRA_UID, -1)),
                                                userId);
                                    }
                                    final int cmd = killProcess
                                            ? ApplicationThreadConstants.PACKAGE_REMOVED
                                            : ApplicationThreadConstants.PACKAGE_REMOVED_DONT_KILL;
                                    sendPackageBroadcastLocked(cmd,
                                            new String[] {ssp}, userId);
                                    if (fullUninstall) {
                                        // Remove all permissions granted from/to this package
                                        mService.mUgmInternal.removeUriPermissionsForPackage(ssp,
                                                userId, true, false);

                                        mService.mAtmInternal.removeRecentTasksByPackageName(ssp,
                                                userId);

                                        mService.mServices.forceStopPackageLocked(ssp, userId);
                                        mService.mAtmInternal.onPackageUninstalled(ssp, userId);
                                        mService.mBatteryStatsService.notePackageUninstalled(ssp);
                                    }
                                } else {
                                    if (killProcess) {
                                        int reason;
                                        int subReason;
                                        if (replacing) {
                                            reason = ApplicationExitInfo.REASON_PACKAGE_UPDATED;
                                            subReason = ApplicationExitInfo.SUBREASON_UNKNOWN;
                                        } else {
                                            reason =
                                                    ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE;
                                            subReason = ApplicationExitInfo.SUBREASON_UNKNOWN;
                                        }

                                        final int extraUid = intent.getIntExtra(Intent.EXTRA_UID,
                                                -1);
                                        synchronized (mService.mProcLock) {
                                            mService.mProcessList.killPackageProcessesLSP(ssp,
                                                    UserHandle.getAppId(extraUid),
                                                    userId, ProcessList.INVALID_ADJ,
                                                    reason,
                                                    subReason,
                                                    "change " + ssp);
                                        }
                                    }
                                    mService.cleanupDisabledPackageComponentsLocked(ssp, userId,
                                            intent.getStringArrayExtra(
                                                    Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST));
                                    mService.mServices.schedulePendingServiceStartLocked(
                                            ssp, userId);
                                }
                            }
                            break;
                        case Intent.ACTION_PACKAGES_SUSPENDED:
                        case Intent.ACTION_PACKAGES_UNSUSPENDED:
                            final boolean suspended = Intent.ACTION_PACKAGES_SUSPENDED.equals(
                                    intent.getAction());
                            final String[] packageNames = intent.getStringArrayExtra(
                                    Intent.EXTRA_CHANGED_PACKAGE_LIST);
                            final int userIdExtra = intent.getIntExtra(
                                    Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);

                            mService.mAtmInternal.onPackagesSuspendedChanged(packageNames,
                                    suspended, userIdExtra);

                            final boolean quarantined = intent.getBooleanExtra(
                                    Intent.EXTRA_QUARANTINED, false);
                            if (suspended && quarantined && packageNames != null) {
                                for (int i = 0; i < packageNames.length; i++) {
                                    mService.forceStopPackage(packageNames[i], userId,
                                            ActivityManager.FLAG_OR_STOPPED, "quarantined");
                                }
                            }

                            break;
                    }
                    break;
                case Intent.ACTION_PACKAGE_REPLACED: {
                    final Uri data = intent.getData();
                    final String ssp;
                    if (data != null && (ssp = data.getSchemeSpecificPart()) != null) {
                        ApplicationInfo aInfo = null;
                        try {
                            aInfo = AppGlobals.getPackageManager()
                                    .getApplicationInfo(ssp, STOCK_PM_FLAGS, userId);
                        } catch (RemoteException ignore) {
                        }
                        if (aInfo == null) {
                            Slog.w(TAG, "Dropping ACTION_PACKAGE_REPLACED for non-existent pkg:"
                                    + " ssp=" + ssp + " data=" + data);
                            scheduleCanceledResultTo(resultToApp, resultTo, intent,
                                    userId, brOptions, callingUid, callerPackage);
                            return ActivityManager.BROADCAST_SUCCESS;
                        }
                        mService.updateAssociationForApp(aInfo);
                        mService.mAtmInternal.onPackageReplaced(aInfo);
                        mService.mServices.updateServiceApplicationInfoLocked(aInfo);
                        sendPackageBroadcastLocked(ApplicationThreadConstants.PACKAGE_REPLACED,
                                new String[] {ssp}, userId);
                    }
                    break;
                }
                case Intent.ACTION_PACKAGE_ADDED: {
                    // Special case for adding a package: by default turn on compatibility mode.
                    Uri data = intent.getData();
                    String ssp;
                    if (data != null && (ssp = data.getSchemeSpecificPart()) != null) {
                        final boolean replacing =
                                intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                        mService.mAtmInternal.onPackageAdded(ssp, replacing);

                        try {
                            ApplicationInfo ai = AppGlobals.getPackageManager()
                                    .getApplicationInfo(ssp, STOCK_PM_FLAGS, 0);
                            mService.mBatteryStatsService.notePackageInstalled(ssp,
                                    ai != null ? ai.longVersionCode : 0);
                        } catch (RemoteException e) {
                        }
                    }
                    break;
                }
                case Intent.ACTION_PACKAGE_DATA_CLEARED: {
                    Uri data = intent.getData();
                    String ssp;
                    if (data != null && (ssp = data.getSchemeSpecificPart()) != null) {
                        mService.mAtmInternal.onPackageDataCleared(ssp, userId);
                    }
                    break;
                }
                case Intent.ACTION_TIMEZONE_CHANGED:
                    // If this is the time zone changed action, queue up a message that will reset
                    // the timezone of all currently running processes. This message will get
                    // queued up before the broadcast happens.
                    mService.mHandler.sendEmptyMessage(UPDATE_TIME_ZONE);
                    break;
                case Intent.ACTION_TIME_CHANGED:
                    // EXTRA_TIME_PREF_24_HOUR_FORMAT is optional so we must distinguish between
                    // the tri-state value it may contain and "unknown".
                    // For convenience we re-use the Intent extra values.
                    final int NO_EXTRA_VALUE_FOUND = -1;
                    final int timeFormatPreferenceMsgValue = intent.getIntExtra(
                            Intent.EXTRA_TIME_PREF_24_HOUR_FORMAT,
                            NO_EXTRA_VALUE_FOUND /* defaultValue */);
                    // Only send a message if the time preference is available.
                    if (timeFormatPreferenceMsgValue != NO_EXTRA_VALUE_FOUND) {
                        Message updateTimePreferenceMsg =
                                mService.mHandler.obtainMessage(UPDATE_TIME_PREFERENCE_MSG,
                                        timeFormatPreferenceMsgValue, 0);
                        mService.mHandler.sendMessage(updateTimePreferenceMsg);
                    }
                    mService.mBatteryStatsService.noteCurrentTimeChanged();
                    break;
                case ConnectivityManager.ACTION_CLEAR_DNS_CACHE:
                    mService.mHandler.sendEmptyMessage(CLEAR_DNS_CACHE_MSG);
                    break;
                case Proxy.PROXY_CHANGE_ACTION:
                    mService.mHandler.sendMessage(mService.mHandler.obtainMessage(
                            UPDATE_HTTP_PROXY_MSG));
                    break;
                case android.hardware.Camera.ACTION_NEW_PICTURE:
                case android.hardware.Camera.ACTION_NEW_VIDEO:
                    // In N we just turned these off; in O we are turing them back on partly,
                    // only for registered receivers.  This will still address the main problem
                    // (a spam of apps waking up when a picture is taken putting significant
                    // memory pressure on the system at a bad point), while still allowing apps
                    // that are already actively running to know about this happening.
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                    break;
                case android.security.KeyChain.ACTION_TRUST_STORE_CHANGED:
                    mService.mHandler.sendEmptyMessage(HANDLE_TRUST_STORAGE_UPDATE_MSG);
                    break;
                case "com.android.launcher.action.INSTALL_SHORTCUT":
                    // As of O, we no longer support this broadcasts, even for pre-O apps.
                    // Apps should now be using ShortcutManager.pinRequestShortcut().
                    Log.w(TAG, "Broadcast " + action
                            + " no longer supported. It will not be delivered.");
                    scheduleCanceledResultTo(resultToApp, resultTo, intent,
                            userId, brOptions, callingUid, callerPackage);
                    return ActivityManager.BROADCAST_SUCCESS;
                case Intent.ACTION_PRE_BOOT_COMPLETED:
                    timeoutExempt = true;
                    break;
                case Intent.ACTION_CLOSE_SYSTEM_DIALOGS:
                    if (!mService.mAtmInternal.checkCanCloseSystemDialogs(callingPid, callingUid,
                            callerPackage)) {
                        scheduleCanceledResultTo(resultToApp, resultTo, intent,
                                userId, brOptions, callingUid, callerPackage);
                        // Returning success seems to be the pattern here
                        return ActivityManager.BROADCAST_SUCCESS;
                    }
                    break;
            }

            if (Intent.ACTION_PACKAGE_ADDED.equals(action)
                    || Intent.ACTION_PACKAGE_REMOVED.equals(action)
                    || Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
                final int uid = getUidFromIntent(intent);
                if (uid != -1) {
                    final UidRecord uidRec = mService.mProcessList.getUidRecordLOSP(uid);
                    if (uidRec != null) {
                        uidRec.updateHasInternetPermission();
                    }
                }
            }
        }

        // Add to the sticky list if requested.
        if (sticky) {
            if (mService.checkPermission(android.Manifest.permission.BROADCAST_STICKY,
                    callingPid, callingUid)
                    != PackageManager.PERMISSION_GRANTED) {
                String msg =
                        "Permission Denial: broadcastIntent() requesting a sticky broadcast from"
                                + " pid="
                                + callingPid
                                + ", uid="
                                + callingUid
                                + " requires "
                                + android.Manifest.permission.BROADCAST_STICKY;
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }
            if (requiredPermissions != null && requiredPermissions.length > 0) {
                Slog.w(TAG, "Can't broadcast sticky intent " + intent
                        + " and enforce permissions " + Arrays.toString(requiredPermissions));
                scheduleCanceledResultTo(resultToApp, resultTo, intent,
                        userId, brOptions, callingUid, callerPackage);
                return ActivityManager.BROADCAST_STICKY_CANT_HAVE_PERMISSION;
            }
            if (intent.getComponent() != null) {
                throw new SecurityException(
                        "Sticky broadcasts can't target a specific component");
            }
            synchronized (mStickyBroadcasts) {
                // We use userId directly here, since the "all" target is maintained
                // as a separate set of sticky broadcasts.
                if (userId != UserHandle.USER_ALL) {
                    // But first, if this is not a broadcast to all users, then
                    // make sure it doesn't conflict with an existing broadcast to
                    // all users.
                    ArrayMap<String, ArrayList<StickyBroadcast>> stickies = mStickyBroadcasts.get(
                            UserHandle.USER_ALL);
                    if (stickies != null) {
                        ArrayList<StickyBroadcast> list = stickies.get(intent.getAction());
                        if (list != null) {
                            int N = list.size();
                            int i;
                            for (i = 0; i < N; i++) {
                                if (intent.filterEquals(list.get(i).intent)) {
                                    throw new IllegalArgumentException("Sticky broadcast " + intent
                                            + " for user " + userId
                                            + " conflicts with existing global broadcast");
                                }
                            }
                        }
                    }
                }
                ArrayMap<String, ArrayList<StickyBroadcast>> stickies =
                        mStickyBroadcasts.get(userId);
                if (stickies == null) {
                    stickies = new ArrayMap<>();
                    mStickyBroadcasts.put(userId, stickies);
                }
                ArrayList<StickyBroadcast> list = stickies.get(intent.getAction());
                if (list == null) {
                    list = new ArrayList<>();
                    stickies.put(intent.getAction(), list);
                }
                final boolean deferUntilActive = BroadcastRecord.calculateDeferUntilActive(
                        callingUid, brOptions, resultTo, ordered,
                        BroadcastRecord.calculateUrgent(intent, brOptions));
                final int stickiesCount = list.size();
                int i;
                for (i = 0; i < stickiesCount; i++) {
                    if (intent.filterEquals(list.get(i).intent)) {
                        // This sticky already exists, replace it.
                        list.set(i, StickyBroadcast.create(new Intent(intent), deferUntilActive,
                                callingUid, callerAppProcessState, resolvedType));
                        break;
                    }
                }
                if (i >= stickiesCount) {
                    list.add(StickyBroadcast.create(new Intent(intent), deferUntilActive,
                            callingUid, callerAppProcessState, resolvedType));
                }
                BroadcastStickyCache.incrementVersion(intent.getAction());
            }
        }

        int[] users;
        if (userId == UserHandle.USER_ALL) {
            // Caller wants broadcast to go to all started users.
            users = mService.mUserController.getStartedUserArray();
        } else {
            // Caller wants broadcast to go to one specific user.
            users = new int[] {userId};
        }

        var args = new SaferIntentUtils.IntentArgs(intent, resolvedType,
                true /* isReceiver */, true /* resolveForStart */, callingUid, callingPid);
        args.platformCompat = mService.mPlatformCompat;

        // Figure out who all will receive this broadcast.
        final int cookie = BroadcastQueue.traceBegin("queryReceivers");
        List receivers = null;
        List<BroadcastFilter> registeredReceivers = null;
        // Need to resolve the intent to interested receivers...
        if ((intent.getFlags() & Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0) {
            receivers = collectReceiverComponents(
                    intent, resolvedType, callingUid, callingPid, users, broadcastAllowList);
        }
        if (intent.getComponent() == null) {
            final PackageDataSnapshot snapshot = mService.getPackageManagerInternal().snapshot();
            if (userId == UserHandle.USER_ALL && callingUid == SHELL_UID) {
                // Query one target user at a time, excluding shell-restricted users
                for (int i = 0; i < users.length; i++) {
                    if (mService.mUserController.hasUserRestriction(
                            UserManager.DISALLOW_DEBUGGING_FEATURES, users[i])) {
                        continue;
                    }
                    List<BroadcastFilter> registeredReceiversForUser =
                            mReceiverResolver.queryIntent(snapshot, intent,
                                    resolvedType, false /*defaultOnly*/, users[i]);
                    if (registeredReceivers == null) {
                        registeredReceivers = registeredReceiversForUser;
                    } else if (registeredReceiversForUser != null) {
                        registeredReceivers.addAll(registeredReceiversForUser);
                    }
                }
            } else {
                registeredReceivers = mReceiverResolver.queryIntent(snapshot, intent,
                        resolvedType, false /*defaultOnly*/, userId);
            }
            if (registeredReceivers != null) {
                SaferIntentUtils.blockNullAction(args, registeredReceivers);
            }
        }
        BroadcastQueue.traceEnd(cookie);

        final boolean replacePending =
                (intent.getFlags() & Intent.FLAG_RECEIVER_REPLACE_PENDING) != 0;

        if (DEBUG_BROADCAST) {
            Slog.v(TAG_BROADCAST, "Enqueueing broadcast: " + intent.getAction()
                    + " replacePending=" + replacePending);
        }
        if (registeredReceivers != null && broadcastAllowList != null) {
            // if a uid whitelist was provided, remove anything in the application space that wasn't
            // in it.
            for (int i = registeredReceivers.size() - 1; i >= 0; i--) {
                final int owningAppId = UserHandle.getAppId(registeredReceivers.get(i).owningUid);
                if (owningAppId >= Process.FIRST_APPLICATION_UID
                        && Arrays.binarySearch(broadcastAllowList, owningAppId) < 0) {
                    registeredReceivers.remove(i);
                }
            }
        }

        int NR = registeredReceivers != null ? registeredReceivers.size() : 0;

        // Merge into one list.
        int ir = 0;
        if (receivers != null) {
            // A special case for PACKAGE_ADDED: do not allow the package
            // being added to see this broadcast.  This prevents them from
            // using this as a back door to get run as soon as they are
            // installed.  Maybe in the future we want to have a special install
            // broadcast or such for apps, but we'd like to deliberately make
            // this decision.
            String[] skipPackages = null;
            if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())
                    || Intent.ACTION_PACKAGE_RESTARTED.equals(intent.getAction())
                    || Intent.ACTION_PACKAGE_DATA_CLEARED.equals(intent.getAction())) {
                Uri data = intent.getData();
                if (data != null) {
                    String pkgName = data.getSchemeSpecificPart();
                    if (pkgName != null) {
                        skipPackages = new String[] { pkgName };
                    }
                }
            } else if (Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(intent.getAction())) {
                skipPackages = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
            }
            if (skipPackages != null && (skipPackages.length > 0)) {
                for (String skipPackage : skipPackages) {
                    if (skipPackage != null) {
                        int NT = receivers.size();
                        for (int it = 0; it < NT; it++) {
                            ResolveInfo curt = (ResolveInfo) receivers.get(it);
                            if (curt.activityInfo.packageName.equals(skipPackage)) {
                                receivers.remove(it);
                                it--;
                                NT--;
                            }
                        }
                    }
                }
            }

            int NT = receivers != null ? receivers.size() : 0;
            int it = 0;
            ResolveInfo curt = null;
            BroadcastFilter curr = null;
            while (it < NT && ir < NR) {
                if (curt == null) {
                    curt = (ResolveInfo) receivers.get(it);
                }
                if (curr == null) {
                    curr = registeredReceivers.get(ir);
                }
                if (curr.getPriority() >= curt.priority) {
                    // Insert this broadcast record into the final list.
                    receivers.add(it, curr);
                    ir++;
                    curr = null;
                    it++;
                    NT++;
                } else {
                    // Skip to the next ResolveInfo in the final list.
                    it++;
                    curt = null;
                }
            }
        }
        while (ir < NR) {
            if (receivers == null) {
                receivers = new ArrayList();
            }
            receivers.add(registeredReceivers.get(ir));
            ir++;
        }

        if (isCallerSystem) {
            checkBroadcastFromSystem(intent, callerApp, callerPackage, callingUid,
                    isProtectedBroadcast, receivers);
        }

        if ((receivers != null && receivers.size() > 0)
                || resultTo != null) {
            BroadcastQueue queue = mBroadcastQueue;
            SaferIntentUtils.filterNonExportedComponents(args, receivers);
            BroadcastRecord r = new BroadcastRecord(queue, intent, callerApp, callerPackage,
                    callerFeatureId, callingPid, callingUid, callerInstantApp, resolvedType,
                    requiredPermissions, excludedPermissions, excludedPackages, appOp, brOptions,
                    receivers, resultToApp, resultTo, resultCode, resultData, resultExtras,
                    ordered, sticky, false, userId,
                    backgroundStartPrivileges, timeoutExempt, filterExtrasForReceiver,
                    callerAppProcessState);
            broadcastSentEventRecord.setBroadcastRecord(r);

            if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Enqueueing ordered broadcast " + r);
            queue.enqueueBroadcastLocked(r);
        } else {
            // There was nobody interested in the broadcast, but we still want to record
            // that it happened.
            if (intent.getComponent() == null && intent.getPackage() == null
                    && (intent.getFlags() & Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0) {
                // This was an implicit broadcast... let's record it for posterity.
                addBroadcastStatLocked(intent.getAction(), callerPackage, 0, 0, 0);
            }
        }

        return ActivityManager.BROADCAST_SUCCESS;
    }

    @GuardedBy("mService")
    private void scheduleCanceledResultTo(ProcessRecord resultToApp, IIntentReceiver resultTo,
            Intent intent, int userId, BroadcastOptions options, int callingUid,
            String callingPackage) {
        if (resultTo == null) {
            return;
        }
        final ProcessRecord app = resultToApp;
        final IApplicationThread thread  = (app != null) ? app.getOnewayThread() : null;
        if (thread != null) {
            try {
                final boolean shareIdentity = (options != null && options.isShareIdentityEnabled());
                thread.scheduleRegisteredReceiver(
                        resultTo, intent, Activity.RESULT_CANCELED, null, null,
                        false, false, true, userId, app.mState.getReportedProcState(),
                        shareIdentity ? callingUid : Process.INVALID_UID,
                        shareIdentity ? callingPackage : null);
            } catch (RemoteException e) {
                final String msg = "Failed to schedule result of " + intent + " via "
                        + app + ": " + e;
                app.killLocked("Can't schedule resultTo", ApplicationExitInfo.REASON_OTHER,
                        ApplicationExitInfo.SUBREASON_UNDELIVERED_BROADCAST, true);
                Slog.d(TAG, msg);
            }
        }
    }

    @GuardedBy("mService")
    private int getRealProcessStateLocked(ProcessRecord app, int pid) {
        if (app == null) {
            synchronized (mService.mPidsSelfLocked) {
                app = mService.mPidsSelfLocked.get(pid);
            }
        }
        if (app != null && app.getThread() != null && !app.isKilled()) {
            return app.mState.getCurProcState();
        }
        return PROCESS_STATE_NONEXISTENT;
    }

    @GuardedBy("mService")
    private int getRealUidStateLocked(ProcessRecord app, int pid) {
        if (app == null) {
            synchronized (mService.mPidsSelfLocked) {
                app = mService.mPidsSelfLocked.get(pid);
            }
        }
        if (app != null && app.getThread() != null && !app.isKilled()) {
            final UidRecord uidRecord = app.getUidRecord();
            if (uidRecord != null) {
                return uidRecord.getCurProcState();
            }
        }
        return PROCESS_STATE_NONEXISTENT;
    }

    @VisibleForTesting
    ArrayList<StickyBroadcast> getStickyBroadcastsForTest(String action, int userId) {
        synchronized (mStickyBroadcasts) {
            final ArrayMap<String, ArrayList<StickyBroadcast>> stickyBroadcasts =
                    mStickyBroadcasts.get(userId);
            if (stickyBroadcasts == null) {
                return null;
            }
            return stickyBroadcasts.get(action);
        }
    }

    void unbroadcastIntent(IApplicationThread caller, Intent intent, int userId) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        userId = mService.mUserController.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, true, ALLOW_NON_FULL,
                "removeStickyBroadcast", null);

        if (mService.checkCallingPermission(android.Manifest.permission.BROADCAST_STICKY)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: unbroadcastIntent() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + android.Manifest.permission.BROADCAST_STICKY;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        final ArrayList<String> changedStickyBroadcasts = new ArrayList<>();
        synchronized (mStickyBroadcasts) {
            ArrayMap<String, ArrayList<StickyBroadcast>> stickies = mStickyBroadcasts.get(userId);
            if (stickies != null) {
                ArrayList<StickyBroadcast> list = stickies.get(intent.getAction());
                if (list != null) {
                    int N = list.size();
                    int i;
                    for (i = 0; i < N; i++) {
                        if (intent.filterEquals(list.get(i).intent)) {
                            list.remove(i);
                            break;
                        }
                    }
                    if (list.size() <= 0) {
                        stickies.remove(intent.getAction());
                    }
                    changedStickyBroadcasts.add(intent.getAction());
                }
                if (stickies.size() <= 0) {
                    mStickyBroadcasts.remove(userId);
                }
            }
        }
        for (int i = changedStickyBroadcasts.size() - 1; i >= 0; --i) {
            BroadcastStickyCache.incrementVersionIfExists(changedStickyBroadcasts.get(i));
        }
    }

    void finishReceiver(IBinder caller, int resultCode, String resultData,
            Bundle resultExtras, boolean resultAbort, int flags) {
        if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Finish receiver: " + caller);

        // Refuse possible leaked file descriptors
        if (resultExtras != null && resultExtras.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Bundle");
        }

        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mService) {
                final ProcessRecord callerApp = mService.getRecordForAppLOSP(caller);
                if (callerApp == null) {
                    Slog.w(TAG, "finishReceiver: no app for " + caller);
                    return;
                }

                mBroadcastQueue.finishReceiverLocked(callerApp, resultCode,
                        resultData, resultExtras, resultAbort, true);
                // updateOomAdjLocked() will be done here
                mService.trimApplicationsLocked(false, OOM_ADJ_REASON_FINISH_RECEIVER);
            }

        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * @return uid from the extra field {@link Intent#EXTRA_UID} if present, Otherwise -1
     */
    private int getUidFromIntent(Intent intent) {
        if (intent == null) {
            return -1;
        }
        final Bundle intentExtras = intent.getExtras();
        return intent.hasExtra(Intent.EXTRA_UID)
                ? intentExtras.getInt(Intent.EXTRA_UID) : -1;
    }

    final void rotateBroadcastStatsIfNeededLocked() {
        final long now = SystemClock.elapsedRealtime();
        if (mCurBroadcastStats == null
                || (mCurBroadcastStats.mStartRealtime + (24 * 60 * 60 * 1000) < now)) {
            mLastBroadcastStats = mCurBroadcastStats;
            if (mLastBroadcastStats != null) {
                mLastBroadcastStats.mEndRealtime = SystemClock.elapsedRealtime();
                mLastBroadcastStats.mEndUptime = SystemClock.uptimeMillis();
            }
            mCurBroadcastStats = new BroadcastStats();
        }
    }

    final void addBroadcastStatLocked(String action, String srcPackage, int receiveCount,
            int skipCount, long dispatchTime) {
        rotateBroadcastStatsIfNeededLocked();
        mCurBroadcastStats.addBroadcast(action, srcPackage, receiveCount, skipCount, dispatchTime);
    }

    final void addBackgroundCheckViolationLocked(String action, String targetPackage) {
        rotateBroadcastStatsIfNeededLocked();
        mCurBroadcastStats.addBackgroundCheckViolation(action, targetPackage);
    }

    final void notifyBroadcastFinishedLocked(@NonNull BroadcastRecord original) {
        final ApplicationInfo info = original.callerApp != null ? original.callerApp.info : null;
        final String callerPackage = info != null ? info.packageName : original.callerPackage;
        if (callerPackage != null) {
            mService.mHandler.obtainMessage(ActivityManagerService.DISPATCH_SENDING_BROADCAST_EVENT,
                    original.callingUid, 0, callerPackage).sendToTarget();
        }
    }

    final Intent verifyBroadcastLocked(Intent intent) {
        if (intent != null) {
            // Refuse possible leaked file descriptors
            if (intent.hasFileDescriptors()) {
                throw new IllegalArgumentException("File descriptors passed in Intent");
            }
            // Remove existing mismatch flag so it can be properly updated later
            intent.removeExtendedFlags(Intent.EXTENDED_FLAG_FILTER_MISMATCH);
        }

        int flags = intent.getFlags();

        if (!mService.mProcessesReady) {
            // if the caller really truly claims to know what they're doing, go
            // ahead and allow the broadcast without launching any receivers
            if ((flags & Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT) != 0) {
                // This will be turned into a FLAG_RECEIVER_REGISTERED_ONLY later on if needed.
            } else if ((flags & Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0) {
                Slog.e(TAG, "Attempt to launch receivers of broadcast intent " + intent
                        + " before boot completion");
                throw new IllegalStateException("Cannot broadcast before boot completed");
            }
        }

        if ((flags & Intent.FLAG_RECEIVER_BOOT_UPGRADE) != 0) {
            throw new IllegalArgumentException(
                    "Can't use FLAG_RECEIVER_BOOT_UPGRADE here");
        }

        if ((flags & Intent.FLAG_RECEIVER_FROM_SHELL) != 0) {
            switch (Binder.getCallingUid()) {
                case ROOT_UID:
                case SHELL_UID:
                    break;
                default:
                    Slog.w(TAG, "Removing FLAG_RECEIVER_FROM_SHELL because caller is UID "
                            + Binder.getCallingUid());
                    intent.removeFlags(Intent.FLAG_RECEIVER_FROM_SHELL);
                    break;
            }
        }

        return intent;
    }

    private ArraySet<String> getBackgroundLaunchBroadcasts() {
        if (mBackgroundLaunchBroadcasts == null) {
            mBackgroundLaunchBroadcasts = SystemConfig.getInstance().getAllowImplicitBroadcasts();
        }
        return mBackgroundLaunchBroadcasts;
    }

    private boolean isInstantApp(ProcessRecord record, @Nullable String callerPackage, int uid) {
        if (UserHandle.getAppId(uid) < FIRST_APPLICATION_UID) {
            return false;
        }
        // Easy case -- we have the app's ProcessRecord.
        if (record != null) {
            return record.info.isInstantApp();
        }
        // Otherwise check with PackageManager.
        IPackageManager pm = AppGlobals.getPackageManager();
        try {
            if (callerPackage == null) {
                final String[] packageNames = pm.getPackagesForUid(uid);
                if (packageNames == null || packageNames.length == 0) {
                    throw new IllegalArgumentException("Unable to determine caller package name");
                }
                // Instant Apps can't use shared uids, so its safe to only check the first package.
                callerPackage = packageNames[0];
            }
            mService.mAppOpsService.checkPackage(uid, callerPackage);
            return pm.isInstantApp(callerPackage, UserHandle.getUserId(uid));
        } catch (RemoteException e) {
            Slog.e(TAG, "Error looking up if " + callerPackage + " is an instant app.", e);
            return true;
        }
    }

    private String getWearRemoteIntentAction() {
        return mContext.getResources().getString(
                com.android.internal.R.string.config_wearRemoteIntentAction);
    }

    private void sendPackageBroadcastLocked(int cmd, String[] packages, int userId) {
        mService.mProcessList.sendPackageBroadcastLocked(cmd, packages, userId);
    }

    private List<ResolveInfo> collectReceiverComponents(
            Intent intent, String resolvedType, int callingUid, int callingPid,
            int[] users, int[] broadcastAllowList) {
        // TODO: come back and remove this assumption to triage all broadcasts
        long pmFlags = STOCK_PM_FLAGS | MATCH_DEBUG_TRIAGED_MISSING;

        List<ResolveInfo> receivers = null;
        HashSet<ComponentName> singleUserReceivers = null;
        boolean scannedFirstReceivers = false;
        for (int user : users) {
            // Skip users that have Shell restrictions
            if (callingUid == SHELL_UID
                    && mService.mUserController.hasUserRestriction(
                    UserManager.DISALLOW_DEBUGGING_FEATURES, user)) {
                continue;
            }
            List<ResolveInfo> newReceivers = mService.mPackageManagerInt.queryIntentReceivers(
                    intent, resolvedType, pmFlags, callingUid, callingPid, user, /* forSend */true);
            if (user != UserHandle.USER_SYSTEM && newReceivers != null) {
                // If this is not the system user, we need to check for
                // any receivers that should be filtered out.
                for (int i = 0; i < newReceivers.size(); i++) {
                    ResolveInfo ri = newReceivers.get(i);
                    if ((ri.activityInfo.flags & ActivityInfo.FLAG_SYSTEM_USER_ONLY) != 0) {
                        newReceivers.remove(i);
                        i--;
                    }
                }
            }
            // Replace the alias receivers with their targets.
            if (newReceivers != null) {
                for (int i = newReceivers.size() - 1; i >= 0; i--) {
                    final ResolveInfo ri = newReceivers.get(i);
                    final ComponentAliasResolver.Resolution<ResolveInfo> resolution =
                            mService.mComponentAliasResolver.resolveReceiver(intent, ri,
                                    resolvedType, pmFlags, user, callingUid, callingPid);
                    if (resolution == null) {
                        // It was an alias, but the target was not found.
                        newReceivers.remove(i);
                        continue;
                    }
                    if (resolution.isAlias()) {
                        newReceivers.set(i, resolution.getTarget());
                    }
                }
            }
            if (newReceivers != null && newReceivers.size() == 0) {
                newReceivers = null;
            }

            if (receivers == null) {
                receivers = newReceivers;
            } else if (newReceivers != null) {
                // We need to concatenate the additional receivers
                // found with what we have do far.  This would be easy,
                // but we also need to de-dup any receivers that are
                // singleUser.
                if (!scannedFirstReceivers) {
                    // Collect any single user receivers we had already retrieved.
                    scannedFirstReceivers = true;
                    for (int i = 0; i < receivers.size(); i++) {
                        ResolveInfo ri = receivers.get(i);
                        if ((ri.activityInfo.flags & ActivityInfo.FLAG_SINGLE_USER) != 0) {
                            ComponentName cn = new ComponentName(
                                    ri.activityInfo.packageName, ri.activityInfo.name);
                            if (singleUserReceivers == null) {
                                singleUserReceivers = new HashSet<ComponentName>();
                            }
                            singleUserReceivers.add(cn);
                        }
                    }
                }
                // Add the new results to the existing results, tracking
                // and de-dupping single user receivers.
                for (int i = 0; i < newReceivers.size(); i++) {
                    ResolveInfo ri = newReceivers.get(i);
                    if ((ri.activityInfo.flags & ActivityInfo.FLAG_SINGLE_USER) != 0) {
                        ComponentName cn = new ComponentName(
                                ri.activityInfo.packageName, ri.activityInfo.name);
                        if (singleUserReceivers == null) {
                            singleUserReceivers = new HashSet<ComponentName>();
                        }
                        if (!singleUserReceivers.contains(cn)) {
                            singleUserReceivers.add(cn);
                            receivers.add(ri);
                        }
                    } else {
                        receivers.add(ri);
                    }
                }
            }
        }
        if (receivers != null && broadcastAllowList != null) {
            for (int i = receivers.size() - 1; i >= 0; i--) {
                final int receiverAppId = UserHandle.getAppId(
                        receivers.get(i).activityInfo.applicationInfo.uid);
                if (receiverAppId >= Process.FIRST_APPLICATION_UID
                        && Arrays.binarySearch(broadcastAllowList, receiverAppId) < 0) {
                    receivers.remove(i);
                }
            }
        }
        return receivers;
    }

    private void checkBroadcastFromSystem(Intent intent, ProcessRecord callerApp,
            String callerPackage, int callingUid, boolean isProtectedBroadcast, List receivers) {
        if ((intent.getFlags() & Intent.FLAG_RECEIVER_FROM_SHELL) != 0) {
            // Don't yell about broadcasts sent via shell
            return;
        }

        final String action = intent.getAction();
        if (isProtectedBroadcast
                || Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)
                || Intent.ACTION_DISMISS_KEYBOARD_SHORTCUTS.equals(action)
                || Intent.ACTION_MEDIA_BUTTON.equals(action)
                || Intent.ACTION_MEDIA_SCANNER_SCAN_FILE.equals(action)
                || Intent.ACTION_SHOW_KEYBOARD_SHORTCUTS.equals(action)
                || Intent.ACTION_MASTER_CLEAR.equals(action)
                || Intent.ACTION_FACTORY_RESET.equals(action)
                || AppWidgetManager.ACTION_APPWIDGET_CONFIGURE.equals(action)
                || AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)
                || TelephonyManager.ACTION_REQUEST_OMADM_CONFIGURATION_UPDATE.equals(action)
                || SuggestionSpan.ACTION_SUGGESTION_PICKED.equals(action)
                || AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(action)
                || AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION.equals(action)) {
            // Broadcast is either protected, or it's a public action that
            // we've relaxed, so it's fine for system internals to send.
            return;
        }

        // This broadcast may be a problem...  but there are often system components that
        // want to send an internal broadcast to themselves, which is annoying to have to
        // explicitly list each action as a protected broadcast, so we will check for that
        // one safe case and allow it: an explicit broadcast, only being received by something
        // that has protected itself.
        if (intent.getPackage() != null || intent.getComponent() != null) {
            if (receivers == null || receivers.size() == 0) {
                // Intent is explicit and there's no receivers.
                // This happens, e.g. , when a system component sends a broadcast to
                // its own runtime receiver, and there's no manifest receivers for it,
                // because this method is called twice for each broadcast,
                // for runtime receivers and manifest receivers and the later check would find
                // no receivers.
                return;
            }
            boolean allProtected = true;
            for (int i = receivers.size() - 1; i >= 0; i--) {
                Object target = receivers.get(i);
                if (target instanceof ResolveInfo) {
                    ResolveInfo ri = (ResolveInfo) target;
                    if (ri.activityInfo.exported && ri.activityInfo.permission == null) {
                        allProtected = false;
                        break;
                    }
                } else {
                    BroadcastFilter bf = (BroadcastFilter) target;
                    if (bf.exported && bf.requiredPermission == null) {
                        allProtected = false;
                        break;
                    }
                }
            }
            if (allProtected) {
                // All safe!
                return;
            }
        }

        // The vast majority of broadcasts sent from system internals
        // should be protected to avoid security holes, so yell loudly
        // to ensure we examine these cases.
        if (callerApp != null) {
            Log.wtf(TAG, "Sending non-protected broadcast " + action
                            + " from system " + callerApp.toShortString() + " pkg " + callerPackage,
                    new Throwable());
        } else {
            Log.wtf(TAG, "Sending non-protected broadcast " + action
                            + " from system uid " + UserHandle.formatUid(callingUid)
                            + " pkg " + callerPackage,
                    new Throwable());
        }
    }

    // Apply permission policy around the use of specific broadcast options
    void enforceBroadcastOptionPermissionsInternal(
            @Nullable Bundle options, int callingUid) {
        enforceBroadcastOptionPermissionsInternal(BroadcastOptions.fromBundleNullable(options),
                callingUid);
    }

    private void enforceBroadcastOptionPermissionsInternal(
            @Nullable BroadcastOptions options, int callingUid) {
        if (options != null && callingUid != Process.SYSTEM_UID) {
            if (options.isAlarmBroadcast()) {
                if (DEBUG_BROADCAST_LIGHT) {
                    Slog.w(TAG, "Non-system caller " + callingUid
                            + " may not flag broadcast as alarm");
                }
                throw new SecurityException(
                        "Non-system callers may not flag broadcasts as alarm");
            }
            if (options.isInteractive()) {
                mService.enforceCallingPermission(
                        android.Manifest.permission.BROADCAST_OPTION_INTERACTIVE,
                        "setInteractive");
            }
        }
    }

    void startBroadcastObservers() {
        mBroadcastQueue.start(mContext.getContentResolver());
    }

    void removeStickyBroadcasts(int userId) {
        final ArrayList<String> changedStickyBroadcasts = new ArrayList<>();
        synchronized (mStickyBroadcasts) {
            final ArrayMap<String, ArrayList<StickyBroadcast>> stickies =
                    mStickyBroadcasts.get(userId);
            if (stickies != null) {
                changedStickyBroadcasts.addAll(stickies.keySet());
            }
            mStickyBroadcasts.remove(userId);
        }
        for (int i = changedStickyBroadcasts.size() - 1; i >= 0; --i) {
            BroadcastStickyCache.incrementVersionIfExists(changedStickyBroadcasts.get(i));
        }
    }

    @NeverCompile
    void dumpBroadcastsLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, String dumpPackage) {
        boolean dumpConstants = true;
        boolean dumpHistory = true;
        boolean needSep = false;
        boolean onlyHistory = false;
        boolean printedAnything = false;
        boolean onlyReceivers = false;
        int filteredUid = Process.INVALID_UID;

        if ("history".equals(dumpPackage)) {
            if (opti < args.length && "-s".equals(args[opti])) {
                dumpAll = false;
            }
            onlyHistory = true;
            dumpPackage = null;
        }
        if ("receivers".equals(dumpPackage)) {
            onlyReceivers = true;
            dumpPackage = null;
            if (opti + 2 <= args.length) {
                for (int i = opti; i < args.length; i++) {
                    String arg = args[i];
                    switch (arg) {
                        case "--uid":
                            filteredUid = getIntArg(pw, args, ++i, Process.INVALID_UID);
                            if (filteredUid == Process.INVALID_UID) {
                                return;
                            }
                            break;
                        default:
                            pw.printf("Invalid argument at index %d: %s\n", i, arg);
                            return;
                    }
                }
            }
        }
        if (DEBUG_BROADCAST) {
            Slogf.d(TAG_BROADCAST, "dumpBroadcastsLocked(): dumpPackage=%s, onlyHistory=%b, "
                            + "onlyReceivers=%b, filteredUid=%d", dumpPackage, onlyHistory,
                    onlyReceivers, filteredUid);
        }

        pw.println("ACTIVITY MANAGER BROADCAST STATE (dumpsys activity broadcasts)");
        if (!onlyHistory && dumpAll) {
            if (mRegisteredReceivers.size() > 0) {
                boolean printed = false;
                Iterator it = mRegisteredReceivers.values().iterator();
                while (it.hasNext()) {
                    ReceiverList r = (ReceiverList) it.next();
                    if (dumpPackage != null && (r.app == null
                            || !dumpPackage.equals(r.app.info.packageName))) {
                        continue;
                    }
                    if (filteredUid != Process.INVALID_UID && filteredUid != r.app.uid) {
                        if (DEBUG_BROADCAST) {
                            Slogf.v(TAG_BROADCAST, "dumpBroadcastsLocked(): skipping receiver whose"
                                    + " uid (%d) is not %d: %s", r.app.uid, filteredUid, r.app);
                        }
                        continue;
                    }
                    if (!printed) {
                        pw.println("  Registered Receivers:");
                        needSep = true;
                        printed = true;
                        printedAnything = true;
                    }
                    pw.print("  * "); pw.println(r);
                    r.dump(pw, "    ");
                }
            } else {
                if (onlyReceivers) {
                    pw.println("  (no registered receivers)");
                }
            }

            if (!onlyReceivers) {
                if (mReceiverResolver.dump(pw, needSep
                                ? "\n  Receiver Resolver Table:" : "  Receiver Resolver Table:",
                        "    ", dumpPackage, false, false)) {
                    needSep = true;
                    printedAnything = true;
                }
            }
        }

        if (!onlyReceivers) {
            needSep = mBroadcastQueue.dumpLocked(fd, pw, args, opti,
                    dumpConstants, dumpHistory, dumpAll, dumpPackage, needSep);
            printedAnything |= needSep;
        }

        needSep = true;

        synchronized (mStickyBroadcasts) {
            if (!onlyHistory && !onlyReceivers && mStickyBroadcasts != null
                    && dumpPackage == null) {
                for (int user = 0; user < mStickyBroadcasts.size(); user++) {
                    if (needSep) {
                        pw.println();
                    }
                    needSep = true;
                    printedAnything = true;
                    pw.print("  Sticky broadcasts for user ");
                    pw.print(mStickyBroadcasts.keyAt(user));
                    pw.println(":");
                    StringBuilder sb = new StringBuilder(128);
                    for (Map.Entry<String, ArrayList<StickyBroadcast>> ent
                            : mStickyBroadcasts.valueAt(user).entrySet()) {
                        pw.print("  * Sticky action ");
                        pw.print(ent.getKey());
                        if (dumpAll) {
                            pw.println(":");
                            ArrayList<StickyBroadcast> broadcasts = ent.getValue();
                            final int N = broadcasts.size();
                            for (int i = 0; i < N; i++) {
                                final Intent intent = broadcasts.get(i).intent;
                                final boolean deferUntilActive = broadcasts.get(i).deferUntilActive;
                                sb.setLength(0);
                                sb.append("    Intent: ");
                                intent.toShortString(sb, false, true, false, false);
                                pw.print(sb);
                                if (deferUntilActive) {
                                    pw.print(" [D]");
                                }
                                pw.println();
                                pw.print("      originalCallingUid: ");
                                pw.println(broadcasts.get(i).originalCallingUid);
                                pw.println();
                                Bundle bundle = intent.getExtras();
                                if (bundle != null) {
                                    pw.print("      extras: ");
                                    pw.println(bundle);
                                }
                            }
                        } else {
                            pw.println("");
                        }
                    }
                }
            }
        }

        if (!onlyHistory && !onlyReceivers && dumpAll) {
            pw.println();
            pw.println("  Queue " + mBroadcastQueue.toString() + ": "
                    + mBroadcastQueue.describeStateLocked());
            pw.println("  mHandler:");
            mService.mHandler.dump(new PrintWriterPrinter(pw), "    ");
            needSep = true;
            printedAnything = true;
        }

        if (!printedAnything) {
            pw.println("  (nothing)");
        }
    }

    /**
     * Gets an {@code int} argument from the given {@code index} on {@code args}, logging an error
     * message on {@code pw} when it cannot be parsed.
     *
     * Returns {@code int} argument or {@code invalidValue} if it could not be parsed.
     */
    private static int getIntArg(PrintWriter pw, String[] args, int index, int invalidValue) {
        if (index > args.length) {
            pw.println("Missing argument");
            return invalidValue;
        }
        String arg = args[index];
        try {
            return Integer.parseInt(arg);
        } catch (Exception e) {
            pw.printf("Non-numeric argument at index %d: %s\n", index, arg);
            return invalidValue;
        }
    }

    @NeverCompile
    void dumpBroadcastStatsLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, String dumpPackage) {
        if (mCurBroadcastStats == null) {
            return;
        }

        pw.println("ACTIVITY MANAGER BROADCAST STATS STATE (dumpsys activity broadcast-stats)");
        final long now = SystemClock.elapsedRealtime();
        if (mLastBroadcastStats != null) {
            pw.print("  Last stats (from ");
            TimeUtils.formatDuration(mLastBroadcastStats.mStartRealtime, now, pw);
            pw.print(" to ");
            TimeUtils.formatDuration(mLastBroadcastStats.mEndRealtime, now, pw);
            pw.print(", ");
            TimeUtils.formatDuration(mLastBroadcastStats.mEndUptime
                    - mLastBroadcastStats.mStartUptime, pw);
            pw.println(" uptime):");
            if (!mLastBroadcastStats.dumpStats(pw, "    ", dumpPackage)) {
                pw.println("    (nothing)");
            }
            pw.println();
        }
        pw.print("  Current stats (from ");
        TimeUtils.formatDuration(mCurBroadcastStats.mStartRealtime, now, pw);
        pw.print(" to now, ");
        TimeUtils.formatDuration(SystemClock.uptimeMillis()
                - mCurBroadcastStats.mStartUptime, pw);
        pw.println(" uptime):");
        if (!mCurBroadcastStats.dumpStats(pw, "    ", dumpPackage)) {
            pw.println("    (nothing)");
        }
    }

    @NeverCompile
    void dumpBroadcastStatsCheckinLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean fullCheckin, String dumpPackage) {
        if (mCurBroadcastStats == null) {
            return;
        }

        if (mLastBroadcastStats != null) {
            mLastBroadcastStats.dumpCheckinStats(pw, dumpPackage);
            if (fullCheckin) {
                mLastBroadcastStats = null;
                return;
            }
        }
        mCurBroadcastStats.dumpCheckinStats(pw, dumpPackage);
        if (fullCheckin) {
            mCurBroadcastStats = null;
        }
    }

    void writeBroadcastsToProtoLocked(ProtoOutputStream proto) {
        if (mRegisteredReceivers.size() > 0) {
            Iterator it = mRegisteredReceivers.values().iterator();
            while (it.hasNext()) {
                ReceiverList r = (ReceiverList) it.next();
                r.dumpDebug(proto, ActivityManagerServiceDumpBroadcastsProto.RECEIVER_LIST);
            }
        }
        mReceiverResolver.dumpDebug(proto,
                ActivityManagerServiceDumpBroadcastsProto.RECEIVER_RESOLVER);
        mBroadcastQueue.dumpDebug(proto, ActivityManagerServiceDumpBroadcastsProto.BROADCAST_QUEUE);
        synchronized (mStickyBroadcasts) {
            for (int user = 0; user < mStickyBroadcasts.size(); user++) {
                long token = proto.start(
                        ActivityManagerServiceDumpBroadcastsProto.STICKY_BROADCASTS);
                proto.write(StickyBroadcastProto.USER, mStickyBroadcasts.keyAt(user));
                for (Map.Entry<String, ArrayList<StickyBroadcast>> ent
                        : mStickyBroadcasts.valueAt(user).entrySet()) {
                    long actionToken = proto.start(StickyBroadcastProto.ACTIONS);
                    proto.write(StickyBroadcastProto.StickyAction.NAME, ent.getKey());
                    for (StickyBroadcast broadcast : ent.getValue()) {
                        broadcast.intent.dumpDebug(proto, StickyBroadcastProto.StickyAction.INTENTS,
                                false, true, true, false);
                    }
                    proto.end(actionToken);
                }
                proto.end(token);
            }
        }

        long handlerToken = proto.start(ActivityManagerServiceDumpBroadcastsProto.HANDLER);
        proto.write(ActivityManagerServiceDumpBroadcastsProto.MainHandler.HANDLER,
                mService.mHandler.toString());
        mService.mHandler.getLooper().dumpDebug(proto,
                ActivityManagerServiceDumpBroadcastsProto.MainHandler.LOOPER);
        proto.end(handlerToken);
    }

    @VisibleForTesting
    static final class StickyBroadcast {
        public Intent intent;
        public boolean deferUntilActive;
        public int originalCallingUid;
        /** The snapshot process state of the app who sent this broadcast */
        public int originalCallingAppProcessState;
        public String resolvedDataType;

        public static StickyBroadcast create(Intent intent, boolean deferUntilActive,
                int originalCallingUid, int originalCallingAppProcessState,
                String resolvedDataType) {
            final StickyBroadcast b = new StickyBroadcast();
            b.intent = intent;
            b.deferUntilActive = deferUntilActive;
            b.originalCallingUid = originalCallingUid;
            b.originalCallingAppProcessState = originalCallingAppProcessState;
            b.resolvedDataType = resolvedDataType;
            return b;
        }

        @Override
        public String toString() {
            return "{intent=" + intent + ", defer=" + deferUntilActive + ", originalCallingUid="
                    + originalCallingUid + ", originalCallingAppProcessState="
                    + originalCallingAppProcessState + ", type=" + resolvedDataType + "}";
        }
    }
}
