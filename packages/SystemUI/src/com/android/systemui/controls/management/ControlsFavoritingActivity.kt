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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.viewpager2.widget.ViewPager2
import com.android.systemui.Prefs
import com.android.systemui.R
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.TooltipManager
import com.android.systemui.controls.controller.ControlsControllerImpl
import com.android.systemui.controls.controller.StructureInfo
import com.android.systemui.controls.ui.ControlsActivity
import com.android.systemui.controls.ui.ControlsUiController
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.CurrentUserTracker
import java.text.Collator
import java.util.concurrent.Executor
import java.util.function.Consumer
import javax.inject.Inject

class ControlsFavoritingActivity @Inject constructor(
    @Main private val executor: Executor,
    private val controller: ControlsControllerImpl,
    private val listingController: ControlsListingController,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val uiController: ControlsUiController
) : ComponentActivity() {

    companion object {
        private const val TAG = "ControlsFavoritingActivity"

        // If provided and no structure is available, use as the title
        const val EXTRA_APP = "extra_app_label"

        // If provided, show this structure page first
        const val EXTRA_STRUCTURE = "extra_structure"
        const val EXTRA_SINGLE_STRUCTURE = "extra_single_structure"
        internal const val EXTRA_FROM_PROVIDER_SELECTOR = "extra_from_provider_selector"
        private const val TOOLTIP_PREFS_KEY = Prefs.Key.CONTROLS_STRUCTURE_SWIPE_TOOLTIP_COUNT
        private const val TOOLTIP_MAX_SHOWN = 2
    }

    private var component: ComponentName? = null
    private var appName: CharSequence? = null
    private var structureExtra: CharSequence? = null
    private var fromProviderSelector = false

    private lateinit var structurePager: ViewPager2
    private lateinit var statusText: TextView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var pageIndicator: ManagementPageIndicator
    private var mTooltipManager: TooltipManager? = null
    private lateinit var doneButton: View
    private lateinit var otherAppsButton: View
    private var listOfStructures = emptyList<StructureContainer>()

    private lateinit var comparator: Comparator<StructureContainer>
    private var cancelLoadRunnable: Runnable? = null
    private var isPagerLoaded = false

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

        override fun onServicesUpdated(serviceInfos: List<ControlsServiceInfo>) {
            if (serviceInfos.size > 1) {
                otherAppsButton.post {
                    otherAppsButton.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onBackPressed() {
        if (!fromProviderSelector) {
            openControlsOrigin()
        }
        animateExitAndFinish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val collator = Collator.getInstance(resources.configuration.locales[0])
        comparator = compareBy(collator) { it.structureName }
        appName = intent.getCharSequenceExtra(EXTRA_APP)
        structureExtra = intent.getCharSequenceExtra(EXTRA_STRUCTURE)
        component = intent.getParcelableExtra<ComponentName>(Intent.EXTRA_COMPONENT_NAME)
        fromProviderSelector = intent.getBooleanExtra(EXTRA_FROM_PROVIDER_SELECTOR, false)

        bindViews()
    }

    private val controlsModelCallback = object : ControlsModel.ControlsModelCallback {
        override fun onFirstChange() {
            doneButton.isEnabled = true
        }
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
                    StructureContainer(it.key, AllModel(
                            it.value, favoriteKeys, emptyZoneString, controlsModelCallback))
                }.sortedWith(comparator)

                val structureIndex = listOfStructures.indexOfFirst {
                    sc -> sc.structureName == structureExtra
                }.let { if (it == -1) 0 else it }

                // If we were requested to show a single structure, set the list to just that one
                if (intent.getBooleanExtra(EXTRA_SINGLE_STRUCTURE, false)) {
                    listOfStructures = listOf(listOfStructures[structureIndex])
                }

                executor.execute {
                    structurePager.adapter = StructureAdapter(listOfStructures)
                    structurePager.setCurrentItem(structureIndex)
                    if (error) {
                        statusText.text = resources.getString(R.string.controls_favorite_load_error,
                                appName ?: "")
                        subtitleView.visibility = View.GONE
                    } else if (listOfStructures.isEmpty()) {
                        statusText.text = resources.getString(R.string.controls_favorite_load_none)
                        subtitleView.visibility = View.GONE
                    } else {
                        statusText.visibility = View.GONE

                        pageIndicator.setNumPages(listOfStructures.size)
                        pageIndicator.setLocation(0f)
                        pageIndicator.visibility =
                            if (listOfStructures.size > 1) View.VISIBLE else View.INVISIBLE

                        ControlsAnimations.enterAnimation(pageIndicator).apply {
                            addListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator?) {
                                    // Position the tooltip if necessary after animations are complete
                                    // so we can get the position on screen. The tooltip is not
                                    // rooted in the layout root.
                                    if (pageIndicator.visibility == View.VISIBLE &&
                                        mTooltipManager != null) {
                                        val p = IntArray(2)
                                        pageIndicator.getLocationOnScreen(p)
                                        val x = p[0] + pageIndicator.width / 2
                                        val y = p[1] + pageIndicator.height
                                        mTooltipManager?.show(
                                            R.string.controls_structure_tooltip, x, y)
                                    }
                                }
                            })
                        }.start()
                        ControlsAnimations.enterAnimation(structurePager).start()
                    }
                }
            }, Consumer { runnable -> cancelLoadRunnable = runnable })
        }
    }

    private fun setUpPager() {
        structurePager.alpha = 0.0f
        pageIndicator.alpha = 0.0f
        structurePager.apply {
            adapter = StructureAdapter(emptyList())
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    val name = listOfStructures[position].structureName
                    val title = if (!TextUtils.isEmpty(name)) name else appName
                    titleView.text = title
                    titleView.requestFocus()
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

        getLifecycle().addObserver(
            ControlsAnimations.observerForAnimations(
                requireViewById<ViewGroup>(R.id.controls_management_root),
                window,
                intent
            )
        )

        requireViewById<ViewStub>(R.id.stub).apply {
            layoutResource = R.layout.controls_management_favorites
            inflate()
        }

        statusText = requireViewById(R.id.status_message)
        if (shouldShowTooltip()) {
            mTooltipManager = TooltipManager(statusText.context,
                TOOLTIP_PREFS_KEY, TOOLTIP_MAX_SHOWN)
            addContentView(
                mTooltipManager?.layout,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.LEFT
                )
            )
        }
        pageIndicator = requireViewById<ManagementPageIndicator>(
            R.id.structure_page_indicator).apply {
            visibilityListener = {
                if (it != View.VISIBLE) {
                    mTooltipManager?.hide(true)
                }
            }
        }

        val title = structureExtra
            ?: (appName ?: resources.getText(R.string.controls_favorite_default_title))
        titleView = requireViewById<TextView>(R.id.title).apply {
            text = title
        }
        subtitleView = requireViewById<TextView>(R.id.subtitle).apply {
            text = resources.getText(R.string.controls_favorite_subtitle)
        }
        structurePager = requireViewById<ViewPager2>(R.id.structure_pager)
        structurePager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                mTooltipManager?.hide(true)
            }
        })
        bindButtons()
    }

    private fun animateExitAndFinish() {
        val rootView = requireViewById<ViewGroup>(R.id.controls_management_root)
        ControlsAnimations.exitAnimation(
                rootView,
                object : Runnable {
                    override fun run() {
                        finish()
                    }
                }
        ).start()
    }

    private fun bindButtons() {
        otherAppsButton = requireViewById<Button>(R.id.other_apps).apply {
            setOnClickListener {
                if (doneButton.isEnabled) {
                    // The user has made changes
                    Toast.makeText(
                            applicationContext,
                            R.string.controls_favorite_toast_no_changes,
                            Toast.LENGTH_SHORT
                            ).show()
                }
                startActivity(
                    Intent(context, ControlsProviderSelectorActivity::class.java),
                    ActivityOptions
                        .makeSceneTransitionAnimation(this@ControlsFavoritingActivity).toBundle()
                )
                animateExitAndFinish()
            }
        }

        doneButton = requireViewById<Button>(R.id.done).apply {
            isEnabled = false
            setOnClickListener {
                if (component == null) return@setOnClickListener
                listOfStructures.forEach {
                    val favoritesForStorage = it.model.favorites
                    controller.replaceFavoritesForStructure(
                        StructureInfo(component!!, it.structureName, favoritesForStorage)
                    )
                }
                animateExitAndFinish()
                openControlsOrigin()
            }
        }
    }

    private fun openControlsOrigin() {
        startActivity(
            Intent(applicationContext, ControlsActivity::class.java),
            ActivityOptions.makeSceneTransitionAnimation(this).toBundle()
        )
    }

    override fun onPause() {
        super.onPause()
        mTooltipManager?.hide(false)
    }

    override fun onStart() {
        super.onStart()

        listingController.addCallback(listingCallback)
        currentUserTracker.startTracking()
    }

    override fun onResume() {
        super.onResume()

        // only do once, to make sure that any user changes do not get replaces if resume is called
        // more than once
        if (!isPagerLoaded) {
            setUpPager()
            loadControls()
            isPagerLoaded = true
        }
    }

    override fun onStop() {
        super.onStop()

        listingController.removeCallback(listingCallback)
        currentUserTracker.stopTracking()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mTooltipManager?.hide(false)
    }

    override fun onDestroy() {
        cancelLoadRunnable?.run()
        super.onDestroy()
    }

    private fun shouldShowTooltip(): Boolean {
        return Prefs.getInt(applicationContext, TOOLTIP_PREFS_KEY, 0) < TOOLTIP_MAX_SHOWN
    }
}

data class StructureContainer(val structureName: CharSequence, val model: ControlsModel)
