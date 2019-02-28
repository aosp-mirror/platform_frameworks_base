/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.util.Log;
import android.util.Pair;
import android.widget.Button;

import com.android.internal.util.ArrayUtils;
import com.android.systemui.Dependency;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.DevicePolicyManagerWrapper;
import com.android.systemui.shared.system.PackageManagerWrapper;
import com.android.systemui.statusbar.SmartReplyController;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Holder for inflated smart replies and actions. These objects should be inflated on a background
 * thread, to later be accessed and modified on the (performance critical) UI thread.
 */
public class InflatedSmartReplies {
    private static final String TAG = "InflatedSmartReplies";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    @Nullable private final SmartReplyView mSmartReplyView;
    @Nullable private final List<Button> mSmartSuggestionButtons;
    @NonNull private final SmartRepliesAndActions mSmartRepliesAndActions;

    private InflatedSmartReplies(
            @Nullable SmartReplyView smartReplyView,
            @Nullable List<Button> smartSuggestionButtons,
            @NonNull SmartRepliesAndActions smartRepliesAndActions) {
        mSmartReplyView = smartReplyView;
        mSmartSuggestionButtons = smartSuggestionButtons;
        mSmartRepliesAndActions = smartRepliesAndActions;
    }

    @Nullable public SmartReplyView getSmartReplyView() {
        return mSmartReplyView;
    }

    @Nullable public List<Button> getSmartSuggestionButtons() {
        return mSmartSuggestionButtons;
    }

    @NonNull public SmartRepliesAndActions getSmartRepliesAndActions() {
        return mSmartRepliesAndActions;
    }

    /**
     * Inflate a SmartReplyView and its smart suggestions.
     */
    public static InflatedSmartReplies inflate(
            Context context,
            NotificationEntry entry,
            SmartReplyConstants smartReplyConstants,
            SmartReplyController smartReplyController,
            HeadsUpManager headsUpManager) {
        SmartRepliesAndActions smartRepliesAndActions =
                chooseSmartRepliesAndActions(smartReplyConstants, entry);
        if (!shouldShowSmartReplyView(entry, smartRepliesAndActions)) {
            return new InflatedSmartReplies(null /* smartReplyView */,
                    null /* smartSuggestionButtons */, smartRepliesAndActions);
        }

        SmartReplyView smartReplyView = SmartReplyView.inflate(context);

        List<Button> suggestionButtons = new ArrayList<>();
        if (smartRepliesAndActions.smartReplies != null) {
            suggestionButtons.addAll(smartReplyView.inflateRepliesFromRemoteInput(
                    smartRepliesAndActions.smartReplies, smartReplyController, entry));
        }
        if (smartRepliesAndActions.smartActions != null) {
            suggestionButtons.addAll(
                    smartReplyView.inflateSmartActions(smartRepliesAndActions.smartActions,
                            smartReplyController, entry, headsUpManager));
        }

        return new InflatedSmartReplies(smartReplyView, suggestionButtons,
                smartRepliesAndActions);
    }

    /**
     * Returns whether we should show the smart reply view and its smart suggestions.
     */
    public static boolean shouldShowSmartReplyView(
            NotificationEntry entry,
            SmartRepliesAndActions smartRepliesAndActions) {
        if (smartRepliesAndActions.smartReplies == null
                && smartRepliesAndActions.smartActions == null) {
            // There are no smart replies and no smart actions.
            return false;
        }
        // If we are showing the spinner we don't want to add the buttons.
        boolean showingSpinner = entry.notification.getNotification()
                .extras.getBoolean(Notification.EXTRA_SHOW_REMOTE_INPUT_SPINNER, false);
        if (showingSpinner) {
            return false;
        }
        // If we are keeping the notification around while sending we don't want to add the buttons.
        boolean hideSmartReplies = entry.notification.getNotification()
                .extras.getBoolean(Notification.EXTRA_HIDE_SMART_REPLIES, false);
        if (hideSmartReplies) {
            return false;
        }
        return true;
    }

