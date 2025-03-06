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

package com.android.systemui.privacy

import android.content.Context
import android.content.Intent
import android.content.pm.PackageItemInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.content.res.Resources.NotFoundException
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.WorkerThread
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.android.settingslib.Utils
import com.android.systemui.res.R
import com.android.systemui.animation.ViewHierarchyAnimator
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.util.maybeForceFullscreen
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dialog to show ongoing and recent app ops element.
 *
 * @param context A context to create the dialog
 * @param list list of elements to show in the dialog. The elements will show in the same order they
 *   appear in the list
 * @param manageApp a callback to start an activity for a given package name, user id, and intent
 * @param closeApp a callback to close an app for a given package name, user id
 * @param openPrivacyDashboard a callback to open the privacy dashboard
 * @see PrivacyDialogControllerV2
 */
class PrivacyDialogV2(
    context: Context,
    private val list: List<PrivacyElement>,
    private val manageApp: (String, Int, Intent) -> Unit,
    private val closeApp: (String, Int) -> Unit,
    private val openPrivacyDashboard: () -> Unit
) : SystemUIDialog(context, R.style.Theme_PrivacyDialog) {

    private val dismissListeners = mutableListOf<WeakReference<OnDialogDismissed>>()
    private val dismissed = AtomicBoolean(false)
    // Note: this will call the dialog create method during init
    private val decorViewLayoutListener = maybeForceFullscreen()?.component2()

    /**
     * Add a listener that will be called when the dialog is dismissed.
     *
     * If the dialog has already been dismissed, the listener will be called immediately, in the
     * same thread.
     */
    fun addOnDismissListener(listener: OnDialogDismissed) {
        if (dismissed.get()) {
            listener.onDialogDismissed()
        } else {
            dismissListeners.add(WeakReference(listener))
        }
    }

    override fun stop() {
        dismissed.set(true)
        val iterator = dismissListeners.iterator()
        while (iterator.hasNext()) {
            val el = iterator.next()
            iterator.remove()
            el.get()?.onDialogDismissed()
        }
        // Remove the layout change listener we may have added to the DecorView.
        if (decorViewLayoutListener != null) {
            window!!.decorView.removeOnLayoutChangeListener(decorViewLayoutListener)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window!!.setGravity(Gravity.CENTER)
        setTitle(R.string.privacy_dialog_title)
        setContentView(R.layout.privacy_dialog_v2)

        val closeButton = requireViewById<Button>(R.id.privacy_dialog_close_button)
        closeButton.setOnClickListener { dismiss() }

        val moreButton = requireViewById<Button>(R.id.privacy_dialog_more_button)
        moreButton.setOnClickListener { openPrivacyDashboard() }

        val itemsContainer = requireViewById<ViewGroup>(R.id.privacy_dialog_items_container)
        list.forEach { itemsContainer.addView(createView(it, itemsContainer)) }
    }

    private fun createView(element: PrivacyElement, itemsContainer: ViewGroup): View {
        val itemCard =
            LayoutInflater.from(context)
                .inflate(R.layout.privacy_dialog_item_v2, itemsContainer, false) as ViewGroup

        updateItemHeader(element, itemCard)

        if (element.isPhoneCall) {
            return itemCard
        }

        setItemExpansionBehavior(itemCard)

        configureIndicatorActionButtons(element, itemCard)

        return itemCard
    }

    private fun updateItemHeader(element: PrivacyElement, itemCard: View) {
        val itemHeader = itemCard.findViewById<ViewGroup>(R.id.privacy_dialog_item_header)!!
        val permGroupLabel = context.packageManager.getDefaultPermGroupLabel(element.permGroupName)

        val iconView = itemHeader.findViewById<ImageView>(R.id.privacy_dialog_item_header_icon)!!
        val indicatorIcon = context.getPermGroupIcon(element.permGroupName)
        updateIconView(iconView, indicatorIcon, element.isActive)
        iconView.contentDescription = permGroupLabel

        val titleView = itemHeader.findViewById<TextView>(R.id.privacy_dialog_item_header_title)!!
        titleView.text = permGroupLabel
        titleView.contentDescription = permGroupLabel

        val usageText = getUsageText(element)
        val summaryView =
            itemHeader.findViewById<TextView>(R.id.privacy_dialog_item_header_summary)!!
        summaryView.text = usageText
        summaryView.contentDescription = usageText
    }

    private fun configureIndicatorActionButtons(element: PrivacyElement, itemCard: View) {
        val expandedLayout =
            itemCard.findViewById<ViewGroup>(R.id.privacy_dialog_item_header_expanded_layout)!!

        val buttons: MutableList<View> = mutableListOf()
        configureCloseAppButton(element, expandedLayout)?.also { buttons.add(it) }
        buttons.add(configureManageButton(element, expandedLayout))

        val backgroundColor = getBackgroundColor(element.isActive)
        when (buttons.size) {
            0 -> return
            1 -> {
                val background =
                    getMutableDrawable(R.drawable.privacy_dialog_background_large_top_large_bottom)
                background.setTint(backgroundColor)
                buttons[0].background = background
            }
            else -> {
                val firstBackground =
                    getMutableDrawable(R.drawable.privacy_dialog_background_large_top_small_bottom)
                val middleBackground =
                    getMutableDrawable(R.drawable.privacy_dialog_background_small_top_small_bottom)
                val lastBackground =
                    getMutableDrawable(R.drawable.privacy_dialog_background_small_top_large_bottom)
                firstBackground.setTint(backgroundColor)
                middleBackground.setTint(backgroundColor)
                lastBackground.setTint(backgroundColor)
                buttons.forEach { it.background = middleBackground }
                buttons.first().background = firstBackground
                buttons.last().background = lastBackground
            }
        }
    }

    private fun configureCloseAppButton(element: PrivacyElement, expandedLayout: ViewGroup): View? {
        if (element.isService || !element.isActive) {
            return null
        }
        val closeAppButton =
            checkNotNull(window).layoutInflater.inflate(
                R.layout.privacy_dialog_card_button,
                expandedLayout,
                false
            ) as Button
        expandedLayout.addView(closeAppButton)
        closeAppButton.id = R.id.privacy_dialog_close_app_button
        closeAppButton.setText(R.string.privacy_dialog_close_app_button)
        closeAppButton.setTextColor(getForegroundColor(true))
        closeAppButton.tag = element
        closeAppButton.setOnClickListener { v ->
            v.tag?.let {
                val element = it as PrivacyElement
                closeApp(element.packageName, element.userId)
                closeAppTransition(element.packageName, element.userId)
            }
        }
        return closeAppButton
    }

    private fun closeAppTransition(packageName: String, userId: Int) {
        val itemsContainer = requireViewById<ViewGroup>(R.id.privacy_dialog_items_container)
        var shouldTransition = false
        for (i in 0 until itemsContainer.getChildCount()) {
            val itemCard = itemsContainer.getChildAt(i)
            val button = itemCard.findViewById<Button>(R.id.privacy_dialog_close_app_button)
            if (button == null || button.tag == null) {
                continue
            }
            val element = button.tag as PrivacyElement
            if (element.packageName != packageName || element.userId != userId) {
                continue
            }

            itemCard.setEnabled(false)

            val expandToggle =
                itemCard.findViewById<ImageView>(R.id.privacy_dialog_item_header_expand_toggle)!!
            expandToggle.visibility = View.GONE

            disableIndicatorCardUi(itemCard, element.applicationName)

            val expandedLayout =
                itemCard.findViewById<View>(R.id.privacy_dialog_item_header_expanded_layout)!!
            if (expandedLayout.visibility == View.VISIBLE) {
                expandedLayout.visibility = View.GONE
                shouldTransition = true
            }
        }
        if (shouldTransition) {
            ViewHierarchyAnimator.animateNextUpdate(window!!.decorView)
        }
    }

    private fun configureManageButton(element: PrivacyElement, expandedLayout: ViewGroup): View {
        val manageButton =
            checkNotNull(window).layoutInflater.inflate(
                R.layout.privacy_dialog_card_button,
                expandedLayout,
                false
            ) as Button
        expandedLayout.addView(manageButton)
        manageButton.id = R.id.privacy_dialog_manage_app_button
        manageButton.setText(
            if (element.isService) R.string.privacy_dialog_manage_service
            else R.string.privacy_dialog_manage_permissions
        )
        manageButton.setTextColor(getForegroundColor(element.isActive))
        manageButton.tag = element
        manageButton.setOnClickListener { v ->
            v.tag?.let {
                val element = it as PrivacyElement
                manageApp(element.packageName, element.userId, element.navigationIntent)
            }
        }
        return manageButton
    }

    private fun disableIndicatorCardUi(itemCard: View, applicationName: CharSequence) {
        val iconView = itemCard.findViewById<ImageView>(R.id.privacy_dialog_item_header_icon)!!
        val indicatorIcon = getMutableDrawable(R.drawable.privacy_dialog_check_icon)
        updateIconView(iconView, indicatorIcon, false)

        val closedAppText =
            context.getString(R.string.privacy_dialog_close_app_message, applicationName)
        val summaryView = itemCard.findViewById<TextView>(R.id.privacy_dialog_item_header_summary)!!
        summaryView.text = closedAppText
        summaryView.contentDescription = closedAppText
    }

    private fun setItemExpansionBehavior(itemCard: ViewGroup) {
        val itemHeader = itemCard.findViewById<ViewGroup>(R.id.privacy_dialog_item_header)!!

        val expandToggle =
            itemHeader.findViewById<ImageView>(R.id.privacy_dialog_item_header_expand_toggle)!!
        expandToggle.setImageResource(R.drawable.privacy_dialog_expand_toggle_down)
        expandToggle.visibility = View.VISIBLE

        ViewCompat.replaceAccessibilityAction(
            itemCard,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            context.getString(R.string.privacy_dialog_expand_action),
            null
        )

        val expandedLayout =
            itemCard.findViewById<View>(R.id.privacy_dialog_item_header_expanded_layout)!!
        expandedLayout.setOnClickListener {
            // Stop clicks from propagating
        }

        itemCard.setOnClickListener {
            if (expandedLayout.visibility == View.VISIBLE) {
                expandedLayout.visibility = View.GONE
                expandToggle.setImageResource(R.drawable.privacy_dialog_expand_toggle_down)
                ViewCompat.replaceAccessibilityAction(
                    it!!,
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                    context.getString(R.string.privacy_dialog_expand_action),
                    null
                )
            } else {
                expandedLayout.visibility = View.VISIBLE
                expandToggle.setImageResource(R.drawable.privacy_dialog_expand_toggle_up)
                ViewCompat.replaceAccessibilityAction(
                    it!!,
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                    context.getString(R.string.privacy_dialog_collapse_action),
                    null
                )
            }
            ViewHierarchyAnimator.animateNextUpdate(
                rootView = window!!.decorView,
                excludedViews = setOf(expandedLayout)
            )
        }
    }

    private fun updateIconView(iconView: ImageView, indicatorIcon: Drawable, active: Boolean) {
        indicatorIcon.setTint(getForegroundColor(active))
        val backgroundIcon = getMutableDrawable(R.drawable.privacy_dialog_background_circle)
        backgroundIcon.setTint(getBackgroundColor(active))
        val backgroundSize =
            context.resources.getDimension(R.dimen.ongoing_appops_dialog_circle_size).toInt()
        val indicatorSize =
            context.resources.getDimension(R.dimen.ongoing_appops_dialog_icon_size).toInt()
        iconView.setImageDrawable(
            constructLayeredIcon(indicatorIcon, indicatorSize, backgroundIcon, backgroundSize)
        )
    }

    @ColorInt
    private fun getForegroundColor(active: Boolean) =
        Utils.getColorAttrDefaultColor(
            context,
            if (active) com.android.internal.R.attr.materialColorOnPrimaryFixed
            else com.android.internal.R.attr.materialColorOnSurface
        )

    @ColorInt
    private fun getBackgroundColor(active: Boolean) =
        Utils.getColorAttrDefaultColor(
            context,
            if (active) com.android.internal.R.attr.materialColorPrimaryFixed
            else com.android.internal.R.attr.materialColorSurfaceContainerHigh
        )

    private fun getMutableDrawable(@DrawableRes resId: Int) = context.getDrawable(resId)!!.mutate()

    private fun getUsageText(element: PrivacyElement) =
        if (element.isPhoneCall) {
            val phoneCallResId =
                if (element.isActive) R.string.privacy_dialog_active_call_usage
                else R.string.privacy_dialog_recent_call_usage
            context.getString(phoneCallResId)
        } else if (element.attributionLabel == null && element.proxyLabel == null) {
            val usageResId: Int =
                if (element.isActive) R.string.privacy_dialog_active_app_usage
                else R.string.privacy_dialog_recent_app_usage
            context.getString(usageResId, element.applicationName)
        } else if (element.attributionLabel == null || element.proxyLabel == null) {
            val singleUsageResId: Int =
                if (element.isActive) R.string.privacy_dialog_active_app_usage_1
                else R.string.privacy_dialog_recent_app_usage_1
            context.getString(
                singleUsageResId,
                element.applicationName,
                element.attributionLabel ?: element.proxyLabel
            )
        } else {
            val doubleUsageResId: Int =
                if (element.isActive) R.string.privacy_dialog_active_app_usage_2
                else R.string.privacy_dialog_recent_app_usage_2
            context.getString(
                doubleUsageResId,
                element.applicationName,
                element.attributionLabel,
                element.proxyLabel
            )
        }

    companion object {
        private const val LOG_TAG = "PrivacyDialogV2"
        private const val REVIEW_PERMISSION_USAGE = "android.intent.action.REVIEW_PERMISSION_USAGE"

        /**
         * Gets a permission group's icon from the system.
         *
         * @param groupName The name of the permission group whose icon we want
         * @return The permission group's icon, the privacy_dialog_default_permission_icon icon if
         *   the group has no icon, or the group does not exist
         */
        @WorkerThread
        private fun Context.getPermGroupIcon(groupName: String): Drawable {
            val groupInfo = packageManager.getGroupInfo(groupName)
            if (groupInfo != null && groupInfo.icon != 0) {
                val icon = packageManager.loadDrawable(groupInfo.packageName, groupInfo.icon)
                if (icon != null) {
                    return icon
                }
            }

            return getDrawable(R.drawable.privacy_dialog_default_permission_icon)!!.mutate()
        }

        /**
         * Gets a permission group's label from the system.
         *
         * @param groupName The name of the permission group whose label we want
         * @return The permission group's label, or the group name, if the group is invalid
         */
        @WorkerThread
        private fun PackageManager.getDefaultPermGroupLabel(groupName: String): CharSequence {
            val groupInfo = getGroupInfo(groupName) ?: return groupName
            return groupInfo.loadSafeLabel(
                this,
                0f,
                TextUtils.SAFE_STRING_FLAG_FIRST_LINE or TextUtils.SAFE_STRING_FLAG_TRIM
            )
        }

        /**
         * Get the [infos][PackageItemInfo] for the given permission group.
         *
         * @param groupName the group
         * @return The info of permission group or null if the group does not have runtime
         *   permissions.
         */
        @WorkerThread
        private fun PackageManager.getGroupInfo(groupName: String): PackageItemInfo? {
            try {
                return getPermissionGroupInfo(groupName, 0)
            } catch (e: NameNotFoundException) {
                /* ignore */
            }
            try {
                return getPermissionInfo(groupName, 0)
            } catch (e: NameNotFoundException) {
                /* ignore */
            }
            return null
        }

        @WorkerThread
        private fun PackageManager.loadDrawable(pkg: String, @DrawableRes resId: Int): Drawable? {
            return try {
                getResourcesForApplication(pkg).getDrawable(resId, null)?.mutate()
            } catch (e: NotFoundException) {
                Log.w(LOG_TAG, "Couldn't get resource", e)
                null
            } catch (e: NameNotFoundException) {
                Log.w(LOG_TAG, "Couldn't get resource", e)
                null
            }
        }

        private fun constructLayeredIcon(
            icon: Drawable,
            iconSize: Int,
            background: Drawable,
            backgroundSize: Int
        ): Drawable {
            val layered = LayerDrawable(arrayOf(background, icon))
            layered.setLayerSize(0, backgroundSize, backgroundSize)
            layered.setLayerGravity(0, Gravity.CENTER)
            layered.setLayerSize(1, iconSize, iconSize)
            layered.setLayerGravity(1, Gravity.CENTER)
            return layered
        }
    }

    /**  */
    data class PrivacyElement(
        val type: PrivacyType,
        val packageName: String,
        val userId: Int,
        val applicationName: CharSequence,
        val attributionTag: CharSequence?,
        val attributionLabel: CharSequence?,
        val proxyLabel: CharSequence?,
        val lastActiveTimestamp: Long,
        val isActive: Boolean,
        val isPhoneCall: Boolean,
        val isService: Boolean,
        val permGroupName: String,
        val navigationIntent: Intent
    ) {
        private val builder = StringBuilder("PrivacyElement(")

        init {
            builder.append("type=${type.logName}")
            builder.append(", packageName=$packageName")
            builder.append(", userId=$userId")
            builder.append(", appName=$applicationName")
            if (attributionTag != null) {
                builder.append(", attributionTag=$attributionTag")
            }
            if (attributionLabel != null) {
                builder.append(", attributionLabel=$attributionLabel")
            }
            if (proxyLabel != null) {
                builder.append(", proxyLabel=$proxyLabel")
            }
            builder.append(", lastActive=$lastActiveTimestamp")
            if (isActive) {
                builder.append(", active")
            }
            if (isPhoneCall) {
                builder.append(", phoneCall")
            }
            if (isService) {
                builder.append(", service")
            }
            builder.append(", permGroupName=$permGroupName")
            builder.append(", navigationIntent=$navigationIntent)")
        }

        override fun toString(): String = builder.toString()
    }

    /**  */
    interface OnDialogDismissed {
        fun onDialogDismissed()
    }
}
