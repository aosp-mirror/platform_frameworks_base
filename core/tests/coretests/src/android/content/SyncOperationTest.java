/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.content;

import android.accounts.Account;
import android.os.Bundle;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * You can run those tests with:
 *
 * adb shell am instrument
 * -e debug false
 * -w
 * -e class android.content.SyncOperationTest com.android.frameworks.coretests/android.test.InstrumentationTestRunner
 */

public class SyncOperationTest extends AndroidTestCase {

    @SmallTest
    public void testToKey() {
        Account account1 = new Account("account1", "type1");
        Account account2 = new Account("account2", "type2");

        Bundle b1 = new Bundle();
        Bundle b2 = new Bundle();
        b2.putBoolean("b2", true);

        SyncOperation op1 = new SyncOperation(account1, 0,
                1,
                "authority1",
                b1,
                100,
                1000,
                10000,
                false);

        // Same as op1 but different time infos
        SyncOperation op2 = new SyncOperation(account1, 0,
                1,
                "authority1",
                b1,
                200,
                2000,
                20000,
                false);

        // Same as op1 but different authority
        SyncOperation op3 = new SyncOperation(account1, 0,
                1,
                "authority2",
                b1,
                100,
                1000,
                10000,
                false);

        // Same as op1 but different account
        SyncOperation op4 = new SyncOperation(account2, 0,
                1,
                "authority1",
                b1,
                100,
                1000,
                10000,
                false);

        // Same as op1 but different bundle
        SyncOperation op5 = new SyncOperation(account1, 0,
                1,
                "authority1",
                b2,
                100,
                1000,
                10000,
                false);

        assertEquals(op1.key, op2.key);
        assertNotSame(op1.key, op3.key);
        assertNotSame(op1.key, op4.key);
        assertNotSame(op1.key, op5.key);
    }
}
