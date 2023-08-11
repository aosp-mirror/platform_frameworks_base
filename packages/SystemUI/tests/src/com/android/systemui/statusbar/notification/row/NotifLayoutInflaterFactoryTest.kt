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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_CONTRACTED
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_EXPANDED
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_HEADS_UP
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationFlag
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify

/** Tests for [NotifLayoutInflaterFactory] */
@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class NotifLayoutInflaterFactoryTest : SysuiTestCase() {

    private lateinit var inflaterFactory: NotifLayoutInflaterFactory

    private val attrs: AttributeSet = mock()
    private val row: ExpandableNotificationRow = mock()
    private val textViewExpandedFactory =
        createReplacementViewFactory("TextView", FLAG_CONTENT_VIEW_EXPANDED) { context, _ ->
            Button(context)
        }
    private val textViewCollapsedFactory =
        createReplacementViewFactory("TextView", FLAG_CONTENT_VIEW_CONTRACTED) { context, _ ->
            Button(context)
        }
    private val textViewExpandedFactorySpy = spy(textViewExpandedFactory)
    private val textViewCollapsedFactorySpy = spy(textViewCollapsedFactory)
    private val viewFactorySpies = setOf(textViewExpandedFactorySpy, textViewCollapsedFactorySpy)

    @Test
    fun onCreateView_noMatchingViewForName_returnNull() {
        // GIVEN we have ViewFactories that replaces TextViews in expanded and collapsed layouts
        val layoutType = FLAG_CONTENT_VIEW_EXPANDED
        inflaterFactory = NotifLayoutInflaterFactory(row, layoutType, viewFactorySpies)

        // WHEN we try to inflate an ImageView for the expanded layout
        val createdView = inflaterFactory.onCreateView("ImageView", context, attrs)

        // THEN the inflater factory returns null
        viewFactorySpies.forEach { viewFactory ->
            verify(viewFactory).instantiate(row, layoutType, null, "ImageView", context, attrs)
        }
        assertThat(createdView).isNull()
    }

    @Test
    fun onCreateView_noMatchingViewForLayoutType_returnNull() {
        // GIVEN we have ViewFactories that replaces TextViews in expanded and collapsed layouts
        val layoutType = FLAG_CONTENT_VIEW_HEADS_UP
        inflaterFactory = NotifLayoutInflaterFactory(row, layoutType, viewFactorySpies)

        // WHEN we try to inflate a TextView for the heads-up layout
        val createdView = inflaterFactory.onCreateView("TextView", context, attrs)

        // THEN the inflater factory returns null
        viewFactorySpies.forEach { viewFactory ->
            verify(viewFactory).instantiate(row, layoutType, null, "TextView", context, attrs)
        }
        assertThat(createdView).isNull()
    }

    @Test
    fun onCreateView_matchingViews_returnReplacementView() {
        // GIVEN we have ViewFactories that replaces TextViews in expanded and collapsed layouts
        val layoutType = FLAG_CONTENT_VIEW_EXPANDED
        inflaterFactory = NotifLayoutInflaterFactory(row, layoutType, viewFactorySpies)

        // WHEN we try to inflate a TextView for the expanded layout
        val createdView = inflaterFactory.onCreateView("TextView", context, attrs)

        // THEN the expanded viewFactory returns the replaced view
        verify(textViewCollapsedFactorySpy)
            .instantiate(row, layoutType, null, "TextView", context, attrs)
        assertThat(createdView).isInstanceOf(Button::class.java)
    }

    @Test(expected = IllegalStateException::class)
    fun onCreateView_multipleFactory_throwIllegalStateException() {
        // GIVEN we have two factories that replaces TextViews in expanded layouts
        val layoutType = FLAG_CONTENT_VIEW_EXPANDED
        inflaterFactory =
            NotifLayoutInflaterFactory(
                row,
                layoutType,
                setOf(
                    createReplacementViewFactory("TextView", layoutType) { context, _ ->
                        FrameLayout(context)
                    },
                    createReplacementViewFactory("TextView", layoutType) { context, _ ->
                        LinearLayout(context)
                    }
                )
            )

        // WHEN we try to inflate a TextView for the expanded layout
        inflaterFactory.onCreateView("TextView", mContext, attrs)
    }

    private fun createReplacementViewFactory(
        replacementName: String,
        @InflationFlag replacementLayoutType: Int,
        createView: (context: Context, attrs: AttributeSet) -> View
    ) =
        object : NotifRemoteViewsFactory {
            override fun instantiate(
                row: ExpandableNotificationRow,
                @InflationFlag layoutType: Int,
                parent: View?,
                name: String,
                context: Context,
                attrs: AttributeSet
            ): View? =
                if (replacementName == name && replacementLayoutType == layoutType) {
                    createView(context, attrs)
                } else {
                    null
                }
        }
}
