/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.test.AndroidTestCase;

import androidx.test.filters.SmallTest;

/** Test to make sure brick intents <b>don't</b> work without permission. */
public class BrickDeniedTest extends AndroidTestCase {
    @SmallTest
    public void testBrick() {
        // Try both the old and new brick intent names.  Neither should work,
        // since this test application doesn't have the required permission.
        // If it does work, well, the test certainly won't pass.
        getContext().sendBroadcast(new Intent("SHES_A_BRICK_HOUSE"));
        getContext().sendBroadcast(new Intent("android.intent.action.BRICK"));
    }
}
