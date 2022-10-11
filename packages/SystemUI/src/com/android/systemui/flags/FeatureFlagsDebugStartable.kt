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

import android.content.Context
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.commandline.CommandRegistry
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import javax.inject.Inject

class FeatureFlagsDebugStartable
@Inject
constructor(
    @Application context: Context,
    dumpManager: DumpManager,
    private val commandRegistry: CommandRegistry,
    private val flagCommand: FlagCommand,
    featureFlags: FeatureFlags
) : CoreStartable(context) {

    init {
        dumpManager.registerDumpable(FeatureFlagsDebug.TAG) { pw, args ->
            featureFlags.dump(pw, args)
        }
    }

    override fun start() {
        commandRegistry.registerCommand(FlagCommand.FLAG_COMMAND) { flagCommand }
    }
}

@Module
abstract class FeatureFlagsDebugStartableModule {
    @Binds
    @IntoMap
    @ClassKey(FeatureFlagsDebugStartable::class)
    abstract fun bind(impl: FeatureFlagsDebugStartable): CoreStartable
}
