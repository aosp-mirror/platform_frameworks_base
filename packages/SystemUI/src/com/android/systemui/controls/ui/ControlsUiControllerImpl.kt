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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.service.controls.Control
import android.service.controls.ControlsProviderService
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.Space
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import com.android.systemui.Dumpable
import com.android.systemui.R
import com.android.systemui.controls.ControlsMetricsLogger
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.settings.ControlsSettingsRepository
import com.android.systemui.controls.CustomIconCache
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.controls.controller.StructureInfo
import com.android.systemui.controls.controller.StructureInfo.Companion.EMPTY_COMPONENT
import com.android.systemui.controls.controller.StructureInfo.Companion.EMPTY_STRUCTURE
import com.android.systemui.controls.management.ControlAdapter
import com.android.systemui.controls.management.ControlsEditingActivity
import com.android.systemui.controls.management.ControlsFavoritingActivity
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.management.ControlsProviderSelectorActivity
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.globalactions.GlobalActionsPopupMenu
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.DeviceControlsControllerImpl
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.asIndenting
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.indentIfPossible
import com.android.wm.shell.TaskViewFactory
import dagger.Lazy
import java.io.PrintWriter
import java.text.Collator
import java.util.Optional
import java.util.function.Consumer
import javax.inject.Inject

private data class ControlKey(val componentName: ComponentName, val controlId: String)

