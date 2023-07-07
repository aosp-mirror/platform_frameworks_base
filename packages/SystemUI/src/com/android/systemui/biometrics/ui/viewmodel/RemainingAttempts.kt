package com.android.systemui.biometrics.ui.viewmodel

/** Metadata about the number of credential attempts the user has left [remaining], if known. */
data class RemainingAttempts(val remaining: Int? = null, val message: String = "")
