package com.android.systemui.keyguard.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockMode
import com.android.systemui.keyguard.shared.model.BiometricUnlockSource
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_DISMISS_BOUNCER
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_NONE
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_ONLY_WAKE
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_SHOW_BOUNCER
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_UNLOCK_COLLAPSING
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_WAKE_AND_UNLOCK
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_WAKE_AND_UNLOCK_FROM_DREAM
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING
import com.android.systemui.statusbar.phone.BiometricUnlockController.WakeAndUnlockMode
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi

@ExperimentalCoroutinesApi
@SysUISingleton
class BiometricUnlockInteractor
@Inject
constructor(
    private val keyguardRepository: KeyguardRepository,
) {

    fun setBiometricUnlockState(
        @WakeAndUnlockMode unlockStateInt: Int,
        biometricUnlockSource: BiometricUnlockSource?,
    ) {
        val state = biometricModeIntToObject(unlockStateInt)
        keyguardRepository.setBiometricUnlockState(state, biometricUnlockSource)
    }

    private fun biometricModeIntToObject(@WakeAndUnlockMode value: Int): BiometricUnlockMode {
        return when (value) {
            MODE_NONE -> BiometricUnlockMode.NONE
            MODE_WAKE_AND_UNLOCK -> BiometricUnlockMode.WAKE_AND_UNLOCK
            MODE_WAKE_AND_UNLOCK_PULSING -> BiometricUnlockMode.WAKE_AND_UNLOCK_PULSING
            MODE_SHOW_BOUNCER -> BiometricUnlockMode.SHOW_BOUNCER
            MODE_ONLY_WAKE -> BiometricUnlockMode.ONLY_WAKE
            MODE_UNLOCK_COLLAPSING -> BiometricUnlockMode.UNLOCK_COLLAPSING
            MODE_WAKE_AND_UNLOCK_FROM_DREAM -> BiometricUnlockMode.WAKE_AND_UNLOCK_FROM_DREAM
            MODE_DISMISS_BOUNCER -> BiometricUnlockMode.DISMISS_BOUNCER
            else -> throw IllegalArgumentException("Invalid BiometricUnlockModel value: $value")
        }
    }
}
