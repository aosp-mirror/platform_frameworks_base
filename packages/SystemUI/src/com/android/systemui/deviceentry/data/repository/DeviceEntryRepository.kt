package com.android.systemui.deviceentry.data.repository

import android.util.Log
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel
import com.android.systemui.keyguard.shared.model.BiometricUnlockSource
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.kotlin.sample
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** Interface for classes that can access device-entry-related application state. */
interface DeviceEntryRepository {
    /** Whether the device is immediately entering the device after a biometric unlock. */
    val enteringDeviceFromBiometricUnlock: Flow<BiometricUnlockSource>

    /**
     * Whether the device is unlocked.
     *
     * A device that is not yet unlocked requires unlocking by completing an authentication
     * challenge according to the current authentication method, unless in cases when the current
     * authentication method is not "secure" (for example, None); in such cases, the value of this
     * flow will always be `true`, even if the lockscreen is showing and still needs to be dismissed
     * by the user to proceed.
     */
    val isUnlocked: StateFlow<Boolean>

    /**
     * Whether the lockscreen is enabled for the current user. This is `true` whenever the user has
     * chosen any secure authentication method and even if they set the lockscreen to be dismissed
     * when the user swipes on it.
     */
    suspend fun isLockscreenEnabled(): Boolean

    /** Report successful authentication for device entry. */
    fun reportSuccessfulAuthentication()

    /**
     * Whether lockscreen bypass is enabled. When enabled, the lockscreen will be automatically
     * dismissed once the authentication challenge is completed.
     *
     * This is a setting that is specific to the face unlock authentication method, because the user
     * intent to unlock is not known. On devices that don't support face unlock, this always returns
     * `true`.
     *
     * When this is `false`, an automatically-triggered face unlock shouldn't automatically dismiss
     * the lockscreen.
     */
    val isBypassEnabled: StateFlow<Boolean>
}

/** Encapsulates application state for device entry. */
@SysUISingleton
class DeviceEntryRepositoryImpl
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val userRepository: UserRepository,
    private val lockPatternUtils: LockPatternUtils,
    private val keyguardBypassController: KeyguardBypassController,
    keyguardStateController: KeyguardStateController,
    keyguardRepository: KeyguardRepository,
) : DeviceEntryRepository {
    override val enteringDeviceFromBiometricUnlock =
        keyguardRepository.biometricUnlockState
            .filter { BiometricUnlockModel.dismissesKeyguard(it) }
            .sample(
                keyguardRepository.biometricUnlockSource.filterNotNull(),
            )

    private val _isUnlocked = MutableStateFlow(false)

    private val isUnlockedReportedByLegacyKeyguard =
        conflatedCallbackFlow {
                val callback =
                    object : KeyguardStateController.Callback {
                        override fun onUnlockedChanged() {
                            trySendWithFailureLogging(
                                keyguardStateController.isUnlocked,
                                TAG,
                                "updated isUnlocked due to onUnlockedChanged"
                            )
                        }

                        override fun onKeyguardShowingChanged() {
                            trySendWithFailureLogging(
                                keyguardStateController.isUnlocked,
                                TAG,
                                "updated isUnlocked due to onKeyguardShowingChanged"
                            )
                        }
                    }

                keyguardStateController.addCallback(callback)
                // Adding the callback does not send an initial update.
                trySendWithFailureLogging(
                    keyguardStateController.isUnlocked,
                    TAG,
                    "initial isKeyguardUnlocked"
                )

                awaitClose { keyguardStateController.removeCallback(callback) }
            }
            .distinctUntilChanged()
            .onEach { _isUnlocked.value = it }
            .stateIn(
                applicationScope,
                SharingStarted.Eagerly,
                initialValue = false,
            )

    override val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    override suspend fun isLockscreenEnabled(): Boolean {
        return withContext(backgroundDispatcher) {
            val selectedUserId = userRepository.getSelectedUserInfo().id
            !lockPatternUtils.isLockScreenDisabled(selectedUserId)
        }
    }

    override fun reportSuccessfulAuthentication() {
        Log.d(TAG, "Successful authentication reported.")
        _isUnlocked.value = true
    }

    override val isBypassEnabled: StateFlow<Boolean> =
        conflatedCallbackFlow {
                val listener =
                    object : KeyguardBypassController.OnBypassStateChangedListener {
                        override fun onBypassStateChanged(isEnabled: Boolean) {
                            trySend(isEnabled)
                        }
                    }
                keyguardBypassController.registerOnBypassStateChangedListener(listener)
                awaitClose {
                    keyguardBypassController.unregisterOnBypassStateChangedListener(listener)
                }
            }
            .stateIn(
                applicationScope,
                SharingStarted.Eagerly,
                initialValue = keyguardBypassController.bypassEnabled,
            )

    companion object {
        private const val TAG = "DeviceEntryRepositoryImpl"
    }
}

@Module
interface DeviceEntryRepositoryModule {
    @Binds fun repository(impl: DeviceEntryRepositoryImpl): DeviceEntryRepository
}
