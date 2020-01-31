/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.controls.ui

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.service.controls.Control
import android.service.controls.TokenProvider
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView

import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.controls.controller.ControlInfo
import com.android.systemui.controls.management.ControlsProviderSelectorActivity
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.R

import dagger.Lazy

import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ControlsUi"

// TEMP CODE for MOCK
private const val TOKEN = "https://www.googleapis.com/auth/assistant"
private const val SCOPE = "oauth2:" + TOKEN
private var tokenProviderConnection: TokenProviderConnection? = null
class TokenProviderConnection(val cc: ControlsController, val context: Context)
    : ServiceConnection {
    private var mTokenProvider: TokenProvider? = null

    override fun onServiceConnected(cName: ComponentName, binder: IBinder) {
        Thread({
            Log.i(TAG, "TokenProviderConnection connected")
            mTokenProvider = TokenProvider.Stub.asInterface(binder)

            val mLastAccountName = mTokenProvider?.getAccountName()

            if (mLastAccountName == null || mLastAccountName.isEmpty()) {
                Log.e(TAG, "NO ACCOUNT IS SET. Open HomeMock app")
            } else {
                mTokenProvider?.setAuthToken(getAuthToken(mLastAccountName))
                cc.subscribeToFavorites()
            }
        }, "TokenProviderThread").start()
    }

    override fun onServiceDisconnected(cName: ComponentName) {
        mTokenProvider = null
    }

    fun getAuthToken(accountName: String): String? {
        val am = AccountManager.get(context)
        val accounts = am.getAccountsByType("com.google")
        if (accounts == null || accounts.size == 0) {
            Log.w(TAG, "No com.google accounts found")
            return null
        }

        var account: Account? = null
        for (a in accounts) {
            if (a.name.equals(accountName)) {
                account = a
                break
            }
        }

        if (account == null) {
            account = accounts[0]
        }

        try {
            return am.blockingGetAuthToken(account!!, SCOPE, true)
        } catch (e: Throwable) {
            Log.e(TAG, "Error getting auth token", e)
            return null
        }
    }
}

@Singleton
class ControlsUiControllerImpl @Inject constructor (
    val controlsController: Lazy<ControlsController>,
    val context: Context,
    @Main val uiExecutor: Executor
) : ControlsUiController {

    private lateinit var controlInfos: List<ControlInfo>
    private val controlsById = mutableMapOf<Pair<ComponentName, String>, ControlWithState>()
    private val controlViewsById = mutableMapOf<String, ControlViewHolder>()
    private lateinit var parent: ViewGroup

    override fun show(parent: ViewGroup) {
        Log.d(TAG, "show()")

        this.parent = parent

        controlInfos = controlsController.get().getFavoriteControls()

        controlInfos.map {
            ControlWithState(it, null)
        }.associateByTo(controlsById) { Pair(it.ci.component, it.ci.controlId) }

        if (controlInfos.isEmpty()) {
            showInitialSetupView()
        } else {
            showControlsView()
        }

        // Temp code to pass auth
        tokenProviderConnection = TokenProviderConnection(controlsController.get(), context)
        val serviceIntent = Intent()
        serviceIntent.setComponent(ComponentName("com.android.systemui.home.mock",
                "com.android.systemui.home.mock.AuthService"))
        context.bindService(serviceIntent, tokenProviderConnection!!, Context.BIND_AUTO_CREATE)
    }

    private fun showInitialSetupView() {
        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.controls_no_favorites, parent, true)

        val textView = parent.requireViewById(R.id.controls_title) as TextView
        textView.setOnClickListener(launchSelectorActivityListener(context))
    }

    private fun launchSelectorActivityListener(context: Context): (View) -> Unit {
        return { _ ->
            val closeDialog = Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            context.sendBroadcast(closeDialog)

            val i = Intent()
            i.setComponent(ComponentName(context, ControlsProviderSelectorActivity::class.java))
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }

    private fun showControlsView() {
        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.controls_with_favorites, parent, true)

        val listView = parent.requireViewById(R.id.global_actions_controls_list) as ViewGroup
        var lastRow: ViewGroup = createRow(inflater, listView)
        controlInfos.forEach {
            Log.d(TAG, "favorited control id: " + it.controlId)
            if (lastRow.getChildCount() == 2) {
                lastRow = createRow(inflater, listView)
            }
            val item = inflater.inflate(
                R.layout.controls_base_item, lastRow, false) as ViewGroup
            lastRow.addView(item)
            val cvh = ControlViewHolder(item, controlsController.get())
            cvh.bindData(controlsById.get(Pair(it.component, it.controlId))!!)
            controlViewsById.put(it.controlId, cvh)
        }

        if ((controlInfos.size % 2) == 1) {
            lastRow.addView(Space(context), LinearLayout.LayoutParams(0, 0, 1f))
        }

        val moreImageView = parent.requireViewById(R.id.controls_more) as View
        moreImageView.setOnClickListener(launchSelectorActivityListener(context))
    }

    override fun hide() {
        Log.d(TAG, "hide()")
        controlsController.get().unsubscribe()
        context.unbindService(tokenProviderConnection)
        tokenProviderConnection = null

        parent.removeAllViews()
        controlsById.clear()
        controlViewsById.clear()
    }

    override fun onRefreshState(componentName: ComponentName, controls: List<Control>) {
        Log.d(TAG, "onRefreshState()")
        controls.forEach { c ->
            controlsById.get(Pair(componentName, c.getControlId()))?.let {
                Log.d(TAG, "onRefreshState() for id: " + c.getControlId())
                val cws = ControlWithState(it.ci, c)
                controlsById.put(Pair(componentName, c.getControlId()), cws)

                uiExecutor.execute {
                    controlViewsById.get(c.getControlId())?.bindData(cws)
                }
            }
        }
    }

    override fun onActionResponse(componentName: ComponentName, controlId: String, response: Int) {
        Log.d(TAG, "onActionResponse()")
        TODO("not implemented")
    }

    private fun createRow(inflater: LayoutInflater, parent: ViewGroup): ViewGroup {
        val row = inflater.inflate(R.layout.controls_row, parent, false) as ViewGroup
        parent.addView(row)
        return row
    }
}
