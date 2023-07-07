/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.pm.test.uninstall

import android.app.PackageDeleteObserver
import android.content.Intent
import android.content.pm.IPackageDeleteObserver2
import android.content.pm.PackageManager
import android.content.pm.PackageManager.UninstallCompleteCallback
import android.os.Parcel
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations.initMocks
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

class UninstallCompleteCallbackTest {

    val PACKAGE_NAME: String = "com.example.package"
    val ERROR_MSG: String = "no error"

    @get:Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @Mock
    lateinit var mockAdapter: PackageDeleteObserver

    val mockBinder: IPackageDeleteObserver2.Stub = object : IPackageDeleteObserver2.Stub() {
        override fun onUserActionRequired(intent: Intent) {
            mockAdapter.onUserActionRequired(intent)
        }
        override fun onPackageDeleted(basePackageName: String, returnCode: Int, msg: String) {
            mockAdapter.onPackageDeleted(basePackageName, returnCode, msg)
        }
    }

    @Before
    fun setUp() {
        initMocks(this)
    }

    @Test
    fun testCallDelegation () {
        doReturn(mockBinder).`when`(mockAdapter).binder

        val callback = UninstallCompleteCallback(mockAdapter.binder.asBinder())
        callback.onUninstallComplete(PACKAGE_NAME, PackageManager.DELETE_SUCCEEDED, ERROR_MSG)

        verify(mockAdapter, times(1)).onPackageDeleted(PACKAGE_NAME,
            PackageManager.DELETE_SUCCEEDED, ERROR_MSG)
    }

    @Test
    fun testClassIsParcelable() {
        doReturn(mockBinder).`when`(mockAdapter).binder

        val callback = UninstallCompleteCallback(mockAdapter.binder.asBinder())

        val parcel = Parcel.obtain()
        callback.writeToParcel(parcel, callback.describeContents())
        parcel.setDataPosition(0)

        val callbackFromParcel = UninstallCompleteCallback.CREATOR.createFromParcel(parcel)

        callbackFromParcel.onUninstallComplete(PACKAGE_NAME, PackageManager.DELETE_SUCCEEDED,
                ERROR_MSG)

        verify(mockAdapter, times(1)).onPackageDeleted(PACKAGE_NAME,
            PackageManager.DELETE_SUCCEEDED, ERROR_MSG)
    }
}
