package com.android.systemui.deviceentry.domain.interactor

import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.domain.model.AuthenticationMethodModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.data.repository.DeviceEntryRepository
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.SceneKey
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Hosts application business logic related to device entry.
 *
 * Device entry occurs when the user successfully dismisses (or bypasses) the lockscreen, regardless
 * of the authentication method used.
 */
@SysUISingleton
class DeviceEntryInteractor
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val repository: DeviceEntryRepository,
    private val authenticationInteractor: AuthenticationInteractor,
    sceneInteractor: SceneInteractor,
) {
    /**
     * Whether the device is unlocked.
     *
     * A device that is not yet unlocked requires unlocking by completing an authentication
     * challenge according to the current authentication method, unless in cases when the current
     * authentication method is not "secure" (for example, None and Swipe); in such cases, the value
     * of this flow will always be `true`, even if the lockscreen is showing and still needs to be
     * dismissed by the user to proceed.
     */
    val isUnlocked: StateFlow<Boolean> =
        combine(
                repository.isUnlocked,
                authenticationInteractor.authenticationMethod,
            ) { isUnlocked, authenticationMethod ->
                !authenticationMethod.isSecure || isUnlocked
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

    /**
     * Whether the device has been entered (i.e. the lockscreen has been dismissed, by any method).
     * This can be `false` when the device is unlocked, e.g. when the user still needs to swipe away
     * the non-secure lockscreen, even though they've already authenticated.
     *
     * Note: This does not imply that the lockscreen is visible or not.
     */
    val isDeviceEntered: StateFlow<Boolean> =
        sceneInteractor.desiredScene
            .map { it.key }
            .filter { currentScene ->
                currentScene == SceneKey.Gone || currentScene == SceneKey.Lockscreen
            }
            .map { it == SceneKey.Gone }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    /**
     * Whether it's currently possible to swipe up to enter the device without requiring
     * authentication. This returns `false` whenever the lockscreen has been dismissed.
     *
     * Note: `true` doesn't mean the lockscreen is visible. It may be occluded or covered by other
     * UI.
     */
    val canSwipeToEnter =
        combine(authenticationInteractor.authenticationMethod, isDeviceEntered) {
                authenticationMethod,
                isDeviceEntered ->
                authenticationMethod is AuthenticationMethodModel.Swipe && !isDeviceEntered
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    /**
     * Returns `true` if the device currently requires authentication before entry is granted;
     * `false` if the device can be entered without authenticating first.
     */
    suspend fun isAuthenticationRequired(): Boolean {
        return !isUnlocked.value && authenticationInteractor.getAuthenticationMethod().isSecure
    }

    /**
     * Whether lock screen bypass is enabled. When enabled, the lock screen will be automatically
     * dismissed once the authentication challenge is completed. For example, completing a biometric
     * authentication challenge via face unlock or fingerprint sensor can automatically bypass the
     * lock screen.
     */
    fun isBypassEnabled() = repository.isBypassEnabled()
}
