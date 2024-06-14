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
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.content.Context
import android.util.Log
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.util.Compile
import com.android.systemui.util.asIndenting
import com.android.systemui.util.printCollection
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
        if (!handler.enableDependencies) {
            return
        }
        defineDependencies()
        allDependencies = workingDependencies.toList()
        unmetDependencies = workingDependencies.filter { !it.isMet }
        workingDependencies.clear()
        handler.onCollected(allDependencies)
        if (unmetDependencies.isNotEmpty()) {
            handler.warnAboutBadFlagConfiguration(all = allDependencies, unmet = unmetDependencies)
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.asIndenting().run {
            printCollection("allDependencies", allDependencies)
            printCollection("unmetDependencies", unmetDependencies)
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
            val prefix =
                when {
                    !isMet -> "  [NOT MET]"
                    alphaEnabled -> "      [met]"
                    betaEnabled -> "    [ready]"
                    else -> "[not ready]"
                }
            val alphaState = if (alphaEnabled) "enabled" else "disabled"
            val betaState = if (betaEnabled) "enabled" else "disabled"
            return "$prefix $alphaName ($alphaState) DEPENDS ON $betaName ($betaState)"
        }
        /** Used whe posting a notification of unmet dependencies */
        fun shortUnmetString(): String = "$alphaName DEPENDS ON $betaName"
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
        if (!handler.enableDependencies) return
        workingDependencies.add(
            Dependency(first.name, first.isEnabled, second.name, second.isEnabled)
        )
    }

    /** An interface which handles dependency collection. */
    interface Handler {
        /**
         * Should FlagDependencies do anything?
         *
         * @return false for user builds so that we skip this overhead.
         */
        val enableDependencies: Boolean
            get() = Compile.IS_DEBUG
        /** Handle the complete list of dependencies. */
        fun onCollected(all: List<Dependency>) {}
        /** Handle a bad flag configuration. */
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
        val title = "Invalid flag dependencies: ${unmet.size}"
        val details = unmet.joinToString("\n") { it.shortUnmetString() }
        Log.e("FlagDependencies", "$title:\n$details")
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, IMPORTANCE_DEFAULT)
        val notification =
            Notification.Builder(context, channel.id)
                .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                .setContentTitle(title)
                .setContentText(details)
                .setStyle(Notification.BigTextStyle().bigText(details))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build()
        notifManager.createNotificationChannel(channel)
        notifManager.notify(NOTIF_TAG, NOTIF_ID, notification)
    }

    override fun onCollected(all: List<FlagDependenciesBase.Dependency>) {
        notifManager.cancel(NOTIF_TAG, NOTIF_ID)
    }

    companion object {
        private const val CHANNEL_ID = "FLAGS"
        private const val CHANNEL_NAME = "Flags"
        private const val NOTIF_TAG = "FlagDependenciesNotifier"
        private const val NOTIF_ID = 0
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
