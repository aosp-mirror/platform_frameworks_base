/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.volume.dialog.ringer.ui.binder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.annotation.LayoutRes
import androidx.compose.ui.util.fastForEachIndexed
import com.android.systemui.lifecycle.WindowLifecycleState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.lifecycle.viewModel
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.RingerButtonViewModel
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.RingerDrawerState
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.RingerViewModel
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.RingerViewModelState
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.VolumeDialogRingerDrawerViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

@VolumeDialogScope
class VolumeDialogRingerViewBinder
@Inject
constructor(private val viewModelFactory: VolumeDialogRingerDrawerViewModel.Factory) {

    fun bind(view: View) {
        with(view) {
            val drawerAndRingerContainer =
                requireViewById<View>(R.id.volume_ringer_and_drawer_container)
            val drawerContainer = requireViewById<View>(R.id.volume_drawer_container)
            val selectedButtonView =
                requireViewById<ImageButton>(R.id.volume_new_ringer_active_button)
            val volumeDialogBackgroundView = requireViewById<View>(R.id.volume_dialog_background)
            repeatWhenAttached {
                viewModel(
                    traceName = "VolumeDialogRingerViewBinder",
                    minWindowLifecycleState = WindowLifecycleState.ATTACHED,
                    factory = { viewModelFactory.create() },
                ) { viewModel ->
                    viewModel.ringerViewModel
                        .onEach { ringerState ->
                            when (ringerState) {
                                is RingerViewModelState.Available -> {
                                    val uiModel = ringerState.uiModel

                                    bindSelectedButton(viewModel, uiModel, selectedButtonView)
                                    bindDrawerButtons(viewModel, uiModel.availableButtons)

                                    // Set up views background and visibility
                                    drawerAndRingerContainer.visibility = View.VISIBLE
                                    when (uiModel.drawerState) {
                                        is RingerDrawerState.Initial -> {
                                            drawerContainer.visibility = View.GONE
                                            selectedButtonView.visibility = View.VISIBLE
                                            volumeDialogBackgroundView.setBackgroundResource(
                                                R.drawable.volume_dialog_background
                                            )
                                        }
                                        is RingerDrawerState.Closed -> {
                                            drawerContainer.visibility = View.GONE
                                            selectedButtonView.visibility = View.VISIBLE
                                            volumeDialogBackgroundView.setBackgroundResource(
                                                R.drawable.volume_dialog_background
                                            )
                                        }
                                        is RingerDrawerState.Open -> {
                                            drawerContainer.visibility = View.VISIBLE
                                            selectedButtonView.visibility = View.GONE
                                            if (
                                                uiModel.currentButtonIndex !=
                                                    uiModel.availableButtons.size - 1
                                            ) {
                                                volumeDialogBackgroundView.setBackgroundResource(
                                                    R.drawable.volume_dialog_background_small_radius
                                                )
                                            }
                                        }
                                    }
                                }
                                is RingerViewModelState.Unavailable -> {
                                    drawerAndRingerContainer.visibility = View.GONE
                                    volumeDialogBackgroundView.setBackgroundResource(
                                        R.drawable.volume_dialog_background
                                    )
                                }
                            }
                        }
                        .launchIn(this)
                }
            }
        }
    }

    private fun View.bindDrawerButtons(
        viewModel: VolumeDialogRingerDrawerViewModel,
        availableButtons: List<RingerButtonViewModel?>,
    ) {
        val drawerOptions = requireViewById<ViewGroup>(R.id.volume_drawer_options)
        val count = availableButtons.size
        drawerOptions.ensureChildCount(R.layout.volume_ringer_button, count)

        availableButtons.fastForEachIndexed { index, ringerButton ->
            ringerButton?.let {
                drawerOptions.getChildAt(count - index - 1).bindDrawerButton(it, viewModel)
            }
        }
    }

    private fun View.bindDrawerButton(
        buttonViewModel: RingerButtonViewModel,
        viewModel: VolumeDialogRingerDrawerViewModel,
    ) {
        with(requireViewById<ImageButton>(R.id.volume_drawer_button)) {
            setImageResource(buttonViewModel.imageResId)
            contentDescription = context.getString(buttonViewModel.contentDescriptionResId)
            setOnClickListener { viewModel.onRingerButtonClicked(buttonViewModel.ringerMode) }
        }
    }

    private fun ViewGroup.ensureChildCount(@LayoutRes viewLayoutId: Int, count: Int) {
        val childCountDelta = childCount - count
        when {
            childCountDelta > 0 -> {
                removeViews(0, childCountDelta)
            }
            childCountDelta < 0 -> {
                val inflater = LayoutInflater.from(context)
                repeat(abs(childCountDelta)) { inflater.inflate(viewLayoutId, this, true) }
            }
        }
    }

    private fun bindSelectedButton(
        viewModel: VolumeDialogRingerDrawerViewModel,
        uiModel: RingerViewModel,
        selectedButtonView: ImageButton,
    ) {
        with(uiModel) {
            selectedButtonView.setImageResource(selectedButton.imageResId)
            selectedButtonView.setOnClickListener {
                viewModel.onRingerButtonClicked(selectedButton.ringerMode)
            }
        }
    }
}
