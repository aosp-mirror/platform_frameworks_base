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

package com.android.systemui.statusbar.policy

import android.app.Notification
import android.app.Notification.Action.SEMANTIC_ACTION_MARK_CONVERSATION_AS_PRIORITY
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.widget.Button
import com.android.systemui.R
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.shared.system.ActivityManagerWrapper
import com.android.systemui.shared.system.DevicePolicyManagerWrapper
import com.android.systemui.shared.system.PackageManagerWrapper
import com.android.systemui.statusbar.NotificationRemoteInputManager
import com.android.systemui.statusbar.NotificationUiAdjustment
import com.android.systemui.statusbar.SmartReplyController
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.logging.NotificationLogger
import com.android.systemui.statusbar.phone.KeyguardDismissUtil
import com.android.systemui.statusbar.policy.InflatedSmartReplyState.SuppressedActions
import com.android.systemui.statusbar.policy.SmartReplyView.SmartActions
import com.android.systemui.statusbar.policy.SmartReplyView.SmartButtonType
import com.android.systemui.statusbar.policy.SmartReplyView.SmartReplies
import javax.inject.Inject

/** Returns whether we should show the smart reply view and its smart suggestions. */
fun shouldShowSmartReplyView(
    entry: NotificationEntry,
    smartReplyState: InflatedSmartReplyState
): Boolean {
    if (smartReplyState.smartReplies == null &&
            smartReplyState.smartActions == null) {
        // There are no smart replies and no smart actions.
        return false
    }
    // If we are showing the spinner we don't want to add the buttons.
    val showingSpinner = entry.sbn.notification.extras
            .getBoolean(Notification.EXTRA_SHOW_REMOTE_INPUT_SPINNER, false)
    if (showingSpinner) {
        return false
    }
    // If we are keeping the notification around while sending we don't want to add the buttons.
    return !entry.sbn.notification.extras
            .getBoolean(Notification.EXTRA_HIDE_SMART_REPLIES, false)
}

/** Determines if two [InflatedSmartReplyState] are visually similar. */
fun areSuggestionsSimilar(
    left: InflatedSmartReplyState?,
    right: InflatedSmartReplyState?
): Boolean = when {
    left === right -> true
    left == null || right == null -> false
    left.hasPhishingAction != right.hasPhishingAction -> false
    left.smartRepliesList != right.smartRepliesList -> false
    left.suppressedActionIndices != right.suppressedActionIndices -> false
    else -> !NotificationUiAdjustment.areDifferent(left.smartActionsList, right.smartActionsList)
}

interface SmartReplyStateInflater {
    fun inflateSmartReplyState(entry: NotificationEntry): InflatedSmartReplyState

    fun inflateSmartReplyViewHolder(
        sysuiContext: Context,
        notifPackageContext: Context,
        entry: NotificationEntry,
        existingSmartReplyState: InflatedSmartReplyState?,
        newSmartReplyState: InflatedSmartReplyState
    ): InflatedSmartReplyViewHolder
}

