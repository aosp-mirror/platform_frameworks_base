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

package com.android.systemui.qs.footer.ui.binder

import android.content.Context
import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.animation.Expandable
import com.android.systemui.common.ui.binder.IconViewBinder
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.people.ui.view.PeopleViewBinder.bind
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsButtonViewModel
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsForegroundServicesButtonViewModel
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsSecurityButtonViewModel
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel
import com.android.systemui.res.R
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** A ViewBinder for [FooterActionsViewBinder]. */
@SysUISingleton
class FooterActionsViewBinder @Inject constructor() {
    /** Create a view that can later be [bound][bind] to a [FooterActionsViewModel]. */
    fun create(context: Context): LinearLayout {
        return LayoutInflater.from(context).inflate(R.layout.footer_actions, /* root= */ null)
            as LinearLayout
    }

    /** Bind [view] to [viewModel]. */
    fun bind(
        view: LinearLayout,
        viewModel: FooterActionsViewModel,
        qsVisibilityLifecycleOwner: LifecycleOwner,
    ) {
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

        // Add the views used by this new implementation.
        val context = view.context
        val inflater = LayoutInflater.from(context)

        val securityHolder = TextButtonViewHolder.createAndAdd(inflater, view)
        val foregroundServicesWithTextHolder = TextButtonViewHolder.createAndAdd(inflater, view)
        val foregroundServicesWithNumberHolder = NumberButtonViewHolder.createAndAdd(inflater, view)
        val userSwitcherHolder = IconButtonViewHolder.createAndAdd(inflater, view, isLast = false)
        val settingsHolder =
            IconButtonViewHolder.createAndAdd(inflater, view, isLast = viewModel.power == null)

        // Bind the static power and settings buttons.
        bindButton(settingsHolder, viewModel.settings)

        if (viewModel.power != null) {
            val powerHolder = IconButtonViewHolder.createAndAdd(inflater, view, isLast = true)
            bindButton(powerHolder, viewModel.power)
        }

        // There are 2 lifecycle scopes we are using here:
        //   1) The scope created by [repeatWhenAttached] when [view] is attached, and destroyed
        //      when the [view] is detached. We use this as the parent scope for all our [viewModel]
        //      state collection, given that we don't want to do any work when [view] is detached.
        //   2) The scope owned by [lifecycleOwner], which should be RESUMED only when Quick
        //      Settings are visible. We use this to make sure we collect UI state only when the
        //      View is visible.
        //
        // Given that we start our collection when the Quick Settings become visible, which happens
        // every time the user swipes down the shade, we remember our previous UI state already
        // bound to the UI to avoid binding the same values over and over for nothing.

        // TODO(b/242040009): Look into using only a single scope.

        var previousSecurity: FooterActionsSecurityButtonViewModel? = null
        var previousForegroundServices: FooterActionsForegroundServicesButtonViewModel? = null
        var previousUserSwitcher: FooterActionsButtonViewModel? = null

        // Listen for ViewModel updates when the View is attached.
        view.repeatWhenAttached {
            val attachedScope = this.lifecycleScope

            attachedScope.launch {
                // Listen for dialog requests as soon as we are attached, even when not visible.
                // TODO(b/242040009): Should this move somewhere else?
                launch { viewModel.observeDeviceMonitoringDialogRequests(view.context) }

                // Make sure we set the correct alphas even when QS are not currently shown.
                launch { viewModel.alpha.collect { view.alpha = it } }
                launch {
                    viewModel.backgroundAlpha.collect {
                        view.background?.alpha = (it * 255).roundToInt()
                    }
                }
            }

            // Listen for model changes only when QS are visible.
            qsVisibilityLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // Security.
                launch {
                    viewModel.security.collect { security ->
                        if (previousSecurity != security) {
                            bindSecurity(view.context, securityHolder, security)
                            previousSecurity = security
                        }
                    }
                }

                // Foreground services.
                launch {
                    viewModel.foregroundServices.collect { foregroundServices ->
                        if (previousForegroundServices != foregroundServices) {
                            bindForegroundService(
                                foregroundServicesWithNumberHolder,
                                foregroundServicesWithTextHolder,
                                foregroundServices,
                            )
                            previousForegroundServices = foregroundServices
                        }
                    }
                }

                // User switcher.
                launch {
                    viewModel.userSwitcher.collect { userSwitcher ->
                        if (previousUserSwitcher != userSwitcher) {
                            bindButton(userSwitcherHolder, userSwitcher)
                            previousUserSwitcher = userSwitcher
                        }
                    }
                }
            }
        }
    }

    private fun bindSecurity(
        quickSettingsContext: Context,
        securityHolder: TextButtonViewHolder,
        security: FooterActionsSecurityButtonViewModel?,
    ) {
        val securityView = securityHolder.view
        securityView.isVisible = security != null
        if (security == null) {
            return
        }

        // Make sure that the chevron is visible and that the button is clickable if there is a
        // listener.
        val chevron = securityHolder.chevron
        val onClick = security.onClick
        if (onClick != null) {
            securityView.isClickable = true
            securityView.setOnClickListener {
                onClick(quickSettingsContext, Expandable.fromView(securityView))
            }
            chevron.isVisible = true
        } else {
            securityView.isClickable = false
            securityView.setOnClickListener(null)
            chevron.isVisible = false
        }

        securityHolder.text.text = security.text
        securityHolder.newDot.isVisible = false
        IconViewBinder.bind(security.icon, securityHolder.icon)
    }

    private fun bindForegroundService(
        foregroundServicesWithNumberHolder: NumberButtonViewHolder,
        foregroundServicesWithTextHolder: TextButtonViewHolder,
        foregroundServices: FooterActionsForegroundServicesButtonViewModel?,
    ) {
        val foregroundServicesWithNumberView = foregroundServicesWithNumberHolder.view
        val foregroundServicesWithTextView = foregroundServicesWithTextHolder.view
        if (foregroundServices == null) {
            foregroundServicesWithNumberView.isVisible = false
            foregroundServicesWithTextView.isVisible = false
            return
        }

        val foregroundServicesCount = foregroundServices.foregroundServicesCount
        if (foregroundServices.displayText) {
            // Button with text, icon and chevron.
            foregroundServicesWithNumberView.isVisible = false

            foregroundServicesWithTextView.isVisible = true
            foregroundServicesWithTextView.setOnClickListener {
                foregroundServices.onClick(Expandable.fromView(foregroundServicesWithTextView))
            }
            foregroundServicesWithTextHolder.text.text = foregroundServices.text
            foregroundServicesWithTextHolder.newDot.isVisible = foregroundServices.hasNewChanges
        } else {
            // Small button with the number only.
            foregroundServicesWithTextView.isVisible = false

            foregroundServicesWithNumberView.isVisible = true
            foregroundServicesWithNumberView.setOnClickListener {
                foregroundServices.onClick(Expandable.fromView(foregroundServicesWithNumberView))
            }
            foregroundServicesWithNumberHolder.number.text = foregroundServicesCount.toString()
            foregroundServicesWithNumberHolder.number.contentDescription = foregroundServices.text
            foregroundServicesWithNumberHolder.newDot.isVisible = foregroundServices.hasNewChanges
        }
    }

    private fun bindButton(button: IconButtonViewHolder, model: FooterActionsButtonViewModel?) {
        val buttonView = button.view
        buttonView.id = model?.id ?: View.NO_ID
        buttonView.isVisible = model != null
        if (model == null) {
            return
        }

        val backgroundResource =
            when (model.backgroundColor) {
                R.attr.shadeInactive -> R.drawable.qs_footer_action_circle
                R.attr.shadeActive -> R.drawable.qs_footer_action_circle_color
                else -> error("Unsupported icon background resource ${model.backgroundColor}")
            }
        buttonView.setBackgroundResource(backgroundResource)
        buttonView.setOnClickListener { model.onClick(Expandable.fromView(buttonView)) }

        val icon = model.icon
        val iconView = button.icon

        IconViewBinder.bind(icon, iconView)
        if (model.iconTint != null) {
            iconView.setColorFilter(model.iconTint, PorterDuff.Mode.SRC_IN)
        } else {
            iconView.clearColorFilter()
        }
    }
}

