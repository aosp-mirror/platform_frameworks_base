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
 * limitations under the License.
 */

package com.android.server.rollback;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;

@RunWith(JUnit4.class)
public class RollbackUnitTest {

    @Test
    public void newEmptyStagedRollbackDefaults() {
        int rollbackId = 123;
        int sessionId = 567;
        File file = new File("/test/testing");

        Rollback rollback = new Rollback(rollbackId, file, sessionId);

        assertThat(rollback.isEnabling()).isTrue();
        assertThat(rollback.getBackupDir().getAbsolutePath()).isEqualTo("/test/testing");
        assertThat(rollback.isStaged()).isTrue();
        assertThat(rollback.getStagedSessionId()).isEqualTo(567);
    }

    @Test
    public void newEmptyNonStagedRollbackDefaults() {
        int rollbackId = 123;
        File file = new File("/test/testing");

        Rollback rollback = new Rollback(rollbackId, file, -1);

        assertThat(rollback.isEnabling()).isTrue();
        assertThat(rollback.getBackupDir().getAbsolutePath()).isEqualTo("/test/testing");
        assertThat(rollback.isStaged()).isFalse();
    }

    @Test
    public void rollbackStateChanges() {
        Rollback rollback = new Rollback(123, new File("/test/testing"), -1);

        assertThat(rollback.isEnabling()).isTrue();
        assertThat(rollback.isAvailable()).isFalse();
        assertThat(rollback.isCommitted()).isFalse();

        rollback.setAvailable();

        assertThat(rollback.isEnabling()).isFalse();
        assertThat(rollback.isAvailable()).isTrue();
        assertThat(rollback.isCommitted()).isFalse();

        rollback.setCommitted();

        assertThat(rollback.isEnabling()).isFalse();
        assertThat(rollback.isAvailable()).isFalse();
        assertThat(rollback.isCommitted()).isTrue();
    }

}
