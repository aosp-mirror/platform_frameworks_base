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

package com.android.systemui.controls.management

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.ViewStub
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.viewpager2.widget.ViewPager2
import com.android.systemui.R
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.controller.ControlsControllerImpl
import com.android.systemui.controls.controller.StructureInfo
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.PageIndicator
import com.android.systemui.settings.CurrentUserTracker
import java.text.Collator
import java.util.concurrent.Executor
import java.util.function.Consumer
import javax.inject.Inject

class ControlsFavoritingActivity @Inject constructor(
    @Main private val executor: Executor,
    private val controller: ControlsControllerImpl,
    private val listingController: ControlsListingController,
    broadcastDispatcher: BroadcastDispatcher
) : Activity() {

    companion object {
        private const val TAG = "ControlsFavoritingActivity"
        const val EXTRA_APP = "extra_app_label"
    }

    private var component: ComponentName? = null
    private var appName: CharSequence? = null

    private lateinit var structurePager: ViewPager2
    private lateinit var statusText: TextView
    private lateinit var titleView: TextView
    private lateinit var iconView: ImageView
    private lateinit var iconFrame: View
    private lateinit var pageIndicator: PageIndicator
    private var listOfStructures = emptyList<StructureContainer>()

    private lateinit var comparator: Comparator<StructureContainer>

    private val currentUserTracker = object : CurrentUserTracker(broadcastDispatcher) {
        private val startingUser = controller.currentUserId

        override fun onUserSwitched(newUserId: Int) {
            if (newUserId != startingUser) {
                stopTracking()
                finish()
            }
        }
    }

    private val listingCallback = object : ControlsListingController.ControlsListingCallback {
        private var icon: Drawable? = null

        override fun onServicesUpdated(serviceInfos: List<ControlsServiceInfo>) {
            val newIcon = serviceInfos.firstOrNull { it.componentName == component }?.loadIcon()
            if (icon == newIcon) return
            icon = newIcon
            executor.execute {
                if (icon != null) {
                    iconView.setImageDrawable(icon)
                }
                iconFrame.visibility = if (icon != null) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onBackPressed() {
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val collator = Collator.getInstance(resources.configuration.locales[0])
        comparator = compareBy(collator) { it.structureName }
        appName = intent.getCharSequenceExtra(EXTRA_APP)
        component = intent.getParcelableExtra<ComponentName>(Intent.EXTRA_COMPONENT_NAME)

        bindViews()

        setUpPager()

        loadControls()

        listingController.addCallback(listingCallback)

        currentUserTracker.startTracking()
    }

    private fun loadControls() {
        component?.let {
            statusText.text = resources.getText(com.android.internal.R.string.loading)
            val emptyZoneString = resources.getText(
                    R.string.controls_favorite_other_zone_header)
            controller.loadForComponent(it, Consumer { data ->
                val allControls = data.allControls
                val favoriteKeys = data.favoritesIds
                val error = data.errorOnLoad
                val controlsByStructure = allControls.groupBy { it.control.structure ?: "" }
                listOfStructures = controlsByStructure.map {
                    StructureContainer(it.key, AllModel(it.value, favoriteKeys, emptyZoneString))
                }.sortedWith(comparator)
                executor.execute {
                    structurePager.adapter = StructureAdapter(listOfStructures)
                    if (error) {
                        statusText.text = resources.getText(R.string.controls_favorite_load_error)
                    } else {
                        statusText.visibility = View.GONE
                    }
                    pageIndicator.setNumPages(listOfStructures.size)
                    pageIndicator.setLocation(0f)
                    pageIndicator.visibility =
                        if (listOfStructures.size > 1) View.VISIBLE else View.GONE
                }
            })
        }
    }

    private fun setUpPager() {
        structurePager.apply {
            adapter = StructureAdapter(emptyList())
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    val name = listOfStructures[position].structureName
                    titleView.text = if (!TextUtils.isEmpty(name)) name else appName
                }

                override fun onPageScrolled(
                    position: Int,
                    positionOffset: Float,
                    positionOffsetPixels: Int
                ) {
                    super.onPageScrolled(position, positionOffset, positionOffsetPixels)
                    pageIndicator.setLocation(position + positionOffset)
                }
            })
        }
    }

    private fun bindViews() {
        setContentView(R.layout.controls_management)
        requireViewById<ViewStub>(R.id.stub).apply {
            layoutResource = R.layout.controls_management_favorites
            inflate()
        }

        statusText = requireViewById(R.id.status_message)
        pageIndicator = requireViewById(R.id.structure_page_indicator)

        titleView = requireViewById<TextView>(R.id.title).apply {
            text = appName ?: resources.getText(R.string.controls_favorite_default_title)
        }
        requireViewById<TextView>(R.id.subtitle).text =
                resources.getText(R.string.controls_favorite_subtitle)
        iconView = requireViewById(com.android.internal.R.id.icon)
        iconFrame = requireViewById(R.id.icon_frame)
        structurePager = requireViewById<ViewPager2>(R.id.structure_pager)
        bindButtons()
    }

    private fun bindButtons() {
        requireViewById<Button>(R.id.other_apps).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                this@ControlsFavoritingActivity.onBackPressed()
            }
        }

        requireViewById<Button>(R.id.done).setOnClickListener {
            if (component == null) return@setOnClickListener
            listOfStructures.forEach {
                val favoritesForStorage = it.model.favorites.map { it.build() }
                controller.replaceFavoritesForStructure(StructureInfo(component!!, it.structureName,
                        favoritesForStorage))
            }

            finishAffinity()
        }
    }

    override fun onDestroy() {
        currentUserTracker.stopTracking()
        listingController.removeCallback(listingCallback)
        super.onDestroy()
    }
}

data class StructureContainer(val structureName: CharSequence, val model: ControlsModel)