private class TextButtonViewHolder(val view: View) {
    val icon = view.requireViewById<ImageView>(R.id.icon)
    val text = view.requireViewById<TextView>(R.id.text)
    val newDot = view.requireViewById<ImageView>(R.id.new_dot)
    val chevron = view.requireViewById<ImageView>(R.id.chevron_icon)

    companion object {
        fun createAndAdd(inflater: LayoutInflater, root: ViewGroup): TextButtonViewHolder {
            val view =
                inflater.inflate(
                    R.layout.footer_actions_text_button,
                    /* root= */ root,
                    /* attachToRoot= */ false,
                )
            root.addView(view)
            return TextButtonViewHolder(view)
        }
    }
}

private class NumberButtonViewHolder(val view: View) {
    val number = view.requireViewById<TextView>(R.id.number)
    val newDot = view.requireViewById<ImageView>(R.id.new_dot)

    companion object {
        fun createAndAdd(inflater: LayoutInflater, root: ViewGroup): NumberButtonViewHolder {
            val view =
                inflater.inflate(
                    R.layout.footer_actions_number_button,
                    /* root= */ root,
                    /* attachToRoot= */ false,
                )
            root.addView(view)
            return NumberButtonViewHolder(view)
        }
    }
}

private class IconButtonViewHolder(val view: View) {
    val icon = view.requireViewById<ImageView>(R.id.icon)

    companion object {
        fun createAndAdd(
            inflater: LayoutInflater,
            root: ViewGroup,
            isLast: Boolean,
        ): IconButtonViewHolder {
            val view =
                inflater.inflate(
                    R.layout.footer_actions_icon_button,
                    /* root= */ root,
                    /* attachToRoot= */ false,
                )

            // All buttons have a background with an inset of qs_footer_action_inset, so the last
            // button must have a negative inset of -qs_footer_action_inset to compensate and be
            // aligned with its parent.
            val marginEnd =
                if (isLast) {
                    -view.context.resources.getDimensionPixelSize(R.dimen.qs_footer_action_inset)
                } else {
                    0
                }

            val size =
                view.context.resources.getDimensionPixelSize(R.dimen.qs_footer_action_button_size)
            root.addView(
                view,
                LinearLayout.LayoutParams(size, size).apply { this.marginEnd = marginEnd },
            )
            return IconButtonViewHolder(view)
        }
    }
}
