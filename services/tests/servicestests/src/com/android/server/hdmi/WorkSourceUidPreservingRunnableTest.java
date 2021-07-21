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
 * limitations under the License.
 */

package com.android.server.hdmi;

import static junit.framework.TestCase.assertEquals;

import android.os.Binder;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Optional;

@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class WorkSourceUidPreservingRunnableTest {
    @Test
    public void preservesAndRestoresWorkSourceUid() {
        int callerUid = 1234;
        int runnerUid = 5678;

        Binder.setCallingWorkSourceUid(callerUid);

        WorkSourceUidReadingRunnable uidReadingRunnable = new WorkSourceUidReadingRunnable();
        WorkSourceUidPreservingRunnable uidPreservingRunnable =
                new WorkSourceUidPreservingRunnable(uidReadingRunnable);

        Binder.setCallingWorkSourceUid(runnerUid);

        uidPreservingRunnable.run();

        assertEquals(Optional.of(callerUid), uidReadingRunnable.getWorkSourceUid());
        assertEquals(runnerUid, Binder.getCallingWorkSourceUid());
    }
}
