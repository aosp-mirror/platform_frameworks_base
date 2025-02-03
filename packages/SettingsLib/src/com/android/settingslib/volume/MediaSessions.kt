/*
 * Copyright 2025 The Android Open Source Project
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
package com.android.settingslib.volume

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.HandlerExecutor
import android.os.Looper
import android.os.Message
import android.util.Log
import java.io.PrintWriter
import java.util.Objects

/**
 * Convenience client for all media session updates. Provides a callback interface for events
 * related to remote media sessions.
 */
class MediaSessions(context: Context, looper: Looper, callbacks: Callbacks) {

    private val mContext = context
    private val mHandler: H = H(looper)
    private val mHandlerExecutor: HandlerExecutor = HandlerExecutor(mHandler)
    private val mMgr: MediaSessionManager =
        mContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val mRecords: MutableMap<MediaSession.Token, MediaControllerRecord> = HashMap()
    private val mCallbacks: Callbacks = callbacks
    private val mSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            onActiveSessionsUpdatedH(controllers!!)
        }

    private val mRemoteSessionCallback: MediaSessionManager.RemoteSessionCallback =
        object : MediaSessionManager.RemoteSessionCallback {
            override fun onVolumeChanged(sessionToken: MediaSession.Token, flags: Int) {
                mHandler.obtainMessage(REMOTE_VOLUME_CHANGED, flags, 0, sessionToken).sendToTarget()
            }

            override fun onDefaultRemoteSessionChanged(sessionToken: MediaSession.Token?) {
                mHandler.obtainMessage(UPDATE_REMOTE_SESSION_LIST, sessionToken).sendToTarget()
            }
        }

    private var mInit = false

    /** Dump to `writer` */
    fun dump(writer: PrintWriter) {
        writer.println(javaClass.simpleName + " state:")
        writer.print("  mInit: ")
        writer.println(mInit)
        writer.print("  mRecords.size: ")
        writer.println(mRecords.size)
        for ((i, r) in mRecords.values.withIndex()) {
            r.controller.dump(i + 1, writer)
        }
    }

    /** init MediaSessions */
    fun init() {
        if (D.BUG) {
            Log.d(TAG, "init")
        }
        // will throw if no permission
        mMgr.addOnActiveSessionsChangedListener(mSessionsListener, null, mHandler)
        mInit = true
        postUpdateSessions()
        mMgr.registerRemoteSessionCallback(mHandlerExecutor, mRemoteSessionCallback)
    }

    /** Destroy MediaSessions */
    fun destroy() {
        if (D.BUG) {
            Log.d(TAG, "destroy")
        }
        mInit = false
        mMgr.removeOnActiveSessionsChangedListener(mSessionsListener)
        mMgr.unregisterRemoteSessionCallback(mRemoteSessionCallback)
    }

    /** Set volume `level` to remote media `token` */
    fun setVolume(token: MediaSession.Token, level: Int) {
        val record = mRecords[token]
        if (record == null) {
            Log.w(TAG, "setVolume: No record found for token $token")
            return
        }
        if (D.BUG) {
            Log.d(TAG, "Setting level to $level")
        }
        record.controller.setVolumeTo(level, 0)
    }

    private fun onRemoteVolumeChangedH(sessionToken: MediaSession.Token, flags: Int) {
        val controller = MediaController(mContext, sessionToken)
        if (D.BUG) {
            Log.d(
                TAG,
                "remoteVolumeChangedH " +
                    controller.packageName +
                    " " +
                    Util.audioManagerFlagsToString(flags),
            )
        }
        val token = controller.sessionToken
        mCallbacks.onRemoteVolumeChanged(token, flags)
    }

    private fun onUpdateRemoteSessionListH(sessionToken: MediaSession.Token?) {
        if (D.BUG) {
            Log.d(
                TAG,
                "onUpdateRemoteSessionListH ${sessionToken?.let {MediaController(mContext, it)}?.packageName}",
            )
        }
        // this may be our only indication that a remote session is changed, refresh
        postUpdateSessions()
    }

    private fun postUpdateSessions() {
        if (mInit) {
            mHandler.sendEmptyMessage(UPDATE_SESSIONS)
        }
    }

    private fun onActiveSessionsUpdatedH(controllers: List<MediaController>) {
        if (D.BUG) {
            Log.d(TAG, "onActiveSessionsUpdatedH n=" + controllers.size)
        }
        val toRemove: MutableSet<MediaSession.Token> = HashSet(mRecords.keys)
        for (controller in controllers) {
            val token = controller.sessionToken
            val playbackInfo = controller.playbackInfo
            toRemove.remove(token)
            if (!mRecords.containsKey(token)) {
                val record = MediaControllerRecord(controller)
                record.name = getControllerName(controller)
                mRecords[token] = record
                controller.registerCallback(record, mHandler)
            }
            val record = mRecords[token]
            val remote = isRemote(playbackInfo)
            if (remote) {
                updateRemoteH(token, record!!.name, playbackInfo)
                record.sentRemote = true
            }
        }
        for (token in toRemove) {
            val record = mRecords[token]!!
            record.controller.unregisterCallback(record)
            mRecords.remove(token)
            if (D.BUG) {
                Log.d(TAG, "Removing " + record.name + " sentRemote=" + record.sentRemote)
            }
            if (record.sentRemote) {
                mCallbacks.onRemoteRemoved(token)
                record.sentRemote = false
            }
        }
    }

    private fun getControllerName(controller: MediaController): String {
        val pm = mContext.packageManager
        val pkg = controller.packageName
        try {
            if (USE_SERVICE_LABEL) {
                val services =
                    pm.queryIntentServices(
                        Intent("android.media.MediaRouteProviderService").setPackage(pkg),
                        0,
                    )
                if (services != null) {
                    for (ri in services) {
                        if (ri.serviceInfo == null) continue
                        if (pkg == ri.serviceInfo.packageName) {
                            val serviceLabel =
                                Objects.toString(ri.serviceInfo.loadLabel(pm), "").trim()
                            if (serviceLabel.isNotEmpty()) {
                                return serviceLabel
                            }
                        }
                    }
                }
            }
            val ai = pm.getApplicationInfo(pkg, 0)
            val appLabel = Objects.toString(ai.loadLabel(pm), "").trim { it <= ' ' }
            if (appLabel.isNotEmpty()) {
                return appLabel
            }
        } catch (_: PackageManager.NameNotFoundException) {}
        return pkg
    }

    private fun updateRemoteH(
        token: MediaSession.Token,
        name: String?,
        pi: MediaController.PlaybackInfo,
    ) = mCallbacks.onRemoteUpdate(token, name, pi)

    private inner class MediaControllerRecord(val controller: MediaController) :
        MediaController.Callback() {
        var sentRemote: Boolean = false
        var name: String? = null

        fun cb(method: String): String {
            return method + " " + controller.packageName + " "
        }

        override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) {
            if (D.BUG) {
                Log.d(
                    TAG,
                    (cb("onAudioInfoChanged") +
                        Util.playbackInfoToString(info) +
                        " sentRemote=" +
                        sentRemote),
                )
            }
            val remote = isRemote(info)
            if (!remote && sentRemote) {
                mCallbacks.onRemoteRemoved(controller.sessionToken)
                sentRemote = false
            } else if (remote) {
                updateRemoteH(controller.sessionToken, name, info)
                sentRemote = true
            }
        }

        override fun onExtrasChanged(extras: Bundle?) {
            if (D.BUG) {
                Log.d(TAG, cb("onExtrasChanged") + extras)
            }
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            if (D.BUG) {
                Log.d(TAG, cb("onMetadataChanged") + Util.mediaMetadataToString(metadata))
            }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (D.BUG) {
                Log.d(TAG, cb("onPlaybackStateChanged") + Util.playbackStateToString(state))
            }
        }

        override fun onQueueChanged(queue: List<MediaSession.QueueItem>?) {
            if (D.BUG) {
                Log.d(TAG, cb("onQueueChanged") + queue)
            }
        }

        override fun onQueueTitleChanged(title: CharSequence?) {
            if (D.BUG) {
                Log.d(TAG, cb("onQueueTitleChanged") + title)
            }
        }

        override fun onSessionDestroyed() {
            if (D.BUG) {
                Log.d(TAG, cb("onSessionDestroyed"))
            }
        }

        override fun onSessionEvent(event: String, extras: Bundle?) {
            if (D.BUG) {
                Log.d(TAG, cb("onSessionEvent") + "event=" + event + " extras=" + extras)
            }
        }
    }

    private inner class H(looper: Looper) : Handler(looper) {

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                UPDATE_SESSIONS -> onActiveSessionsUpdatedH(mMgr.getActiveSessions(null))
                REMOTE_VOLUME_CHANGED ->
                    onRemoteVolumeChangedH(msg.obj as MediaSession.Token, msg.arg1)
                UPDATE_REMOTE_SESSION_LIST ->
                    onUpdateRemoteSessionListH(msg.obj as MediaSession.Token?)
            }
        }
    }

    /** Callback for remote media sessions */
    interface Callbacks {
        /** Invoked when remote media session is updated */
        fun onRemoteUpdate(
            token: MediaSession.Token?,
            name: String?,
            pi: MediaController.PlaybackInfo?,
        )

        /** Invoked when remote media session is removed */
        fun onRemoteRemoved(token: MediaSession.Token?)

        /** Invoked when remote volume is changed */
        fun onRemoteVolumeChanged(token: MediaSession.Token?, flags: Int)
    }

    companion object {
        private val TAG: String = Util.logTag(MediaSessions::class.java)

        const val UPDATE_SESSIONS: Int = 1
        const val REMOTE_VOLUME_CHANGED: Int = 2
        const val UPDATE_REMOTE_SESSION_LIST: Int = 3

        private const val USE_SERVICE_LABEL = false

        private fun isRemote(pi: MediaController.PlaybackInfo?): Boolean =
            pi != null && pi.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
    }
}

private fun MediaController.dump(n: Int, writer: PrintWriter) {
    writer.println("  Controller $n: $packageName")

    writer.println("    PlaybackState: ${Util.playbackStateToString(playbackState)}")
    writer.println("    PlaybackInfo: ${Util.playbackInfoToString(playbackInfo)}")
    val metadata = this.metadata
    if (metadata != null) {
        writer.println("  MediaMetadata.desc=${metadata.description}")
    }
    writer.println("    RatingType: $ratingType")
    writer.println("    Flags: $flags")

    writer.println("    Extras:")
    val extras = this.extras
    if (extras == null) {
        writer.println("      <null>")
    } else {
        for (key in extras.keySet()) {
            writer.println("      $key=${extras[key]}")
        }
    }
    writer.println("    QueueTitle: $queueTitle")
    val queue = this.queue
    if (!queue.isNullOrEmpty()) {
        writer.println("    Queue:")
        for (qi in queue) {
            writer.println("      $qi")
        }
    }
    writer.println("    sessionActivity: $sessionActivity")
}
