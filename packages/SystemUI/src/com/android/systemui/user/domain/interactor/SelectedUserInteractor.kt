package com.android.systemui.user.domain.interactor

import android.annotation.UserIdInt
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags.REFACTOR_GETCURRENTUSER
import com.android.systemui.user.data.repository.UserRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Encapsulates business logic to interact the selected user */
@SysUISingleton
class SelectedUserInteractor
@Inject
constructor(
    private val repository: UserRepository,
    private val flags: FeatureFlagsClassic,
) {

    /** Flow providing the ID of the currently selected user. */
    val selectedUser = repository.selectedUserInfo.map { it.id }.distinctUntilChanged()

    /**
     * Returns the ID of the currently-selected user.
     *
     * @param bypassFlag this will ignore the feature flag and get the data from the repository
     *   instead. This is used for refactored methods that were previously pointing to `userTracker`
     *   and therefore should not be routed back to KeyguardUpdateMonitor when flag is disabled.
     *   KeyguardUpdateMonitor.getCurrentUser() is deprecated and will be removed soon (together
     *   with this flag).
     */
    @UserIdInt
    @JvmOverloads
    fun getSelectedUserId(bypassFlag: Boolean = false): Int {
        return if (bypassFlag || flags.isEnabled(REFACTOR_GETCURRENTUSER)) {
            repository.getSelectedUserInfo().id
        } else {
            KeyguardUpdateMonitor.getCurrentUser()
        }
    }
}
