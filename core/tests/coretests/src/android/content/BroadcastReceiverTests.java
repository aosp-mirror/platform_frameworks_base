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
 * limitations under the License.
 */

package android.content;

import static org.junit.Assert.fail;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class BroadcastReceiverTests {

    private static final int RECEIVER_LIMIT_PER_APP = 1000;
    private static final class EmptyReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Empty
        }
    }
    private Context mContext;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
    }

    @Test
    public void testReceiverLimit() {
        final IntentFilter mockFilter = new IntentFilter("android.content.tests.TestAction");
        try {
            for (int i = 0; i < RECEIVER_LIMIT_PER_APP + 1; i++) {
                mContext.registerReceiver(new EmptyReceiver(), mockFilter);
            }
            fail("No exception thrown when registering "
                    + (RECEIVER_LIMIT_PER_APP + 1) + " receivers");
        } catch (IllegalStateException ise) {
            // Expected
        }
    }
}
