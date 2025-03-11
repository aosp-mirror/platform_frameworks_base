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

package com.android.systemui.recordissue

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import androidx.annotation.WorkerThread
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.recordissue.IssueRecordingState.Companion.TAG_TITLE_DELIMITER
import com.android.systemui.settings.UserContextProvider
import com.android.traceur.FileSender
import com.android.traceur.MessageConstants
import com.android.traceur.MessageConstants.TRACING_APP_ACTIVITY
import com.android.traceur.MessageConstants.TRACING_APP_PACKAGE_NAME
import com.android.traceur.TraceConfig
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject

private const val TAG = "TraceurConnection"

class TraceurConnection
private constructor(userContextProvider: UserContextProvider, private val bgLooper: Looper) :
    UserAwareConnection(
        userContextProvider,
        Intent().setClassName(TRACING_APP_PACKAGE_NAME, TRACING_APP_ACTIVITY),
    ) {

    @SysUISingleton
    class Provider
    @Inject
    constructor(
        private val userContextProvider: UserContextProvider,
        @Background private val bgLooper: Looper,
    ) {
        fun create() = TraceurConnection(userContextProvider, bgLooper)
    }

    val onBound: MutableList<Runnable> = CopyOnWriteArrayList(mutableListOf())

    override fun onServiceConnected(className: ComponentName, service: IBinder) {
        super.onServiceConnected(className, service)
        onBound.forEach(Runnable::run)
        onBound.clear()
    }

    @WorkerThread
    fun startTracing(traceType: TraceConfig) {
        val data =
            Bundle().apply { putParcelable(MessageConstants.INTENT_EXTRA_TRACE_TYPE, traceType) }
        sendMessage(MessageConstants.START_WHAT, data)
    }

    @WorkerThread fun stopTracing() = sendMessage(MessageConstants.STOP_WHAT)

    @WorkerThread
    fun shareTraces(screenRecordingUris: List<Uri>) {
        val replyHandler =
            Messenger(ShareFilesHandler(screenRecordingUris, userContextProvider, bgLooper))
        sendMessage(MessageConstants.SHARE_WHAT, replyTo = replyHandler)
    }

    @WorkerThread
    fun getTags(state: IssueRecordingState) =
        sendMessage(MessageConstants.TAGS_WHAT, replyTo = Messenger(TagsHandler(bgLooper, state)))

    @WorkerThread
    private fun sendMessage(what: Int, data: Bundle = Bundle(), replyTo: Messenger? = null) =
        try {
            val msg =
                Message.obtain().apply {
                    this.what = what
                    this.data = data
                    this.replyTo = replyTo
                }
            binder?.send(msg) ?: onBound.add { binder!!.send(msg) }
        } catch (e: Exception) {
            Log.e(TAG, "failed to notify Traceur", e)
        }
}

private class ShareFilesHandler(
    private val screenRecordingUris: List<Uri>,
    private val userContextProvider: UserContextProvider,
    looper: Looper,
) : Handler(looper) {

    override fun handleMessage(msg: Message) {
        if (MessageConstants.SHARE_WHAT == msg.what) {
            shareTraces(
                msg.data.getParcelable(MessageConstants.EXTRA_PERFETTO, Uri::class.java),
                msg.data.getParcelable(MessageConstants.EXTRA_WINSCOPE, Uri::class.java),
            )
        } else {
            throw IllegalArgumentException("received unknown msg.what: " + msg.what)
        }
    }

    private fun shareTraces(perfetto: Uri?, winscope: Uri?) {
        val uris: ArrayList<Uri> =
            ArrayList<Uri>().apply {
                perfetto?.let { add(it) }
                winscope?.let { add(it) }
                screenRecordingUris.forEach { add(it) }
            }
        val fileSharingIntent =
            FileSender.buildSendIntent(userContextProvider.userContext, uris)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT)
        userContextProvider.userContext.startActivity(fileSharingIntent)
    }
}

private class TagsHandler(looper: Looper, private val state: IssueRecordingState) :
    Handler(looper) {

    override fun handleMessage(msg: Message) {
        if (MessageConstants.TAGS_WHAT == msg.what) {
            val keys = msg.data.getStringArrayList(MessageConstants.BUNDLE_KEY_TAGS)
            val values = msg.data.getStringArrayList(MessageConstants.BUNDLE_KEY_TAG_DESCRIPTIONS)
            if (keys == null || values == null) {
                throw IllegalArgumentException(
                    "Neither keys: $keys, nor values: $values can be null"
                )
            }
            state.tagTitles =
                keys.zip(values).map { it.first + TAG_TITLE_DELIMITER + it.second }.toSet()
        } else {
            throw IllegalArgumentException("received unknown msg.what: " + msg.what)
        }
    }
}
