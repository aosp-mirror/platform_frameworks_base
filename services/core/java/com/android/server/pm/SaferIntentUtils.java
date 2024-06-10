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

package com.android.server.pm;

import static com.android.internal.util.FrameworkStatsLog.UNSAFE_INTENT_EVENT_REPORTED__EVENT_TYPE__EXPLICIT_INTENT_FILTER_UNMATCH;
import static com.android.internal.util.FrameworkStatsLog.UNSAFE_INTENT_EVENT_REPORTED__EVENT_TYPE__INTERNAL_NON_EXPORTED_COMPONENT_MATCH;
import static com.android.internal.util.FrameworkStatsLog.UNSAFE_INTENT_EVENT_REPORTED__EVENT_TYPE__NULL_ACTION_MATCH;
import static com.android.server.pm.PackageManagerService.DEBUG_INTENT_MATCHING;
import static com.android.server.pm.PackageManagerService.TAG;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.compat.annotation.ChangeId;
import android.compat.annotation.Disabled;
import android.compat.annotation.EnabledAfter;
import android.compat.annotation.Overridable;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.security.Flags;
import android.util.Log;
import android.util.LogPrinter;
import android.util.Printer;
import android.util.Slog;

import com.android.internal.pm.pkg.component.ParsedMainComponent;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.IntentResolver;
import com.android.server.LocalServices;
import com.android.server.am.BroadcastFilter;
import com.android.server.compat.PlatformCompat;
import com.android.server.pm.resolution.ComponentResolverApi;
import com.android.server.pm.snapshot.PackageDataSnapshot;

import java.util.List;

/**
 * The way Safer Intent is implemented is to add several "hooks" into PMS's intent
 * resolution process, and in some cases, AMS's runtime receiver resolution. Think of
 * these methods as resolution "passes", where they post-process the resolved component list.
 * <p>
 * Here are the 4 main hooking entry points for each component type:
 * <ul>
 *     <li>Activity: {@link ComputerEngine#queryIntentActivitiesInternal} or
 *     {@link ResolveIntentHelper#resolveIntentInternal}</li>
 *     <li>Service: {@link Computer#queryIntentServicesInternal}</li>
 *     <li>Static BroadcastReceivers: {@link ResolveIntentHelper#queryIntentReceiversInternal}</li>
 *     <li>Runtime BroadcastReceivers:
 *     {@link com.android.server.am.ActivityManagerService#broadcastIntentLockedTraced}</li>
 * </ul>
 */
public class SaferIntentUtils {

    // This is a hack to workaround b/240373119; a proper fix should be implemented instead.
    public static final ThreadLocal<Boolean> DISABLE_ENFORCE_INTENTS_TO_MATCH_INTENT_FILTERS =
            ThreadLocal.withInitial(() -> false);

    /**
     * Apps targeting Android U and above will need to export components in order to invoke them
     * through implicit intents.
     * <p>
     * If a component is not exported and invoked, it will be removed from the list of receivers.
     * This applies specifically to activities and broadcasts.
     */
    @ChangeId
    @Overridable
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    private static final long IMPLICIT_INTENTS_ONLY_MATCH_EXPORTED_COMPONENTS = 229362273;

    /**
     * Intents sent from apps enabling this feature will stop resolving to components with
     * non matching intent filters, even when explicitly setting a component name, unless the
     * target components are in the same app as the calling app.
     * <p>
     * When an app registers an exported component in its manifest and adds &lt;intent-filter&gt;s,
     * the component can be started by any intent - even those that do not match the intent filter.
     * This has proven to be something that many developers find counterintuitive.
     * Without checking the intent when the component is started, in some circumstances this can
     * allow 3P apps to trigger internal-only functionality.
     */
    @ChangeId
    @Overridable
    @Disabled
    private static final long ENFORCE_INTENTS_TO_MATCH_INTENT_FILTERS = 161252188;

    private static ParsedMainComponent infoToComponent(
            ComponentInfo info, ComponentResolverApi resolver, boolean isReceiver) {
        if (info instanceof ActivityInfo) {
            if (isReceiver) {
                return resolver.getReceiver(info.getComponentName());
            } else {
                return resolver.getActivity(info.getComponentName());
            }
        } else if (info instanceof ServiceInfo) {
            return resolver.getService(info.getComponentName());
        } else {
            // This shall never happen
            throw new IllegalArgumentException("Unsupported component type");
        }
    }

