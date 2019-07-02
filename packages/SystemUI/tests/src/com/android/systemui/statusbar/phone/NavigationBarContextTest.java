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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.policy.KeyButtonDrawable;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

/** atest NavigationBarContextTest */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class NavigationBarContextTest extends SysuiTestCase {
    private static final int GROUP_ID = 1;
    private static final int BUTTON_0_ID = GROUP_ID + 1;
    private static final int BUTTON_1_ID = GROUP_ID + 2;
    private static final int BUTTON_2_ID = GROUP_ID + 3;

    private static final float TEST_DARK_INTENSITY = 0.6f;
    private static final float DARK_INTENSITY_ERR = 0.0002f;
    private static final int ICON_RES_ID = 1;

    private ContextualButtonGroup mGroup;
    private ContextualButton mBtn0;
    private ContextualButton mBtn1;
    private ContextualButton mBtn2;

    @Before
    public void setup() {
        mGroup = new ContextualButtonGroup(GROUP_ID);
        mBtn0 = new ContextualButton(BUTTON_0_ID, ICON_RES_ID);
        mBtn1 = new ContextualButton(BUTTON_1_ID, ICON_RES_ID);
        mBtn2 = new ContextualButton(BUTTON_2_ID, ICON_RES_ID);

        // Order of adding buttons to group determines the priority, ascending priority order
        mGroup.addButton(mBtn0);
        mGroup.addButton(mBtn1);
        mGroup.addButton(mBtn2);
    }

    @Test
    public void testAddGetContextButtons() throws Exception {
        assertEquals(mBtn0, mGroup.getContextButton(BUTTON_0_ID));
        assertEquals(mBtn1, mGroup.getContextButton(BUTTON_1_ID));
        assertEquals(mBtn2, mGroup.getContextButton(BUTTON_2_ID));
    }

    @Test
    public void testSetButtonVisibility() throws Exception {
        assertTrue("By default the group should be visible.", mGroup.isVisible());

        // Set button 1 to be visible, make sure it is the only visible button
        showButton(mBtn1);
        assertFalse(mBtn0.isVisible());
        assertTrue(mBtn1.isVisible());
        assertFalse(mBtn2.isVisible());

        // Hide button 1 and make sure the group is also invisible
        assertNotEquals(mGroup.setButtonVisibility(BUTTON_1_ID, false /* visible */), View.VISIBLE);
        assertFalse("No buttons are visible, group should also be hidden", mGroup.isVisible());
        assertNull("No buttons should be visible", mGroup.getVisibleContextButton());
    }

    @Test(expected = RuntimeException.class)
    public void testSetButtonVisibilityUnaddedButton() throws Exception {
        int id = mBtn2.getId() + 1;
        mGroup.setButtonVisibility(id, true /* visible */);
        fail("Did not throw when setting a button with an invalid id");
    }

    @Test
    public void testSetHigherPriorityButton() throws Exception {
        // Show button 0
        showButton(mBtn0);

        // Show button 1
        showButton(mBtn1);
        assertTrue("Button 0 should be visible behind",
                mGroup.isButtonVisibleWithinGroup(mBtn0.getId()));

        // Show button 2
        showButton(mBtn2);
        assertTrue("Button 1 should be visible behind",
                mGroup.isButtonVisibleWithinGroup(mBtn1.getId()));
        assertTrue(mGroup.isButtonVisibleWithinGroup(mBtn0.getId()));
        assertTrue(mGroup.isButtonVisibleWithinGroup(mBtn1.getId()));
        assertTrue(mGroup.isButtonVisibleWithinGroup(mBtn2.getId()));

        // Hide button 2
        assertNotEquals(mGroup.setButtonVisibility(BUTTON_2_ID, false /* visible */), View.VISIBLE);
        assertEquals("Hiding button 2 should show button 1", mBtn1,
                mGroup.getVisibleContextButton());

        // Hide button 1
        assertNotEquals(mGroup.setButtonVisibility(BUTTON_1_ID, false /* visible */), View.VISIBLE);
        assertEquals("Hiding button 1 should show button 0", mBtn0,
                mGroup.getVisibleContextButton());

        // Hide button 0, all buttons are now invisible
        assertNotEquals(mGroup.setButtonVisibility(BUTTON_0_ID, false /* visible */), View.VISIBLE);
        assertFalse("No buttons are visible, group should also be invisible", mGroup.isVisible());
        assertNull(mGroup.getVisibleContextButton());
        assertFalse(mGroup.isButtonVisibleWithinGroup(mBtn0.getId()));
        assertFalse(mGroup.isButtonVisibleWithinGroup(mBtn1.getId()));
        assertFalse(mGroup.isButtonVisibleWithinGroup(mBtn2.getId()));
    }

    @Test
    public void testSetLowerPriorityButton() throws Exception {
        // Show button 2
        showButton(mBtn2);

        // Show button 1
        assertNotEquals(mGroup.setButtonVisibility(BUTTON_1_ID, true /* visible */), View.VISIBLE);
        assertTrue("Showing button 1 lower priority should be hidden but visible underneath",
                mGroup.isButtonVisibleWithinGroup(BUTTON_1_ID));
        assertFalse(mBtn0.isVisible());
        assertFalse(mBtn1.isVisible());
        assertTrue(mBtn2.isVisible());

        // Hide button 1
        assertNotEquals(mGroup.setButtonVisibility(BUTTON_1_ID, false /* visible */), View.VISIBLE);
        assertFalse("Hiding button 1 with lower priority hides itself underneath",
                mGroup.isButtonVisibleWithinGroup(BUTTON_1_ID));
        assertTrue("A button still visible, group should also be visible", mGroup.isVisible());
        assertEquals(mBtn2, mGroup.getVisibleContextButton());
    }

    @Test
    public void testSetSamePriorityButton() throws Exception {
        // Show button 1
        showButton(mBtn1);

        // Show button 1 again
        showButton(mBtn1);

        // The original button should still be visible
        assertEquals(mBtn1, mGroup.getVisibleContextButton());
        assertFalse(mGroup.isButtonVisibleWithinGroup(mBtn0.getId()));
        assertFalse(mGroup.isButtonVisibleWithinGroup(mBtn2.getId()));
    }

    @Test
    @Ignore("b/112934365")
    public void testUpdateIconsDarkIntensity() throws Exception {
        final int unusedColor = 0;
        final Drawable d = mock(Drawable.class);
        final ContextualButton button = spy(mBtn0);
        final KeyButtonDrawable kbd1 = spy(new KeyButtonDrawable(d, unusedColor, unusedColor,
                false /* horizontalFlip */, null /* ovalBackgroundColor */));
        final KeyButtonDrawable kbd2 = spy(new KeyButtonDrawable(d, unusedColor, unusedColor,
                false /* horizontalFlip */, null /* ovalBackgroundColor */));
        kbd1.setDarkIntensity(TEST_DARK_INTENSITY);
        kbd2.setDarkIntensity(0f);

        // Update icon returns the drawable intensity to half
        doReturn(kbd1).when(button).getNewDrawable();
        button.updateIcon();
        assertEquals(TEST_DARK_INTENSITY, kbd1.getDarkIntensity(), DARK_INTENSITY_ERR);

        // Return old dark intensity on new drawable after update icon
        doReturn(kbd2).when(button).getNewDrawable();
        button.updateIcon();
        assertEquals(TEST_DARK_INTENSITY, kbd2.getDarkIntensity(), DARK_INTENSITY_ERR);
    }

    private void showButton(ContextualButton button) {
        assertEquals(View.VISIBLE, mGroup.setButtonVisibility(button.getId(), true /* visible */));
        assertTrue("After set a button visible, group should also be visible", mGroup.isVisible());
        assertEquals(button, mGroup.getVisibleContextButton());
    }
}
