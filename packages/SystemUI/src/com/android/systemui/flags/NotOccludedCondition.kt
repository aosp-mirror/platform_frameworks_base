package com.android.systemui.flags

import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Returns true when the device is "asleep" as defined by the [WakefullnessLifecycle]. */
class NotOccludedCondition
@Inject
constructor(
    private val keyguardTransitionInteractorLazy: Lazy<KeyguardTransitionInteractor>,
) : ConditionalRestarter.Condition {

    override val canRestartNow: Flow<Boolean>
        get() {
            return keyguardTransitionInteractorLazy
                .get()
                .transitionValue(KeyguardState.OCCLUDED)
                .map { it == 0f }
        }
}
