package com.android.credentialmanager.di

import android.app.Application
import com.android.credentialmanager.CredentialSelectorApp
import com.android.credentialmanager.repository.PasswordRepository
import com.android.credentialmanager.repository.RequestRepository

// TODO b/301601582 add Hilt for dependency injection

fun CredentialSelectorApp.inject() {
    requestRepository = requestRepository(application = this)
    passwordRepository = passwordRepository()
}

private fun requestRepository(
    application: Application,
): RequestRepository = RequestRepository(
    application = application,
)

private fun passwordRepository(): PasswordRepository = PasswordRepository()
