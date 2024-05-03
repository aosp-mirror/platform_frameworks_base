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
import android.os.UserHandle;
import android.security.Flags;
import android.util.Log;
import android.util.LogPrinter;
import android.util.Printer;
import android.util.Slog;

import com.android.internal.pm.pkg.component.ParsedMainComponent;
import com.android.server.IntentResolver;
import com.android.server.LocalServices;
import com.android.server.am.ActivityManagerUtils;
import com.android.server.am.BroadcastFilter;
import com.android.server.compat.PlatformCompat;
import com.android.server.pm.resolution.ComponentResolverApi;
import com.android.server.pm.snapshot.PackageDataSnapshot;

import java.util.List;

public class SaferIntentUtils {

    // This is a hack to workaround b/240373119; a proper fix should be implemented instead.
    public static final ThreadLocal<Boolean> DISABLE_ENFORCE_INTENTS_TO_MATCH_INTENT_FILTERS =
            ThreadLocal.withInitial(() -> false);

    /**
     * Apps targeting Android U and above will need to export components in order to invoke them
     * through implicit intents.
     *
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
     *
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
     * Under the correct conditions, remove components if the intent has null action.
     *
     * `compat` and `snapshot` may be null when this method is called in ActivityManagerService
     * CTS tests. The code in this method will properly avoid control flows using these arguments.
     */
    public static void blockNullAction(
            @Nullable PlatformCompat compat, @Nullable PackageDataSnapshot snapshot,
            List componentList, boolean isReceiver, Intent intent, int filterCallingUid) {
        if (ActivityManager.canAccessUnexportedComponents(filterCallingUid)) return;

        final Computer computer = (Computer) snapshot;
        ComponentResolverApi resolver = null;

        final boolean enforce = Flags.blockNullActionIntents()
                && (compat == null || compat.isChangeEnabledByUidInternal(
                IntentFilter.BLOCK_NULL_ACTION_INTENTS, filterCallingUid));

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
                        resolveInfo.getComponentInfo(), resolver, isReceiver);
                if (!comp.getIntents().isEmpty() && intent.getAction() == null) {
                    match = false;
                }
            } else if (c instanceof IntentFilter) {
                if (intent.getAction() == null) {
                    match = false;
                }
            }

            if (!match) {
                ActivityManagerUtils.logUnsafeIntentEvent(
                        UNSAFE_INTENT_EVENT_REPORTED__EVENT_TYPE__NULL_ACTION_MATCH,
                        filterCallingUid, intent, null, enforce);
                if (enforce) {
                    Slog.w(TAG, "Blocking intent with null action: " + intent);
                    componentList.remove(i);
                }
            }
        }
    }

    /**
     * Remove ResolveInfos that does not match the provided intent.
     */
    public static void enforceIntentFilterMatching(
            PlatformCompat compat, PackageDataSnapshot snapshot,
            List<ResolveInfo> resolveInfos, boolean isReceiver,
            Intent intent, String resolvedType, int filterCallingUid) {
        if (DISABLE_ENFORCE_INTENTS_TO_MATCH_INTENT_FILTERS.get()) return;

        // Do not enforce filter matching when the caller is system or root
        if (ActivityManager.canAccessUnexportedComponents(filterCallingUid)) return;

        final Computer computer = (Computer) snapshot;
        final ComponentResolverApi resolver = computer.getComponentResolver();

        final Printer logPrinter = DEBUG_INTENT_MATCHING
                ? new LogPrinter(Log.VERBOSE, TAG, Log.LOG_ID_SYSTEM)
                : null;

        final boolean enforceMatch = Flags.enforceIntentFilterMatch()
                && compat.isChangeEnabledByUidInternal(
                ENFORCE_INTENTS_TO_MATCH_INTENT_FILTERS, filterCallingUid);
        final boolean blockNullAction = Flags.blockNullActionIntents()
                && compat.isChangeEnabledByUidInternal(
                IntentFilter.BLOCK_NULL_ACTION_INTENTS, filterCallingUid);

        for (int i = resolveInfos.size() - 1; i >= 0; --i) {
            final ComponentInfo info = resolveInfos.get(i).getComponentInfo();

            // Skip filter matching when the caller is targeting the same app
            if (UserHandle.isSameApp(filterCallingUid, info.applicationInfo.uid)) {
                continue;
            }

            final ParsedMainComponent comp = infoToComponent(info, resolver, isReceiver);

            if (comp == null || comp.getIntents().isEmpty()) {
                continue;
            }

            Boolean match = null;

            if (intent.getAction() == null) {
                ActivityManagerUtils.logUnsafeIntentEvent(
                        UNSAFE_INTENT_EVENT_REPORTED__EVENT_TYPE__NULL_ACTION_MATCH,
                        filterCallingUid, intent, resolvedType, enforceMatch && blockNullAction);
                if (blockNullAction) {
                    // Skip intent filter matching if blocking null action
                    match = false;
                }
            }

            if (match == null) {
                // Check if any intent filter matches
                for (int j = 0, size = comp.getIntents().size(); j < size; ++j) {
                    IntentFilter intentFilter = comp.getIntents().get(j).getIntentFilter();
                    if (IntentResolver.intentMatchesFilter(intentFilter, intent, resolvedType)) {
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
                ActivityManagerUtils.logUnsafeIntentEvent(
                        UNSAFE_INTENT_EVENT_REPORTED__EVENT_TYPE__EXPLICIT_INTENT_FILTER_UNMATCH,
                        filterCallingUid, intent, resolvedType, enforceMatch);
                match = false;
            }

            if (!match) {
                // All non-matching intents has to be marked accordingly
                if (Flags.enforceIntentFilterMatch()) {
                    intent.addExtendedFlags(Intent.EXTENDED_FLAG_FILTER_MISMATCH);
                }
                if (enforceMatch) {
                    Slog.w(TAG, "Intent does not match component's intent filter: " + intent);
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
     */
    public static void filterNonExportedComponents(
            PlatformCompat platformCompat, Intent intent, String resolvedType, List componentList,
            int callingUid, int callingPid) {
        if (componentList == null
                || intent.getPackage() != null
                || intent.getComponent() != null
                || ActivityManager.canAccessUnexportedComponents(callingUid)) {
            return;
        }

        final boolean enforce = platformCompat.isChangeEnabledByUidInternal(
                IMPLICIT_INTENTS_ONLY_MATCH_EXPORTED_COMPONENTS, callingUid);
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
            ActivityManagerUtils.logUnsafeIntentEvent(
                    UNSAFE_INTENT_EVENT_REPORTED__EVENT_TYPE__INTERNAL_NON_EXPORTED_COMPONENT_MATCH,
                    callingUid, intent, resolvedType, enforce);
            LocalServices.getService(ActivityManagerInternal.class)
                    .triggerUnsafeIntentStrictMode(callingPid, intent);
        }
    }
}
