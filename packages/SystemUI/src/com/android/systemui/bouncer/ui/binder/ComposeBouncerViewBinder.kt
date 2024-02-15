package com.android.systemui.bouncer.ui.binder

import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.keyguard.ViewMediatorCallback
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.bouncer.ui.BouncerDialogFactory
import com.android.systemui.bouncer.ui.viewmodel.BouncerViewModel
import com.android.systemui.compose.ComposeFacade
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** View binder responsible for binding the compose version of the bouncer. */
object ComposeBouncerViewBinder {
    fun bind(
        view: ViewGroup,
        legacyInteractor: PrimaryBouncerInteractor,
        viewModel: BouncerViewModel,
        dialogFactory: BouncerDialogFactory,
        authenticationInteractor: AuthenticationInteractor,
        selectedUserInteractor: SelectedUserInteractor,
        viewMediatorCallback: ViewMediatorCallback?,
    ) {
        view.addView(
            ComposeFacade.createBouncer(
                view.context,
                viewModel,
                dialogFactory,
            )
        )
        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch {
                    legacyInteractor.isShowing.collectLatest { bouncerShowing ->
                        view.isVisible = bouncerShowing
                    }
                }

                launch {
                    authenticationInteractor.onAuthenticationResult.collectLatest {
                        authenticationSucceeded ->
                        if (authenticationSucceeded) {
                            // Some dismiss actions require that keyguard be dismissed right away or
                            // deferred until something else later on dismisses keyguard (eg. end of
                            // a hide animation).
                            val deferKeyguardDone =
                                legacyInteractor.bouncerDismissAction?.onDismissAction?.onDismiss()
                            legacyInteractor.setDismissAction(null, null)

                            viewMediatorCallback?.let {
                                val selectedUserId = selectedUserInteractor.getSelectedUserId()
                                if (deferKeyguardDone == true) {
                                    it.keyguardDonePending(selectedUserId)
                                } else {
                                    it.keyguardDone(selectedUserId)
                                }
                            }
                        }
                    }
                }
                launch {
                    legacyInteractor.startingDisappearAnimation.collectLatest {
                        it.run()
                        legacyInteractor.hide()
                    }
                }
            }
        }
    }
}