/*internal*/ class SmartReplyStateInflaterImpl @Inject constructor(
    private val constants: SmartReplyConstants,
    private val activityManagerWrapper: ActivityManagerWrapper,
    private val packageManagerWrapper: PackageManagerWrapper,
    private val devicePolicyManagerWrapper: DevicePolicyManagerWrapper,
    private val smartRepliesInflater: SmartReplyInflater,
    private val smartActionsInflater: SmartActionInflater
) : SmartReplyStateInflater {

    override fun inflateSmartReplyState(entry: NotificationEntry): InflatedSmartReplyState =
            chooseSmartRepliesAndActions(entry)

    override fun inflateSmartReplyViewHolder(
        sysuiContext: Context,
        notifPackageContext: Context,
        entry: NotificationEntry,
        existingSmartReplyState: InflatedSmartReplyState?,
        newSmartReplyState: InflatedSmartReplyState
    ): InflatedSmartReplyViewHolder {
        if (!shouldShowSmartReplyView(entry, newSmartReplyState)) {
            return InflatedSmartReplyViewHolder(
                    null /* smartReplyView */,
                    null /* smartSuggestionButtons */)
        }

        // Only block clicks if the smart buttons are different from the previous set - to avoid
        // scenarios where a user incorrectly cannot click smart buttons because the
        // notification is updated.
        val delayOnClickListener =
                !areSuggestionsSimilar(existingSmartReplyState, newSmartReplyState)

        val smartReplyView = SmartReplyView.inflate(sysuiContext, constants)

        val smartReplies = newSmartReplyState.smartReplies
        smartReplyView.setSmartRepliesGeneratedByAssistant(smartReplies?.fromAssistant ?: false)
        val smartReplyButtons = smartReplies?.let {
            smartReplies.choices.asSequence().mapIndexed { index, choice ->
                smartRepliesInflater.inflateReplyButton(
                        smartReplyView,
                        entry,
                        smartReplies,
                        index,
                        choice,
                        delayOnClickListener)
            }
        } ?: emptySequence()

        val smartActionButtons = newSmartReplyState.smartActions?.let { smartActions ->
            val themedPackageContext =
                    ContextThemeWrapper(notifPackageContext, sysuiContext.theme)
            smartActions.actions.asSequence()
                    .filter { it.actionIntent != null }
                    .mapIndexed { index, action ->
                        smartActionsInflater.inflateActionButton(
                                smartReplyView,
                                entry,
                                smartActions,
                                index,
                                action,
                                delayOnClickListener,
                                themedPackageContext)
                    }
        } ?: emptySequence()

        return InflatedSmartReplyViewHolder(
                smartReplyView,
                (smartReplyButtons + smartActionButtons).toList())
    }

    /**
     * Chose what smart replies and smart actions to display. App generated suggestions take
     * precedence. So if the app provides any smart replies, we don't show any
     * replies or actions generated by the NotificationAssistantService (NAS), and if the app
     * provides any smart actions we also don't show any NAS-generated replies or actions.
     */
    fun chooseSmartRepliesAndActions(entry: NotificationEntry): InflatedSmartReplyState {
        val notification = entry.sbn.notification
        val remoteInputActionPair = notification.findRemoteInputActionPair(false /* freeform */)
        val freeformRemoteInputActionPair =
                notification.findRemoteInputActionPair(true /* freeform */)
        if (!constants.isEnabled) {
            if (DEBUG) {
                Log.d(TAG, "Smart suggestions not enabled, not adding suggestions for " +
                        entry.sbn.key)
            }
            return InflatedSmartReplyState(null, null, null, false)
        }
        // Only use smart replies from the app if they target P or above. We have this check because
        // the smart reply API has been used for other things (Wearables) in the past. The API to
        // add smart actions is new in Q so it doesn't require a target-sdk check.
        val enableAppGeneratedSmartReplies = (!constants.requiresTargetingP() ||
                entry.targetSdk >= Build.VERSION_CODES.P)
        val appGeneratedSmartActions = notification.contextualActions

        var smartReplies: SmartReplies? = when {
            enableAppGeneratedSmartReplies -> remoteInputActionPair?.let { pair ->
                pair.second.actionIntent?.let { actionIntent ->
                    if (pair.first.choices?.isNotEmpty() == true)
                        SmartReplies(
                                pair.first.choices.asList(),
                                pair.first,
                                actionIntent,
                                false /* fromAssistant */)
                    else null
                }
            }
            else -> null
        }
        var smartActions: SmartActions? = when {
            appGeneratedSmartActions.isNotEmpty() ->
                SmartActions(appGeneratedSmartActions, false /* fromAssistant */)
            else -> null
        }
        // Apps didn't provide any smart replies / actions, use those from NAS (if any).
        if (smartReplies == null && smartActions == null) {
            val entryReplies = entry.smartReplies
            val entryActions = entry.smartActions
            if (entryReplies.isNotEmpty() &&
                    freeformRemoteInputActionPair != null &&
                    freeformRemoteInputActionPair.second.allowGeneratedReplies &&
                    freeformRemoteInputActionPair.second.actionIntent != null) {
                smartReplies = SmartReplies(
                        entryReplies,
                        freeformRemoteInputActionPair.first,
                        freeformRemoteInputActionPair.second.actionIntent,
                        true /* fromAssistant */)
            }
            if (entryActions.isNotEmpty() &&
                    notification.allowSystemGeneratedContextualActions) {
                val systemGeneratedActions: List<Notification.Action> = when {
                    activityManagerWrapper.isLockTaskKioskModeActive ->
                        // Filter actions if we're in kiosk-mode - we don't care about screen
                        // pinning mode, since notifications aren't shown there anyway.
                        filterAllowlistedLockTaskApps(entryActions)
                    else -> entryActions
                }
                smartActions = SmartActions(systemGeneratedActions, true /* fromAssistant */)
            }
        }
        val hasPhishingAction = smartActions?.actions?.any {
            it.isContextual && it.semanticAction ==
                    Notification.Action.SEMANTIC_ACTION_CONVERSATION_IS_PHISHING
        } ?: false
        var suppressedActions: SuppressedActions? = null
        if (hasPhishingAction) {
            // If there is a phishing action, calculate the indices of the actions with RemoteInput
            //  as those need to be hidden from the view.
            val suppressedActionIndices = notification.actions.mapIndexedNotNull { index, action ->
                if (action.remoteInputs?.isNotEmpty() == true) index else null
            }
            suppressedActions = SuppressedActions(suppressedActionIndices)
        }
        return InflatedSmartReplyState(smartReplies, smartActions, suppressedActions,
                hasPhishingAction)
    }

    /**
     * Filter actions so that only actions pointing to allowlisted apps are permitted.
     * This filtering is only meaningful when in lock-task mode.
     */
    private fun filterAllowlistedLockTaskApps(
        actions: List<Notification.Action>
    ): List<Notification.Action> = actions.filter { action ->
        //  Only allow actions that are explicit (implicit intents are not handled in lock-task
        //  mode), and link to allowlisted apps.
        action.actionIntent?.intent?.let { intent ->
            packageManagerWrapper.resolveActivity(intent, 0 /* flags */)
        }?.let { resolveInfo ->
            devicePolicyManagerWrapper.isLockTaskPermitted(resolveInfo.activityInfo.packageName)
        } ?: false
    }
}

