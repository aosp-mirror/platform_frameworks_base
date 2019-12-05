/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.service.controls;

import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.annotation.DrawableRes;
import android.graphics.drawable.Icon;
import android.os.Parcel;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import java.security.InvalidParameterException;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ControlTemplateTest {

    private static final String PACKAGE_NAME = "com.android.frameworks.coretests";
    private static final @DrawableRes int TEST_ICON_ID = R.drawable.box;
    private static final String TEST_ID = "TEST_ID";
    private static final CharSequence TEST_CONTENT_DESCRIPTION = "TEST_CONTENT_DESCRIPTION";
    private Icon mIcon;
    private ControlButton mControlButton;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mIcon = Icon.createWithResource(PACKAGE_NAME, TEST_ICON_ID);
        mControlButton = new ControlButton(true, mIcon, TEST_CONTENT_DESCRIPTION);
    }

    @Test
    public void testUnparcelingCorrectClass_none() {
        ControlTemplate toParcel = ControlTemplate.NO_TEMPLATE;

        ControlTemplate fromParcel = parcelAndUnparcel(toParcel);

        assertEquals(ControlTemplate.NO_TEMPLATE, fromParcel);
    }

    @Test
    public void testUnparcelingCorrectClass_toggle() {
        ControlTemplate toParcel = new ToggleTemplate(TEST_ID, mControlButton);

        ControlTemplate fromParcel = parcelAndUnparcel(toParcel);

        assertEquals(ControlTemplate.TYPE_TOGGLE, fromParcel.getTemplateType());
        assertTrue(fromParcel instanceof ToggleTemplate);
    }

    @Test
    public void testUnparcelingCorrectClass_range() {
        ControlTemplate toParcel = new RangeTemplate(TEST_ID, 0, 2, 1, 1, "%f");

        ControlTemplate fromParcel = parcelAndUnparcel(toParcel);

        assertEquals(ControlTemplate.TYPE_RANGE, fromParcel.getTemplateType());
        assertTrue(fromParcel instanceof RangeTemplate);
    }

    @Test(expected = InvalidParameterException.class)
    public void testRangeParameters_minMax() {
        RangeTemplate range = new RangeTemplate(TEST_ID, 2, 0, 1, 1, "%f");
    }

    @Test(expected = InvalidParameterException.class)
    public void testRangeParameters_minCurrent() {
        RangeTemplate range = new RangeTemplate(TEST_ID, 0, 2, -1, 1, "%f");
    }

    @Test(expected = InvalidParameterException.class)
    public void testRangeParameters_maxCurrent() {
        RangeTemplate range = new RangeTemplate(TEST_ID, 0, 2, 3, 1, "%f");
    }

    @Test(expected = InvalidParameterException.class)
    public void testRangeParameters_negativeStep() {
        RangeTemplate range = new RangeTemplate(TEST_ID, 0, 2, 1, -1, "%f");
    }

    @Test
    public void testUnparcelingCorrectClass_thumbnail() {
        ControlTemplate toParcel = new ThumbnailTemplate(TEST_ID, mIcon, TEST_CONTENT_DESCRIPTION);

        ControlTemplate fromParcel = parcelAndUnparcel(toParcel);

        assertEquals(ControlTemplate.TYPE_THUMBNAIL, fromParcel.getTemplateType());
        assertTrue(fromParcel instanceof ThumbnailTemplate);
    }

    @Test
    public void testUnparcelingCorrectClass_discreteToggle() {
        ControlTemplate toParcel =
                new DiscreteToggleTemplate(TEST_ID, mControlButton, mControlButton);

        ControlTemplate fromParcel = parcelAndUnparcel(toParcel);

        assertEquals(ControlTemplate.TYPE_DISCRETE_TOGGLE, fromParcel.getTemplateType());
        assertTrue(fromParcel instanceof DiscreteToggleTemplate);
    }

    private ControlTemplate parcelAndUnparcel(ControlTemplate toParcel) {
        Parcel parcel = Parcel.obtain();

        assertNotNull(parcel);

        parcel.setDataPosition(0);
        toParcel.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        return ControlTemplate.CREATOR.createFromParcel(parcel);
    }
}
