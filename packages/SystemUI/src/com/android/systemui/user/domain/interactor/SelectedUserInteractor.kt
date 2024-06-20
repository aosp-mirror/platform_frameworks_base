package com.android.systemui.user.domain.interactor

import android.annotation.UserIdInt
import android.content.pm.UserInfo
import android.os.UserManager
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.Flags.refactorGetCurrentUser
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.user.data.repository.UserRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Encapsulates business logic to interact the selected user */
@SysUISingleton
class SelectedUserInteractor @Inject constructor(private val repository: UserRepository) {

    /** Flow providing the ID of the currently selected user. */
    val selectedUser = repository.selectedUserInfo.map { it.id }.distinctUntilChanged()

    /** Flow providing the [UserInfo] of the currently selected user. */
    val selectedUserInfo = repository.selectedUserInfo

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
        return if (bypassFlag || refactorGetCurrentUser()) {
            repository.getSelectedUserInfo().id
        } else {
            KeyguardUpdateMonitor.getCurrentUser()
        }
    }

    /**
     * Returns the user ID of the "main user" of the device. This user may have access to certain
     * features which are limited to at most one user. There will never be more than one main user
     * on a device.
     *
     * <p>Currently, on most form factors the first human user on the device will be the main user;
     * in the future, the concept may be transferable, so a different user (or even no user at all)
     * may be designated the main user instead. On other form factors there might not be a main
     * user.
     *
     * <p> When the device doesn't have a main user, this will return {@code null}.
     *
     * @see [UserManager.getMainUser]
     */
    @UserIdInt
    fun getMainUserId(): Int? {
        return repository.mainUserId
    }
}
