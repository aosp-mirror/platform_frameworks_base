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
 *
 */

package com.android.systemui.user.ui.binder

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.SHOW_DIVIDER_MIDDLE
import android.widget.TextView
import androidx.constraintlayout.helper.widget.Flow as FlowWidget
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.Gefingerpoken
import com.android.systemui.R
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.user.UserSwitcherPopupMenu
import com.android.systemui.user.UserSwitcherRootView
import com.android.systemui.user.shared.model.UserActionModel
import com.android.systemui.user.ui.viewmodel.UserActionViewModel
import com.android.systemui.user.ui.viewmodel.UserSwitcherViewModel
import com.android.systemui.util.children
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Binds a user switcher to its view-model. */
object UserSwitcherViewBinder {

    private const val USER_VIEW_TAG = "user_view"

    /** Binds the given view to the given view-model. */
    fun bind(
        view: ViewGroup,
        viewModel: UserSwitcherViewModel,
        layoutInflater: LayoutInflater,
        falsingCollector: FalsingCollector,
        onFinish: () -> Unit,
    ) {
        val gridContainerView: UserSwitcherRootView =
            view.requireViewById(R.id.user_switcher_grid_container)
        val flowWidget: FlowWidget = gridContainerView.requireViewById(R.id.flow)
        val addButton: View = view.requireViewById(R.id.add)
        val cancelButton: View = view.requireViewById(R.id.cancel)
        val popupMenuAdapter = MenuAdapter(layoutInflater)
        var popupMenu: UserSwitcherPopupMenu? = null

        gridContainerView.touchHandler =
            object : Gefingerpoken {
                override fun onTouchEvent(ev: MotionEvent?): Boolean {
                    falsingCollector.onTouchEvent(ev)
                    return false
                }
            }
        addButton.setOnClickListener { viewModel.onOpenMenuButtonClicked() }
        cancelButton.setOnClickListener { viewModel.onCancelButtonClicked() }

        view.repeatWhenAttached {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    launch {
                        viewModel.isFinishRequested
                            .filter { it }
                            .collect {
                                //finish requested, we want to dismiss popupmenu at the same time
                                popupMenu?.dismiss()
                                onFinish()
                                viewModel.onFinished()
                            }
                    }
                }
            }

            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch { viewModel.isOpenMenuButtonVisible.collect { addButton.isVisible = it } }

                    launch {
                        viewModel.isMenuVisible.collect { isVisible ->
                            if (isVisible && popupMenu?.isShowing != true) {
                                popupMenu?.dismiss()
                                // Use post to make sure we show the popup menu *after* the activity is
                                // ready to show one to avoid a WindowManager$BadTokenException.
                                view.post {
                                    popupMenu =
                                        createAndShowPopupMenu(
                                            context = view.context,
                                            anchorView = addButton,
                                            adapter = popupMenuAdapter,
                                            onDismissed = viewModel::onMenuClosed,
                                        )
                                }
                            } else if (!isVisible && popupMenu?.isShowing == true) {
                                popupMenu?.dismiss()
                                popupMenu = null
                            }
                        }
                    }

                    launch {
                        viewModel.menu.collect { menuViewModels ->
                            popupMenuAdapter.setItems(menuViewModels)
                        }
                    }

                    launch {
                        viewModel.maximumUserColumns.collect { maximumColumns ->
                            flowWidget.setMaxElementsWrap(maximumColumns)
                        }
                    }

                    launch {
                        viewModel.users.collect { users ->
                            val viewPool =
                                gridContainerView.children
                                    .filter { it.tag == USER_VIEW_TAG }
                                    .toMutableList()
                            viewPool.forEach {
                                gridContainerView.removeView(it)
                                flowWidget.removeView(it)
                            }
                            users.forEach { userViewModel ->
                                val userView =
                                    if (viewPool.isNotEmpty()) {
                                        viewPool.removeAt(0)
                                    } else {
                                        val inflatedView =
                                            layoutInflater.inflate(
                                                R.layout.user_switcher_fullscreen_item,
                                                view,
                                                false,
                                            )
                                        inflatedView.tag = USER_VIEW_TAG
                                        inflatedView
                                    }
                                userView.id = View.generateViewId()
                                gridContainerView.addView(userView)
                                flowWidget.addView(userView)
                                UserViewBinder.bind(
                                    view = userView,
                                    viewModel = userViewModel,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun createAndShowPopupMenu(
        context: Context,
        anchorView: View,
        adapter: MenuAdapter,
        onDismissed: () -> Unit,
    ): UserSwitcherPopupMenu {
        return UserSwitcherPopupMenu(context).apply {
            this.setDropDownGravity(Gravity.END)
            this.anchorView = anchorView
            setAdapter(adapter)
            setOnDismissListener { onDismissed() }
            show()
        }
    }

    /** Adapter for the menu that can be opened. */
    private class MenuAdapter(
        private val layoutInflater: LayoutInflater,
    ) : BaseAdapter() {

        private var sections = listOf<List<UserActionViewModel>>()

        override fun getCount(): Int {
            return sections.size
        }

        override fun getItem(position: Int): List<UserActionViewModel> {
            return sections[position]
        }

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val section = getItem(position)
            val context = parent.context
            val sectionView =
                convertView as? LinearLayout
                    ?: LinearLayout(context, null).apply {
                        this.orientation = LinearLayout.VERTICAL
                        this.background =
                            parent.resources.getDrawable(
                                R.drawable.bouncer_user_switcher_popup_bg,
                                context.theme
                            )
                        this.showDividers = SHOW_DIVIDER_MIDDLE
                        this.dividerDrawable =
                            context.getDrawable(
                                R.drawable.fullscreen_userswitcher_menu_item_divider
                            )
                    }
            sectionView.removeAllViewsInLayout()

            for (viewModel in section) {
                val view =
                    layoutInflater.inflate(
                        R.layout.user_switcher_fullscreen_popup_item,
                        /* parent= */ null
                    )
                view
                    .requireViewById<ImageView>(R.id.icon)
                    .setImageResource(viewModel.iconResourceId)
                view.requireViewById<TextView>(R.id.text).text =
                    view.resources.getString(viewModel.textResourceId)
                view.setOnClickListener { viewModel.onClicked() }
                sectionView.addView(view)
            }
            return sectionView
        }

        fun setItems(items: List<UserActionViewModel>) {
            val primarySection =
                items.filter {
                    it.viewKey != UserActionModel.NAVIGATE_TO_USER_MANAGEMENT.ordinal.toLong()
                }
            val secondarySection =
                items.filter {
                    it.viewKey == UserActionModel.NAVIGATE_TO_USER_MANAGEMENT.ordinal.toLong()
                }
            this.sections = listOf(primarySection, secondarySection)
            notifyDataSetChanged()
        }
    }
}