interface SmartActionInflater {
    fun inflateActionButton(
        parent: ViewGroup,
        entry: NotificationEntry,
        smartActions: SmartActions,
        actionIndex: Int,
        action: Notification.Action,
        delayOnClickListener: Boolean,
        packageContext: Context
    ): Button
}

/* internal */ class SmartActionInflaterImpl @Inject constructor(
    private val constants: SmartReplyConstants,
    private val activityStarter: ActivityStarter,
    private val smartReplyController: SmartReplyController,
    private val headsUpManager: HeadsUpManager
) : SmartActionInflater {

    override fun inflateActionButton(
        parent: ViewGroup,
        entry: NotificationEntry,
        smartActions: SmartActions,
        actionIndex: Int,
        action: Notification.Action,
        delayOnClickListener: Boolean,
        packageContext: Context
    ): Button =
            (LayoutInflater.from(parent.context)
                    .inflate(R.layout.smart_action_button, parent, false) as Button
            ).apply {
                text = action.title

                // We received the Icon from the application - so use the Context of the application to
                // reference icon resources.
                val iconDrawable = action.getIcon().loadDrawable(packageContext)
                        .apply {
                            val newIconSize: Int = context.resources.getDimensionPixelSize(
                                    R.dimen.smart_action_button_icon_size)
                            setBounds(0, 0, newIconSize, newIconSize)
                        }
                // Add the action icon to the Smart Action button.
                setCompoundDrawablesRelative(iconDrawable, null, null, null)

                val onClickListener = View.OnClickListener {
                    onSmartActionClick(entry, smartActions, actionIndex, action)
                }
                setOnClickListener(
                        if (delayOnClickListener)
                            DelayedOnClickListener(onClickListener, constants.onClickInitDelay)
                        else onClickListener)

                // Mark this as an Action button
                (layoutParams as SmartReplyView.LayoutParams).mButtonType = SmartButtonType.ACTION
            }

    private fun onSmartActionClick(
        entry: NotificationEntry,
        smartActions: SmartActions,
        actionIndex: Int,
        action: Notification.Action
    ) =
        if (smartActions.fromAssistant &&
            SEMANTIC_ACTION_MARK_CONVERSATION_AS_PRIORITY == action.semanticAction) {
            entry.row.doSmartActionClick(entry.row.x.toInt() / 2,
                entry.row.y.toInt() / 2, SEMANTIC_ACTION_MARK_CONVERSATION_AS_PRIORITY)
            smartReplyController
                .smartActionClicked(entry, actionIndex, action, smartActions.fromAssistant)
        } else {
            activityStarter.startPendingIntentDismissingKeyguard(action.actionIntent, entry.row) {
                smartReplyController
                    .smartActionClicked(entry, actionIndex, action, smartActions.fromAssistant)
            }
        }
}

interface SmartReplyInflater {
    fun inflateReplyButton(
        parent: SmartReplyView,
        entry: NotificationEntry,
        smartReplies: SmartReplies,
        replyIndex: Int,
        choice: CharSequence,
        delayOnClickListener: Boolean
    ): Button
}

