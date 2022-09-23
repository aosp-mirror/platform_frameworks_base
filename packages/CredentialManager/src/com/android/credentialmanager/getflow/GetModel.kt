package com.android.credentialmanager.getflow

import android.graphics.drawable.Drawable

data class ProviderInfo(
  val icon: Drawable,
  val name: String,
  val appDomainName: String,
  val credentialTypeIcon: Drawable,
  val credentialOptions: List<CredentialOptionInfo>,
)

data class CredentialOptionInfo(
  val icon: Drawable,
  val title: String,
  val subtitle: String,
  val id: String,
)
