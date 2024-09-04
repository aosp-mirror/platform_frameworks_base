/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */

package com.android.systemui.statusbar.notification.icon.ui.viewbinder

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.util.asIndenting
import com.android.systemui.util.printCollection
import dagger.Binds
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import java.io.PrintWriter
import javax.inject.Inject

@SysUISingleton
class StatusBarIconViewBindingFailureTracker @Inject constructor() : CoreStartable {

    var aodFailures: Collection<String> = emptyList()
    var statusBarFailures: Collection<String> = emptyList()
    var shelfFailures: Collection<String> = emptyList()

    // TODO(b/310681665): Ideally we wouldn't need to implement CoreStartable at all, and could just
    //  @Binds @IntoSet the Dumpable.
    override fun start() {
        // no-op, we're just using this as a dumpable
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.asIndenting().run {
            printCollection("AOD Icon binding failures:", aodFailures)
            printCollection("Status Bar Icon binding failures:", statusBarFailures)
            printCollection("Shelf Icon binding failures:", shelfFailures)
        }
    }

    @dagger.Module
    interface Module {
        @Binds
        @IntoMap
        @ClassKey(StatusBarIconViewBindingFailureTracker::class)
        fun bindStartable(impl: StatusBarIconViewBindingFailureTracker): CoreStartable
    }
}
