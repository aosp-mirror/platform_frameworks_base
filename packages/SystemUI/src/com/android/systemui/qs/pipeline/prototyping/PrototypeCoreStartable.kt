/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.pipeline.prototyping

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.qs.pipeline.data.repository.TileSpecRepository
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.user.data.repository.UserRepository
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * Class for observing results while prototyping.
 *
 * The flows do their own logging, so we just need to make sure that they collect.
 *
 * This will be torn down together with the last of the new pipeline flags remaining here.
 */
// TODO(b/270385608)
@SysUISingleton
class PrototypeCoreStartable
@Inject
constructor(
    private val tileSpecRepository: TileSpecRepository,
    private val userRepository: UserRepository,
    private val featureFlags: FeatureFlags,
    @Application private val scope: CoroutineScope,
    private val commandRegistry: CommandRegistry,
) : CoreStartable {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun start() {
        if (featureFlags.isEnabled(Flags.QS_PIPELINE_NEW_HOST)) {
            scope.launch {
                userRepository.selectedUserInfo
                    .flatMapLatest { user -> tileSpecRepository.tilesSpecs(user.id) }
                    .collect {}
            }
            commandRegistry.registerCommand(COMMAND, ::CommandExecutor)
        }
    }

    private inner class CommandExecutor : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            if (args.size < 2) {
                pw.println("Error: needs at least two arguments")
                return
            }
            val spec = TileSpec.create(args[1])
            if (spec == TileSpec.Invalid) {
                pw.println("Error: Invalid tile spec ${args[1]}")
            }
            if (args[0] == "add") {
                performAdd(args, spec)
                pw.println("Requested tile added")
            } else if (args[0] == "remove") {
                performRemove(args, spec)
                pw.println("Requested tile removed")
            } else {
                pw.println("Error: unknown command")
            }
        }

        private fun performAdd(args: List<String>, spec: TileSpec) {
            val position = args.getOrNull(2)?.toInt() ?: TileSpecRepository.POSITION_AT_END
            val user = args.getOrNull(3)?.toInt() ?: userRepository.getSelectedUserInfo().id
            scope.launch { tileSpecRepository.addTile(user, spec, position) }
        }

        private fun performRemove(args: List<String>, spec: TileSpec) {
            val user = args.getOrNull(2)?.toInt() ?: userRepository.getSelectedUserInfo().id
            scope.launch { tileSpecRepository.removeTiles(user, listOf(spec)) }
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar $COMMAND:")
            pw.println("  add <spec> [position] [user]")
            pw.println("  remove <spec> [user]")
        }
    }

    companion object {
        private const val COMMAND = "qs-pipeline"
    }
}
