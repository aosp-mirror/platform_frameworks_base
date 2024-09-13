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

package android.service.controls.templates;

import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.annotation.DrawableRes;
import android.graphics.drawable.Icon;
import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ControlTemplateTest {

    private static final String PACKAGE_NAME = "com.android.frameworks.coretests";
    private static final @DrawableRes int TEST_ICON_ID = R.drawable.box;
    private static final String TEST_ID = "TEST_ID";
    private static final CharSequence TEST_ACTION_DESCRIPTION = "TEST_ACTION_DESCRIPTION";
    private Icon mIcon;
    private ControlButton mControlButton;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mIcon = Icon.createWithResource(PACKAGE_NAME, TEST_ICON_ID);
        mControlButton = new ControlButton(true, TEST_ACTION_DESCRIPTION);
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

    @Test(expected = IllegalArgumentException.class)
    public void testRangeParameters_minMax() {
        new RangeTemplate(TEST_ID, 2, 0, 1, 1, "%f");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRangeParameters_minCurrent() {
        new RangeTemplate(TEST_ID, 0, 2, -1, 1, "%f");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRangeParameters_maxCurrent() {
        new RangeTemplate(TEST_ID, 0, 2, 3, 1, "%f");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRangeParameters_negativeStep() {
        new RangeTemplate(TEST_ID, 0, 2, 1, -1, "%f");
    }

    @Test
    public void testUnparcelingCorrectClass_thumbnail() {
        ControlTemplate toParcel = new ThumbnailTemplate(TEST_ID, false, mIcon,
                TEST_ACTION_DESCRIPTION);

        ControlTemplate fromParcel = parcelAndUnparcel(toParcel);

        assertEquals(ControlTemplate.TYPE_THUMBNAIL, fromParcel.getTemplateType());
        assertTrue(fromParcel instanceof ThumbnailTemplate);
    }

    @Test
    public void testUnparcelingCorrectClass_toggleRange() {
        ControlTemplate toParcel = new ToggleRangeTemplate(TEST_ID, mControlButton,
                new RangeTemplate(TEST_ID, 0, 2, 1, 1, "%f"));

        ControlTemplate fromParcel = parcelAndUnparcel(toParcel);

        assertEquals(ControlTemplate.TYPE_TOGGLE_RANGE, fromParcel.getTemplateType());
        assertTrue(fromParcel instanceof ToggleRangeTemplate);
    }

    @Test
    public void testUnparcelingCorrectClass_stateless() {
        ControlTemplate toParcel = new StatelessTemplate(TEST_ID);

        ControlTemplate fromParcel = parcelAndUnparcel(toParcel);

        assertEquals(ControlTemplate.TYPE_STATELESS, fromParcel.getTemplateType());
        assertTrue(fromParcel instanceof StatelessTemplate);
    }

    @Test
    public void testUnparcelingCorrectClass_thermostat() {
        ControlTemplate toParcel = new TemperatureControlTemplate(
                TEST_ID,
                new ToggleTemplate("", mControlButton),
                TemperatureControlTemplate.MODE_OFF,
                TemperatureControlTemplate.MODE_OFF,
                TemperatureControlTemplate.FLAG_MODE_OFF);

        ControlTemplate fromParcel = parcelAndUnparcel(toParcel);

        assertEquals(ControlTemplate.TYPE_TEMPERATURE, fromParcel.getTemplateType());
        assertTrue(fromParcel instanceof TemperatureControlTemplate);
    }

    @Test
    public void testThermostatParams_wrongMode() {
        TemperatureControlTemplate thermostat = new TemperatureControlTemplate(
                TEST_ID,
                ControlTemplate.NO_TEMPLATE,
                -1,
                TemperatureControlTemplate.MODE_OFF,
                TemperatureControlTemplate.FLAG_MODE_OFF);
        assertEquals(TemperatureControlTemplate.MODE_UNKNOWN, thermostat.getCurrentMode());

        thermostat = new TemperatureControlTemplate(
                TEST_ID,
                ControlTemplate.NO_TEMPLATE,
                100,
                TemperatureControlTemplate.MODE_OFF,
                TemperatureControlTemplate.FLAG_MODE_OFF);
        assertEquals(TemperatureControlTemplate.MODE_UNKNOWN, thermostat.getCurrentMode());
    }

    @Test
    public void testThermostatParams_wrongActiveMode() {
        TemperatureControlTemplate thermostat = new TemperatureControlTemplate(
                TEST_ID,
                ControlTemplate.NO_TEMPLATE,
                TemperatureControlTemplate.MODE_OFF,
                -1,
                TemperatureControlTemplate.FLAG_MODE_OFF);
        assertEquals(TemperatureControlTemplate.MODE_UNKNOWN, thermostat.getCurrentActiveMode());

        thermostat = new TemperatureControlTemplate(
                TEST_ID,
                ControlTemplate.NO_TEMPLATE,
                TemperatureControlTemplate.MODE_OFF,
                100,
                TemperatureControlTemplate.FLAG_MODE_OFF);
        assertEquals(TemperatureControlTemplate.MODE_UNKNOWN, thermostat.getCurrentActiveMode());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testThermostatParams_wrongFlags_currentMode() {
        new TemperatureControlTemplate(
                TEST_ID,
                ControlTemplate.NO_TEMPLATE,
                TemperatureControlTemplate.MODE_HEAT,
                TemperatureControlTemplate.MODE_OFF,
                TemperatureControlTemplate.FLAG_MODE_OFF);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testThermostatParams_wrongFlags_currentActiveMode() {
        new TemperatureControlTemplate(TEST_ID,
                ControlTemplate.NO_TEMPLATE,
                TemperatureControlTemplate.MODE_HEAT,
                TemperatureControlTemplate.MODE_OFF,
                TemperatureControlTemplate.FLAG_MODE_HEAT);
    }

    private ControlTemplate parcelAndUnparcel(ControlTemplate toParcel) {
        Parcel parcel = Parcel.obtain();

        assertNotNull(parcel);

        parcel.setDataPosition(0);
        new ControlTemplateWrapper(toParcel).writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        return ControlTemplateWrapper.CREATOR.createFromParcel(parcel).getWrappedTemplate();
    }
}
