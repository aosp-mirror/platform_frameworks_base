/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.permission.persistence

import android.content.ApexEnvironment
import android.content.Context
import android.os.Process
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations.initMocks
import org.mockito.MockitoSession
import org.mockito.quality.Strictness
import java.io.File

@RunWith(AndroidJUnit4::class)
class RuntimePermissionsPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().context

    private lateinit var mockDataDirectory: File

    private lateinit var mockitoSession: MockitoSession
    @Mock
    lateinit var apexEnvironment: ApexEnvironment

    private val persistence = RuntimePermissionsPersistence.createInstance()
    private val permissionState = RuntimePermissionsState.PermissionState("permission", true, 3)
    private val state = RuntimePermissionsState(
        1, "fingerprint", mapOf("package" to listOf(permissionState)),
        mapOf("sharedUser" to listOf(permissionState))
    )
    private val user = Process.myUserHandle()

    @Before
    fun createMockDataDirectory() {
        mockDataDirectory = context.getDir("mock_data", Context.MODE_PRIVATE)
        mockDataDirectory.listFiles()!!.forEach { assertThat(it.deleteRecursively()).isTrue() }
    }

    @Before
    fun mockApexEnvironment() {
        initMocks(this)
        mockitoSession = mockitoSession()
            .mockStatic(ApexEnvironment::class.java)
            .strictness(Strictness.LENIENT)
            .startMocking()
        `when`(ApexEnvironment.getApexEnvironment(eq(APEX_MODULE_NAME))).thenReturn(apexEnvironment)
        `when`(apexEnvironment.getDeviceProtectedDataDirForUser(any(UserHandle::class.java))).then {
            File(mockDataDirectory, it.arguments[0].toString()).also { it.mkdirs() }
        }
    }

    @After
    fun finishMockingApexEnvironment() {
        mockitoSession.finishMocking()
    }

    @Test
    fun testReadWrite() {
        persistence.writeForUser(state, user)
        val persistedState = persistence.readForUser(user)

        assertThat(persistedState).isEqualTo(state)
        assertThat(persistedState!!.version).isEqualTo(state.version)
        assertThat(persistedState.fingerprint).isEqualTo(state.fingerprint)
        assertThat(persistedState.packagePermissions).isEqualTo(state.packagePermissions)
        val persistedPermissionState = persistedState.packagePermissions.values.first().first()
        assertThat(persistedPermissionState.name).isEqualTo(permissionState.name)
        assertThat(persistedPermissionState.isGranted).isEqualTo(permissionState.isGranted)
        assertThat(persistedPermissionState.flags).isEqualTo(permissionState.flags)
        assertThat(persistedState.sharedUserPermissions).isEqualTo(state.sharedUserPermissions)
    }

    @Test
    fun testDelete() {
        persistence.writeForUser(state, user)
        persistence.deleteForUser(user)
        val persistedState = persistence.readForUser(user)

        assertThat(persistedState).isNull()
    }

    companion object {
        private const val APEX_MODULE_NAME = "com.android.permission"
    }
}
