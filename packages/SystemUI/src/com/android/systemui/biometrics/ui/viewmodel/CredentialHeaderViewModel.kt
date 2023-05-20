package com.android.systemui.biometrics.ui.viewmodel

import android.graphics.drawable.Drawable
import android.os.UserHandle

/** View model for the top-level header / info area of BiometricPrompt. */
interface CredentialHeaderViewModel {
    val user: UserHandle
    val title: String
    val subtitle: String
    val description: String
    val icon: Drawable
}