    /**
     * Helper method to report an unsafe intent event.
     */
    public static void reportUnsafeIntentEvent(
            int event, int callingUid, int callingPid,
            Intent intent, String resolvedType, boolean blocked) {
        String[] categories = intent.getCategories() == null ? new String[0]
                : intent.getCategories().toArray(String[]::new);
        String component = intent.getComponent() == null ? null
                : intent.getComponent().flattenToString();
        FrameworkStatsLog.write(FrameworkStatsLog.UNSAFE_INTENT_EVENT_REPORTED,
                event,
                callingUid,
                component,
                intent.getPackage(),
                intent.getAction(),
                categories,
                resolvedType,
                intent.getScheme(),
                blocked);
        LocalServices.getService(ActivityManagerInternal.class)
                .triggerUnsafeIntentStrictMode(callingPid, event, intent);
    }

    /**
     * All the relevant information about an intent resolution transaction.
     */
    public static class IntentArgs {

        /* Several system_server components */

        @Nullable
        public PlatformCompat platformCompat;
        @Nullable
        public PackageDataSnapshot snapshot;

        /* Information about the intent itself */

        public Intent intent;
        public String resolvedType;
        public boolean isReceiver;

        /* Information about the caller */

        // Whether this intent resolution transaction is actually for starting a component and
        // not only for querying matching components.
        // This information is required because we only want to log and trigger strict mode
        // violations on unsafe intent events when the caller actually wants to start something.
        public boolean resolveForStart;
        public int callingUid;
        // When resolveForStart is false, callingPid does not matter as this is only used
        // to lookup the strict mode violation callback.
        public int callingPid;

        public IntentArgs(
                Intent intent, String resolvedType, boolean isReceiver,
                boolean resolveForStart, int callingUid, int callingPid) {
            this.isReceiver = isReceiver;
            this.intent = intent;
            this.resolvedType = resolvedType;
            this.resolveForStart = resolveForStart;
            this.callingUid = callingUid;
            this.callingPid = resolveForStart ? callingPid : Process.INVALID_PID;
        }

        boolean isChangeEnabled(long changeId) {
            return platformCompat == null || platformCompat.isChangeEnabledByUidInternal(
                    changeId, callingUid);
        }

        void reportEvent(int event, boolean blocked) {
            if (resolveForStart) {
                SaferIntentUtils.reportUnsafeIntentEvent(
                        event, callingUid, callingPid, intent, resolvedType, blocked);
            }
        }
    }

    /**
     * Remove components if the intent has null action.
     * <p>
     * Because blocking null action applies to all resolution cases, it has to be hooked
     * in all 4 locations. Note, for component intent resolution in Activity, Service,
     * and static BroadcastReceivers, null action blocking is actually handled within
     * {@link #enforceIntentFilterMatching}; we only need to handle it in this method when
     * the intent does not specify an explicit component name.
     * <p>
     * `compat` and `snapshot` may be null when this method is called in ActivityManagerService
     * CTS tests. The code in this method shall properly avoid control flows using these arguments.
     */
    public static void blockNullAction(IntentArgs args, List componentList) {
        if (ActivityManager.canAccessUnexportedComponents(args.callingUid)) return;

        final Computer computer = (Computer) args.snapshot;
        ComponentResolverApi resolver = null;

        final boolean enforce = Flags.blockNullActionIntents()
                && args.isChangeEnabled(IntentFilter.BLOCK_NULL_ACTION_INTENTS);

        for (int i = componentList.size() - 1; i >= 0; --i) {
            boolean match = true;

            Object c = componentList.get(i);
            if (c instanceof ResolveInfo resolveInfo) {
                if (computer == null) {
                    // PackageManagerService is not started
                    return;
                }
                if (resolver == null) {
                    resolver = computer.getComponentResolver();
                }
                final ParsedMainComponent comp = infoToComponent(
                        resolveInfo.getComponentInfo(), resolver, args.isReceiver);
                if (!comp.getIntents().isEmpty() && args.intent.getAction() == null) {
                    match = false;
                }
            } else if (c instanceof IntentFilter) {
                if (args.intent.getAction() == null) {
                    match = false;
                }
            }

            if (!match) {
                args.reportEvent(
                        UNSAFE_INTENT_EVENT_REPORTED__EVENT_TYPE__NULL_ACTION_MATCH, enforce);
                if (enforce) {
                    Slog.w(TAG, "Blocking intent with null action: " + args.intent);
                    componentList.remove(i);
                }
            }
        }
    }

