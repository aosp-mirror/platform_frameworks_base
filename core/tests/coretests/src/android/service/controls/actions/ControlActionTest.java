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

package android.service.controls.actions;

import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.os.Parcel;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ControlActionTest {

    private static final String TEST_ID = "TEST_ID";

    @Test
    public void testUnparcelingCorrectClass_boolean() {
        ControlAction toParcel = new BooleanAction(TEST_ID, true);

        ControlAction fromParcel = parcelAndUnparcel(toParcel);

        assertEquals(ControlAction.TYPE_BOOLEAN, fromParcel.getActionType());
        assertTrue(fromParcel instanceof BooleanAction);
    }

    @Test
    public void testUnparcelingCorrectClass_float() {
        ControlAction toParcel = new FloatAction(TEST_ID, 1);

        ControlAction fromParcel = parcelAndUnparcel(toParcel);

        assertEquals(ControlAction.TYPE_FLOAT, fromParcel.getActionType());
        assertTrue(fromParcel instanceof FloatAction);
    }

    @Test
    public void testUnparcelingCorrectClass_mode() {
        ControlAction toParcel = new ModeAction(TEST_ID, 1);

        ControlAction fromParcel = parcelAndUnparcel(toParcel);

        assertEquals(ControlAction.TYPE_MODE, fromParcel.getActionType());
        assertTrue(fromParcel instanceof ModeAction);
    }

    @Test
    public void testUnparcelingCorrectClass_command() {
        ControlAction toParcel = new CommandAction(TEST_ID);

        ControlAction fromParcel = parcelAndUnparcel(toParcel);

        assertEquals(ControlAction.TYPE_COMMAND, fromParcel.getActionType());
        assertTrue(fromParcel instanceof CommandAction);
    }

    private ControlAction parcelAndUnparcel(ControlAction toParcel) {
        Parcel parcel = Parcel.obtain();

        assertNotNull(parcel);

        parcel.setDataPosition(0);
        new ControlActionWrapper(toParcel).writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        return ControlActionWrapper.CREATOR.createFromParcel(parcel).getWrappedAction();
    }
}
