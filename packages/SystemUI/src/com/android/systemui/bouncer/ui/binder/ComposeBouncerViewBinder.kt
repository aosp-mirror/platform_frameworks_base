package com.android.systemui.bouncer.ui.binder

import android.view.ViewGroup
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import com.android.systemui.bouncer.ui.BouncerDialogFactory
import com.android.systemui.bouncer.ui.composable.BouncerContainer
import com.android.systemui.bouncer.ui.viewmodel.BouncerContainerViewModel
import com.android.systemui.bouncer.ui.viewmodel.BouncerSceneContentViewModel
import com.android.systemui.lifecycle.WindowLifecycleState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.lifecycle.viewModel
import kotlinx.coroutines.awaitCancellation

/** View binder responsible for binding the compose version of the bouncer. */
object ComposeBouncerViewBinder {
    fun bind(
        view: ViewGroup,
        viewModelFactory: BouncerSceneContentViewModel.Factory,
        dialogFactory: BouncerDialogFactory,
        bouncerContainerViewModelFactory: BouncerContainerViewModel.Factory,
    ) {
        view.repeatWhenAttached {
            view.viewModel(
                minWindowLifecycleState = WindowLifecycleState.ATTACHED,
                factory = { bouncerContainerViewModelFactory.create() },
                traceName = "ComposeBouncerViewBinder",
            ) { viewModel ->
                try {
                    view.setViewTreeOnBackPressedDispatcherOwner(
                        object : OnBackPressedDispatcherOwner {
                            override val onBackPressedDispatcher =
                                OnBackPressedDispatcher().apply {
                                    setOnBackInvokedDispatcher(
                                        view.viewRootImpl.onBackInvokedDispatcher
                                    )
                                }

                            override val lifecycle: Lifecycle = this@repeatWhenAttached.lifecycle
                        }
                    )

                    view.addView(
                        ComposeView(view.context).apply {
                            setContent { BouncerContainer(viewModelFactory, dialogFactory) }
                        }
                    )
                    awaitCancellation()
                } finally {
                    view.removeAllViews()
                }
            }
        }
    }
}
