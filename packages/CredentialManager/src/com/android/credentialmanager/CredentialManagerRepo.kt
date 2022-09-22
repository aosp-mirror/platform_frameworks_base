package com.android.credentialmanager

import android.content.Context
import com.android.credentialmanager.createflow.CreateOptionInfo
import com.android.credentialmanager.createflow.CreatePasskeyUiState
import com.android.credentialmanager.createflow.CreateScreenState
import com.android.credentialmanager.createflow.ProviderInfo
import com.android.credentialmanager.getflow.CredentialOptionInfo
import com.android.credentialmanager.getflow.GetCredentialUiState
import com.android.credentialmanager.getflow.GetScreenState

// Consider repo per screen, similar to view model?
class CredentialManagerRepo(
  private val context: Context
) {
  private fun getCredentialProviderList():
    List<com.android.credentialmanager.getflow.ProviderInfo> {
      return listOf(
        com.android.credentialmanager.getflow.ProviderInfo(
          icon = context.getDrawable(R.drawable.ic_passkey)!!,
          name = "Google Password Manager",
          appDomainName = "tribank.us",
          credentialTypeIcon = context.getDrawable(R.drawable.ic_passkey)!!,
          credentialOptions = listOf(
            CredentialOptionInfo(
              icon = context.getDrawable(R.drawable.ic_passkey)!!,
              title = "Elisa Backett",
              subtitle = "elisa.beckett@gmail.com",
              id = "id-1",
            ),
            CredentialOptionInfo(
              icon = context.getDrawable(R.drawable.ic_passkey)!!,
              title = "Elisa Backett Work",
              subtitle = "elisa.beckett.work@google.com",
              id = "id-2",
            ),
          )
        ),
        com.android.credentialmanager.getflow.ProviderInfo(
          icon = context.getDrawable(R.drawable.ic_passkey)!!,
          name = "Lastpass",
          appDomainName = "tribank.us",
          credentialTypeIcon = context.getDrawable(R.drawable.ic_passkey)!!,
          credentialOptions = listOf(
            CredentialOptionInfo(
              icon = context.getDrawable(R.drawable.ic_passkey)!!,
              title = "Elisa Backett",
              subtitle = "elisa.beckett@lastpass.com",
              id = "id-1",
            ),
          )
        ),
        com.android.credentialmanager.getflow.ProviderInfo(
          icon = context.getDrawable(R.drawable.ic_passkey)!!,
          name = "Dashlane",
          appDomainName = "tribank.us",
          credentialTypeIcon = context.getDrawable(R.drawable.ic_passkey)!!,
          credentialOptions = listOf(
            CredentialOptionInfo(
              icon = context.getDrawable(R.drawable.ic_passkey)!!,
              title = "Elisa Backett",
              subtitle = "elisa.beckett@dashlane.com",
              id = "id-1",
            ),
          )
        ),
      )
  }

  private fun createCredentialProviderList(): List<ProviderInfo> {
    return listOf(
      ProviderInfo(
        icon = context.getDrawable(R.drawable.ic_passkey)!!,
        name = "Google Password Manager",
        appDomainName = "tribank.us",
        credentialTypeIcon = context.getDrawable(R.drawable.ic_passkey)!!,
        createOptions = listOf(
          CreateOptionInfo(
            icon = context.getDrawable(R.drawable.ic_passkey)!!,
            title = "Elisa Backett",
            subtitle = "elisa.beckett@gmail.com",
            id = "id-1",
          ),
          CreateOptionInfo(
            icon = context.getDrawable(R.drawable.ic_passkey)!!,
            title = "Elisa Backett Work",
            subtitle = "elisa.beckett.work@google.com",
            id = "id-2",
          ),
        )
      ),
      ProviderInfo(
        icon = context.getDrawable(R.drawable.ic_passkey)!!,
        name = "Lastpass",
        appDomainName = "tribank.us",
        credentialTypeIcon = context.getDrawable(R.drawable.ic_passkey)!!,
        createOptions = listOf(
          CreateOptionInfo(
            icon = context.getDrawable(R.drawable.ic_passkey)!!,
            title = "Elisa Backett",
            subtitle = "elisa.beckett@lastpass.com",
            id = "id-1",
          ),
        )
      ),
      ProviderInfo(
        icon = context.getDrawable(R.drawable.ic_passkey)!!,
        name = "Dashlane",
        appDomainName = "tribank.us",
        credentialTypeIcon = context.getDrawable(R.drawable.ic_passkey)!!,
        createOptions = listOf(
          CreateOptionInfo(
            icon = context.getDrawable(R.drawable.ic_passkey)!!,
            title = "Elisa Backett",
            subtitle = "elisa.beckett@dashlane.com",
            id = "id-1",
          ),
        )
      ),
    )
  }

  fun getCredentialInitialUiState(): GetCredentialUiState {
    val providerList = getCredentialProviderList()
    return GetCredentialUiState(
      providerList,
      GetScreenState.CREDENTIAL_SELECTION,
      providerList.first()
    )
  }

  fun createPasskeyInitialUiState(): CreatePasskeyUiState {
    val providerList = createCredentialProviderList()
    return CreatePasskeyUiState(
      providers = providerList,
      currentScreenState = CreateScreenState.PASSKEY_INTRO,
    )
  }

  companion object {
    lateinit var repo: CredentialManagerRepo

    fun setup(context: Context) {
      repo = CredentialManagerRepo(context)
    }

    fun getInstance(): CredentialManagerRepo {
      return repo
    }
  }
}
