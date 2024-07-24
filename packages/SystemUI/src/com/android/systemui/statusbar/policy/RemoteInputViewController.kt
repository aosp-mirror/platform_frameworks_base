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

package com.android.systemui.statusbar.policy

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.ShortcutManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.util.ArraySet
import android.util.Log
import android.view.View
import com.android.internal.logging.UiEventLogger
import com.android.systemui.res.R
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags.NOTIFICATION_INLINE_REPLY_ANIMATION
import com.android.systemui.statusbar.NotificationRemoteInputManager
import com.android.systemui.statusbar.RemoteInputController
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntry.EditedSuggestionInfo
import com.android.systemui.statusbar.policy.RemoteInputView.NotificationRemoteInputEvent
import com.android.systemui.statusbar.policy.RemoteInputView.RevealParams
import com.android.systemui.statusbar.policy.dagger.RemoteInputViewScope
import javax.inject.Inject

interface RemoteInputViewController {
    fun bind()
    fun unbind()

    val isActive: Boolean

    /**
     * A [NotificationRemoteInputManager.BouncerChecker] that will be used to determine if the
     * device needs to be unlocked before sending the RemoteInput.
     */
    var bouncerChecker: NotificationRemoteInputManager.BouncerChecker?

    // TODO(b/193539698): these properties probably shouldn't be nullable
    /** A [PendingIntent] to be used to send the RemoteInput. */
    var pendingIntent: PendingIntent?
    /** The [RemoteInput] data backing this Controller. */
    var remoteInput: RemoteInput?
    /** Other [RemoteInput]s from the notification associated with this Controller. */
    var remoteInputs: Array<RemoteInput>?

    var revealParams: RevealParams?

    val isFocusAnimationFlagActive: Boolean

    /**
     * Sets the smart reply that should be inserted in the remote input, or `null` if the user is
     * not editing a smart reply.
     */
    fun setEditedSuggestionInfo(info: EditedSuggestionInfo?)

    /**
     * Tries to find an action in {@param actions} that matches the current pending intent
     * of this view and updates its state to that of the found action
     *
     * @return true if a matching action was found, false otherwise
     */
    fun updatePendingIntentFromActions(actions: Array<Notification.Action>?): Boolean

    /** Registers a listener for send events. */
    fun addOnSendRemoteInputListener(listener: OnSendRemoteInputListener)

    /** Unregisters a listener previously registered via [addOnSendRemoteInputListener] */
    fun removeOnSendRemoteInputListener(listener: OnSendRemoteInputListener)

    fun close()

    fun focus()

    fun stealFocusFrom(other: RemoteInputViewController) {
        other.close()
        remoteInput = other.remoteInput
        remoteInputs = other.remoteInputs
        revealParams = other.revealParams
        pendingIntent = other.pendingIntent
        focus()
    }
}

/** Listener for send events  */
interface OnSendRemoteInputListener {

    /** Invoked when the remote input has been sent successfully.  */
    fun onSendRemoteInput()

    /**
     * Invoked when the user had requested to send the remote input, but authentication was
     * required and the bouncer was shown instead.
     */
    fun onSendRequestBounced()
}

private const val TAG = "RemoteInput"

