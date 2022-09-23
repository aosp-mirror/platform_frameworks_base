package com.android.credentialmanager.common

enum class DialogType {
  CREATE_PASSKEY,
  GET_CREDENTIALS,
  CREATE_PASSWORD,
  UNKNOWN;

  companion object {
    fun toDialogType(value: String): DialogType {
      return try {
        valueOf(value)
      } catch (e: IllegalArgumentException) {
        UNKNOWN
      }
    }
  }
}
