package com.android.systemui.bouncer.ui.binder

import android.view.ViewGroup
import com.android.keyguard.KeyguardMessageAreaController
import com.android.keyguard.dagger.KeyguardBouncerComponent
import com.android.systemui.bouncer.domain.interactor.BouncerMessageInteractor
import com.android.systemui.bouncer.shared.flag.ComposeBouncerFlags
import com.android.systemui.bouncer.ui.BouncerDialogFactory
import com.android.systemui.bouncer.ui.viewmodel.BouncerContainerViewModel
import com.android.systemui.bouncer.ui.viewmodel.BouncerSceneContentViewModel
import com.android.systemui.bouncer.ui.viewmodel.KeyguardBouncerViewModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToGoneTransitionViewModel
import com.android.systemui.log.BouncerLogger
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Helper data class that allows to lazy load all the dependencies of the legacy bouncer. */
@OptIn(ExperimentalCoroutinesApi::class)
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
    val viewModelFactory: BouncerSceneContentViewModel.Factory,
    val dialogFactory: BouncerDialogFactory,
    val bouncerContainerViewModelFactory: BouncerContainerViewModel.Factory,
)

/**
 * Toggles between the compose and non compose version of the bouncer, instantiating only the
 * dependencies required for each.
 */
@SysUISingleton
class BouncerViewBinder
@Inject
constructor(
    private val legacyBouncerDependencies: Lazy<LegacyBouncerDependencies>,
    private val composeBouncerDependencies: Lazy<ComposeBouncerDependencies>,
) {
    fun bind(view: ViewGroup) {
        if (ComposeBouncerFlags.isOnlyComposeBouncerEnabled()) {
            val deps = composeBouncerDependencies.get()
            ComposeBouncerViewBinder.bind(
                view,
                deps.viewModelFactory,
                deps.dialogFactory,
                deps.bouncerContainerViewModelFactory,
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
