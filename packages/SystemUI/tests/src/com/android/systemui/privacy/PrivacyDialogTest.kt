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

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.filters.SmallTest
import com.android.systemui.R
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
class PrivacyDialogTest : SysuiTestCase() {

    companion object {
        private const val TEST_PACKAGE_NAME = "test_pkg"
        private const val TEST_USER_ID = 0
    }

    @Mock
    private lateinit var starter: (String, Int) -> Unit

    private lateinit var dialog: PrivacyDialog

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
    fun testStarterCalledWithCorrectParams() {
        val list = listOf(
                PrivacyDialog.PrivacyElement(
                        PrivacyType.TYPE_MICROPHONE,
                        TEST_PACKAGE_NAME,
                        TEST_USER_ID,
                        "App",
                        null,
                        0L,
                        false,
                        false,
                        false
                )
        )
        dialog = PrivacyDialog(context, list, starter)
        dialog.show()
        dialog.requireViewById<View>(R.id.privacy_item).callOnClick()
        verify(starter).invoke(TEST_PACKAGE_NAME, TEST_USER_ID)
    }

    @Test
    fun testDismissListenerCalledOnDismiss() {
        dialog = PrivacyDialog(context, emptyList(), starter)
        val dismissListener = mock(PrivacyDialog.OnDialogDismissed::class.java)
        dialog.addOnDismissListener(dismissListener)
        dialog.show()

        verify(dismissListener, never()).onDialogDismissed()
        dialog.dismiss()
        verify(dismissListener).onDialogDismissed()
    }

    @Test
    fun testDismissListenerCalledImmediatelyIfDialogAlreadyDismissed() {
        dialog = PrivacyDialog(context, emptyList(), starter)
        val dismissListener = mock(PrivacyDialog.OnDialogDismissed::class.java)
        dialog.show()
        dialog.dismiss()

        dialog.addOnDismissListener(dismissListener)
        verify(dismissListener).onDialogDismissed()
    }

    @Test
    fun testCorrectNumElements() {
        val list = listOf(
                PrivacyDialog.PrivacyElement(
                        PrivacyType.TYPE_CAMERA,
                        TEST_PACKAGE_NAME,
                        TEST_USER_ID,
                        "App",
                        null,
                        0L,
                        true,
                        false,
                        false
                ),
                PrivacyDialog.PrivacyElement(
                        PrivacyType.TYPE_MICROPHONE,
                        TEST_PACKAGE_NAME,
                        TEST_USER_ID,
                        "App",
                        null,
                        0L,
                        false,
                        false,
                        false
                )
        )
        dialog = PrivacyDialog(context, list, starter)
        dialog.show()
        assertThat(dialog.requireViewById<ViewGroup>(R.id.root).childCount).isEqualTo(2)
    }

    @Test
    fun testUsingText() {
        val element = PrivacyDialog.PrivacyElement(
                PrivacyType.TYPE_CAMERA,
                TEST_PACKAGE_NAME,
                TEST_USER_ID,
                "App",
                null,
                0L,
                true,
                false,
                false
        )

        val list = listOf(element)
        dialog = PrivacyDialog(context, list, starter)
        dialog.show()
        assertThat(dialog.requireViewById<TextView>(R.id.text).text).isEqualTo(
                context.getString(
                        R.string.ongoing_privacy_dialog_using_op,
                        element.applicationName,
                        element.type.getName(context)
                )
        )
    }

    @Test
    fun testRecentText() {
        val element = PrivacyDialog.PrivacyElement(
                PrivacyType.TYPE_MICROPHONE,
                TEST_PACKAGE_NAME,
                TEST_USER_ID,
                "App",
                null,
                0L,
                false,
                false,
                false
        )

        val list = listOf(element)
        dialog = PrivacyDialog(context, list, starter)
        dialog.show()
        assertThat(dialog.requireViewById<TextView>(R.id.text).text).isEqualTo(
                context.getString(
                        R.string.ongoing_privacy_dialog_recent_op,
                        element.applicationName,
                        element.type.getName(context)
                )
        )
    }

    @Test
    fun testEnterprise() {
        val element = PrivacyDialog.PrivacyElement(
                PrivacyType.TYPE_MICROPHONE,
                TEST_PACKAGE_NAME,
                TEST_USER_ID,
                "App",
                null,
                0L,
                false,
                true,
                false
        )

        val list = listOf(element)
        dialog = PrivacyDialog(context, list, starter)
        dialog.show()
        assertThat(dialog.requireViewById<TextView>(R.id.text).text.toString()).contains(
                context.getString(R.string.ongoing_privacy_dialog_enterprise)
        )
    }

    @Test
    fun testPhoneCall() {
        val element = PrivacyDialog.PrivacyElement(
                PrivacyType.TYPE_MICROPHONE,
                TEST_PACKAGE_NAME,
                TEST_USER_ID,
                "App",
                null,
                0L,
                false,
                false,
                true
        )

        val list = listOf(element)
        dialog = PrivacyDialog(context, list, starter)
        dialog.show()
        assertThat(dialog.requireViewById<TextView>(R.id.text).text.toString()).contains(
                context.getString(R.string.ongoing_privacy_dialog_phonecall)
        )
    }

    @Test
    fun testPhoneCallNotClickable() {
        val element = PrivacyDialog.PrivacyElement(
                PrivacyType.TYPE_MICROPHONE,
                TEST_PACKAGE_NAME,
                TEST_USER_ID,
                "App",
                null,
                0L,
                false,
                false,
                true
        )

        val list = listOf(element)
        dialog = PrivacyDialog(context, list, starter)
        dialog.show()
        assertThat(dialog.requireViewById<View>(R.id.privacy_item).isClickable).isFalse()
        assertThat(dialog.requireViewById<View>(R.id.chevron).visibility).isEqualTo(View.GONE)
    }

    @Test
    fun testAttribution() {
        val element = PrivacyDialog.PrivacyElement(
                PrivacyType.TYPE_MICROPHONE,
                TEST_PACKAGE_NAME,
                TEST_USER_ID,
                "App",
                "attribution",
                0L,
                false,
                false,
                true
        )

        val list = listOf(element)
        dialog = PrivacyDialog(context, list, starter)
        dialog.show()
        assertThat(dialog.requireViewById<TextView>(R.id.text).text.toString()).contains(
                context.getString(
                        R.string.ongoing_privacy_dialog_attribution_text,
                        element.attribution
                )
        )
    }
}