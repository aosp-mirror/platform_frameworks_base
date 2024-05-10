package com.android.systemui.utils

import android.content.Context
import com.android.settingslib.RestrictedLockUtils
import com.android.settingslib.RestrictedLockUtilsInternal
import javax.inject.Inject

/** Proxy to call [RestrictedLockUtilsInternal] */
class UserRestrictionChecker @Inject constructor() {
    fun checkIfRestrictionEnforced(
        context: Context,
        userRestriction: String,
        userId: Int
    ): RestrictedLockUtils.EnforcedAdmin? {
        return RestrictedLockUtilsInternal.checkIfRestrictionEnforced(
            context,
            userRestriction,
            userId
        )
    }

    fun hasBaseUserRestriction(context: Context, userRestriction: String, userId: Int): Boolean {
        return RestrictedLockUtilsInternal.hasBaseUserRestriction(context, userRestriction, userId)
    }
}
