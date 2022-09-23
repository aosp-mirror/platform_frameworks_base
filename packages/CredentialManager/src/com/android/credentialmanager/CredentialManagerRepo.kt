package com.android.credentialmanager

import android.content.Context
import com.android.credentialmanager.createflow.CreateOptionInfo
import com.android.credentialmanager.createflow.ProviderInfo
import com.android.credentialmanager.createflow.ProviderList
import com.android.credentialmanager.getflow.CredentialOptionInfo

class CredentialManagerRepo(
  private val context: Context
) {
  fun getCredentialProviderList(): List<com.android.credentialmanager.getflow.ProviderInfo> {
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

  fun createCredentialProviderList(): ProviderList {
    return ProviderList(
      listOf(
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
