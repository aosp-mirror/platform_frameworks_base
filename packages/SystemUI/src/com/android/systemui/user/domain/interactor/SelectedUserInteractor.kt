package com.android.systemui.user.domain.interactor

import android.annotation.UserIdInt
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.user.data.repository.UserRepository
import javax.inject.Inject

/** Encapsulates business logic to interact the selected user */
@SysUISingleton
class SelectedUserInteractor @Inject constructor(private val repository: UserRepository) {

    /** Returns the ID of the currently-selected user. */
    @UserIdInt
    fun getSelectedUserId(): Int {
        return repository.getSelectedUserInfo().id
    }
}
