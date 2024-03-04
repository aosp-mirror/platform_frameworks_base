package com.android.systemui.bouncer.ui.binder

import android.view.ViewGroup
import com.android.keyguard.KeyguardMessageAreaController
import com.android.keyguard.ViewMediatorCallback
import com.android.keyguard.dagger.KeyguardBouncerComponent
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.bouncer.domain.interactor.BouncerMessageInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.bouncer.shared.flag.ComposeBouncerFlags
import com.android.systemui.bouncer.ui.BouncerDialogFactory
import com.android.systemui.bouncer.ui.viewmodel.BouncerViewModel
import com.android.systemui.bouncer.ui.viewmodel.KeyguardBouncerViewModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.Flags.COMPOSE_BOUNCER_ENABLED
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToGoneTransitionViewModel
import com.android.systemui.log.BouncerLogger
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import dagger.Lazy
import javax.inject.Inject

/** Helper data class that allows to lazy load all the dependencies of the legacy bouncer. */
@SysUISingleton
data class LegacyBouncerDependencies
@Inject
constructor(
    val viewModel: KeyguardBouncerViewModel,
    val primaryBouncerToGoneTransitionViewModel: PrimaryBouncerToGoneTransitionViewModel,
    val componentFactory: KeyguardBouncerComponent.Factory,
    val messageAreaControllerFactory: KeyguardMessageAreaController.Factory,
    val bouncerMessageInteractor: BouncerMessageInteractor,
    val bouncerLogger: BouncerLogger,
    val selectedUserInteractor: SelectedUserInteractor,
)

/** Helper data class that allows to lazy load all the dependencies of the compose based bouncer. */
@SysUISingleton
data class ComposeBouncerDependencies
@Inject
constructor(
    val legacyInteractor: PrimaryBouncerInteractor,
    val viewModel: BouncerViewModel,
    val dialogFactory: BouncerDialogFactory,
    val authenticationInteractor: AuthenticationInteractor,
    val viewMediatorCallback: ViewMediatorCallback?,
    val selectedUserInteractor: SelectedUserInteractor,
)

/**
 * Toggles between the compose and non compose version of the bouncer, instantiating only the
 * dependencies required for each.
 */
@SysUISingleton
class BouncerViewBinder
@Inject
constructor(
    private val composeBouncerFlags: ComposeBouncerFlags,
    private val legacyBouncerDependencies: Lazy<LegacyBouncerDependencies>,
    private val composeBouncerDependencies: Lazy<ComposeBouncerDependencies>,
) {
    fun bind(view: ViewGroup) {
        if (COMPOSE_BOUNCER_ENABLED && composeBouncerFlags.isOnlyComposeBouncerEnabled()) {
            val deps = composeBouncerDependencies.get()
            ComposeBouncerViewBinder.bind(
                view,
                deps.legacyInteractor,
                deps.viewModel,
                deps.dialogFactory,
                deps.authenticationInteractor,
                deps.selectedUserInteractor,
                deps.viewMediatorCallback,
            )
        } else {
            val deps = legacyBouncerDependencies.get()
            KeyguardBouncerViewBinder.bind(
                view,
                deps.viewModel,
                deps.primaryBouncerToGoneTransitionViewModel,
                deps.componentFactory,
                deps.messageAreaControllerFactory,
                deps.bouncerMessageInteractor,
                deps.bouncerLogger,
                deps.selectedUserInteractor,
            )
        }
    }
}