@SysUISingleton
class ControlsUiControllerImpl @Inject constructor (
        val controlsController: Lazy<ControlsController>,
        val context: Context,
        @Main val uiExecutor: DelayableExecutor,
        @Background val bgExecutor: DelayableExecutor,
        val controlsListingController: Lazy<ControlsListingController>,
        val controlActionCoordinator: ControlActionCoordinator,
        private val activityStarter: ActivityStarter,
        private val iconCache: CustomIconCache,
        private val controlsMetricsLogger: ControlsMetricsLogger,
        private val keyguardStateController: KeyguardStateController,
        private val userFileManager: UserFileManager,
        private val userTracker: UserTracker,
        private val taskViewFactory: Optional<TaskViewFactory>,
        private val controlsSettingsRepository: ControlsSettingsRepository,
        dumpManager: DumpManager
) : ControlsUiController, Dumpable {

    companion object {
        private const val PREF_COMPONENT = "controls_component"
        private const val PREF_STRUCTURE_OR_APP_NAME = "controls_structure"
        private const val PREF_IS_PANEL = "controls_is_panel"

        private const val FADE_IN_MILLIS = 200L
    }

    private var selectedItem: SelectedItem = SelectedItem.EMPTY_SELECTION
    private lateinit var allStructures: List<StructureInfo>
    private val controlsById = mutableMapOf<ControlKey, ControlWithState>()
    private val controlViewsById = mutableMapOf<ControlKey, ControlViewHolder>()
    private lateinit var parent: ViewGroup
    private var popup: ListPopupWindow? = null
    private var hidden = true
    private lateinit var onDismiss: Runnable
    private val popupThemedContext = ContextThemeWrapper(context, R.style.Control_ListPopupWindow)
    private var retainCache = false
    private var lastSelections = emptyList<SelectionItem>()
    private val sharedPreferences
        get() = userFileManager.getSharedPreferences(
            fileName = DeviceControlsControllerImpl.PREFS_CONTROLS_FILE,
            mode = 0,
            userId = userTracker.userId
        )

    private var taskViewController: PanelTaskViewController? = null

    private val collator = Collator.getInstance(context.resources.configuration.locales[0])
    private val localeComparator = compareBy<SelectionItem, CharSequence>(collator) {
        it.getTitle()
    }

    private val onSeedingComplete = Consumer<Boolean> {
        accepted ->
            if (accepted) {
                selectedItem = controlsController.get().getFavorites().maxByOrNull {
                    it.controls.size
                }?.let {
                    SelectedItem.StructureItem(it)
                } ?: SelectedItem.EMPTY_SELECTION
                updatePreferences(selectedItem)
            }
            reload(parent)
    }

    private lateinit var activityContext: Context
    private lateinit var listingCallback: ControlsListingController.ControlsListingCallback

    init {
        dumpManager.registerDumpable(javaClass.name, this)
    }

    private fun createCallback(
        onResult: (List<SelectionItem>) -> Unit
    ): ControlsListingController.ControlsListingCallback {
        return object : ControlsListingController.ControlsListingCallback {
            override fun onServicesUpdated(serviceInfos: List<ControlsServiceInfo>) {
                val lastItems = serviceInfos.map {
                    val uid = it.serviceInfo.applicationInfo.uid

                    SelectionItem(
                            it.loadLabel(),
                            "",
                            it.loadIcon(),
                            it.componentName,
                            uid,
                            it.panelActivity
                    )
                }
                uiExecutor.execute {
                    parent.removeAllViews()
                    if (lastItems.size > 0) {
                        onResult(lastItems)
                    }
                }
            }
        }
    }

    override fun resolveActivity(): Class<*> {
        val allStructures = controlsController.get().getFavorites()
        val selected = getPreferredSelectedItem(allStructures)
        val anyPanels = controlsListingController.get().getCurrentServices()
                .any { it.panelActivity != null }

        return if (controlsController.get().addSeedingFavoritesCallback(onSeedingComplete)) {
            ControlsActivity::class.java
        } else if (!selected.hasControls && allStructures.size <= 1 && !anyPanels) {
            ControlsProviderSelectorActivity::class.java
        } else {
            ControlsActivity::class.java
        }
    }

    override fun show(
        parent: ViewGroup,
        onDismiss: Runnable,
        activityContext: Context
    ) {
        Log.d(ControlsUiController.TAG, "show()")
        this.parent = parent
        this.onDismiss = onDismiss
        this.activityContext = activityContext
        hidden = false
        retainCache = false

        controlActionCoordinator.activityContext = activityContext

        allStructures = controlsController.get().getFavorites()
        selectedItem = getPreferredSelectedItem(allStructures)

        if (controlsController.get().addSeedingFavoritesCallback(onSeedingComplete)) {
            listingCallback = createCallback(::showSeedingView)
        } else if (
                selectedItem !is SelectedItem.PanelItem &&
                !selectedItem.hasControls &&
                allStructures.size <= 1
        ) {
            // only show initial view if there are really no favorites across any structure
            listingCallback = createCallback(::initialView)
        } else {
            val selected = selectedItem
            if (selected is SelectedItem.StructureItem) {
                selected.structure.controls.map {
                    ControlWithState(selected.structure.componentName, it, null)
                }.associateByTo(controlsById) {
                    ControlKey(selected.structure.componentName, it.ci.controlId)
                }
                controlsController.get().subscribeToFavorites(selected.structure)
            } else {
                controlsController.get().bindComponentForPanel(selected.componentName)
            }
            listingCallback = createCallback(::showControlsView)
        }

        controlsListingController.get().addCallback(listingCallback)
    }

    private fun initialView(items: List<SelectionItem>) {
        if (items.any { it.isPanel }) {
            // We have at least a panel, so we'll end up showing that.
            showControlsView(items)
        } else {
            showInitialSetupView(items)
        }
    }

    private fun reload(parent: ViewGroup) {
        if (hidden) return

        controlsListingController.get().removeCallback(listingCallback)
        controlsController.get().unsubscribe()
        taskViewController?.dismiss()
        taskViewController = null

        val fadeAnim = ObjectAnimator.ofFloat(parent, "alpha", 1.0f, 0.0f)
        fadeAnim.setInterpolator(AccelerateInterpolator(1.0f))
        fadeAnim.setDuration(FADE_IN_MILLIS)
        fadeAnim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                controlViewsById.clear()
                controlsById.clear()

                show(parent, onDismiss, activityContext)
                val showAnim = ObjectAnimator.ofFloat(parent, "alpha", 0.0f, 1.0f)
                showAnim.setInterpolator(DecelerateInterpolator(1.0f))
                showAnim.setDuration(FADE_IN_MILLIS)
                showAnim.start()
            }
        })
        fadeAnim.start()
    }

    private fun showSeedingView(items: List<SelectionItem>) {
        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.controls_no_favorites, parent, true)
        val subtitle = parent.requireViewById<TextView>(R.id.controls_subtitle)
        subtitle.setText(context.resources.getString(R.string.controls_seeding_in_progress))
    }

    private fun showInitialSetupView(items: List<SelectionItem>) {
        startProviderSelectorActivity()
        onDismiss.run()
    }

    private fun startFavoritingActivity(si: StructureInfo) {
        startTargetedActivity(si, ControlsFavoritingActivity::class.java)
    }

    private fun startEditingActivity(si: StructureInfo) {
        startTargetedActivity(si, ControlsEditingActivity::class.java)
    }

    private fun startTargetedActivity(si: StructureInfo, klazz: Class<*>) {
        val i = Intent(activityContext, klazz)
        putIntentExtras(i, si)
        startActivity(i)

        retainCache = true
    }

    private fun putIntentExtras(intent: Intent, si: StructureInfo) {
        intent.apply {
            putExtra(ControlsFavoritingActivity.EXTRA_APP,
                    controlsListingController.get().getAppLabel(si.componentName))
            putExtra(ControlsFavoritingActivity.EXTRA_STRUCTURE, si.structure)
            putExtra(Intent.EXTRA_COMPONENT_NAME, si.componentName)
        }
    }

    private fun startProviderSelectorActivity() {
        val i = Intent(activityContext, ControlsProviderSelectorActivity::class.java)
        i.putExtra(ControlsProviderSelectorActivity.BACK_SHOULD_EXIT, true)
        startActivity(i)
    }

    private fun startActivity(intent: Intent) {
        // Force animations when transitioning from a dialog to an activity
        intent.putExtra(ControlsUiController.EXTRA_ANIMATE, true)

        if (keyguardStateController.isShowing()) {
            activityStarter.postStartActivityDismissingKeyguard(intent, 0 /* delay */)
        } else {
            activityContext.startActivity(
                intent,
                ActivityOptions.makeSceneTransitionAnimation(activityContext as Activity).toBundle()
            )
        }
    }

    private fun showControlsView(items: List<SelectionItem>) {
        controlViewsById.clear()

        val (panels, structures) = items.partition { it.isPanel }
        val panelComponents = panels.map { it.componentName }.toSet()

        val itemsByComponent = structures.associateBy { it.componentName }
                .filterNot { it.key in panelComponents }
        val panelsAndStructures = mutableListOf<SelectionItem>()
        allStructures.mapNotNullTo(panelsAndStructures) {
            itemsByComponent.get(it.componentName)?.copy(structure = it.structure)
        }
        panelsAndStructures.addAll(panels)

        panelsAndStructures.sortWith(localeComparator)

        lastSelections = panelsAndStructures

        val selectionItem = findSelectionItem(selectedItem, panelsAndStructures)
                ?: if (panels.isNotEmpty()) {
                    // If we couldn't find a good selected item, but there's at least one panel,
                    // show a panel.
                    panels[0]
                } else {
                    items[0]
                }
        maybeUpdateSelectedItem(selectionItem)

        createControlsSpaceFrame()

        if (taskViewFactory.isPresent && selectionItem.isPanel) {
            createPanelView(selectionItem.panelComponentName!!)
        } else if (!selectionItem.isPanel) {
            controlsMetricsLogger
                    .refreshBegin(selectionItem.uid, !keyguardStateController.isUnlocked())
            createListView(selectionItem)
        } else {
            Log.w(ControlsUiController.TAG, "Not TaskViewFactory to display panel $selectionItem")
        }

        createDropDown(panelsAndStructures, selectionItem)
        createMenu()
    }

    private fun createPanelView(componentName: ComponentName) {
        val setting = controlsSettingsRepository
                .allowActionOnTrivialControlsInLockscreen.value
        val pendingIntent = PendingIntent.getActivityAsUser(
                context,
                0,
                Intent()
                        .setComponent(componentName)
                        .putExtra(
                                ControlsProviderService.EXTRA_LOCKSCREEN_ALLOW_TRIVIAL_CONTROLS,
                                setting
                        ),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                null,
                userTracker.userHandle
        )

        parent.requireViewById<View>(R.id.controls_scroll_view).visibility = View.GONE
        val container = parent.requireViewById<FrameLayout>(R.id.controls_panel)
        container.visibility = View.VISIBLE
        container.post {
            taskViewFactory.get().create(activityContext, uiExecutor) { taskView ->
                taskViewController = PanelTaskViewController(
                        activityContext,
                        uiExecutor,
                        pendingIntent,
                        taskView,
                        onDismiss::run
                ).also {
                    container.addView(taskView)
                    it.launchTaskView()
                }
            }
        }
    }

    private fun createMenu() {
        val isPanel = selectedItem is SelectedItem.PanelItem
        val selectedStructure = (selectedItem as? SelectedItem.StructureItem)?.structure
                ?: EMPTY_STRUCTURE

        val items = if (isPanel) {
            arrayOf(
                    context.resources.getString(R.string.controls_menu_add),
            )
        } else {
            arrayOf(
                    context.resources.getString(R.string.controls_menu_add),
                    context.resources.getString(R.string.controls_menu_edit)
            )
        }
        var adapter = ArrayAdapter<String>(context, R.layout.controls_more_item, items)

        val anchor = parent.requireViewById<ImageView>(R.id.controls_more)
        anchor.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                popup = GlobalActionsPopupMenu(
                        popupThemedContext,
                        false /* isDropDownMode */
                ).apply {
                    setAnchorView(anchor)
                    setAdapter(adapter)
                    setOnItemClickListener(object : AdapterView.OnItemClickListener {
                        override fun onItemClick(
                            parent: AdapterView<*>,
                            view: View,
                            pos: Int,
                            id: Long
                        ) {
                            when (pos) {
                                // 0: Add Control
                                0 -> {
                                    if (isPanel) {
                                        startProviderSelectorActivity()
                                    } else {
                                        startFavoritingActivity(selectedStructure)
                                    }
                                }
                                // 1: Edit controls
                                1 -> startEditingActivity(selectedStructure)
                            }
                            dismiss()
                        }
                    })
                    show()
                }
            }
        })
    }

    private fun createDropDown(items: List<SelectionItem>, selected: SelectionItem) {
        items.forEach {
            RenderInfo.registerComponentIcon(it.componentName, it.icon)
        }

        var adapter = ItemAdapter(context, R.layout.controls_spinner_item).apply {
            addAll(items)
        }

        val iconSize = context.resources
                .getDimensionPixelSize(R.dimen.controls_header_app_icon_size)

        /*
         * Default spinner widget does not work with the window type required
         * for this dialog. Use a textView with the ListPopupWindow to achieve
         * a similar effect
         */
        val spinner = parent.requireViewById<TextView>(R.id.app_or_structure_spinner).apply {
            setText(selected.getTitle())
            // override the default color on the dropdown drawable
            (getBackground() as LayerDrawable).getDrawable(0)
                .setTint(context.resources.getColor(R.color.control_spinner_dropdown, null))
            selected.icon.setBounds(0, 0, iconSize, iconSize)
            compoundDrawablePadding = (iconSize / 2.4f).toInt()
            setCompoundDrawablesRelative(selected.icon, null, null, null)
        }

        val anchor = parent.requireViewById<ViewGroup>(R.id.controls_header)
        if (items.size == 1) {
            spinner.setBackground(null)
            anchor.setOnClickListener(null)
            return
        } else {
            spinner.background = parent.context.resources
                    .getDrawable(R.drawable.control_spinner_background)
        }

        anchor.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                popup = GlobalActionsPopupMenu(
                        popupThemedContext,
                        true /* isDropDownMode */
                ).apply {
                    setAnchorView(anchor)
                    setAdapter(adapter)

                    setOnItemClickListener(object : AdapterView.OnItemClickListener {
                        override fun onItemClick(
                            parent: AdapterView<*>,
                            view: View,
                            pos: Int,
                            id: Long
                        ) {
                            val listItem = parent.getItemAtPosition(pos) as SelectionItem
                            this@ControlsUiControllerImpl.switchAppOrStructure(listItem)
                            dismiss()
                        }
                    })
                    show()
                }
            }
        })
    }

    private fun createControlsSpaceFrame() {
        val inflater = LayoutInflater.from(activityContext)
        inflater.inflate(R.layout.controls_with_favorites, parent, true)

        parent.requireViewById<ImageView>(R.id.controls_close).apply {
            setOnClickListener { _: View -> onDismiss.run() }
            visibility = View.VISIBLE
        }
    }

    private fun createListView(selected: SelectionItem) {
        if (selectedItem !is SelectedItem.StructureItem) return
        val selectedStructure = (selectedItem as SelectedItem.StructureItem).structure
        val inflater = LayoutInflater.from(activityContext)

        val maxColumns = ControlAdapter.findMaxColumns(activityContext.resources)

        val listView = parent.requireViewById(R.id.global_actions_controls_list) as ViewGroup
        var lastRow: ViewGroup = createRow(inflater, listView)
        selectedStructure.controls.forEach {
            val key = ControlKey(selectedStructure.componentName, it.controlId)
            controlsById.get(key)?.let {
                if (lastRow.getChildCount() == maxColumns) {
                    lastRow = createRow(inflater, listView)
                }
                val baseLayout = inflater.inflate(
                    R.layout.controls_base_item, lastRow, false) as ViewGroup
                lastRow.addView(baseLayout)

                // Use ConstraintLayout in the future... for now, manually adjust margins
                if (lastRow.getChildCount() == 1) {
                    val lp = baseLayout.getLayoutParams() as ViewGroup.MarginLayoutParams
                    lp.setMarginStart(0)
                }
                val cvh = ControlViewHolder(
                    baseLayout,
                    controlsController.get(),
                    uiExecutor,
                    bgExecutor,
                    controlActionCoordinator,
                    controlsMetricsLogger,
                    selected.uid
                )
                cvh.bindData(it, false /* isLocked, will be ignored on initial load */)
                controlViewsById.put(key, cvh)
            }
        }

        // add spacers if necessary to keep control size consistent
        val mod = selectedStructure.controls.size % maxColumns
        var spacersToAdd = if (mod == 0) 0 else maxColumns - mod
        val margin = context.resources.getDimensionPixelSize(R.dimen.control_spacing)
        while (spacersToAdd > 0) {
            val lp = LinearLayout.LayoutParams(0, 0, 1f).apply {
                setMarginStart(margin)
            }
            lastRow.addView(Space(context), lp)
            spacersToAdd--
        }
    }

    override fun getPreferredSelectedItem(structures: List<StructureInfo>): SelectedItem {
        val sp = sharedPreferences

        val component = sp.getString(PREF_COMPONENT, null)?.let {
            ComponentName.unflattenFromString(it)
        } ?: EMPTY_COMPONENT
        val name = sp.getString(PREF_STRUCTURE_OR_APP_NAME, "")!!
        val isPanel = sp.getBoolean(PREF_IS_PANEL, false)
        return if (isPanel) {
            SelectedItem.PanelItem(name, component)
        } else {
            if (structures.isEmpty()) return SelectedItem.EMPTY_SELECTION
            SelectedItem.StructureItem(structures.firstOrNull {
                component == it.componentName && name == it.structure
            } ?: structures.get(0))
        }
    }

    private fun updatePreferences(si: SelectedItem) {
        sharedPreferences.edit()
                .putString(PREF_COMPONENT, si.componentName.flattenToString())
                .putString(PREF_STRUCTURE_OR_APP_NAME, si.name.toString())
                .putBoolean(PREF_IS_PANEL, si is SelectedItem.PanelItem)
                .commit()
    }

    private fun maybeUpdateSelectedItem(item: SelectionItem): Boolean {
        val newSelection = if (item.isPanel) {
            SelectedItem.PanelItem(item.appName, item.componentName)
        } else {
            SelectedItem.StructureItem(allStructures.firstOrNull {
                it.structure == item.structure && it.componentName == item.componentName
            } ?: EMPTY_STRUCTURE)
        }
        return if (newSelection != selectedItem ) {
            selectedItem = newSelection
            updatePreferences(selectedItem)
            true
        } else {
            false
        }
    }

    private fun switchAppOrStructure(item: SelectionItem) {
        if (maybeUpdateSelectedItem(item)) {
            reload(parent)
        }
    }

    override fun closeDialogs(immediately: Boolean) {
        if (immediately) {
            popup?.dismissImmediate()
        } else {
            popup?.dismiss()
        }
        popup = null

        controlViewsById.forEach {
            it.value.dismiss()
        }
        controlActionCoordinator.closeDialogs()
    }

    override fun hide() {
        hidden = true

        closeDialogs(true)
        controlsController.get().unsubscribe()
        taskViewController?.dismiss()
        taskViewController = null

        parent.removeAllViews()
        controlsById.clear()
        controlViewsById.clear()

        controlsListingController.get().removeCallback(listingCallback)

        if (!retainCache) RenderInfo.clearCache()
    }

    override fun onRefreshState(componentName: ComponentName, controls: List<Control>) {
        val isLocked = !keyguardStateController.isUnlocked()
        controls.forEach { c ->
            controlsById.get(ControlKey(componentName, c.getControlId()))?.let {
                Log.d(ControlsUiController.TAG, "onRefreshState() for id: " + c.getControlId())
                iconCache.store(componentName, c.controlId, c.customIcon)
                val cws = ControlWithState(componentName, it.ci, c)
                val key = ControlKey(componentName, c.getControlId())
                controlsById.put(key, cws)

                controlViewsById.get(key)?.let {
                    uiExecutor.execute { it.bindData(cws, isLocked) }
                }
            }
        }
    }

    override fun onActionResponse(componentName: ComponentName, controlId: String, response: Int) {
        val key = ControlKey(componentName, controlId)
        uiExecutor.execute {
            controlViewsById.get(key)?.actionResponse(response)
        }
    }

    private fun createRow(inflater: LayoutInflater, listView: ViewGroup): ViewGroup {
        val row = inflater.inflate(R.layout.controls_row, listView, false) as ViewGroup
        listView.addView(row)
        return row
    }

    private fun findSelectionItem(si: SelectedItem, items: List<SelectionItem>): SelectionItem? =
        items.firstOrNull { it.matches(si) }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("ControlsUiControllerImpl:")
        pw.asIndenting().indentIfPossible {
            println("hidden: $hidden")
            println("selectedItem: $selectedItem")
            println("lastSelections: $lastSelections")
            println("setting: ${controlsSettingsRepository
                    .allowActionOnTrivialControlsInLockscreen.value}")
        }
    }
}

