/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.privacy

import android.content.Intent
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.TextView
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class PrivacyDialogV2Test : SysuiTestCase() {

    companion object {
        private const val TEST_PACKAGE_NAME = "test_pkg"
        private const val TEST_USER_ID = 0
        private const val TEST_PERM_GROUP = "test_perm_group"

        private val TEST_INTENT = Intent("test_intent_action")

        private fun createPrivacyElement(
            type: PrivacyType = PrivacyType.TYPE_MICROPHONE,
            packageName: String = TEST_PACKAGE_NAME,
            userId: Int = TEST_USER_ID,
            applicationName: CharSequence = "App",
            attributionTag: CharSequence? = null,
            attributionLabel: CharSequence? = null,
            proxyLabel: CharSequence? = null,
            lastActiveTimestamp: Long = 0L,
            isActive: Boolean = false,
            isPhoneCall: Boolean = false,
            isService: Boolean = false,
            permGroupName: String = TEST_PERM_GROUP,
            navigationIntent: Intent = TEST_INTENT
        ) =
            PrivacyDialogV2.PrivacyElement(
                type,
                packageName,
                userId,
                applicationName,
                attributionTag,
                attributionLabel,
                proxyLabel,
                lastActiveTimestamp,
                isActive,
                isPhoneCall,
                isService,
                permGroupName,
                navigationIntent
            )
    }

    @Mock private lateinit var manageApp: (String, Int, Intent) -> Unit
    @Mock private lateinit var closeApp: (String, Int) -> Unit
    @Mock private lateinit var openPrivacyDashboard: () -> Unit
    private lateinit var dialog: PrivacyDialogV2

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @After
    fun teardown() {
        if (this::dialog.isInitialized) {
            dialog.dismiss()
        }
    }

    @Test
    fun testManageAppCalledWithCorrectParams() {
        val list = listOf(createPrivacyElement())
        dialog = PrivacyDialogV2(context, list, manageApp, closeApp, openPrivacyDashboard)
        dialog.show()

        dialog.requireViewById<View>(R.id.privacy_dialog_manage_app_button).callOnClick()

        verify(manageApp).invoke(TEST_PACKAGE_NAME, TEST_USER_ID, TEST_INTENT)
    }

    @Test
    fun testCloseAppCalledWithCorrectParams() {
        val list = listOf(createPrivacyElement(isActive = true))
        dialog = PrivacyDialogV2(context, list, manageApp, closeApp, openPrivacyDashboard)
        dialog.show()

        dialog.requireViewById<View>(R.id.privacy_dialog_close_app_button).callOnClick()

        verify(closeApp).invoke(TEST_PACKAGE_NAME, TEST_USER_ID)
    }

    @Test
    fun testCloseAppMissingForService() {
        val list = listOf(createPrivacyElement(isActive = true, isService = true))
        dialog = PrivacyDialogV2(context, list, manageApp, closeApp, openPrivacyDashboard)

        dialog.show()

        assertThat(dialog.findViewById<View>(R.id.privacy_dialog_manage_app_button)).isNotNull()
        assertThat(dialog.findViewById<View>(R.id.privacy_dialog_close_app_button)).isNull()
    }

    @Test
    fun testMoreButton() {
        dialog = PrivacyDialogV2(context, emptyList(), manageApp, closeApp, openPrivacyDashboard)
        dialog.show()

        dialog.requireViewById<View>(R.id.privacy_dialog_more_button).callOnClick()

        verify(openPrivacyDashboard).invoke()
    }

    @Test
    fun testCloseButton() {
        dialog = PrivacyDialogV2(context, emptyList(), manageApp, closeApp, openPrivacyDashboard)
        val dismissListener = mock(PrivacyDialogV2.OnDialogDismissed::class.java)
        dialog.addOnDismissListener(dismissListener)
        dialog.show()
        verify(dismissListener, never()).onDialogDismissed()

        dialog.requireViewById<View>(R.id.privacy_dialog_close_button).callOnClick()

        verify(dismissListener).onDialogDismissed()
    }

    @Test
    fun testDismissListenerCalledOnDismiss() {
        dialog = PrivacyDialogV2(context, emptyList(), manageApp, closeApp, openPrivacyDashboard)
        val dismissListener = mock(PrivacyDialogV2.OnDialogDismissed::class.java)
        dialog.addOnDismissListener(dismissListener)
        dialog.show()
        verify(dismissListener, never()).onDialogDismissed()

        dialog.dismiss()

        verify(dismissListener).onDialogDismissed()
    }

    @Test
    fun testDismissListenerCalledImmediatelyIfDialogAlreadyDismissed() {
        dialog = PrivacyDialogV2(context, emptyList(), manageApp, closeApp, openPrivacyDashboard)
        val dismissListener = mock(PrivacyDialogV2.OnDialogDismissed::class.java)
        dialog.show()
        dialog.dismiss()

        dialog.addOnDismissListener(dismissListener)

        verify(dismissListener).onDialogDismissed()
    }

    @Test
    fun testCorrectNumElements() {
        val list =
            listOf(
                createPrivacyElement(type = PrivacyType.TYPE_CAMERA, isActive = true),
                createPrivacyElement()
            )
        dialog = PrivacyDialogV2(context, list, manageApp, closeApp, openPrivacyDashboard)

        dialog.show()

        assertThat(
                dialog.requireViewById<ViewGroup>(R.id.privacy_dialog_items_container).childCount
            )
            .isEqualTo(2)
    }

    @Test
    fun testHeaderText() {
        val list = listOf(createPrivacyElement())
        dialog = PrivacyDialogV2(context, list, manageApp, closeApp, openPrivacyDashboard)

        dialog.show()

        assertThat(dialog.requireViewById<TextView>(R.id.privacy_dialog_item_header_title).text)
            .isEqualTo(TEST_PERM_GROUP)
    }

    @Test
    fun testUsingText() {
        val list = listOf(createPrivacyElement(type = PrivacyType.TYPE_CAMERA, isActive = true))
        dialog = PrivacyDialogV2(context, list, manageApp, closeApp, openPrivacyDashboard)

        dialog.show()

        assertThat(dialog.requireViewById<TextView>(R.id.privacy_dialog_item_header_summary).text)
            .isEqualTo("In use by App")
    }

    @Test
    fun testRecentText() {
        val list = listOf(createPrivacyElement())
        dialog = PrivacyDialogV2(context, list, manageApp, closeApp, openPrivacyDashboard)

        dialog.show()

        assertThat(dialog.requireViewById<TextView>(R.id.privacy_dialog_item_header_summary).text)
            .isEqualTo("Recently used by App")
    }

    @Test
    fun testPhoneCall() {
        val list = listOf(createPrivacyElement(isPhoneCall = true))
        dialog = PrivacyDialogV2(context, list, manageApp, closeApp, openPrivacyDashboard)

        dialog.show()

        assertThat(dialog.requireViewById<TextView>(R.id.privacy_dialog_item_header_summary).text)
            .isEqualTo("Recently used in phone call")
    }

    @Test
    fun testPhoneCallNotClickable() {
        val list = listOf(createPrivacyElement(isPhoneCall = true))
        dialog = PrivacyDialogV2(context, list, manageApp, closeApp, openPrivacyDashboard)

        dialog.show()

        assertThat(dialog.requireViewById<View>(R.id.privacy_dialog_item_card).isClickable)
            .isFalse()
        assertThat(
                dialog
                    .requireViewById<View>(R.id.privacy_dialog_item_header_expand_toggle)
                    .visibility
            )
            .isEqualTo(View.GONE)
    }

    @Test
    fun testProxyLabel() {
        val list = listOf(createPrivacyElement(proxyLabel = "proxy label"))
        dialog = PrivacyDialogV2(context, list, manageApp, closeApp, openPrivacyDashboard)

        dialog.show()

        assertThat(dialog.requireViewById<TextView>(R.id.privacy_dialog_item_header_summary).text)
            .isEqualTo("Recently used by App (proxy label)")
    }

    @Test
    fun testSubattribution() {
        val list =
            listOf(
                createPrivacyElement(
                    attributionLabel = "For subattribution",
                    isActive = true,
                    isService = true
                )
            )
        dialog = PrivacyDialogV2(context, list, manageApp, closeApp, openPrivacyDashboard)

        dialog.show()

        assertThat(dialog.requireViewById<TextView>(R.id.privacy_dialog_item_header_summary).text)
            .isEqualTo("In use by App (For subattribution)")
    }

    @Test
    fun testSubattributionAndProxyLabel() {
        val list =
            listOf(
                createPrivacyElement(
                    attributionLabel = "For subattribution",
                    proxyLabel = "proxy label",
                    isActive = true
                )
            )
        dialog = PrivacyDialogV2(context, list, manageApp, closeApp, openPrivacyDashboard)
        dialog.show()
        assertThat(dialog.requireViewById<TextView>(R.id.privacy_dialog_item_header_summary).text)
            .isEqualTo("In use by App (For subattribution \u2022 proxy label)")
    }

    @Test
    fun testDialogHasTitle() {
        val list = listOf(createPrivacyElement())
        dialog = PrivacyDialogV2(context, list, manageApp, closeApp, openPrivacyDashboard)
        dialog.show()

        assertThat(dialog.window?.attributes?.title).isEqualTo("Microphone & Camera")
    }

    @Test
    fun testDialogIsFullscreen() {
        dialog = PrivacyDialogV2(context, emptyList(), manageApp, closeApp, openPrivacyDashboard)
        dialog.show()

        assertThat(dialog.window?.attributes?.width).isEqualTo(MATCH_PARENT)
        assertThat(dialog.window?.attributes?.height).isEqualTo(MATCH_PARENT)
    }
}
