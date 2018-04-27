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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.os.PowerManager;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.doze.DozeScreenState;
import com.android.systemui.statusbar.phone.DozeParameters.IntInOutMatcher;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.fail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DozeParametersTest extends SysuiTestCase {

    @Test
    public void test_inOutMatcher_defaultIn() {
        IntInOutMatcher intInOutMatcher = new IntInOutMatcher("*");

        assertTrue(intInOutMatcher.isIn(1));
        assertTrue(intInOutMatcher.isIn(-1));
        assertTrue(intInOutMatcher.isIn(0));
    }

    @Test
    public void test_inOutMatcher_defaultOut() {
        IntInOutMatcher intInOutMatcher = new IntInOutMatcher("!*");

        assertFalse(intInOutMatcher.isIn(1));
        assertFalse(intInOutMatcher.isIn(-1));
        assertFalse(intInOutMatcher.isIn(0));
    }

    @Test
    public void test_inOutMatcher_someIn() {
        IntInOutMatcher intInOutMatcher = new IntInOutMatcher("1,2,3,!*");

        assertTrue(intInOutMatcher.isIn(1));
        assertTrue(intInOutMatcher.isIn(2));
        assertTrue(intInOutMatcher.isIn(3));

        assertFalse(intInOutMatcher.isIn(0));
        assertFalse(intInOutMatcher.isIn(4));
    }

    @Test
    public void test_inOutMatcher_someOut() {
        IntInOutMatcher intInOutMatcher = new IntInOutMatcher("!1,!2,!3,*");

        assertFalse(intInOutMatcher.isIn(1));
        assertFalse(intInOutMatcher.isIn(2));
        assertFalse(intInOutMatcher.isIn(3));

        assertTrue(intInOutMatcher.isIn(0));
        assertTrue(intInOutMatcher.isIn(4));
    }

    @Test
    public void test_inOutMatcher_mixed() {
        IntInOutMatcher intInOutMatcher = new IntInOutMatcher("!1,2,!3,*");

        assertFalse(intInOutMatcher.isIn(1));
        assertTrue(intInOutMatcher.isIn(2));
        assertFalse(intInOutMatcher.isIn(3));

        assertTrue(intInOutMatcher.isIn(0));
        assertTrue(intInOutMatcher.isIn(4));
    }

    @Test
    public void test_inOutMatcher_failEmpty() {
        try {
            new IntInOutMatcher("");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    public void test_inOutMatcher_failNull() {
        try {
            new IntInOutMatcher(null);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    public void test_inOutMatcher_failEmptyClause() {
        try {
            new IntInOutMatcher("!1,*,");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    public void test_inOutMatcher_failDuplicate() {
        try {
            new IntInOutMatcher("!1,*,!1");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    public void test_inOutMatcher_failDuplicateDefault() {
        try {
            new IntInOutMatcher("!1,*,*");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    public void test_inOutMatcher_failMalformedNot() {
        try {
            new IntInOutMatcher("!,*");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    public void test_inOutMatcher_failText() {
        try {
            new IntInOutMatcher("!abc,*");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    public void test_inOutMatcher_failContradiction() {
        try {
            new IntInOutMatcher("1,!1,*");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    public void test_inOutMatcher_failContradictionDefault() {
        try {
            new IntInOutMatcher("1,*,!*");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    public void test_inOutMatcher_failMissingDefault() {
        try {
            new IntInOutMatcher("1");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    public void test_setControlScreenOffAnimation_setsDozeAfterScreenOff_false() {
        TestableDozeParameters dozeParameters = new TestableDozeParameters(getContext());
        PowerManager mockedPowerManager = dozeParameters.getPowerManager();
        dozeParameters.setControlScreenOffAnimation(true);
        reset(mockedPowerManager);
        dozeParameters.setControlScreenOffAnimation(false);
        verify(mockedPowerManager).setDozeAfterScreenOff(eq(true));
    }

    @Test
    public void test_setControlScreenOffAnimation_setsDozeAfterScreenOff_true() {
        TestableDozeParameters dozeParameters = new TestableDozeParameters(getContext());
        PowerManager mockedPowerManager = dozeParameters.getPowerManager();
        dozeParameters.setControlScreenOffAnimation(false);
        reset(mockedPowerManager);
        dozeParameters.setControlScreenOffAnimation(true);
        verify(dozeParameters.getPowerManager()).setDozeAfterScreenOff(eq(false));
    }

    @Test
    public void test_getWallpaperAodDuration_when_shouldControlScreenOff() {
        TestableDozeParameters dozeParameters = new TestableDozeParameters(getContext());
        dozeParameters.setControlScreenOffAnimation(true);
        Assert.assertEquals("wallpaper hides faster when controlling screen off",
                dozeParameters.getWallpaperAodDuration(),
                DozeScreenState.ENTER_DOZE_HIDE_WALLPAPER_DELAY);
    }

    private class TestableDozeParameters extends DozeParameters {
        private PowerManager mPowerManager;

        TestableDozeParameters(Context context) {
            super(context);
            mPowerManager = mock(PowerManager.class);
        }

        @Override
        protected PowerManager getPowerManager() {
            return mPowerManager;
        }
    }

}
