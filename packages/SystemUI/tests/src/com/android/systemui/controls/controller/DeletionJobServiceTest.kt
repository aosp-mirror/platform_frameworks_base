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

package com.android.systemui.controls.controller

import android.app.job.JobParameters
import android.content.Context
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.util.concurrent.TimeUnit

@SmallTest
@RunWith(AndroidTestingRunner::class)
class DeletionJobServiceTest : SysuiTestCase() {

    @Mock
    private lateinit var context: Context

    private lateinit var service: AuxiliaryPersistenceWrapper.DeletionJobService

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        service = AuxiliaryPersistenceWrapper.DeletionJobService()
        service.attachContext(context)
    }

    @Test
    fun testOnStartJob() {
        // false means job is terminated
        assertFalse(service.onStartJob(mock(JobParameters::class.java)))
        verify(context).deleteFile(AuxiliaryPersistenceWrapper.AUXILIARY_FILE_NAME)
    }

    @Test
    fun testOnStopJob() {
        // true means run after backoff
        assertTrue(service.onStopJob(mock(JobParameters::class.java)))
    }

    @Test
    fun testJobHasRightParameters() {
        val userId = 10
        `when`(context.userId).thenReturn(userId)
        `when`(context.packageName).thenReturn(mContext.packageName)

        val jobInfo = AuxiliaryPersistenceWrapper.DeletionJobService.getJobForContext(context)
        assertEquals(
            AuxiliaryPersistenceWrapper.DeletionJobService.DELETE_FILE_JOB_ID + userId, jobInfo.id)
        assertTrue(jobInfo.isPersisted)
        assertEquals(TimeUnit.DAYS.toMillis(7), jobInfo.minLatencyMillis)
    }
}