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
package com.android.settingslib.drawer;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class PrimarySwitchControllerTest {

    @Rule
    public final ExpectedException thrown = ExpectedException.none();

    private PrimarySwitchController mController;

    @Before
    public void setUp() {
        mController = new TestPrimarySwitchController("123");
    }

    @Test
    public void getMetaData_shouldThrowUnsupportedOperationException() {
        thrown.expect(UnsupportedOperationException.class);

        mController.getMetaData();
    }

    @Test
    public void getBundle_shouldThrowUnsupportedOperationException() {
        thrown.expect(UnsupportedOperationException.class);

        mController.getBundle();
    }

    static class TestPrimarySwitchController extends PrimarySwitchController {

        private String mKey;

        TestPrimarySwitchController(String key) {
            mKey = key;
        }

        @Override
        public String getSwitchKey() {
            return mKey;
        }

        @Override
        protected boolean isChecked() {
            return true;
        }

        @Override
        protected boolean onCheckedChanged(boolean checked) {
            return true;
        }

        @Override
        protected String getErrorMessage(boolean attemptedChecked) {
            return null;
        }
    }
}
