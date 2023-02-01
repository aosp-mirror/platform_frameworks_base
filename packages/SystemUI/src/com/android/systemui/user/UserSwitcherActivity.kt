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

package com.android.systemui.user

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.constraintlayout.helper.widget.Flow
import androidx.lifecycle.ViewModelProvider
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.util.UserIcons
import com.android.settingslib.Utils
import com.android.systemui.Gefingerpoken
import com.android.systemui.R
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.FalsingManager.LOW_PENALTY
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.BaseUserSwitcherAdapter
import com.android.systemui.statusbar.policy.UserSwitcherController
import com.android.systemui.user.data.source.UserRecord
import com.android.systemui.user.ui.binder.UserSwitcherViewBinder
import com.android.systemui.user.ui.viewmodel.UserSwitcherViewModel
import dagger.Lazy
import javax.inject.Inject
import kotlin.math.ceil

private const val USER_VIEW = "user_view"

/** Support a fullscreen user switcher */
open class UserSwitcherActivity
@Inject
constructor(
    private val userSwitcherController: UserSwitcherController,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val falsingCollector: FalsingCollector,
    private val falsingManager: FalsingManager,
    private val userManager: UserManager,
    private val userTracker: UserTracker,
    private val flags: FeatureFlags,
    private val viewModelFactory: Lazy<UserSwitcherViewModel.Factory>,
) : ComponentActivity() {

    private lateinit var parent: UserSwitcherRootView
    private lateinit var broadcastReceiver: BroadcastReceiver
    private var popupMenu: UserSwitcherPopupMenu? = null
    private lateinit var addButton: View
    private var addUserRecords = mutableListOf<UserRecord>()
    private val onBackCallback = OnBackInvokedCallback { finish() }
    private val userSwitchedCallback: UserTracker.Callback =
        object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) {
                finish()
            }
        }
    // When the add users options become available, insert another option to manage users
    private val manageUserRecord =
        UserRecord(
            null /* info */,
            null /* picture */,
            false /* isGuest */,
            false /* isCurrent */,
            false /* isAddUser */,
            false /* isRestricted */,
            false /* isSwitchToEnabled */,
            false /* isAddSupervisedUser */
        )

    private val adapter: UserAdapter by lazy { UserAdapter() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createActivity()
    }

    @VisibleForTesting
    fun createActivity() {
        setContentView(R.layout.user_switcher_fullscreen)
        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
        if (isUsingModernArchitecture()) {
            Log.d(TAG, "Using modern architecture.")
            val viewModel =
                ViewModelProvider(this, viewModelFactory.get())[UserSwitcherViewModel::class.java]
            UserSwitcherViewBinder.bind(
                view = requireViewById(R.id.user_switcher_root),
                viewModel = viewModel,
                lifecycleOwner = this,
                layoutInflater = layoutInflater,
                falsingCollector = falsingCollector,
                onFinish = this::finish,
            )
            return
        } else {
            Log.d(TAG, "Not using modern architecture.")
        }

        parent = requireViewById<UserSwitcherRootView>(R.id.user_switcher_root)

        parent.touchHandler =
            object : Gefingerpoken {
                override fun onTouchEvent(ev: MotionEvent?): Boolean {
                    falsingCollector.onTouchEvent(ev)
                    return false
                }
            }

        requireViewById<View>(R.id.cancel).apply { setOnClickListener { _ -> finish() } }

        addButton =
            requireViewById<View>(R.id.add).apply { setOnClickListener { _ -> showPopupMenu() } }

        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            onBackCallback
        )

        userSwitcherController.init(parent)
        initBroadcastReceiver()

        parent.post { buildUserViews() }
        userTracker.addCallback(userSwitchedCallback, mainExecutor)
    }

    private fun showPopupMenu() {
        val items = mutableListOf<UserRecord>()
        addUserRecords.forEach { items.add(it) }

        var popupMenuAdapter =
            ItemAdapter(
                this,
                R.layout.user_switcher_fullscreen_popup_item,
                layoutInflater,
                { item: UserRecord -> adapter.getName(this@UserSwitcherActivity, item, true) },
                { item: UserRecord ->
                    adapter.findUserIcon(item, true).mutate().apply {
                        setTint(
                            resources.getColor(
                                R.color.user_switcher_fullscreen_popup_item_tint,
                                getTheme()
                            )
                        )
                    }
                }
            )
        popupMenuAdapter.addAll(items)

        popupMenu =
            UserSwitcherPopupMenu(this).apply {
                setAnchorView(addButton)
                setAdapter(popupMenuAdapter)
                setOnItemClickListener { parent: AdapterView<*>, view: View, pos: Int, id: Long ->
                    if (falsingManager.isFalseTap(LOW_PENALTY) || !view.isEnabled()) {
                        return@setOnItemClickListener
                    }
                    // -1 for the header
                    val item = popupMenuAdapter.getItem(pos - 1)
                    if (item == manageUserRecord) {
                        val i = Intent().setAction(Settings.ACTION_USER_SETTINGS)
                        this@UserSwitcherActivity.startActivity(i)
                    } else {
                        adapter.onUserListItemClicked(item)
                    }

                    dismiss()
                    popupMenu = null

                    if (!item.isAddUser) {
                        this@UserSwitcherActivity.finish()
                    }
                }

                show()
            }
    }

    private fun buildUserViews() {
        var count = 0
        var start = 0
        for (i in 0 until parent.getChildCount()) {
            if (parent.getChildAt(i).getTag() == USER_VIEW) {
                if (count == 0) start = i
                count++
            }
        }
        parent.removeViews(start, count)
        addUserRecords.clear()
        val flow = requireViewById<Flow>(R.id.flow)
        val totalWidth = parent.width
        val userViewCount = adapter.getTotalUserViews()
        val maxColumns = getMaxColumns(userViewCount)
        val horizontalGap =
            resources.getDimensionPixelSize(R.dimen.user_switcher_fullscreen_horizontal_gap)
        val totalWidthOfHorizontalGap = (maxColumns - 1) * horizontalGap
        val maxWidgetDiameter = (totalWidth - totalWidthOfHorizontalGap) / maxColumns

        flow.setMaxElementsWrap(maxColumns)

        for (i in 0 until adapter.getCount()) {
            val item = adapter.getItem(i)
            if (adapter.doNotRenderUserView(item)) {
                addUserRecords.add(item)
            } else {
                val userView = adapter.getView(i, null, parent)
                userView.requireViewById<ImageView>(R.id.user_switcher_icon).apply {
                    val lp = layoutParams
                    if (maxWidgetDiameter < lp.width) {
                        lp.width = maxWidgetDiameter
                        lp.height = maxWidgetDiameter
                        layoutParams = lp
                    }
                }

                userView.setId(View.generateViewId())
                parent.addView(userView)

                // Views must have an id and a parent in order for Flow to lay them out
                flow.addView(userView)

                userView.setOnClickListener { v ->
                    if (falsingManager.isFalseTap(LOW_PENALTY) || !v.isEnabled()) {
                        return@setOnClickListener
                    }

                    if (!item.isCurrent || item.isGuest) {
                        adapter.onUserListItemClicked(item)
                    }
                }
            }
        }

        if (!addUserRecords.isEmpty()) {
            addUserRecords.add(manageUserRecord)
            addButton.visibility = View.VISIBLE
        } else {
            addButton.visibility = View.GONE
        }
    }

    override fun onBackPressed() {
        if (isUsingModernArchitecture()) {
            return super.onBackPressed()
        }

        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isUsingModernArchitecture()) {
            return
        }
        destroyActivity()
    }

    @VisibleForTesting
    fun destroyActivity() {
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(onBackCallback)
        broadcastDispatcher.unregisterReceiver(broadcastReceiver)
        userTracker.removeCallback(userSwitchedCallback)
    }

    private fun initBroadcastReceiver() {
        broadcastReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val action = intent.getAction()
                    if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        finish()
                    }
                }
            }

        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        broadcastDispatcher.registerReceiver(broadcastReceiver, filter)
    }

    @VisibleForTesting
    fun getMaxColumns(userCount: Int): Int {
        return if (userCount < 5) 4 else ceil(userCount / 2.0).toInt()
    }

    private fun isUsingModernArchitecture(): Boolean {
        return flags.isEnabled(Flags.MODERN_USER_SWITCHER_ACTIVITY)
    }

    /** Provides views to populate the option menu. */
    private class ItemAdapter(
        val parentContext: Context,
        val resource: Int,
        val layoutInflater: LayoutInflater,
        val textGetter: (UserRecord) -> String,
        val iconGetter: (UserRecord) -> Drawable
    ) : ArrayAdapter<UserRecord>(parentContext, resource) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = getItem(position)
            val view = convertView ?: layoutInflater.inflate(resource, parent, false)

            view.requireViewById<ImageView>(R.id.icon).apply { setImageDrawable(iconGetter(item)) }
            view.requireViewById<TextView>(R.id.text).apply { setText(textGetter(item)) }

            return view
        }
    }

    private inner class UserAdapter : BaseUserSwitcherAdapter(userSwitcherController) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = getItem(position)
            var view = convertView as ViewGroup?
            if (view == null) {
                view =
                    layoutInflater.inflate(R.layout.user_switcher_fullscreen_item, parent, false)
                        as ViewGroup
            }
            (view.getChildAt(0) as ImageView).apply { setImageDrawable(getDrawable(item)) }
            (view.getChildAt(1) as TextView).apply { setText(getName(getContext(), item)) }

            view.setEnabled(item.isSwitchToEnabled)
            UserSwitcherController.setSelectableAlpha(view)
            view.setTag(USER_VIEW)
            return view
        }

        override fun getName(context: Context, item: UserRecord, isTablet: Boolean): String {
            return if (item == manageUserRecord) {
                getString(R.string.manage_users)
            } else {
                super.getName(context, item, isTablet)
            }
        }

        fun findUserIcon(item: UserRecord, isTablet: Boolean = false): Drawable {
            if (item == manageUserRecord) {
                return getDrawable(R.drawable.ic_manage_users)
            }
            if (item.info == null) {
                return getIconDrawable(this@UserSwitcherActivity, item, isTablet)
            }
            val userIcon = userManager.getUserIcon(item.info.id)
            if (userIcon != null) {
                return BitmapDrawable(userIcon)
            }
            return UserIcons.getDefaultUserIcon(resources, item.info.id, false)
        }

        fun getTotalUserViews(): Int {
            return users.count { item -> !doNotRenderUserView(item) }
        }

        fun doNotRenderUserView(item: UserRecord): Boolean {
            return item.isAddUser || item.isAddSupervisedUser || item.isGuest && item.info == null
        }

        private fun getDrawable(item: UserRecord): Drawable {
            var drawable =
                if (item.isGuest) {
                    getDrawable(R.drawable.ic_account_circle)
                } else {
                    findUserIcon(item)
                }
            drawable.mutate()

            if (!item.isCurrent && !item.isSwitchToEnabled) {
                drawable.setTint(
                    resources.getColor(
                        R.color.kg_user_switcher_restricted_avatar_icon_color,
                        getTheme()
                    )
                )
            }

            val ld = getDrawable(R.drawable.user_switcher_icon_large).mutate() as LayerDrawable
            if (item == userSwitcherController.currentUserRecord) {
                (ld.findDrawableByLayerId(R.id.ring) as GradientDrawable).apply {
                    val stroke =
                        resources.getDimensionPixelSize(R.dimen.user_switcher_icon_selected_width)
                    val color =
                        Utils.getColorAttrDefaultColor(
                            this@UserSwitcherActivity,
                            com.android.internal.R.attr.colorAccentPrimary
                        )

                    setStroke(stroke, color)
                }
            }

            ld.setDrawableByLayerId(R.id.user_avatar, drawable)
            return ld
        }

        override fun notifyDataSetChanged() {
            super.notifyDataSetChanged()
            buildUserViews()
        }
    }

    companion object {
        private const val TAG = "UserSwitcherActivity"
    }
}
