/*
 * Copyright (C) 2023 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.systemui.statusbar.notification.row

import android.content.Context
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertNotNull
import junit.framework.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

/** Tests for [NotifLayoutInflaterFactory] */
@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class NotifLayoutInflaterFactoryTest : SysuiTestCase() {

    @Mock private lateinit var attrs: AttributeSet

    @Before
    fun before() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun onCreateView_notMatchingViews_returnNull() {
        // GIVEN
        val layoutInflaterFactory =
            createNotifLayoutInflaterFactoryImpl(
                setOf(
                    createReplacementViewFactory("TextView") { context, attrs ->
                        FrameLayout(context)
                    }
                )
            )

        // WHEN
        val createView = layoutInflaterFactory.onCreateView("ImageView", mContext, attrs)

        // THEN
        assertNull(createView)
    }

    @Test
    fun onCreateView_matchingViews_returnReplacementView() {
        // GIVEN
        val layoutInflaterFactory =
            createNotifLayoutInflaterFactoryImpl(
                setOf(
                    createReplacementViewFactory("TextView") { context, attrs ->
                        FrameLayout(context)
                    }
                )
            )

        // WHEN
        val createView = layoutInflaterFactory.onCreateView("TextView", mContext, attrs)

        // THEN
        assertNotNull(createView)
        assertEquals(requireNotNull(createView)::class.java, FrameLayout::class.java)
    }

    @Test(expected = IllegalStateException::class)
    fun onCreateView_multipleFactory_throwIllegalStateException() {
        // GIVEN
        val layoutInflaterFactory =
            createNotifLayoutInflaterFactoryImpl(
                setOf(
                    createReplacementViewFactory("TextView") { context, attrs ->
                        FrameLayout(context)
                    },
                    createReplacementViewFactory("TextView") { context, attrs ->
                        LinearLayout(context)
                    }
                )
            )

        // WHEN
        layoutInflaterFactory.onCreateView("TextView", mContext, attrs)
    }

    private fun createNotifLayoutInflaterFactoryImpl(
        replacementViewFactories: Set<@JvmSuppressWildcards NotifRemoteViewsFactory>
    ) = NotifLayoutInflaterFactory(DumpManager(), replacementViewFactories)

    private fun createReplacementViewFactory(
        replacementName: String,
        createView: (context: Context, attrs: AttributeSet) -> View
    ) =
        object : NotifRemoteViewsFactory {
            override fun instantiate(
                parent: View?,
                name: String,
                context: Context,
                attrs: AttributeSet
            ): View? =
                if (replacementName == name) {
                    createView(context, attrs)
                } else {
                    null
                }
        }
}
