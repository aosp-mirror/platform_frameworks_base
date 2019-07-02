/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.backup.keyvalue;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.expectThrows;

import android.app.backup.BackupTransport;
import android.platform.test.annotations.Presubmit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class TaskExceptionTest {
    @Test
    public void testStateCompromised() {
        TaskException exception = TaskException.stateCompromised();

        assertThat(exception.isStateCompromised()).isTrue();
        assertThat(exception.getStatus()).isEqualTo(BackupTransport.TRANSPORT_ERROR);
    }

    @Test
    public void testStateCompromised_whenCauseInstanceOfTaskException() {
        Exception cause = TaskException.forStatus(BackupTransport.TRANSPORT_NOT_INITIALIZED);

        TaskException exception = TaskException.stateCompromised(cause);

        assertThat(exception.isStateCompromised()).isTrue();
        assertThat(exception.getStatus()).isEqualTo(BackupTransport.TRANSPORT_NOT_INITIALIZED);
        assertThat(exception.getCause()).isEqualTo(cause);
    }

    @Test
    public void testStateCompromised_whenCauseNotInstanceOfTaskException() {
        Exception cause = new IOException();

        TaskException exception = TaskException.stateCompromised(cause);

        assertThat(exception.isStateCompromised()).isTrue();
        assertThat(exception.getStatus()).isEqualTo(BackupTransport.TRANSPORT_ERROR);
        assertThat(exception.getCause()).isEqualTo(cause);
    }

    @Test
    public void testForStatus_whenTransportOk_throws() {
        expectThrows(
                IllegalArgumentException.class,
                () -> TaskException.forStatus(BackupTransport.TRANSPORT_OK));
    }

    @Test
    public void testForStatus_whenTransportNotInitialized() {
        TaskException exception =
                TaskException.forStatus(BackupTransport.TRANSPORT_NOT_INITIALIZED);

        assertThat(exception.isStateCompromised()).isFalse();
        assertThat(exception.getStatus()).isEqualTo(BackupTransport.TRANSPORT_NOT_INITIALIZED);
    }

    @Test
    public void testCausedBy_whenCauseInstanceOfTaskException_returnsCause() {
        Exception cause = TaskException.forStatus(BackupTransport.TRANSPORT_NOT_INITIALIZED);

        TaskException exception = TaskException.causedBy(cause);

        assertThat(exception).isEqualTo(cause);
    }

    @Test
    public void testCausedBy_whenCauseNotInstanceOfTaskException() {
        Exception cause = new IOException();

        TaskException exception = TaskException.causedBy(cause);

        assertThat(exception).isNotEqualTo(cause);
        assertThat(exception.isStateCompromised()).isFalse();
        assertThat(exception.getStatus()).isEqualTo(BackupTransport.TRANSPORT_ERROR);
        assertThat(exception.getCause()).isEqualTo(cause);
    }

    @Test
    public void testCreate() {
        TaskException exception = TaskException.create();

        assertThat(exception.isStateCompromised()).isFalse();
        assertThat(exception.getStatus()).isEqualTo(BackupTransport.TRANSPORT_ERROR);
    }

    @Test
    public void testIsStateCompromised_whenStateCompromised_returnsTrue() {
        TaskException taskException = TaskException.stateCompromised();

        boolean stateCompromised = taskException.isStateCompromised();

        assertThat(stateCompromised).isTrue();
    }

    @Test
    public void testIsStateCompromised_whenCreatedWithCreate_returnsFalse() {
        TaskException taskException = TaskException.create();

        boolean stateCompromised = taskException.isStateCompromised();

        assertThat(stateCompromised).isFalse();
    }

    @Test
    public void testGetStatus_whenStatusIsTransportPackageRejected() {
        TaskException taskException =
                TaskException.forStatus(BackupTransport.TRANSPORT_PACKAGE_REJECTED);

        int status = taskException.getStatus();

        assertThat(status).isEqualTo(BackupTransport.TRANSPORT_PACKAGE_REJECTED);
    }

    @Test
    public void testGetStatus_whenStatusIsTransportNotInitialized() {
        TaskException taskException =
                TaskException.forStatus(BackupTransport.TRANSPORT_NOT_INITIALIZED);

        int status = taskException.getStatus();

        assertThat(status).isEqualTo(BackupTransport.TRANSPORT_NOT_INITIALIZED);
    }
}
