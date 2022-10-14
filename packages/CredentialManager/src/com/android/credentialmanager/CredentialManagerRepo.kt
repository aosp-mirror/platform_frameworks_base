package com.android.credentialmanager

import android.app.slice.Slice
import android.app.slice.SliceSpec
import android.content.Context
import android.content.Intent
import android.credentials.ui.Entry
import android.credentials.ui.ProviderData
import android.credentials.ui.RequestInfo
import android.graphics.drawable.Icon
import android.os.Binder
import com.android.credentialmanager.createflow.CreateOptionInfo
import com.android.credentialmanager.createflow.CreatePasskeyUiState
import com.android.credentialmanager.createflow.CreateScreenState
import com.android.credentialmanager.createflow.ProviderInfo
import com.android.credentialmanager.getflow.CredentialOptionInfo
import com.android.credentialmanager.getflow.GetCredentialUiState
import com.android.credentialmanager.getflow.GetScreenState

// Consider repo per screen, similar to view model?
class CredentialManagerRepo(
  private val context: Context,
  intent: Intent,
) {
  private val requestInfo: RequestInfo
  private val providerList: List<ProviderData>

  init {
    requestInfo = intent.extras?.getParcelable(
      RequestInfo.EXTRA_REQUEST_INFO,
      RequestInfo::class.java
    ) ?: RequestInfo(
      Binder(),
      RequestInfo.TYPE_CREATE,
      /*isFirstUsage=*/false
    )

    providerList = intent.extras?.getParcelableArrayList(
      ProviderData.EXTRA_PROVIDER_DATA_LIST,
      ProviderData::class.java
    ) ?: testProviderList()
  }

  private fun testProviderList(): List<ProviderData> {
    return listOf(
      ProviderData(
        "com.google",
        listOf<Entry>(
          newEntry(1, "elisa.beckett@gmail.com", "Elisa Backett"),
          newEntry(2, "elisa.work@google.com", "Elisa Backett Work"),
        ),
        listOf<Entry>(
          newEntry(3, "Go to Settings", ""),
          newEntry(4, "Switch Account", ""),
        ),
        null
      ),
      ProviderData(
        "com.dashlane",
        listOf<Entry>(
          newEntry(5, "elisa.beckett@dashlane.com", "Elisa Backett"),
          newEntry(6, "elisa.work@dashlane.com", "Elisa Backett Work"),
        ),
        listOf<Entry>(
          newEntry(7, "Manage Accounts", "Manage your accounts in the dashlane app"),
        ),
        null
      ),
      ProviderData(
        "com.lastpass",
        listOf<Entry>(
          newEntry(8, "elisa.beckett@lastpass.com", "Elisa Backett"),
        ),
        listOf<Entry>(),
        null
      )

    )
  }

  private fun newEntry(id: Int, title: String, subtitle: String): Entry {
    val slice = Slice.Builder(
      Entry.CREDENTIAL_MANAGER_ENTRY_URI, SliceSpec(Entry.VERSION, 1)
    )
      .addText(title, null, listOf(Entry.HINT_TITLE))
      .addText(subtitle, null, listOf(Entry.HINT_SUBTITLE))
      .addIcon(
        Icon.createWithResource(context, R.drawable.ic_passkey),
        null,
        listOf(Entry.HINT_ICON))
      .build()
    return Entry(
      id,
      slice
    )
  }

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

    fun setup(
      context: Context,
      intent: Intent,
    ) {
      repo = CredentialManagerRepo(context, intent)
    }

    fun getInstance(): CredentialManagerRepo {
      return repo
    }
  }
}
