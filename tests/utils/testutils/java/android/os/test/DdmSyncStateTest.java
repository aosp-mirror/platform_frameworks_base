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
 * limitations under the License.
 */

package android.os.test;

import android.os.DdmSyncState;
import android.os.DdmSyncState.Stage;

import org.junit.Assert;
import org.junit.Test;

/**
 * Test DdmSyncState, the Android app stage boot sync system for DDM Client.
 */

public class DdmSyncStateTest {

    @Test
    public void testNoCycle() {
        DdmSyncState.reset();
        try {
            DdmSyncState.next(Stage.Attach);
            DdmSyncState.next(Stage.Bind);
            DdmSyncState.next(Stage.Named);
            DdmSyncState.next(Stage.Debugger);
            DdmSyncState.next(Stage.Running);

            // Cycling back here which is not allowed
            DdmSyncState.next(Stage.Attach);
            Assert.fail("Going back to attach should have failed");
        } catch (IllegalStateException ignored) {

        }
    }

    @Test
    public void testDebuggerFlow() {
        DdmSyncState.reset();
        DdmSyncState.next(Stage.Attach);
        DdmSyncState.next(Stage.Bind);
        DdmSyncState.next(Stage.Named);
        DdmSyncState.next(Stage.Debugger);
        DdmSyncState.next(Stage.Running);
        Assert.assertEquals(Stage.Running, DdmSyncState.getStage());

    }

    @Test
    public void testNoDebugFlow() {
        DdmSyncState.reset();
        DdmSyncState.next(Stage.Attach);
        DdmSyncState.next(Stage.Bind);
        DdmSyncState.next(Stage.Named);
        // Notice how Stage.Debugger stage is skipped
        DdmSyncState.next(Stage.Running);
        Assert.assertEquals(Stage.Running, DdmSyncState.getStage());
    }
}
