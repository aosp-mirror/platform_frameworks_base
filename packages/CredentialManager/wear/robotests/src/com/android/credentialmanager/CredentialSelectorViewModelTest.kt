/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.credentialmanager

import org.mockito.kotlin.whenever
import com.android.credentialmanager.model.EntryInfo
import com.android.credentialmanager.model.Request
import androidx.test.filters.SmallTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.Before
import java.util.Collections.emptyList
import org.junit.runner.RunWith
import android.content.Intent
import com.android.credentialmanager.client.CredentialManagerClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import android.credentials.selection.BaseDialogResult
import com.google.common.truth.Truth.assertThat
import org.mockito.kotlin.doReturn
import kotlinx.coroutines.Job
import org.junit.After
import org.robolectric.shadows.ShadowLooper
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Unit tests for [CredentialSelectorViewModel]. */
@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class CredentialSelectorViewModelTest {
    private val testScope = TestScope(UnconfinedTestDispatcher())

    private val stateFlow: MutableStateFlow<Request?> = MutableStateFlow(Request.Create(null))
    private val credentialManagerClient = mock<CredentialManagerClient>{
        on { requests } doReturn stateFlow
    }
    private val mViewModel = CredentialSelectorViewModel(credentialManagerClient)
    private lateinit var job: Job

    val testEntryInfo =
        EntryInfo(
            providerId = "",
            entryKey = "",
            entrySubkey = "",
            pendingIntent = null,
            fillInIntent = null,
            shouldTerminateUiUponSuccessfulProviderResult = true)

    @Before
    fun setUp() {
      job = checkNotNull(mViewModel).uiState.launchIn(testScope)
    }

    @After
    fun teardown() {
        job.cancel()
    }

    @Test
    fun `Setting state to idle when receiving null request`() {
        stateFlow.value = null
        ShadowLooper.idleMainLooper()

        assertThat(mViewModel.uiState.value).isEqualTo(CredentialSelectorUiState.Idle)
    }

    @Test
    fun `Setting state to cancel when receiving Cancel request`() {
        stateFlow.value = Request.Cancel(appName = "appName", token = null)
        ShadowLooper.idleMainLooper()

        assertThat(mViewModel.uiState.value)
            .isEqualTo(CredentialSelectorUiState.Cancel("appName"))
    }

    @Test
    fun `Setting state to create when receiving Create request`() {
        stateFlow.value = Request.Create(token = null)
        ShadowLooper.idleMainLooper()

        assertThat(mViewModel.uiState.value).isEqualTo(CredentialSelectorUiState.Create)
    }

    @Test
    fun `Closing app when receiving Close request`() {
        stateFlow.value = Request.Close(token = null)
        ShadowLooper.idleMainLooper()

        assertThat(mViewModel.uiState.value).isEqualTo(CredentialSelectorUiState.Close)
    }

    @Test
    fun `Updates request`() {
        val intent = Intent()

        mViewModel.updateRequest(intent)

        verify(credentialManagerClient).updateRequest(intent)
    }

    @Test
    fun `Back on a single entry screen closes app`() {
        mViewModel.openSecondaryScreen()
        stateFlow.value = Request.Get(
            token = null,
            resultReceiver = null,
            finalResponseReceiver = null,
            providerInfos = emptyList())

        mViewModel.back()
        ShadowLooper.idleMainLooper()

        assertThat(mViewModel.uiState.value).isEqualTo(CredentialSelectorUiState.Close)
    }

    @Test
    fun `Back on a multiple entry screen gets us back to a primary screen`() {
        mViewModel.openSecondaryScreen()
        stateFlow.value = Request.Get(
            token = null,
            resultReceiver = null,
            finalResponseReceiver = null,
            providerInfos = emptyList())

        mViewModel.back()
        ShadowLooper.idleMainLooper()

        assertThat(mViewModel.uiState.value).isEqualTo(CredentialSelectorUiState.Close)
    }

    @Test
    fun `Back on create request state closes app`() {
        stateFlow.value = Request.Create(token = null)

        mViewModel.back()
        ShadowLooper.idleMainLooper()

        assertThat(mViewModel.uiState.value).isEqualTo(CredentialSelectorUiState.Close)
    }

    @Test
    fun `Back on close request state closes app`() {
        stateFlow.value = Request.Close(token = null)

        mViewModel.back()
        ShadowLooper.idleMainLooper()

        assertThat(mViewModel.uiState.value).isEqualTo(CredentialSelectorUiState.Close)
    }

    @Test
    fun `Back on cancel request state closes app`() {
        stateFlow.value = Request.Cancel(appName = "", token = null)

        mViewModel.back()
        ShadowLooper.idleMainLooper()

        assertThat(mViewModel.uiState.value).isEqualTo(CredentialSelectorUiState.Close)
    }

    @Test
    fun `Back on idle request state closes app`() {
        stateFlow.value = null

        mViewModel.back()
        ShadowLooper.idleMainLooper()

        assertThat(mViewModel.uiState.value).isEqualTo(CredentialSelectorUiState.Close)
    }

    @Test
    fun `Cancel closes the app`() {
        mViewModel.cancel()
        ShadowLooper.idleMainLooper()

        verify(credentialManagerClient).sendError(BaseDialogResult.RESULT_CODE_DIALOG_USER_CANCELED)
        assertThat(mViewModel.uiState.value).isEqualTo(CredentialSelectorUiState.Close)
    }

    @Test
    fun `Send entry selection result closes app and calls client method`() {
        whenever(credentialManagerClient.sendEntrySelectionResult(
            entryInfo = testEntryInfo,
            resultCode = null,
            resultData = null,
            isAutoSelected = false
        )).thenReturn(true)

        mViewModel.sendSelectionResult(
            entryInfo = testEntryInfo,
            resultCode = null,
            resultData = null,
            isAutoSelected = false)
        ShadowLooper.idleMainLooper()

        verify(credentialManagerClient).sendEntrySelectionResult(
            testEntryInfo, null, null, false
        )
        assertThat(mViewModel.uiState.value).isEqualTo(CredentialSelectorUiState.Close)
    }

    @Test
    fun `Send entry selection result does not close app on false return`() {
        whenever(credentialManagerClient.sendEntrySelectionResult(
            entryInfo = testEntryInfo,
            resultCode = null,
            resultData = null,
            isAutoSelected = false
        )).thenReturn(false)
        stateFlow.value = Request.Create(null)

        mViewModel.sendSelectionResult(entryInfo = testEntryInfo, resultCode = null,
            resultData = null, isAutoSelected = false)
        ShadowLooper.idleMainLooper()

        verify(credentialManagerClient).sendEntrySelectionResult(
            entryInfo = testEntryInfo,
            resultCode = null,
            resultData = null,
            isAutoSelected = false
        )
        assertThat(mViewModel.uiState.value).isEqualTo(CredentialSelectorUiState.Create)
    }
}
