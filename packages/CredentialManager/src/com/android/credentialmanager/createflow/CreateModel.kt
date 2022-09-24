package com.android.credentialmanager.createflow

import android.graphics.drawable.Drawable

data class ProviderInfo(
  val icon: Drawable,
  val name: String,
  val appDomainName: String,
  val credentialTypeIcon: Drawable,
  val createOptions: List<CreateOptionInfo>,
)

data class CreateOptionInfo(
  val icon: Drawable,
  val title: String,
  val subtitle: String,
  val id: String,
)

/** The name of the current screen. */
enum class CreateScreenState {
  PASSKEY_INTRO,
  PROVIDER_SELECTION,
  CREATION_OPTION_SELECTION,
}
