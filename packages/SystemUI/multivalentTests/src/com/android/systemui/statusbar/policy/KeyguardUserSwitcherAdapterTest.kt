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
 * limitations under the License
 */

package com.android.systemui.statusbar.policy

import android.content.Context
import android.content.pm.UserInfo
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.util.UserIcons
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.tiles.UserDetailItemView
import com.android.systemui.user.data.source.UserRecord
import com.android.systemui.util.mockito.whenever
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

@RunWith(AndroidJUnit4::class)
@SmallTest
class KeyguardUserSwitcherAdapterTest : SysuiTestCase() {
    @Mock
    private lateinit var userSwitcherController: UserSwitcherController
    @Mock
    private lateinit var parent: ViewGroup
    @Mock
    private lateinit var keyguardUserDetailItemView: KeyguardUserDetailItemView
    @Mock
    private lateinit var otherView: View
    @Mock
    private lateinit var inflatedUserDetailItemView: KeyguardUserDetailItemView
    @Mock
    private lateinit var layoutInflater: LayoutInflater
    @Mock
    private lateinit var keyguardUserSwitcherController: KeyguardUserSwitcherController

    private lateinit var adapter: KeyguardUserSwitcherController.KeyguardUserAdapter
    private lateinit var picture: Bitmap

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(userSwitcherController.isUserSwitcherEnabled).thenReturn(true)

        mContext.addMockSystemService(Context.LAYOUT_INFLATER_SERVICE, layoutInflater)
        `when`(layoutInflater.inflate(anyInt(), any(ViewGroup::class.java), anyBoolean()))
                .thenReturn(inflatedUserDetailItemView)
        adapter = KeyguardUserSwitcherController.KeyguardUserAdapter(
                mContext,
                mContext.resources,
                LayoutInflater.from(mContext),
                userSwitcherController, keyguardUserSwitcherController)
        picture = UserIcons.convertToBitmap(mContext.getDrawable(R.drawable.ic_avatar_user))
    }

    /**
     * Uses the KeyguardUserAdapter to create a UserDetailItemView where the convertView has an
     * incompatible type
     */
    private fun createViewFromDifferentType(
        isCurrentUser: Boolean,
        isGuestUser: Boolean
    ): UserDetailItemView? {
        val user = createUserRecord(isCurrentUser, isGuestUser)
        return adapter.createUserDetailItemView(otherView, parent, user)
    }

    /**
     * Uses the KeyguardUserAdapter to create a UserDetailItemView where the convertView is an
     * instance of KeyguardUserDetailItemView
     */
    private fun createViewFromSameType(
        isCurrentUser: Boolean,
        isGuestUser: Boolean
    ): UserDetailItemView? {
        val user = createUserRecord(isCurrentUser, isGuestUser)
        return adapter.createUserDetailItemView(keyguardUserDetailItemView, parent, user)
    }

    @Test
    fun shouldSetOnClickListener_notCurrentUser_notGuestUser_oldViewIsSameType() {
        val v: UserDetailItemView? = createViewFromSameType(
                isCurrentUser = false, isGuestUser = false)
        assertNotNull(v)
        verify(v)!!.setOnClickListener(adapter)
    }

    @Test
    fun shouldSetOnClickListener_notCurrentUser_guestUser_oldViewIsSameType() {
        val v: UserDetailItemView? = createViewFromSameType(
                isCurrentUser = false, isGuestUser = true)
        assertNotNull(v)
        verify(v)!!.setOnClickListener(adapter)
    }

    @Test
    fun shouldSetOnOnClickListener_currentUser_notGuestUser_oldViewIsSameType() {
        val v: UserDetailItemView? = createViewFromSameType(
                isCurrentUser = true, isGuestUser = false)
        assertNotNull(v)
        verify(v)!!.setOnClickListener(adapter)
    }

    @Test
    fun shouldSetOnClickListener_currentUser_guestUser_oldViewIsSameType() {
        val v: UserDetailItemView? = createViewFromSameType(
                isCurrentUser = true, isGuestUser = true)
        assertNotNull(v)
        verify(v)!!.setOnClickListener(adapter)
    }

    @Test
    fun shouldSetOnClickListener_notCurrentUser_notGuestUser_oldViewIsDifferentType() {
        val v: UserDetailItemView? = createViewFromDifferentType(
                isCurrentUser = false, isGuestUser = false)
        assertNotNull(v)
        verify(v)!!.setOnClickListener(adapter)
    }

    @Test
    fun shouldSetOnClickListener_notCurrentUser_guestUser_oldViewIsDifferentType() {
        val v: UserDetailItemView? = createViewFromDifferentType(
                isCurrentUser = false, isGuestUser = true)
        assertNotNull(v)
        verify(v)!!.setOnClickListener(adapter)
    }

    @Test
    fun shouldSetOnOnClickListener_currentUser_notGuestUser_oldViewIsDifferentType() {
        val v: UserDetailItemView? = createViewFromDifferentType(
                isCurrentUser = true, isGuestUser = false)
        assertNotNull(v)
        verify(v)!!.setOnClickListener(adapter)
    }

    @Test
    fun shouldSetOnClickListener_currentUser_guestUser_oldViewIsDifferentType() {
        val v: UserDetailItemView? = createViewFromDifferentType(
                isCurrentUser = true, isGuestUser = true)
        assertNotNull(v)
        verify(v)!!.setOnClickListener(adapter)
    }

    @Test
    fun testCurrentUserIsAlwaysFirst() {
        `when`(userSwitcherController.users).thenReturn(arrayListOf(
                createUserRecord(isCurrentUser = false, isGuestUser = false),
                createUserRecord(isCurrentUser = true, isGuestUser = false),
                createUserRecord(isCurrentUser = false, isGuestUser = true),
                createUserRecord(isCurrentUser = false, isGuestUser = false)
        ))

        adapter.notifyDataSetChanged()
        assertTrue("Expected current user to be first in list", adapter.getItem(0).isCurrent)
        assertFalse("Did not expect current user in position 1", adapter.getItem(1).isCurrent)
        assertFalse("Did not expect current user in position 2", adapter.getItem(2).isCurrent)
        assertTrue("Expected guest user to remain in position 2", adapter.getItem(2).isGuest)
        assertFalse("Did not expect current user in position 3", adapter.getItem(3).isCurrent)
    }

    private fun createUserRecord(isCurrentUser: Boolean, isGuestUser: Boolean) =
        UserRecord(
            UserInfo(0 /* id */, "name", 0 /* flags */),
            picture,
            isGuestUser,
            isCurrentUser,
            false /* isAddUser */,
            false /* isRestricted */,
            true /* isSwitchToEnabled */,
            false /* isAddSupervisedUser */
        )
}
