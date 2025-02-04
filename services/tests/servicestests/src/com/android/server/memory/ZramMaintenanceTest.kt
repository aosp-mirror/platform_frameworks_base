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

package com.android.server.memory

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.os.IMmd
import android.os.PersistableBundle
import android.os.RemoteException
import android.testing.TestableContext
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry

import com.google.common.truth.Truth.assertThat

import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

private fun generateJobParameters(jobId: Int, extras: PersistableBundle): JobParameters {
    return JobParameters(
        null, "", jobId, extras, null, null, 0, false, false, false, null, null, null
    )
}

@SmallTest
@RunWith(JUnit4::class)
class ZramMaintenanceTest {
    private val context = TestableContext(InstrumentationRegistry.getInstrumentation().context)

    @Captor
    private lateinit var jobInfoCaptor: ArgumentCaptor<JobInfo>

    @Mock
    private lateinit var mockJobScheduler: JobScheduler

    @Mock
    private lateinit var mockMmd: IMmd

    @Before
    @Throws(RemoteException::class)
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        context.addMockSystemService(JobScheduler::class.java, mockJobScheduler)
    }

    @Test
    fun startZramMaintenance() {
        ZramMaintenance.startZramMaintenance(context)

        verify(mockJobScheduler, times(1)).schedule(jobInfoCaptor.capture())
        val job = jobInfoCaptor.value
        assertThat(job.id).isEqualTo(ZramMaintenance.JOB_ID)
        assertThat(job.extras.getBoolean(ZramMaintenance.KEY_CHECK_STATUS)).isTrue()
    }

    @Test
    fun startJobForFirstTime() {
        val extras = PersistableBundle()
        extras.putBoolean(ZramMaintenance.KEY_CHECK_STATUS, true)
        val params = generateJobParameters(
            ZramMaintenance.JOB_ID,
            extras,
        )
        `when`(mockMmd.isZramMaintenanceSupported()).thenReturn(true)

        ZramMaintenance.startJob(context, params, mockMmd)

        verify(mockMmd, times(1)).isZramMaintenanceSupported()
        verify(mockMmd, times(1)).doZramMaintenanceAsync()
        verify(mockJobScheduler, times(1)).schedule(jobInfoCaptor.capture())
        val nextJob = jobInfoCaptor.value
        assertThat(nextJob.id).isEqualTo(ZramMaintenance.JOB_ID)
        assertThat(nextJob.extras.getBoolean(ZramMaintenance.KEY_CHECK_STATUS)).isFalse()
    }

    @Test
    fun startJobWithoutCheckStatus() {
        val extras = PersistableBundle()
        extras.putBoolean(ZramMaintenance.KEY_CHECK_STATUS, false)
        val params = generateJobParameters(
            ZramMaintenance.JOB_ID,
            extras,
        )

        ZramMaintenance.startJob(context, params, mockMmd)

        verify(mockMmd, never()).isZramMaintenanceSupported()
        verify(mockMmd, times(1)).doZramMaintenanceAsync()
        verify(mockJobScheduler, times(1)).schedule(jobInfoCaptor.capture())
        val nextJob = jobInfoCaptor.value
        assertThat(nextJob.id).isEqualTo(ZramMaintenance.JOB_ID)
        assertThat(nextJob.extras.getBoolean(ZramMaintenance.KEY_CHECK_STATUS)).isFalse()
    }

    @Test
    fun startJobZramIsDisabled() {
        val extras = PersistableBundle()
        extras.putBoolean(ZramMaintenance.KEY_CHECK_STATUS, true)
        val params = generateJobParameters(
            ZramMaintenance.JOB_ID,
            extras,
        )
        `when`(mockMmd.isZramMaintenanceSupported()).thenReturn(false)

        ZramMaintenance.startJob(context, params, mockMmd)

        verify(mockMmd, times(1)).isZramMaintenanceSupported()
        verify(mockMmd, never()).doZramMaintenanceAsync()
        verify(mockJobScheduler, never()).schedule(any())
    }

    @Test
    fun startJobMmdIsNotReadyYet() {
        val extras = PersistableBundle()
        extras.putBoolean(ZramMaintenance.KEY_CHECK_STATUS, true)
        val params = generateJobParameters(
            ZramMaintenance.JOB_ID,
            extras,
        )

        ZramMaintenance.startJob(context, params, null)

        verify(mockJobScheduler, times(1)).schedule(jobInfoCaptor.capture())
        val nextJob = jobInfoCaptor.value
        assertThat(nextJob.id).isEqualTo(ZramMaintenance.JOB_ID)
        assertThat(nextJob.extras.getBoolean(ZramMaintenance.KEY_CHECK_STATUS)).isTrue()
    }
}