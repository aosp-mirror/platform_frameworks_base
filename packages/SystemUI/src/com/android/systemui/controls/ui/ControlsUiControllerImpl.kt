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
import android.app.AlertDialog
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Process
import android.provider.Settings
import android.service.controls.Control
import android.service.controls.actions.ControlAction
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.Space
import android.widget.TextView
import com.android.systemui.R
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.controller.ControlInfo
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.controls.controller.StructureInfo
import com.android.systemui.controls.management.ControlsEditingActivity
import com.android.systemui.controls.management.ControlsFavoritingActivity
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.management.ControlsProviderSelectorActivity
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.concurrency.DelayableExecutor
import dagger.Lazy
import java.text.Collator
import java.util.function.Consumer
import javax.inject.Inject
import javax.inject.Singleton

private data class ControlKey(val componentName: ComponentName, val controlId: String)

@Singleton
class ControlsUiControllerImpl @Inject constructor (
    val controlsController: Lazy<ControlsController>,
    val context: Context,
    @Main val uiExecutor: DelayableExecutor,
    @Background val bgExecutor: DelayableExecutor,
    val controlsListingController: Lazy<ControlsListingController>,
    @Main val sharedPreferences: SharedPreferences
) : ControlsUiController {

    companion object {
        private const val PREF_COMPONENT = "controls_component"
        private const val PREF_STRUCTURE = "controls_structure"

        private const val USE_PANELS = "systemui.controls_use_panel"
        private const val FADE_IN_MILLIS = 225L

        private val EMPTY_COMPONENT = ComponentName("", "")
        private val EMPTY_STRUCTURE = StructureInfo(
            EMPTY_COMPONENT,
            "",
            mutableListOf<ControlInfo>()
        )
    }

    private var selectedStructure: StructureInfo = EMPTY_STRUCTURE
    private lateinit var allStructures: List<StructureInfo>
    private val controlsById = mutableMapOf<ControlKey, ControlWithState>()
    private val controlViewsById = mutableMapOf<ControlKey, ControlViewHolder>()
    private lateinit var parent: ViewGroup
    private lateinit var lastItems: List<SelectionItem>
    private var popup: ListPopupWindow? = null
    private var activeDialog: Dialog? = null
    private var hidden = true

    override val available: Boolean
        get() = controlsController.get().available

    private lateinit var listingCallback: ControlsListingController.ControlsListingCallback

    private fun createCallback(
        onResult: (List<SelectionItem>) -> Unit
    ): ControlsListingController.ControlsListingCallback {
        return object : ControlsListingController.ControlsListingCallback {
            override fun onServicesUpdated(serviceInfos: List<ControlsServiceInfo>) {
                bgExecutor.execute {
                    val collator = Collator.getInstance(context.resources.configuration.locales[0])
                    val localeComparator = compareBy<ControlsServiceInfo, CharSequence>(collator) {
                        it.loadLabel()
                    }

                    val mList = serviceInfos.toMutableList()
                    mList.sortWith(localeComparator)
                    lastItems = mList.map {
                        SelectionItem(it.loadLabel(), "", it.loadIcon(), it.componentName)
                    }
                    uiExecutor.execute {
                        onResult(lastItems)
                    }
                }
            }
        }
    }

    override fun show(parent: ViewGroup) {
        Log.d(ControlsUiController.TAG, "show()")
        this.parent = parent
        hidden = false

        allStructures = controlsController.get().getFavorites()
        selectedStructure = loadPreference(allStructures)

        val cb = Consumer<Boolean> { _ -> reload(parent) }
        if (controlsController.get().addSeedingFavoritesCallback(cb)) {
            listingCallback = createCallback(::showSeedingView)
        } else if (selectedStructure.controls.isEmpty() && allStructures.size <= 1) {
            // only show initial view if there are really no favorites across any structure
            listingCallback = createCallback(::showInitialSetupView)
        } else {
            selectedStructure.controls.map {
                ControlWithState(selectedStructure.componentName, it, null)
            }.associateByTo(controlsById) {
                ControlKey(selectedStructure.componentName, it.ci.controlId)
            }
            listingCallback = createCallback(::showControlsView)
            controlsController.get().subscribeToFavorites(selectedStructure)
        }

        controlsListingController.get().addCallback(listingCallback)
    }

    private fun reload(parent: ViewGroup) {
        if (hidden) return

        val fadeAnim = ObjectAnimator.ofFloat(parent, "alpha", 1.0f, 0.0f)
        fadeAnim.setInterpolator(AccelerateInterpolator(1.0f))
        fadeAnim.setDuration(FADE_IN_MILLIS)
        fadeAnim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                show(parent)
                val showAnim = ObjectAnimator.ofFloat(parent, "alpha", 0.0f, 1.0f)
                showAnim.setInterpolator(DecelerateInterpolator(1.0f))
                showAnim.setDuration(FADE_IN_MILLIS)
                showAnim.start()
            }
        })
        fadeAnim.start()
    }

    private fun showSeedingView(items: List<SelectionItem>) {
        parent.removeAllViews()

        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.controls_no_favorites, parent, true)
        val subtitle = parent.requireViewById<TextView>(R.id.controls_subtitle)
        subtitle.setText(context.resources.getString(R.string.controls_seeding_in_progress))
    }

    private fun showInitialSetupView(items: List<SelectionItem>) {
        parent.removeAllViews()

        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.controls_no_favorites, parent, true)

        val viewGroup = parent.requireViewById(R.id.controls_no_favorites_group) as ViewGroup
        viewGroup.setOnClickListener { v: View -> startProviderSelectorActivity(v.context) }

        val subtitle = parent.requireViewById<TextView>(R.id.controls_subtitle)
        subtitle.setText(context.resources.getString(R.string.quick_controls_subtitle))

        val iconRowGroup = parent.requireViewById(R.id.controls_icon_row) as ViewGroup
        items.forEach {
            val imageView = inflater.inflate(R.layout.controls_icon, viewGroup, false) as ImageView
            imageView.setContentDescription(it.getTitle())
            imageView.setImageDrawable(it.icon)
            iconRowGroup.addView(imageView)
        }
    }

    override fun onFocusChanged(focusedControl: ControlWithState?) {
        controlViewsById.forEach { key: ControlKey, viewHolder: ControlViewHolder ->
            val state = controlsById.get(key) ?: return@forEach
            val shouldBeDimmed = focusedControl != null && state != focusedControl
            if (viewHolder.dimmed == shouldBeDimmed) {
                return@forEach
            }

            uiExecutor.execute {
                viewHolder.dimmed = shouldBeDimmed
            }
        }
    }

    private fun startFavoritingActivity(context: Context, si: StructureInfo) {
        startTargetedActivity(context, si, ControlsFavoritingActivity::class.java)
    }

    private fun startEditingActivity(context: Context, si: StructureInfo) {
        startTargetedActivity(context, si, ControlsEditingActivity::class.java)
    }

    private fun startTargetedActivity(context: Context, si: StructureInfo, klazz: Class<*>) {
        val i = Intent(context, klazz).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        putIntentExtras(i, si)
        startActivity(context, i)
    }

    private fun putIntentExtras(intent: Intent, si: StructureInfo) {
        intent.apply {
            putExtra(ControlsFavoritingActivity.EXTRA_APP,
                    controlsListingController.get().getAppLabel(si.componentName))
            putExtra(ControlsFavoritingActivity.EXTRA_STRUCTURE, si.structure)
            putExtra(Intent.EXTRA_COMPONENT_NAME, si.componentName)
        }
    }

    private fun startProviderSelectorActivity(context: Context) {
        val i = Intent(context, ControlsProviderSelectorActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(context, i)
    }

    private fun startActivity(context: Context, intent: Intent) {
        val closeDialog = Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        context.sendBroadcast(closeDialog)
        context.startActivity(intent)
    }

    private fun showControlsView(items: List<SelectionItem>) {
        parent.removeAllViews()
        controlViewsById.clear()

        createListView()
        createDropDown(items)
        createMenu()
    }

    private fun createPopup(): ListPopupWindow {
        return ListPopupWindow(
            ContextThemeWrapper(context, R.style.Control_ListPopupWindow)).apply {
            setWindowLayoutType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY)
            setModal(true)
        }
    }

    private fun createMenu() {
        val items = arrayOf(
            context.resources.getString(R.string.controls_menu_add),
            context.resources.getString(R.string.controls_menu_edit),
            "Reset"
        )
        var adapter = ArrayAdapter<String>(context, R.layout.controls_more_item, items)

        val anchor = parent.requireViewById<ImageView>(R.id.controls_more)
        anchor.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                popup = createPopup().apply {
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
                                0 -> startFavoritingActivity(view.context, selectedStructure)
                                // 1: Edit controls
                                1 -> startEditingActivity(view.context, selectedStructure)
                                // 2: TEMPORARY for reset controls
                                2 -> showResetConfirmation()
                                else -> Log.w(ControlsUiController.TAG,
                                    "Unsupported index ($pos) on 'more' menu selection")
                            }
                            dismiss()
                        }
                    })
                    // need to call show() first in order to construct the listView
                    show()
                    var width = 0
                    getListView()?.apply {
                        // width should be between [.5, .9] of screen
                        val parentWidth = this@ControlsUiControllerImpl.parent.getWidth()
                        val widthSpec = MeasureSpec.makeMeasureSpec(
                            (parentWidth * 0.9).toInt(), MeasureSpec.AT_MOST)
                        val child = adapter.getView(0, null, this)
                        child.measure(widthSpec, MeasureSpec.UNSPECIFIED)
                        width = Math.max(child.getMeasuredWidth(), (parentWidth * 0.5).toInt())
                    }
                    setWidth(width)
                    setHorizontalOffset(-width + anchor.getWidth())
                    show()
                }
            }
        })
    }

    private fun showResetConfirmation() {
        val builder = AlertDialog.Builder(
            context,
            android.R.style.Theme_DeviceDefault_Dialog_Alert
        ).apply {
            setMessage("For testing purposes: Would you like to " +
                "reset your favorited device controls?")
            setPositiveButton(
                android.R.string.ok,
                DialogInterface.OnClickListener { dialog, _ ->
                    val userHandle = Process.myUserHandle()
                    val userContext = context.createContextAsUser(userHandle, 0)
                    val prefs = userContext.getSharedPreferences(
                        "controls_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("ControlsSeedingCompleted", false).apply()
                    controlsController.get().resetFavorites()
                    dialog.dismiss()
                    context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
            })
            setNegativeButton(
                android.R.string.cancel,
                DialogInterface.OnClickListener {
                    dialog, _ -> dialog.cancel()
                }
            )
        }
        builder.create().apply {
            getWindow().apply {
                setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY)
            }
        }.show()
    }

    private fun createDropDown(items: List<SelectionItem>) {
        items.forEach {
            RenderInfo.registerComponentIcon(it.componentName, it.icon)
        }

        val itemsByComponent = items.associateBy { it.componentName }
        val itemsWithStructure = allStructures.mapNotNull {
            itemsByComponent.get(it.componentName)?.copy(structure = it.structure)
        }
        val selectionItem = findSelectionItem(selectedStructure, itemsWithStructure) ?: items[0]

        var adapter = ItemAdapter(context, R.layout.controls_spinner_item).apply {
            addAll(itemsWithStructure)
        }

        /*
         * Default spinner widget does not work with the window type required
         * for this dialog. Use a textView with the ListPopupWindow to achieve
         * a similar effect
         */
        val spinner = parent.requireViewById<TextView>(R.id.app_or_structure_spinner).apply {
            setText(selectionItem.getTitle())
            // override the default color on the dropdown drawable
            (getBackground() as LayerDrawable).getDrawable(1)
                .setTint(context.resources.getColor(R.color.control_spinner_dropdown, null))
        }
        parent.requireViewById<ImageView>(R.id.app_icon).apply {
            setImageDrawable(selectionItem.icon)
        }

        if (itemsWithStructure.size == 1) {
            spinner.setBackground(null)
            return
        }

        val anchor = parent.requireViewById<ViewGroup>(R.id.controls_header)
        anchor.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                popup = createPopup().apply {
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
                    // need to call show() first in order to construct the listView
                    show()
                    getListView()?.apply {
                        setDividerHeight(
                            context.resources.getDimensionPixelSize(R.dimen.control_list_divider))
                        setDivider(
                            context.resources.getDrawable(R.drawable.controls_list_divider))
                    }
                    show()
                }
            }
        })
    }

    private fun createListView() {
        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.controls_with_favorites, parent, true)

        val maxColumns = findMaxColumns()

        // use flag only temporarily for testing
        val usePanels = Settings.Secure.getInt(context.contentResolver, USE_PANELS, 0) == 1

        val listView = parent.requireViewById(R.id.global_actions_controls_list) as ViewGroup
        var lastRow: ViewGroup = createRow(inflater, listView)
        selectedStructure.controls.forEach {
            if (lastRow.getChildCount() == maxColumns) {
                lastRow = createRow(inflater, listView)
            }
            val baseLayout = inflater.inflate(
                R.layout.controls_base_item, lastRow, false) as ViewGroup
            lastRow.addView(baseLayout)
            val cvh = ControlViewHolder(
                baseLayout,
                controlsController.get(),
                uiExecutor,
                bgExecutor,
                usePanels
            )
            val key = ControlKey(selectedStructure.componentName, it.controlId)
            cvh.bindData(controlsById.getValue(key))
            controlViewsById.put(key, cvh)
        }

        // add spacers if necessary to keep control size consistent
        val mod = selectedStructure.controls.size % maxColumns
        var spacersToAdd = if (mod == 0) 0 else maxColumns - mod
        while (spacersToAdd > 0) {
            lastRow.addView(Space(context), LinearLayout.LayoutParams(0, 0, 1f))
            spacersToAdd--
        }
    }

    /**
     * For low-dp width screens that also employ an increased font scale, adjust the
     * number of columns. This helps prevent text truncation on these devices.
     */
    private fun findMaxColumns(): Int {
        val res = context.resources
        var maxColumns = res.getInteger(R.integer.controls_max_columns)
        val maxColumnsAdjustWidth =
            res.getInteger(R.integer.controls_max_columns_adjust_below_width_dp)

        val outValue = TypedValue()
        res.getValue(R.dimen.controls_max_columns_adjust_above_font_scale, outValue, true)
        val maxColumnsAdjustFontScale = outValue.getFloat()

        val config = res.configuration
        val isPortrait = config.orientation == Configuration.ORIENTATION_PORTRAIT
        if (isPortrait &&
            config.screenWidthDp != Configuration.SCREEN_WIDTH_DP_UNDEFINED &&
            config.screenWidthDp <= maxColumnsAdjustWidth &&
            config.fontScale >= maxColumnsAdjustFontScale) {
            maxColumns--
        }

        return maxColumns
    }

    private fun loadPreference(structures: List<StructureInfo>): StructureInfo {
        if (structures.isEmpty()) return EMPTY_STRUCTURE

        val component = sharedPreferences.getString(PREF_COMPONENT, null)?.let {
            ComponentName.unflattenFromString(it)
        } ?: EMPTY_COMPONENT
        val structure = sharedPreferences.getString(PREF_STRUCTURE, "")

        return structures.firstOrNull {
            component == it.componentName && structure == it.structure
        } ?: structures.get(0)
    }

    private fun updatePreferences(si: StructureInfo) {
        sharedPreferences.edit()
            .putString(PREF_COMPONENT, si.componentName.flattenToString())
            .putString(PREF_STRUCTURE, si.structure.toString())
            .commit()
    }

    private fun switchAppOrStructure(item: SelectionItem) {
        val newSelection = allStructures.first {
            it.structure == item.structure && it.componentName == item.componentName
        }

        if (newSelection != selectedStructure) {
            selectedStructure = newSelection
            updatePreferences(selectedStructure)
            controlsListingController.get().removeCallback(listingCallback)
            reload(parent)
        }
    }

    override fun hide() {
        Log.d(ControlsUiController.TAG, "hide()")
        hidden = true
        popup?.dismiss()
        activeDialog?.dismiss()
        ControlActionCoordinator.closeDialog()

        controlsController.get().unsubscribe()

        parent.removeAllViews()
        controlsById.clear()
        controlViewsById.clear()
        controlsListingController.get().removeCallback(listingCallback)

        RenderInfo.clearCache()
    }

    override fun onRefreshState(componentName: ComponentName, controls: List<Control>) {
        Log.d(ControlsUiController.TAG, "onRefreshState()")
        controls.forEach { c ->
            controlsById.get(ControlKey(componentName, c.getControlId()))?.let {
                Log.d(ControlsUiController.TAG, "onRefreshState() for id: " + c.getControlId())
                val cws = ControlWithState(componentName, it.ci, c)
                val key = ControlKey(componentName, c.getControlId())
                controlsById.put(key, cws)

                uiExecutor.execute {
                    controlViewsById.get(key)?.bindData(cws)
                }
            }
        }
    }

    override fun onActionResponse(componentName: ComponentName, controlId: String, response: Int) {
        val key = ControlKey(componentName, controlId)
        uiExecutor.execute {
            controlViewsById.get(key)?.let { cvh ->
                when (response) {
                    ControlAction.RESPONSE_CHALLENGE_PIN -> {
                        activeDialog = ChallengeDialogs.createPinDialog(cvh, false)
                        activeDialog?.show()
                    }
                    ControlAction.RESPONSE_CHALLENGE_PASSPHRASE -> {
                        activeDialog = ChallengeDialogs.createPinDialog(cvh, true)
                        activeDialog?.show()
                    }
                    ControlAction.RESPONSE_CHALLENGE_ACK -> {
                        activeDialog = ChallengeDialogs.createConfirmationDialog(cvh)
                        activeDialog?.show()
                    }
                    else -> cvh.actionResponse(response)
                }
            }
        }
    }

    private fun createRow(inflater: LayoutInflater, listView: ViewGroup): ViewGroup {
        val row = inflater.inflate(R.layout.controls_row, listView, false) as ViewGroup
        listView.addView(row)
        return row
    }

    private fun findSelectionItem(si: StructureInfo, items: List<SelectionItem>): SelectionItem? =
        items.firstOrNull {
            it.componentName == si.componentName && it.structure == si.structure
        }
}

private data class SelectionItem(
    val appName: CharSequence,
    val structure: CharSequence,
    val icon: Drawable,
    val componentName: ComponentName
) {
    fun getTitle() = if (structure.isEmpty()) { appName } else { structure }
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
