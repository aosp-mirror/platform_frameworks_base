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

package com.android.systemui.haptics.msdl

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.google.android.msdl.domain.MSDLPlayer
import com.google.android.msdl.logging.MSDLHistoryLogger
import java.io.PrintWriter
import javax.inject.Inject

@SysUISingleton
class MSDLCoreStartable @Inject constructor(private val msdlPlayer: MSDLPlayer) : CoreStartable {
    override fun start() {}

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println(msdlPlayer)
        pw.println("MSDL player history of the last ${MSDLHistoryLogger.HISTORY_SIZE} events:")
        msdlPlayer.getHistory().forEach { event -> pw.println("$event") }
    }
}