    /**
     * Chose what smart replies and smart actions to display. App generated suggestions take
     * precedence. So if the app provides any smart replies, we don't show any
     * replies or actions generated by the NotificationAssistantService (NAS), and if the app
     * provides any smart actions we also don't show any NAS-generated replies or actions.
     */
    @NonNull
    public static SmartRepliesAndActions chooseSmartRepliesAndActions(
            SmartReplyConstants smartReplyConstants,
            final NotificationEntry entry) {
        Notification notification = entry.notification.getNotification();
        Pair<RemoteInput, Notification.Action> remoteInputActionPair =
                notification.findRemoteInputActionPair(false /* freeform */);
        Pair<RemoteInput, Notification.Action> freeformRemoteInputActionPair =
                notification.findRemoteInputActionPair(true /* freeform */);

        if (!smartReplyConstants.isEnabled()) {
            if (DEBUG) {
                Log.d(TAG, "Smart suggestions not enabled, not adding suggestions for "
                        + entry.notification.getKey());
            }
            return new SmartRepliesAndActions(null, null);
        }
        // Only use smart replies from the app if they target P or above. We have this check because
        // the smart reply API has been used for other things (Wearables) in the past. The API to
        // add smart actions is new in Q so it doesn't require a target-sdk check.
        boolean enableAppGeneratedSmartReplies = (!smartReplyConstants.requiresTargetingP()
                || entry.targetSdk >= Build.VERSION_CODES.P);

        boolean appGeneratedSmartRepliesExist =
                enableAppGeneratedSmartReplies
                        && remoteInputActionPair != null
                        && !ArrayUtils.isEmpty(remoteInputActionPair.first.getChoices())
                        && remoteInputActionPair.second.actionIntent != null;

        List<Notification.Action> appGeneratedSmartActions = notification.getContextualActions();
        boolean appGeneratedSmartActionsExist = !appGeneratedSmartActions.isEmpty();

        SmartReplyView.SmartReplies smartReplies = null;
        SmartReplyView.SmartActions smartActions = null;
        if (appGeneratedSmartRepliesExist) {
            smartReplies = new SmartReplyView.SmartReplies(
                    remoteInputActionPair.first.getChoices(),
                    remoteInputActionPair.first,
                    remoteInputActionPair.second.actionIntent,
                    false /* fromAssistant */);
        }
        if (appGeneratedSmartActionsExist) {
            smartActions = new SmartReplyView.SmartActions(appGeneratedSmartActions,
                    false /* fromAssistant */);
        }
        // Apps didn't provide any smart replies / actions, use those from NAS (if any).
        if (!appGeneratedSmartRepliesExist && !appGeneratedSmartActionsExist) {
            boolean useGeneratedReplies = !ArrayUtils.isEmpty(entry.systemGeneratedSmartReplies)
                    && freeformRemoteInputActionPair != null
                    && freeformRemoteInputActionPair.second.getAllowGeneratedReplies()
                    && freeformRemoteInputActionPair.second.actionIntent != null;
            if (useGeneratedReplies) {
                smartReplies = new SmartReplyView.SmartReplies(
                        entry.systemGeneratedSmartReplies,
                        freeformRemoteInputActionPair.first,
                        freeformRemoteInputActionPair.second.actionIntent,
                        true /* fromAssistant */);
            }
            boolean useSmartActions = !ArrayUtils.isEmpty(entry.systemGeneratedSmartActions)
                    && notification.getAllowSystemGeneratedContextualActions();
            if (useSmartActions) {
                List<Notification.Action> systemGeneratedActions =
                        entry.systemGeneratedSmartActions;
                // Filter actions if we're in kiosk-mode - we don't care about screen pinning mode,
                // since notifications aren't shown there anyway.
                ActivityManagerWrapper activityManagerWrapper =
                        Dependency.get(ActivityManagerWrapper.class);
                if (activityManagerWrapper.isLockTaskKioskModeActive()) {
                    systemGeneratedActions = filterWhiteListedLockTaskApps(systemGeneratedActions);
                }
                smartActions = new SmartReplyView.SmartActions(
                        systemGeneratedActions, true /* fromAssistant */);
            }
        }
        return new SmartRepliesAndActions(smartReplies, smartActions);
    }

    /**
     * Filter actions so that only actions pointing to whitelisted apps are allowed.
     * This filtering is only meaningful when in lock-task mode.
     */
    private static List<Notification.Action> filterWhiteListedLockTaskApps(
            List<Notification.Action> actions) {
        PackageManagerWrapper packageManagerWrapper = Dependency.get(PackageManagerWrapper.class);
        DevicePolicyManagerWrapper devicePolicyManagerWrapper =
                Dependency.get(DevicePolicyManagerWrapper.class);
        List<Notification.Action> filteredActions = new ArrayList<>();
        for (Notification.Action action : actions) {
            if (action.actionIntent == null) continue;
            Intent intent = action.actionIntent.getIntent();
            //  Only allow actions that are explicit (implicit intents are not handled in lock-task
            //  mode), and link to whitelisted apps.
            ResolveInfo resolveInfo = packageManagerWrapper.resolveActivity(intent, 0 /* flags */);
            if (resolveInfo != null && devicePolicyManagerWrapper.isLockTaskPermitted(
                    resolveInfo.activityInfo.packageName)) {
                filteredActions.add(action);
            }
        }
        return filteredActions;
    }

    /**
     * Returns whether the {@link Notification} represented by entry has a free-form remote input.
     * Such an input can be used e.g. to implement smart reply buttons - by passing the replies
     * through the remote input.
     */
    public static boolean hasFreeformRemoteInput(NotificationEntry entry) {
        Notification notification = entry.notification.getNotification();
        return null != notification.findRemoteInputActionPair(true /* freeform */);
    }

    /**
     * A storage for smart replies and smart action.
     */
    public static class SmartRepliesAndActions {
        @Nullable public final SmartReplyView.SmartReplies smartReplies;
        @Nullable public final SmartReplyView.SmartActions smartActions;

        SmartRepliesAndActions(
                @Nullable SmartReplyView.SmartReplies smartReplies,
                @Nullable SmartReplyView.SmartActions smartActions) {
            this.smartReplies = smartReplies;
            this.smartActions = smartActions;
        }
    }
}
