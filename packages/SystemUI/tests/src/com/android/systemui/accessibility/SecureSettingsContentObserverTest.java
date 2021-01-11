/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.accessibility;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Test for {@link SecureSettingsContentObserver}. */
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class SecureSettingsContentObserverTest extends SysuiTestCase {

    private FakeSecureSettingsContentObserver mTestObserver;

    @Before
    public void setUpObserver() {
        mTestObserver = new FakeSecureSettingsContentObserver(mContext,
                Settings.Secure.ACCESSIBILITY_BUTTON_MODE);
    }

    @Test(expected = NullPointerException.class)
    public void addNullListener_throwNPE() {
        mTestObserver.addListener(null);
    }

    @Test(expected = NullPointerException.class)
    public void removeNullListener_throwNPE() {
        mTestObserver.removeListener(null);
    }

    @Test
    public void checkValue() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_MODE, 1);

        assertThat(mTestObserver.getSettingsValue()).isEqualTo("1");
    }


    private static class FakeSecureSettingsContentObserver extends
            SecureSettingsContentObserver<Object> {

        protected FakeSecureSettingsContentObserver(Context context,
                String secureSettingsKey) {
            super(context, secureSettingsKey);
        }

        @Override
        void onValueChanged(Object listener, String value) {
        }
    }
}
