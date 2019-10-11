/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.transition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AutoTransitionTest {
    @Test
    @SmallTest
    public void testFadeOutMoveFadeIn() throws Throwable {
        AutoTransition autoTransition = new AutoTransition();
        assertEquals(3, autoTransition.getTransitionCount());
        Transition fadeOut = autoTransition.getTransitionAt(0);
        assertNotNull(fadeOut);
        assertTrue(fadeOut instanceof Fade);
        assertEquals(Visibility.MODE_OUT, ((Fade)fadeOut).getMode());

        Transition move = autoTransition.getTransitionAt(1);
        assertNotNull(move);
        assertTrue(move instanceof ChangeBounds);

        Transition fadeIn = autoTransition.getTransitionAt(2);
        assertNotNull(fadeIn);
        assertTrue(fadeIn instanceof Fade);
        assertEquals(Visibility.MODE_IN, ((Fade)fadeIn).getMode());

        assertEquals(TransitionSet.ORDERING_SEQUENTIAL, autoTransition.getOrdering());
    }
}
