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

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
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
import com.android.traceur.FileSender
import com.android.traceur.MessageConstants
import com.android.traceur.TraceUtils.PresetTraceType
import javax.inject.Inject

private const val TAG = "TraceurMessageSender"

@SysUISingleton
class TraceurMessageSender @Inject constructor(@Background private val backgroundLooper: Looper) {
    private var binder: Messenger? = null
    private var isBound: Boolean = false

    private val traceurConnection =
        object : ServiceConnection {
            override fun onServiceConnected(className: ComponentName, service: IBinder) {
                binder = Messenger(service)
                isBound = true
            }

            override fun onServiceDisconnected(className: ComponentName) {
                binder = null
                isBound = false
            }
        }

    @SuppressLint("WrongConstant")
    @WorkerThread
    fun bindToTraceur(context: Context) {
        if (isBound) {
            // Binding needs to happen after the phone has been unlocked. The RecordIssueTile is
            // initialized before this happens though, so binding is placed at a later time, during
            // normal operations that can be repeated. This check avoids calling "bindService" 2x+
            return
        }
        try {
            val info =
                context.packageManager.getPackageInfo(
                    MessageConstants.TRACING_APP_PACKAGE_NAME,
                    PackageManager.MATCH_SYSTEM_ONLY
                )
            val intent =
                Intent().setClassName(info.packageName, MessageConstants.TRACING_APP_ACTIVITY)
            val flags =
                Context.BIND_AUTO_CREATE or
                    Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE or
                    Context.BIND_WAIVE_PRIORITY
            context.bindService(intent, traceurConnection, flags)
        } catch (e: Exception) {
            Log.e(TAG, "failed to bind to Traceur's service", e)
        }
    }

    @WorkerThread
    fun unbindFromTraceur(context: Context) {
        if (isBound) {
            context.unbindService(traceurConnection)
        }
    }

    @WorkerThread
    fun startTracing(traceType: PresetTraceType?) {
        val data =
            Bundle().apply { putSerializable(MessageConstants.INTENT_EXTRA_TRACE_TYPE, traceType) }
        notifyTraceur(MessageConstants.START_WHAT, data)
    }

    @WorkerThread fun stopTracing() = notifyTraceur(MessageConstants.STOP_WHAT)

    @WorkerThread
    fun shareTraces(context: Context, screenRecord: Uri?) {
        val replyHandler = Messenger(TraceurMessageHandler(context, screenRecord, backgroundLooper))
        notifyTraceur(MessageConstants.SHARE_WHAT, replyTo = replyHandler)
    }

    @WorkerThread
    private fun notifyTraceur(what: Int, data: Bundle = Bundle(), replyTo: Messenger? = null) {
        try {
            binder!!.send(
                Message.obtain().apply {
                    this.what = what
                    this.data = data
                    this.replyTo = replyTo
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "failed to notify Traceur", e)
        }
    }

    private class TraceurMessageHandler(
        private val context: Context,
        private val screenRecord: Uri?,
        looper: Looper,
    ) : Handler(looper) {

        override fun handleMessage(msg: Message) {
            if (MessageConstants.SHARE_WHAT == msg.what) {
                shareTraces(
                    msg.data.getParcelable(MessageConstants.EXTRA_PERFETTO, Uri::class.java),
                    msg.data.getParcelable(MessageConstants.EXTRA_WINSCOPE, Uri::class.java)
                )
            } else {
                throw IllegalArgumentException("received unknown msg.what: " + msg.what)
            }
        }

        private fun shareTraces(perfetto: Uri?, winscope: Uri?) {
            val uris: List<Uri> =
                mutableListOf<Uri>().apply {
                    perfetto?.let { add(it) }
                    winscope?.let { add(it) }
                    screenRecord?.let { add(it) }
                }
            val fileSharingIntent =
                FileSender.buildSendIntent(context, uris)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
                    )
            context.startActivity(fileSharingIntent)
        }
    }
}
