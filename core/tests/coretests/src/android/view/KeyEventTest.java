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

package android.view;

import static org.junit.Assert.assertEquals;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KeyEventTest {

    @Test
    public void testObtain() {
        KeyEvent keyEvent = KeyEvent.obtain(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0,
                0, 0, 0, 0, 0, InputDevice.SOURCE_KEYBOARD, null);
        assertEquals(KeyEvent.ACTION_DOWN, keyEvent.getAction());
        assertEquals(KeyEvent.KEYCODE_0, keyEvent.getKeyCode());
        assertEquals(InputDevice.SOURCE_KEYBOARD, keyEvent.getSource());
    }
}
