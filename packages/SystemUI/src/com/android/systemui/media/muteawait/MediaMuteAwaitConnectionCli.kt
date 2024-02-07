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

package com.android.systemui.media.muteawait

import android.annotation.SuppressLint
import android.media.AudioAttributes.USAGE_MEDIA
import android.media.AudioDeviceAttributes
import android.media.AudioManager
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import java.io.PrintWriter
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** A command line interface to manually test [MediaMuteAwaitConnectionManager]. */
@SysUISingleton
class MediaMuteAwaitConnectionCli @Inject constructor(
    private val commandRegistry: CommandRegistry,
    private val audioManager: AudioManager,
) : CoreStartable {
    override fun start() {
        commandRegistry.registerCommand(MEDIA_MUTE_AWAIT_COMMAND) { MuteAwaitCommand() }
    }

    inner class MuteAwaitCommand : Command {
        @SuppressLint("MissingPermission")
        override fun execute(pw: PrintWriter, args: List<String>) {
            val device = AudioDeviceAttributes(
                AudioDeviceAttributes.ROLE_OUTPUT,
                /* type= */ Integer.parseInt(args[0]),
                ADDRESS,
                /* name= */ args[1],
                listOf(),
                listOf(),
            )
            val startOrCancel = args[2]

            when (startOrCancel) {
                START ->
                    audioManager.muteAwaitConnection(
                            intArrayOf(USAGE_MEDIA), device, TIMEOUT, TIMEOUT_UNITS
                    )
                CANCEL -> audioManager.cancelMuteAwaitConnection(device)
                else -> pw.println("Must specify `$START` or `$CANCEL`; was $startOrCancel")
            }
        }
        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar $MEDIA_MUTE_AWAIT_COMMAND " +
                    "[type] [name] [$START|$CANCEL]")
        }
    }

    @Module
    interface StartableModule {
        @Binds
        @IntoMap
        @ClassKey(MediaMuteAwaitConnectionCli::class)
        fun bindsMediaMuteAwaitConnectionCli(impl: MediaMuteAwaitConnectionCli): CoreStartable
    }
}

private const val MEDIA_MUTE_AWAIT_COMMAND = "media-mute-await"
private const val START = "start"
private const val CANCEL = "cancel"
private const val ADDRESS = "address"
private const val TIMEOUT = 5L
private val TIMEOUT_UNITS = TimeUnit.SECONDS
