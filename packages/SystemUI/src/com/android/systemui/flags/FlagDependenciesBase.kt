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

package com.android.systemui.flags

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.util.Compile
import com.android.systemui.util.asIndenting
import com.android.systemui.util.withIncreasedIndent
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import java.io.PrintWriter
import javax.inject.Inject

/**
 * This base class provides the helpers necessary to define dependencies between flags from the
 * different flagging systems; classic and aconfig. This class is to be extended
 */
abstract class FlagDependenciesBase(
    private val featureFlags: FeatureFlagsClassic,
    private val handler: Handler
) : CoreStartable {
    protected abstract fun defineDependencies()

    private val workingDependencies = mutableListOf<Dependency>()
    private var allDependencies = emptyList<Dependency>()
    private var unmetDependencies = emptyList<Dependency>()

    override fun start() {
        defineDependencies()
        allDependencies = workingDependencies.toList()
        unmetDependencies = workingDependencies.filter { !it.isMet }
        workingDependencies.clear()
        if (unmetDependencies.isNotEmpty()) {
            handler.warnAboutBadFlagConfiguration(all = allDependencies, unmet = unmetDependencies)
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.asIndenting().run {
            println("allDependencies: ${allDependencies.size}")
            withIncreasedIndent { allDependencies.forEach(::println) }
            println("unmetDependencies: ${unmetDependencies.size}")
            withIncreasedIndent { unmetDependencies.forEach(::println) }
        }
    }

    /** A dependency where enabling the `alpha` feature depends on enabling the `beta` feature */
    class Dependency(
        private val alphaName: String,
        private val alphaEnabled: Boolean,
        private val betaName: String,
        private val betaEnabled: Boolean
    ) {
        val isMet = !alphaEnabled || betaEnabled
        override fun toString(): String {
            val isMetBullet = if (isMet) "+" else "-"
            return "$isMetBullet $alphaName ($alphaEnabled) DEPENDS ON $betaName ($betaEnabled)"
        }
    }

    protected infix fun UnreleasedFlag.dependsOn(other: UnreleasedFlag) =
        addDependency(this.token, other.token)
    protected infix fun ReleasedFlag.dependsOn(other: UnreleasedFlag) =
        addDependency(this.token, other.token)
    protected infix fun ReleasedFlag.dependsOn(other: ReleasedFlag) =
        addDependency(this.token, other.token)
    protected infix fun FlagToken.dependsOn(other: UnreleasedFlag) =
        addDependency(this, other.token)
    protected infix fun FlagToken.dependsOn(other: ReleasedFlag) = addDependency(this, other.token)
    protected infix fun FlagToken.dependsOn(other: FlagToken) = addDependency(this, other)

    private val UnreleasedFlag.token
        get() = FlagToken("classic.$name", featureFlags.isEnabled(this))
    private val ReleasedFlag.token
        get() = FlagToken("classic.$name", featureFlags.isEnabled(this))

    /** Add a dependency to the working list */
    private fun addDependency(first: FlagToken, second: FlagToken) {
        if (!Compile.IS_DEBUG) return // `user` builds should omit all this code
        workingDependencies.add(
            Dependency(first.name, first.isEnabled, second.name, second.isEnabled)
        )
    }

    /** An interface which handles a warning about a bad flag configuration. */
    interface Handler {
        fun warnAboutBadFlagConfiguration(all: List<Dependency>, unmet: List<Dependency>)
    }
}

/**
 * A flag dependencies handler which posts a notification and logs to logcat that the configuration
 * is invalid.
 */
@SysUISingleton
class FlagDependenciesNotifier
@Inject
constructor(
    private val context: Context,
    private val notifManager: NotificationManager,
) : FlagDependenciesBase.Handler {
    override fun warnAboutBadFlagConfiguration(
        all: List<FlagDependenciesBase.Dependency>,
        unmet: List<FlagDependenciesBase.Dependency>
    ) {
        val title = "Invalid flag dependencies: ${unmet.size} of ${all.size}"
        val details = unmet.joinToString("\n")
        Log.e("FlagDependencies", "$title:\n$details")
        val channel = NotificationChannel("FLAGS", "Flags", NotificationManager.IMPORTANCE_DEFAULT)
        val notification =
            Notification.Builder(context, channel.id)
                .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                .setContentTitle(title)
                .setContentText(details)
                .setStyle(Notification.BigTextStyle().bigText(details))
                .build()
        notifManager.createNotificationChannel(channel)
        notifManager.notify("flags", 0, notification)
    }
}

@Module
abstract class FlagDependenciesModule {

    /** Inject into FlagDependencies. */
    @Binds
    @IntoMap
    @ClassKey(FlagDependencies::class)
    abstract fun bindFlagDependencies(sysui: FlagDependencies): CoreStartable

    /** Bind the flag dependencies handler */
    @Binds
    abstract fun bindFlagDependenciesHandler(
        handler: FlagDependenciesNotifier
    ): FlagDependenciesBase.Handler
}