    /**
     * Remove ResolveInfos that does not match the provided component intent.
     * <p>
     * Component intents cannot refer to a runtime registered BroadcastReceiver, so we only
     * need to hook into the rest of the 3 entry points. Please note, this method also
     * handles null action blocking for all component intents; do not go through an additional
     * {@link #blockNullAction} pass!
     */
    public static void enforceIntentFilterMatching(
            IntentArgs args, List<ResolveInfo> resolveInfos) {
        if (DISABLE_ENFORCE_INTENTS_TO_MATCH_INTENT_FILTERS.get()) return;

        // Do not enforce filter matching when the caller is system or root
        if (ActivityManager.canAccessUnexportedComponents(args.callingUid)) return;

        final Computer computer = (Computer) args.snapshot;
        final ComponentResolverApi resolver = computer.getComponentResolver();

        final Printer logPrinter = DEBUG_INTENT_MATCHING
                ? new LogPrinter(Log.VERBOSE, TAG, Log.LOG_ID_SYSTEM)
                : null;

        final boolean enforceMatch = Flags.enforceIntentFilterMatch()
                && args.isChangeEnabled(ENFORCE_INTENTS_TO_MATCH_INTENT_FILTERS);
        final boolean blockNullAction = Flags.blockNullActionIntents()
                && args.isChangeEnabled(IntentFilter.BLOCK_NULL_ACTION_INTENTS);

        for (int i = resolveInfos.size() - 1; i >= 0; --i) {
            final ComponentInfo info = resolveInfos.get(i).getComponentInfo();

            // Skip filter matching when the caller is targeting the same app
            if (UserHandle.isSameApp(args.callingUid, info.applicationInfo.uid)) {
                continue;
            }

            final ParsedMainComponent comp = infoToComponent(info, resolver, args.isReceiver);

            if (comp == null || comp.getIntents().isEmpty()) {
                continue;
            }

            Boolean match = null;

            if (args.intent.getAction() == null) {
                args.reportEvent(
                        UNSAFE_INTENT_EVENT_REPORTED__EVENT_TYPE__NULL_ACTION_MATCH,
                        enforceMatch && blockNullAction);
                if (blockNullAction) {
                    // Skip intent filter matching if blocking null action
                    match = false;
                }
            }

            if (match == null) {
                // Check if any intent filter matches
                for (int j = 0, size = comp.getIntents().size(); j < size; ++j) {
                    IntentFilter intentFilter = comp.getIntents().get(j).getIntentFilter();
                    if (IntentResolver.intentMatchesFilter(
                            intentFilter, args.intent, args.resolvedType)) {
                        match = true;
                        break;
                    }
                }
            }

            // At this point, the value `match` has the following states:
            // null : Intent does not match any intent filter
            // false: Null action intent detected AND blockNullAction == true
            // true : The intent matches at least one intent filter

            if (match == null) {
                args.reportEvent(
                        UNSAFE_INTENT_EVENT_REPORTED__EVENT_TYPE__EXPLICIT_INTENT_FILTER_UNMATCH,
                        enforceMatch);
                match = false;
            }

            if (!match) {
                // All non-matching intents has to be marked accordingly
                if (Flags.enforceIntentFilterMatch()) {
                    args.intent.addExtendedFlags(Intent.EXTENDED_FLAG_FILTER_MISMATCH);
                }
                if (enforceMatch) {
                    Slog.w(TAG, "Intent does not match component's intent filter: " + args.intent);
                    Slog.w(TAG, "Access blocked: " + comp.getComponentName());
                    if (DEBUG_INTENT_MATCHING) {
                        Slog.v(TAG, "Component intent filters:");
                        comp.getIntents().forEach(f -> f.getIntentFilter().dump(logPrinter, "  "));
                        Slog.v(TAG, "-----------------------------");
                    }
                    resolveInfos.remove(i);
                }
            }
        }
    }

    /**
     * Filter non-exported components from the componentList if intent is implicit.
     * <p>
     * Implicit intents cannot be used to start Services since API 21+.
     * Implicit broadcasts cannot be delivered to static BroadcastReceivers since API 25+.
     * So we only need to hook into Activity and runtime BroadcastReceiver intent resolution.
     */
    public static void filterNonExportedComponents(IntentArgs args, List componentList) {
        if (componentList == null
                || args.intent.getPackage() != null
                || args.intent.getComponent() != null
                || ActivityManager.canAccessUnexportedComponents(args.callingUid)) {
            return;
        }

        final boolean enforce =
                args.isChangeEnabled(IMPLICIT_INTENTS_ONLY_MATCH_EXPORTED_COMPONENTS);
        boolean violated = false;

        for (int i = componentList.size() - 1; i >= 0; i--) {
            Object c = componentList.get(i);
            if (c instanceof ResolveInfo resolveInfo) {
                if (resolveInfo.getComponentInfo().exported) {
                    continue;
                }
            } else if (c instanceof BroadcastFilter broadcastFilter) {
                if (broadcastFilter.exported) {
                    continue;
                }
            } else {
                continue;
            }
            violated = true;
            if (!enforce) {
                break;
            }
            componentList.remove(i);
        }

        if (violated) {
            args.reportEvent(
                    UNSAFE_INTENT_EVENT_REPORTED__EVENT_TYPE__INTERNAL_NON_EXPORTED_COMPONENT_MATCH,
                    enforce);
        }
    }
}
