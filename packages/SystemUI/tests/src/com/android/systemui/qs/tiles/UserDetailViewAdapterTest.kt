/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.qs.tiles

import android.content.Context
import android.content.pm.UserInfo
import android.graphics.Bitmap
import android.testing.AndroidTestingRunner
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.test.filters.SmallTest
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.internal.util.UserIcons
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.qs.QSUserSwitcherEvent
import com.android.systemui.statusbar.policy.UserSwitcherController
import com.android.systemui.user.data.source.UserRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
class UserDetailViewAdapterTest : SysuiTestCase() {

    @Mock private lateinit var mUserSwitcherController: UserSwitcherController
    @Mock private lateinit var mParent: ViewGroup
    @Mock private lateinit var mUserDetailItemView: UserDetailItemView
    @Mock private lateinit var mOtherView: View
    @Mock private lateinit var mInflatedUserDetailItemView: UserDetailItemView
    @Mock private lateinit var mLayoutInflater: LayoutInflater
    private var falsingManagerFake: FalsingManagerFake = FalsingManagerFake()
    private lateinit var adapter: UserDetailView.Adapter
    private lateinit var uiEventLogger: UiEventLoggerFake
    private lateinit var mPicture: Bitmap

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        uiEventLogger = UiEventLoggerFake()

        mContext.addMockSystemService(Context.LAYOUT_INFLATER_SERVICE, mLayoutInflater)
        `when`(mLayoutInflater.inflate(anyInt(), any(ViewGroup::class.java), anyBoolean()))
            .thenReturn(mInflatedUserDetailItemView)
        `when`(mParent.context).thenReturn(mContext)
        adapter =
            UserDetailView.Adapter(
                mContext,
                mUserSwitcherController,
                uiEventLogger,
                falsingManagerFake
            )
        mPicture = UserIcons.convertToBitmap(mContext.getDrawable(R.drawable.ic_avatar_user))
    }

    private fun clickableTest(
        current: Boolean,
        guest: Boolean,
        convertView: View,
        shouldBeClickable: Boolean
    ) {
        val user = createUserRecord(current, guest)
        val v = adapter.createUserDetailItemView(convertView, mParent, user)
        if (shouldBeClickable) {
            verify(v).setOnClickListener(adapter)
        } else {
            verify(v).setOnClickListener(null)
        }
    }

    @Test
    fun testUserSwitchLog() {
        val user = createUserRecord(false /* current */, false /* guest */)
        val v = adapter.createUserDetailItemView(View(mContext), mParent, user)
        `when`(v.tag).thenReturn(user)
        adapter.onClick(v)

        assertEquals(1, uiEventLogger.numLogs())
        assertEquals(QSUserSwitcherEvent.QS_USER_SWITCH.id, uiEventLogger.eventId(0))
    }

    @Test
    fun testGuestIsClickable_differentViews_notCurrent() {
        clickableTest(false, true, mOtherView, true)
    }

    @Test
    fun testGuestIsClickable_differentViews_Current() {
        clickableTest(true, true, mOtherView, true)
    }

    @Test
    fun testGuestIsClickable_sameView_notCurrent() {
        clickableTest(false, true, mUserDetailItemView, true)
    }

    @Test
    fun testGuestIsClickable_sameView_Current() {
        clickableTest(true, true, mUserDetailItemView, true)
    }

    @Test
    fun testNotGuestCurrentUserIsNotClickable_otherView() {
        clickableTest(true, false, mOtherView, false)
    }

    @Test
    fun testNotGuestCurrentUserIsNotClickable_sameView() {
        clickableTest(true, false, mUserDetailItemView, false)
    }

    @Test
    fun testNotGuestNotCurrentUserIsClickable_otherView() {
        clickableTest(false, false, mOtherView, true)
    }

    @Test
    fun testNotGuestNotCurrentUserIsClickable_sameView() {
        clickableTest(false, false, mUserDetailItemView, true)
    }

    @Test
    fun testManageUsersIsNotAvailable() {
        assertNull(adapter.users.find { it.isManageUsers })
    }

    private fun createUserRecord(current: Boolean, guest: Boolean) =
        UserRecord(
            UserInfo(0 /* id */, "name", 0 /* flags */),
            mPicture,
            guest,
            current,
            false /* isAddUser */,
            false /* isRestricted */,
            true /* isSwitchToEnabled */,
            false /* isAddSupervisedUser */
        )
}
