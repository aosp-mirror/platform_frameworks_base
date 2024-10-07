/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.biometrics

import android.content.res.Resources
import android.os.SystemClock.elapsedRealtime
import com.android.keyguard.logging.BiometricMessageDeferralLogger
import com.android.systemui.Dumpable
import com.android.systemui.Flags.faceMessageDeferUpdate
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.deviceentry.shared.model.HelpFaceAuthenticationStatus
import com.android.systemui.dump.DumpManager
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.dagger.BiometricLog
import com.android.systemui.res.R
import com.android.systemui.util.time.SystemClock
import dagger.Lazy
import java.io.PrintWriter
import java.util.Objects
import java.util.UUID
import javax.inject.Inject

@SysUISingleton
class FaceHelpMessageDeferralFactory
@Inject
constructor(
    @Main private val resources: Resources,
    @BiometricLog private val logBuffer: LogBuffer,
    private val dumpManager: DumpManager,
    private val systemClock: Lazy<SystemClock>,
) {
    fun create(): FaceHelpMessageDeferral {
        val id = UUID.randomUUID().toString()
        return FaceHelpMessageDeferral(
            resources = resources,
            logBuffer = BiometricMessageDeferralLogger(logBuffer, "FaceHelpMessageDeferral[$id]"),
            dumpManager = dumpManager,
            id = id,
            systemClock,
        )
    }
}

/**
 * Provides whether a face acquired help message should be shown immediately when its received or
 * should be shown when face auth times out. See [updateMessage] and [getDeferredMessage].
 */
class FaceHelpMessageDeferral(
    resources: Resources,
    logBuffer: BiometricMessageDeferralLogger,
    dumpManager: DumpManager,
    val id: String,
    val systemClock: Lazy<SystemClock>,
) :
    BiometricMessageDeferral(
        resources.getIntArray(R.array.config_face_help_msgs_defer_until_timeout).toHashSet(),
        resources.getIntArray(R.array.config_face_help_msgs_ignore).toHashSet(),
        resources.getFloat(R.dimen.config_face_help_msgs_defer_until_timeout_threshold),
        resources.getInteger(R.integer.config_face_help_msgs_defer_analyze_timeframe).toLong(),
        logBuffer,
        dumpManager,
        id,
        systemClock,
    )

/**
 * @property messagesToDefer messages that shouldn't show immediately when received, but may be
 *   shown later if the message is the most frequent acquiredInfo processed and meets [threshold]
 *   percentage of all acquired frames, excluding [acquiredInfoToIgnore].
 */
