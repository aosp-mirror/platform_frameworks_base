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
import android.service.controls.templates.ControlButton;
import android.service.controls.templates.ControlTemplate;
import android.service.controls.templates.CoordinatedRangeTemplate;
import android.service.controls.templates.DiscreteToggleTemplate;
import android.service.controls.templates.RangeTemplate;
import android.service.controls.templates.StatelessTemplate;
import android.service.controls.templates.TemperatureControlTemplate;
import android.service.controls.templates.ThumbnailTemplate;
import android.service.controls.templates.ToggleRangeTemplate;
import android.service.controls.templates.ToggleTemplate;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

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
        ControlTemplate
                toParcel = ControlTemplate.NO_TEMPLATE;

        ControlTemplate fromParcel = parcelAndUnparcel(toParcel);

        assertEquals(ControlTemplate.NO_TEMPLATE, fromParcel);
    }

    @Test
    public void testUnparcelingCorrectClass_toggle() {
        ControlTemplate
                toParcel = new android.service.controls.templates.ToggleTemplate(TEST_ID, mControlButton);

        ControlTemplate fromParcel = parcelAndUnparcel(toParcel);

        assertEquals(ControlTemplate.TYPE_TOGGLE, fromParcel.getTemplateType());
        assertTrue(fromParcel instanceof ToggleTemplate);
    }

    @Test
    public void testUnparcelingCorrectClass_range() {
        ControlTemplate
                toParcel = new RangeTemplate(TEST_ID, 0, 2, 1, 1, "%f");

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
        ControlTemplate
                toParcel = new ThumbnailTemplate(TEST_ID, mIcon, TEST_ACTION_DESCRIPTION);

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

    @Test
    public void testUnparcelingCorrectClass_coordRange() {
        ControlTemplate toParcel =
                new CoordinatedRangeTemplate(TEST_ID,0.1f,  0, 1, 0.5f, 1, 2, 1.5f, 0.1f, "%f");
        ControlTemplate fromParcel = parcelAndUnparcel(toParcel);
        assertEquals(ControlTemplate.TYPE_COORD_RANGE, fromParcel.getTemplateType());
        assertTrue(fromParcel instanceof CoordinatedRangeTemplate);
    }

    @Test
    public void testCoordRangeParameters_negativeMinGap() {
        CoordinatedRangeTemplate template =
                new CoordinatedRangeTemplate(TEST_ID,-0.1f,  0, 1, 0.5f, 1, 2, 1.5f, 0.1f, "%f");
        assertEquals(0, template.getMinGap(), 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCoordRangeParameters_differentStep() {
        RangeTemplate rangeLow = new RangeTemplate(TEST_ID, 0, 1, 0.5f, 0.1f, "%f");
        RangeTemplate rangeHigh = new RangeTemplate(TEST_ID, 0, 1, 0.75f, 0.2f, "%f");
        new CoordinatedRangeTemplate(TEST_ID, 0.1f, rangeLow, rangeHigh);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCoordRangeParameters_differentFormat() {
        RangeTemplate rangeLow = new RangeTemplate(TEST_ID, 0, 1, 0.5f, 0.1f, "%f");
        RangeTemplate rangeHigh = new RangeTemplate(TEST_ID, 0, 1, 0.75f, 0.1f, "%.1f");
        new CoordinatedRangeTemplate(TEST_ID, 0.1f, rangeLow, rangeHigh);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCoordRangeParameters_LargeMinGap() {
        RangeTemplate rangeLow = new RangeTemplate(TEST_ID, 0, 1, 0.5f, 0.1f, "%f");
        RangeTemplate rangeHigh = new RangeTemplate(TEST_ID, 0, 1, 0.75f, 0.1f, "%f");
        new CoordinatedRangeTemplate(TEST_ID, 0.5f, rangeLow, rangeHigh);
    }

    @Test
    public void testUnparcelingCorrectClass_toggleRange() {
        ControlTemplate toParcel =
                new ToggleRangeTemplate(TEST_ID, mControlButton,
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
        ControlTemplate toParcel = new TemperatureControlTemplate(TEST_ID,
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
        TemperatureControlTemplate thermostat = new TemperatureControlTemplate(TEST_ID, ControlTemplate.NO_TEMPLATE, -1,
                TemperatureControlTemplate.MODE_OFF, TemperatureControlTemplate.FLAG_MODE_OFF);
        assertEquals(TemperatureControlTemplate.MODE_UNKNOWN, thermostat.getCurrentMode());

        thermostat = new TemperatureControlTemplate(TEST_ID, ControlTemplate.NO_TEMPLATE, 100,
                TemperatureControlTemplate.MODE_OFF, TemperatureControlTemplate.FLAG_MODE_OFF);
        assertEquals(TemperatureControlTemplate.MODE_UNKNOWN, thermostat.getCurrentMode());
    }

    @Test
    public void testThermostatParams_wrongActiveMode() {
        TemperatureControlTemplate thermostat = new TemperatureControlTemplate(TEST_ID, ControlTemplate.NO_TEMPLATE,
                TemperatureControlTemplate.MODE_OFF,-1, TemperatureControlTemplate.FLAG_MODE_OFF);
        assertEquals(TemperatureControlTemplate.MODE_UNKNOWN, thermostat.getCurrentActiveMode());

        thermostat = new TemperatureControlTemplate(TEST_ID, ControlTemplate.NO_TEMPLATE,
                TemperatureControlTemplate.MODE_OFF,100, TemperatureControlTemplate.FLAG_MODE_OFF);
        assertEquals(TemperatureControlTemplate.MODE_UNKNOWN, thermostat.getCurrentActiveMode());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testThermostatParams_wrongFlags_currentMode() {
        new TemperatureControlTemplate(TEST_ID, ControlTemplate.NO_TEMPLATE, TemperatureControlTemplate.MODE_HEAT,
                TemperatureControlTemplate.MODE_OFF, TemperatureControlTemplate.FLAG_MODE_OFF);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testThermostatParams_wrongFlags_currentActiveMode() {
        new TemperatureControlTemplate(TEST_ID, ControlTemplate.NO_TEMPLATE, TemperatureControlTemplate.MODE_HEAT,
                TemperatureControlTemplate.MODE_OFF, TemperatureControlTemplate.FLAG_MODE_HEAT);
    }

    private ControlTemplate parcelAndUnparcel(
            ControlTemplate toParcel) {
        Parcel parcel = Parcel.obtain();

        assertNotNull(parcel);

        parcel.setDataPosition(0);
        toParcel.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        return ControlTemplate.CREATOR.createFromParcel(parcel);
    }
}
