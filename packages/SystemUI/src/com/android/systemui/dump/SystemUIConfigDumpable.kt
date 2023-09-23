/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.dump

import android.content.Context
import com.android.systemui.CoreStartable
import com.android.systemui.Dumpable
import com.android.systemui.res.R
import com.android.systemui.dagger.SysUISingleton
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Provider

@SysUISingleton
class SystemUIConfigDumpable
@Inject
constructor(
    dumpManager: DumpManager,
    private val context: Context,
    private val startables: MutableMap<Class<*>, Provider<CoreStartable>>,
) : Dumpable {
    init {
        dumpManager.registerCriticalDumpable("SystemUiServiceComponents", this)
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("SystemUiServiceComponents configuration:")
        pw.print("vendor component: ")
        pw.println(context.resources.getString(R.string.config_systemUIVendorServiceComponent))
        val services: MutableList<String> =
            startables.keys.map { cls: Class<*> -> cls.simpleName }.toMutableList()

        services.add(context.resources.getString(R.string.config_systemUIVendorServiceComponent))
        dumpServiceList(pw, "global", services.toTypedArray())
        dumpServiceList(pw, "per-user", R.array.config_systemUIServiceComponentsPerUser)
    }

    private fun dumpServiceList(pw: PrintWriter, type: String, resId: Int) {
        val services: Array<String> = context.resources.getStringArray(resId)
        dumpServiceList(pw, type, services)
    }

    private fun dumpServiceList(pw: PrintWriter, type: String, services: Array<String>?) {
        pw.print(type)
        pw.print(": ")
        if (services == null) {
            pw.println("N/A")
            return
        }
        pw.print(services.size)
        pw.println(" services")
        for (i in services.indices) {
            pw.print("  ")
            pw.print(i)
            pw.print(": ")
            pw.println(services[i])
        }
    }
}