open class BiometricMessageDeferral(
    private val messagesToDefer: Set<Int>,
    private val acquiredInfoToIgnore: Set<Int>,
    private val threshold: Float,
    private val windowToAnalyzeLastNFrames: Long,
    private val logBuffer: BiometricMessageDeferralLogger,
    dumpManager: DumpManager,
    id: String,
    private val systemClock: Lazy<SystemClock>,
) : Dumpable {

    private val faceHelpMessageDebouncer: FaceHelpMessageDebouncer? =
        if (faceMessageDeferUpdate()) {
            FaceHelpMessageDebouncer(
                window = windowToAnalyzeLastNFrames,
                startWindow = 0L,
                shownFaceMessageFrequencyBoost = 0,
                threshold = threshold,
            )
        } else {
            null
        }
    private val acquiredInfoToFrequency: MutableMap<Int, Int> = HashMap()
    private val acquiredInfoToHelpString: MutableMap<Int, String> = HashMap()
    private var mostFrequentAcquiredInfoToDefer: Int? = null
    private var totalFrames = 0

    init {
        dumpManager.registerNormalDumpable(
            "${this.javaClass.name}[$id]",
            this,
        )
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("messagesToDefer=$messagesToDefer")
        pw.println("totalFrames=$totalFrames")
        pw.println("threshold=$threshold")
        pw.println("faceMessageDeferUpdateFlagEnabled=${faceMessageDeferUpdate()}")
        if (faceMessageDeferUpdate()) {
            pw.println("windowToAnalyzeLastNFrames(ms)=$windowToAnalyzeLastNFrames")
        }
    }

    /** Reset all saved counts. */
    fun reset() {
        totalFrames = 0
        if (!faceMessageDeferUpdate()) {
            mostFrequentAcquiredInfoToDefer = null
            acquiredInfoToFrequency.clear()
        }

        acquiredInfoToHelpString.clear()
        logBuffer.reset()
    }

    /** Updates the message associated with the acquiredInfo if it's a message we may defer. */
    fun updateMessage(acquiredInfo: Int, helpString: String) {
        if (!messagesToDefer.contains(acquiredInfo)) {
            return
        }
        if (!Objects.equals(acquiredInfoToHelpString[acquiredInfo], helpString)) {
            logBuffer.logUpdateMessage(acquiredInfo, helpString)
            acquiredInfoToHelpString[acquiredInfo] = helpString
        }
    }

    /** Whether the given message should be deferred instead of being shown immediately. */
    fun shouldDefer(acquiredMsgId: Int): Boolean {
        return messagesToDefer.contains(acquiredMsgId)
    }

    /**
     * Adds the acquiredInfo frame to the counts. We account for frames not included in
     * acquiredInfoToIgnore.
     */
    fun processFrame(acquiredInfo: Int) {
        if (messagesToDefer.isEmpty()) {
            return
        }

        if (acquiredInfoToIgnore.contains(acquiredInfo)) {
            logBuffer.logFrameIgnored(acquiredInfo)
            return
        }
        totalFrames++

        if (faceMessageDeferUpdate()) {
            faceHelpMessageDebouncer?.let {
                val helpFaceAuthStatus =
                    HelpFaceAuthenticationStatus(
                        msgId = acquiredInfo,
                        msg = null,
                        systemClock.get().elapsedRealtime()
                    )
                if (totalFrames == 1) { // first frame
                    it.startNewFaceAuthSession(helpFaceAuthStatus.createdAt)
                }
                it.addMessage(helpFaceAuthStatus)
            }
        } else {
            val newAcquiredInfoCount = acquiredInfoToFrequency.getOrDefault(acquiredInfo, 0) + 1
            acquiredInfoToFrequency[acquiredInfo] = newAcquiredInfoCount
            if (
                messagesToDefer.contains(acquiredInfo) &&
                    (mostFrequentAcquiredInfoToDefer == null ||
                        newAcquiredInfoCount >
                            acquiredInfoToFrequency.getOrDefault(
                                mostFrequentAcquiredInfoToDefer!!,
                                0
                            ))
            ) {
                mostFrequentAcquiredInfoToDefer = acquiredInfo
            }
        }

        logBuffer.logFrameProcessed(
            acquiredInfo,
            totalFrames,
            if (faceMessageDeferUpdate()) {
                faceHelpMessageDebouncer
                    ?.getMessageToShow(systemClock.get().elapsedRealtime())
                    ?.msgId
                    .toString()
            } else {
                mostFrequentAcquiredInfoToDefer?.toString()
            }
        )
    }

    /**
     * Get the most frequent deferred message that meets the [threshold] percentage of processed
     * frames.
     *
     * @return null if no acquiredInfo have been deferred OR deferred messages didn't meet the
     *   [threshold] percentage.
     */
    fun getDeferredMessage(): CharSequence? {
        if (faceMessageDeferUpdate()) {
            faceHelpMessageDebouncer?.let {
                val helpFaceAuthStatus = it.getMessageToShow(systemClock.get().elapsedRealtime())
                return acquiredInfoToHelpString[helpFaceAuthStatus?.msgId]
            }
        } else {
            mostFrequentAcquiredInfoToDefer?.let {
                if (acquiredInfoToFrequency.getOrDefault(it, 0) > (threshold * totalFrames)) {
                    return acquiredInfoToHelpString[it]
                }
            }
        }
        return null
    }
}
