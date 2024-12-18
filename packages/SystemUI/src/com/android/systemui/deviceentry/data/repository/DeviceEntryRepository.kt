package com.android.systemui.deviceentry.data.repository

import com.android.internal.widget.LockPatternUtils
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.user.data.repository.UserRepository
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** Interface for classes that can access device-entry-related application state. */
interface DeviceEntryRepository {
    /**
     * Whether the lockscreen is enabled for the current user. This is `true` whenever the user has
     * chosen any secure authentication method and even if they set the lockscreen to be dismissed
     * when the user swipes on it.
     */
    val isLockscreenEnabled: StateFlow<Boolean>

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

    /**
     * Whether the lockscreen is enabled for the current user. This is `true` whenever the user has
     * chosen any secure authentication method and even if they set the lockscreen to be dismissed
     * when the user swipes on it.
     */
    suspend fun isLockscreenEnabled(): Boolean
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
) : DeviceEntryRepository {

    private val _isLockscreenEnabled = MutableStateFlow(true)
    override val isLockscreenEnabled: StateFlow<Boolean> = _isLockscreenEnabled.asStateFlow()

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

    override suspend fun isLockscreenEnabled(): Boolean {
        return withContext(backgroundDispatcher) {
            val selectedUserId = userRepository.getSelectedUserInfo().id
            val isEnabled = !lockPatternUtils.isLockScreenDisabled(selectedUserId)
            _isLockscreenEnabled.value = isEnabled
            isEnabled
        }
    }
}

@Module
interface DeviceEntryRepositoryModule {
    @Binds fun repository(impl: DeviceEntryRepositoryImpl): DeviceEntryRepository
}
