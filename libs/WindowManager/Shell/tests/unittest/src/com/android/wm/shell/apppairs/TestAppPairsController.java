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

package com.android.wm.shell.apppairs;

import static org.mockito.Mockito.mock;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;

public class TestAppPairsController extends AppPairsController {
    private TestAppPairsPool mPool;

    public TestAppPairsController(ShellTaskOrganizer organizer, SyncTransactionQueue syncQueue,
            DisplayController displayController) {
        super(organizer, syncQueue, displayController, mock(ShellExecutor.class),
                mock(DisplayImeController.class));
        mPool = new TestAppPairsPool(this);
        setPairsPool(mPool);
    }

    TestAppPairsPool getPool() {
        return mPool;
    }
}