@VisibleForTesting
internal data class SelectionItem(
    val appName: CharSequence,
    val structure: CharSequence,
    val icon: Drawable,
    val componentName: ComponentName,
    val uid: Int,
    val panelComponentName: ComponentName?
) {
    fun getTitle() = if (structure.isEmpty()) { appName } else { structure }

    val isPanel: Boolean = panelComponentName != null

    fun matches(selectedItem: SelectedItem): Boolean {
        if (componentName != selectedItem.componentName) {
            // Not the same component so they are not the same.
            return false
        }
        if (isPanel || selectedItem is SelectedItem.PanelItem) {
            // As they have the same component, if [this.isPanel] then we may be migrating from
            // device controls API into panel. Want this to match, even if the selectedItem is not
            // a panel. We don't want to match on app name because that can change with locale.
            return true
        }
        // Return true if we find a structure with the correct name
        return structure == (selectedItem as SelectedItem.StructureItem).structure.structure
    }
}

private class ItemAdapter(
    val parentContext: Context,
    val resource: Int
) : ArrayAdapter<SelectionItem>(parentContext, resource) {

    val layoutInflater = LayoutInflater.from(context)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val item = getItem(position)
        val view = convertView ?: layoutInflater.inflate(resource, parent, false)
        view.requireViewById<TextView>(R.id.controls_spinner_item).apply {
            setText(item.getTitle())
        }
        view.requireViewById<ImageView>(R.id.app_icon).apply {
            setImageDrawable(item.icon)
        }
        return view
    }
}