class SmartReplyInflaterImpl @Inject constructor(
    private val constants: SmartReplyConstants,
    private val keyguardDismissUtil: KeyguardDismissUtil,
    private val remoteInputManager: NotificationRemoteInputManager,
    private val smartReplyController: SmartReplyController,
    private val context: Context
) : SmartReplyInflater {

    override fun inflateReplyButton(
        parent: SmartReplyView,
        entry: NotificationEntry,
        smartReplies: SmartReplies,
        replyIndex: Int,
        choice: CharSequence,
        delayOnClickListener: Boolean
    ): Button =
            (LayoutInflater.from(parent.context)
                    .inflate(R.layout.smart_reply_button, parent, false) as Button
            ).apply {
                text = choice
                val onClickListener = View.OnClickListener {
                    onSmartReplyClick(
                            entry,
                            smartReplies,
                            replyIndex,
                            parent,
                            this,
                            choice)
                }
                setOnClickListener(
                        if (delayOnClickListener)
                            DelayedOnClickListener(onClickListener, constants.onClickInitDelay)
                        else onClickListener)
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: View,
                        info: AccessibilityNodeInfo
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        val label = parent.resources
                                .getString(R.string.accessibility_send_smart_reply)
                        val action = AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, label)
                        info.addAction(action)
                    }
                }
                // TODO: probably shouldn't do this here, bad API
                // Mark this as a Reply button
                (layoutParams as SmartReplyView.LayoutParams).mButtonType = SmartButtonType.REPLY
            }

    private fun onSmartReplyClick(
        entry: NotificationEntry,
        smartReplies: SmartReplies,
        replyIndex: Int,
        smartReplyView: SmartReplyView,
        button: Button,
        choice: CharSequence
    ) = keyguardDismissUtil.executeWhenUnlocked(!entry.isRowPinned) {
        val canEditBeforeSend = constants.getEffectiveEditChoicesBeforeSending(
                smartReplies.remoteInput.editChoicesBeforeSending)
        if (canEditBeforeSend) {
            remoteInputManager.activateRemoteInput(
                    button,
                    arrayOf(smartReplies.remoteInput),
                    smartReplies.remoteInput,
                    smartReplies.pendingIntent,
                    NotificationEntry.EditedSuggestionInfo(choice, replyIndex))
        } else {
            smartReplyController.smartReplySent(
                    entry,
                    replyIndex,
                    button.text,
                    NotificationLogger.getNotificationLocation(entry).toMetricsEventEnum(),
                    false /* modifiedBeforeSending */)
            entry.setHasSentReply()
            try {
                val intent = createRemoteInputIntent(smartReplies, choice)
                smartReplies.pendingIntent.send(context, 0, intent)
            } catch (e: PendingIntent.CanceledException) {
                Log.w(TAG, "Unable to send smart reply", e)
            }
            smartReplyView.hideSmartSuggestions()
        }
        false // do not defer
    }

    private fun createRemoteInputIntent(smartReplies: SmartReplies, choice: CharSequence): Intent {
        val results = Bundle()
        results.putString(smartReplies.remoteInput.resultKey, choice.toString())
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(arrayOf(smartReplies.remoteInput), intent, results)
        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_CHOICE)
        return intent
    }
}

/**
 * An OnClickListener wrapper that blocks the underlying OnClickListener for a given amount of
 * time.
 */
private class DelayedOnClickListener(
    private val mActualListener: View.OnClickListener,
    private val mInitDelayMs: Long
) : View.OnClickListener {

    private val mInitTimeMs = SystemClock.elapsedRealtime()

    override fun onClick(v: View) {
        if (hasFinishedInitialization()) {
            mActualListener.onClick(v)
        } else {
            Log.i(TAG, "Accidental Smart Suggestion click registered, delay: $mInitDelayMs")
        }
    }

    private fun hasFinishedInitialization(): Boolean =
            SystemClock.elapsedRealtime() >= mInitTimeMs + mInitDelayMs
}

private const val TAG = "SmartReplyViewInflater"
private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

// convenience function that swaps parameter order so that lambda can be placed at the end
private fun KeyguardDismissUtil.executeWhenUnlocked(
    requiresShadeOpen: Boolean,
    onDismissAction: () -> Boolean
) = executeWhenUnlocked(onDismissAction, requiresShadeOpen, false)

// convenience function that swaps parameter order so that lambda can be placed at the end
private fun ActivityStarter.startPendingIntentDismissingKeyguard(
    intent: PendingIntent,
    associatedView: View?,
    runnable: () -> Unit
) = startPendingIntentDismissingKeyguard(intent, runnable::invoke, associatedView)