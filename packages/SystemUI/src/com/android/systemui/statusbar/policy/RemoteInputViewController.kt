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
import com.android.systemui.R
import com.android.systemui.statusbar.NotificationRemoteInputManager
import com.android.systemui.statusbar.RemoteInputController
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.policy.RemoteInputView.NotificationRemoteInputEvent
import com.android.systemui.statusbar.policy.dagger.RemoteInputViewScope
import javax.inject.Inject

interface RemoteInputViewController {
    fun bind()
    fun unbind()

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
    private val uiEventLogger: UiEventLogger
) : RemoteInputViewController {

    private val onSendListeners = ArraySet<OnSendRemoteInputListener>()
    private val resources get() = view.resources

    private var isBound = false

    override var pendingIntent: PendingIntent? = null
    override var bouncerChecker: NotificationRemoteInputManager.BouncerChecker? = null
    override var remoteInput: RemoteInput? = null
    override var remoteInputs: Array<RemoteInput>? = null

    override fun bind() {
        if (isBound) return
        isBound = true

        view.addOnEditTextFocusChangedListener(onFocusChangeListener)
        view.addOnSendRemoteInputListener(onSendRemoteInputListener)
    }

    override fun unbind() {
        if (!isBound) return
        isBound = false

        view.removeOnEditTextFocusChangedListener(onFocusChangeListener)
        view.removeOnSendRemoteInputListener(onSendRemoteInputListener)
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
            view.pendingIntent = actionIntent
            view.setRemoteInput(inputs, input, null /* editedSuggestionInfo */)
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
        remoteInputController.removeRemoteInput(entry, view.mToken)
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
            pendingIntent.send(view.context, 0, intent)
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
            if (entry.remoteInputAttachment == null) prepareRemoteInputFromText(remoteInput)
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
        if (entry.editedSuggestionInfo == null) {
            RemoteInput.setResultsSource(fillInIntent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        } else {
            RemoteInput.setResultsSource(fillInIntent, RemoteInput.SOURCE_CHOICE)
        }
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
        if (entry.editedSuggestionInfo == null) {
            RemoteInput.setResultsSource(fillInIntent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        } else if (entry.remoteInputAttachment == null) {
            RemoteInput.setResultsSource(fillInIntent, RemoteInput.SOURCE_CHOICE)
        }
        return fillInIntent
    }
}
