package com.android.systemui.keyguard.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel
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

@SysUISingleton
class BiometricUnlockInteractor
@Inject
constructor(
    private val keyguardRepository: KeyguardRepository,
) {

    fun setBiometricUnlockState(@WakeAndUnlockMode unlockStateInt: Int) {
        val state = biometricModeIntToObject(unlockStateInt)
        keyguardRepository.setBiometricUnlockState(state)
    }

    private fun biometricModeIntToObject(@WakeAndUnlockMode value: Int): BiometricUnlockModel {
        return when (value) {
            MODE_NONE -> BiometricUnlockModel.NONE
            MODE_WAKE_AND_UNLOCK -> BiometricUnlockModel.WAKE_AND_UNLOCK
            MODE_WAKE_AND_UNLOCK_PULSING -> BiometricUnlockModel.WAKE_AND_UNLOCK_PULSING
            MODE_SHOW_BOUNCER -> BiometricUnlockModel.SHOW_BOUNCER
            MODE_ONLY_WAKE -> BiometricUnlockModel.ONLY_WAKE
            MODE_UNLOCK_COLLAPSING -> BiometricUnlockModel.UNLOCK_COLLAPSING
            MODE_WAKE_AND_UNLOCK_FROM_DREAM -> BiometricUnlockModel.WAKE_AND_UNLOCK_FROM_DREAM
            MODE_DISMISS_BOUNCER -> BiometricUnlockModel.DISMISS_BOUNCER
            else -> throw IllegalArgumentException("Invalid BiometricUnlockModel value: $value")
        }
    }
}
