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

package com.android.systemui.flags

import android.content.Intent
import com.android.systemui.CoreStartable
import com.android.systemui.broadcast.BroadcastSender
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.util.InitializationChecker
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import javax.inject.Inject

class FeatureFlagsDebugStartable
@Inject
constructor(
    dumpManager: DumpManager,
    private val commandRegistry: CommandRegistry,
    private val flagCommand: FlagCommand,
    private val featureFlags: FeatureFlagsDebug,
    private val broadcastSender: BroadcastSender,
    private val initializationChecker: InitializationChecker,
) : CoreStartable {

    init {
        dumpManager.registerCriticalDumpable(FeatureFlagsDebug.TAG) { pw, args ->
            featureFlags.dump(pw, args)
        }
    }

    override fun start() {
        featureFlags.init()
        commandRegistry.registerCommand(FlagCommand.FLAG_COMMAND) { flagCommand }
        if (initializationChecker.initializeComponents()) {
            // protected broadcast should only be sent for the main process
            val intent = Intent(FlagManager.ACTION_SYSUI_STARTED)
            broadcastSender.sendBroadcast(intent)
        }
    }
}

@Module
abstract class FeatureFlagsDebugStartableModule {
    @Binds
    @IntoMap
    @ClassKey(FeatureFlagsDebugStartable::class)
    abstract fun bind(impl: FeatureFlagsDebugStartable): CoreStartable
}