@RemoteInputViewScope
class RemoteInputViewControllerImpl @Inject constructor(
    private val view: RemoteInputView,
    private val entry: NotificationEntry,
    private val remoteInputQuickSettingsDisabler: RemoteInputQuickSettingsDisabler,
    private val remoteInputController: RemoteInputController,
    private val shortcutManager: ShortcutManager,
    private val uiEventLogger: UiEventLogger,
    private val mFlags: FeatureFlags
) : RemoteInputViewController {

    private val onSendListeners = ArraySet<OnSendRemoteInputListener>()
    private val resources get() = view.resources

    private var isBound = false

    override var bouncerChecker: NotificationRemoteInputManager.BouncerChecker? = null

    override var remoteInput: RemoteInput? = null
        set(value) {
            field = value
            value?.takeIf { isBound }?.let {
                view.setHintText(it.label)
                view.setSupportedMimeTypes(it.allowedDataTypes)
            }
        }

    override var pendingIntent: PendingIntent? = null
    override var remoteInputs: Array<RemoteInput>? = null

    override var revealParams: RevealParams? = null
        set(value) {
            field = value
            if (isBound) {
                view.setRevealParameters(value)
            }
        }

    override val isActive: Boolean get() = view.isActive

    override val isFocusAnimationFlagActive: Boolean
        get() = mFlags.isEnabled(NOTIFICATION_INLINE_REPLY_ANIMATION)

    override fun bind() {
        if (isBound) return
        isBound = true

        // TODO: refreshUI method?
        remoteInput?.let {
            view.setHintText(it.label)
            view.setSupportedMimeTypes(it.allowedDataTypes)
        }
        view.setRevealParameters(revealParams)
        view.setIsFocusAnimationFlagActive(isFocusAnimationFlagActive)

        view.addOnEditTextFocusChangedListener(onFocusChangeListener)
        view.addOnSendRemoteInputListener(onSendRemoteInputListener)
    }

    override fun unbind() {
        if (!isBound) return
        isBound = false

        view.removeOnEditTextFocusChangedListener(onFocusChangeListener)
        view.removeOnSendRemoteInputListener(onSendRemoteInputListener)
    }

    override fun setEditedSuggestionInfo(info: EditedSuggestionInfo?) {
        entry.editedSuggestionInfo = info
        if (info != null) {
            entry.remoteInputText = info.originalText
            entry.remoteInputAttachment = null
        }
    }

    override fun updatePendingIntentFromActions(actions: Array<Notification.Action>?): Boolean {
        actions ?: return false
        val current: Intent = pendingIntent?.intent ?: return false
        for (a in actions) {
            val actionIntent = a.actionIntent ?: continue
            val inputs = a.remoteInputs ?: continue
            if (!current.filterEquals(actionIntent.intent)) continue
            val input = inputs.firstOrNull { it.allowFreeFormInput } ?: continue
            pendingIntent = actionIntent
            remoteInput = input
            remoteInputs = inputs
            setEditedSuggestionInfo(null)
            return true
        }
        return false
    }

    override fun addOnSendRemoteInputListener(listener: OnSendRemoteInputListener) {
        onSendListeners.add(listener)
    }

    /** Removes a previously-added listener for send events on this RemoteInputView  */
    override fun removeOnSendRemoteInputListener(listener: OnSendRemoteInputListener) {
        onSendListeners.remove(listener)
    }

    override fun close() {
        view.close()
    }

    override fun focus() {
        view.focus()
    }

    private val onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
        remoteInputQuickSettingsDisabler.setRemoteInputActive(hasFocus)
    }

    private val onSendRemoteInputListener = Runnable {
        val remoteInput = remoteInput ?: run {
            Log.e(TAG, "cannot send remote input, RemoteInput data is null")
            return@Runnable
        }
        val pendingIntent = pendingIntent ?: run {
            Log.e(TAG, "cannot send remote input, PendingIntent is null")
            return@Runnable
        }
        val intent = prepareRemoteInput(remoteInput)
        sendRemoteInput(pendingIntent, intent)
    }

    private fun sendRemoteInput(pendingIntent: PendingIntent, intent: Intent) {
        if (bouncerChecker?.showBouncerIfNecessary() == true) {
            view.hideIme()
            for (listener in onSendListeners.toList()) {
                listener.onSendRequestBounced()
            }
            return
        }

        view.startSending()

        entry.lastRemoteInputSent = SystemClock.elapsedRealtime()
        entry.mRemoteEditImeAnimatingAway = true
        remoteInputController.addSpinning(entry.key, view.mToken)
        remoteInputController.removeRemoteInput(entry, view.mToken,
               /* reason= */ "RemoteInputViewController#sendRemoteInput")
        remoteInputController.remoteInputSent(entry)
        entry.setHasSentReply()

        for (listener in onSendListeners.toList()) {
            listener.onSendRemoteInput()
        }

        // Tell ShortcutManager that this package has been "activated". ShortcutManager will reset
        // the throttling for this package.
        // Strictly speaking, the intent receiver may be different from the notification publisher,
        // but that's an edge case, and also because we can't always know which package will receive
        // an intent, so we just reset for the publisher.
        shortcutManager.onApplicationActive(entry.sbn.packageName, entry.sbn.user.identifier)

        uiEventLogger.logWithInstanceId(
                NotificationRemoteInputEvent.NOTIFICATION_REMOTE_INPUT_SEND,
                entry.sbn.uid, entry.sbn.packageName,
                entry.sbn.instanceId)

        try {
            val options = ActivityOptions.makeBasic()
            options.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            pendingIntent.send(view.context, 0, intent, null, null, null, options.toBundle())
        } catch (e: PendingIntent.CanceledException) {
            Log.i(TAG, "Unable to send remote input result", e)
            uiEventLogger.logWithInstanceId(
                    NotificationRemoteInputEvent.NOTIFICATION_REMOTE_INPUT_FAILURE,
                    entry.sbn.uid, entry.sbn.packageName,
                    entry.sbn.instanceId)
        }

        view.clearAttachment()
    }

    /**
     * Reply intent
     * @return returns intent with granted URI permissions that should be used immediately
     */
    private fun prepareRemoteInput(remoteInput: RemoteInput): Intent =
        if (entry.remoteInputAttachment == null)
            prepareRemoteInputFromText(remoteInput)
        else prepareRemoteInputFromData(
                remoteInput,
                entry.remoteInputMimeType,
                entry.remoteInputUri)

    private fun prepareRemoteInputFromText(remoteInput: RemoteInput): Intent {
        val results = Bundle()
        results.putString(remoteInput.resultKey, view.text.toString())
        val fillInIntent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(remoteInputs, fillInIntent, results)
        entry.remoteInputText = view.text
        view.clearAttachment()
        entry.remoteInputUri = null
        entry.remoteInputMimeType = null
        RemoteInput.setResultsSource(fillInIntent, remoteInputResultsSource)
        return fillInIntent
    }

    private fun prepareRemoteInputFromData(
        remoteInput: RemoteInput,
        contentType: String,
        data: Uri
    ): Intent {
        val results = HashMap<String, Uri>()
        results[contentType] = data
        // grant for the target app.
        remoteInputController.grantInlineReplyUriPermission(entry.sbn, data)
        val fillInIntent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addDataResultToIntent(remoteInput, fillInIntent, results)
        val bundle = Bundle()
        bundle.putString(remoteInput.resultKey, view.text.toString())
        RemoteInput.addResultsToIntent(remoteInputs, fillInIntent, bundle)
        val attachmentText: CharSequence = entry.remoteInputAttachment.clip.description.label
        val attachmentLabel =
                if (TextUtils.isEmpty(attachmentText))
                    resources.getString(R.string.remote_input_image_insertion_text)
                else attachmentText
        // add content description to reply text for context
        val fullText =
                if (TextUtils.isEmpty(view.text)) attachmentLabel
                else "\"" + attachmentLabel + "\" " + view.text
        entry.remoteInputText = fullText

        // mirror prepareRemoteInputFromText for text input
        RemoteInput.setResultsSource(fillInIntent, remoteInputResultsSource)
        return fillInIntent
    }

    private val remoteInputResultsSource
        get() = entry.editedSuggestionInfo
                ?.let { RemoteInput.SOURCE_CHOICE }
                ?: RemoteInput.SOURCE_FREE_FORM_INPUT
}
