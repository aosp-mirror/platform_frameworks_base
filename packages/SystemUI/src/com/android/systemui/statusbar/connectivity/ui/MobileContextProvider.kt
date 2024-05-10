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

package com.android.systemui.statusbar.connectivity.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.telephony.SubscriptionInfo
import android.view.ContextThemeWrapper
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.demomode.DemoMode
import com.android.systemui.demomode.DemoMode.COMMAND_NETWORK
import com.android.systemui.demomode.DemoModeController
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.connectivity.NetworkController
import com.android.systemui.statusbar.connectivity.SignalCallback
import java.io.PrintWriter
import javax.inject.Inject

/**
 * Every subscriptionId can have its own CarrierConfig associated with it, so we have to create our
 * own [Configuration] and track resources based on the full set of available mcc-mnc combinations.
 *
 * (for future reference: b/240555502 is the initiating bug for this)
 *
 * NOTE: MCC/MNC qualifiers are not sufficient to fully describe a network type icon qualified by
 * network type + carrier ID. This class exists to keep the legacy behavior of using the MCC/MNC
 * resource qualifiers working, but if a carrier-specific icon is requested, then the override
 * provided by [MobileIconCarrierIdOverrides] will take precedence.
 *
 * TODO(b/258503704): consider removing this class in favor of the `carrierId` overrides
 */
@SysUISingleton
class MobileContextProvider
@Inject
constructor(
    networkController: NetworkController,
    dumpManager: DumpManager,
    private val demoModeController: DemoModeController,
) : Dumpable, DemoMode {
    private val subscriptions = mutableMapOf<Int, SubscriptionInfo>()
    private val signalCallback =
        object : SignalCallback {
            override fun setSubs(subs: List<SubscriptionInfo>) {
                subscriptions.clear()
                subs.forEach { info -> subscriptions[info.subscriptionId] = info }
            }
        }

    // These should always be null when not in demo mode
    private var demoMcc: Int? = null
    private var demoMnc: Int? = null

    init {
        networkController.addCallback(signalCallback)
        dumpManager.registerDumpable(this)
        demoModeController.addCallback(this)
    }

    /**
     * @return a context with the MCC/MNC [Configuration] values corresponding to this
     *   subscriptionId
     */
    fun getMobileContextForSub(subId: Int, context: Context): Context {
        if (demoModeController.isInDemoMode) {
            return createMobileContextForDemoMode(context)
        }

        // Fail back to the given context if no sub exists
        val info = subscriptions[subId] ?: return context

        return createCarrierConfigContext(context, info.mcc, info.mnc)
    }

    /** For Demo mode (for now), just apply the same MCC/MNC override for all subIds */
    private fun createMobileContextForDemoMode(context: Context): Context {
        return createCarrierConfigContext(context, demoMcc ?: 0, demoMnc ?: 0)
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println(
            "Subscriptions below will be inflated with a configuration context with " +
                "MCC/MNC overrides"
        )
        subscriptions.forEach { (subId, info) ->
            pw.println("  Subscription with subId($subId) with MCC/MNC(${info.mcc}/${info.mnc})")
        }
        pw.println("  MCC override: ${demoMcc ?: "(none)"}")
        pw.println("  MNC override: ${demoMnc ?: "(none)"}")
    }

    override fun demoCommands(): List<String> {
        return listOf(COMMAND_NETWORK)
    }

    override fun onDemoModeFinished() {
        demoMcc = null
        demoMnc = null
    }

    override fun dispatchDemoCommand(command: String, args: Bundle) {
        val mccmnc = args.getString("mccmnc") ?: return
        // Only length 5/6 strings are valid
        if (!(mccmnc.length == 5 || mccmnc.length == 6)) {
            return
        }

        // MCC is always the first 3 digits, and mnc is the last 2 or 3
        demoMcc = mccmnc.subSequence(0, 3).toString().toInt()
        demoMnc = mccmnc.subSequence(3, mccmnc.length).toString().toInt()
    }

    companion object {
        /**
         * Creates a context based on this [SubscriptionInfo]'s MCC/MNC values, allowing the overlay
         * system to properly load different carrier's iconography
         */
        private fun createCarrierConfigContext(context: Context, mcc: Int, mnc: Int): Context {
            // Copy the existing configuration
            val c = Configuration(context.resources.configuration)
            c.mcc = mcc
            c.mnc = mnc

            return ContextThemeWrapper(context, context.theme).also { ctx ->
                ctx.applyOverrideConfiguration(c)
            }
        }
    }
}
