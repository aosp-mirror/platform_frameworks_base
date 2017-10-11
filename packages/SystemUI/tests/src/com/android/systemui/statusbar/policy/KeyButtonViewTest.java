/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.ACTION_NAV_BUTTON_EVENT;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_FLAGS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_NAV_ACTION;

import static org.mockito.ArgumentMatchers.argThat;

import android.metrics.LogMaker;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.KeyEvent;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;

import java.util.Objects;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class KeyButtonViewTest extends SysuiTestCase {

    private KeyButtonView mKeyButtonView;
    private MetricsLogger mMetricsLogger;

    @Before
    public void setup() throws Exception {
        mMetricsLogger = mDependency.injectMockDependency(MetricsLogger.class);
        TestableLooper.get(this).runWithLooper(() ->
                mKeyButtonView = new KeyButtonView(mContext, null));
    }

    @Test
    public void testMetrics() {
        int action = 42;
        int flags = 0x141;
        int code = KeyEvent.KEYCODE_ENTER;
        mKeyButtonView.setCode(code);
        mKeyButtonView.sendEvent(action, flags);

        Mockito.verify(mMetricsLogger).write(argThat(new ArgumentMatcher<LogMaker>() {
            public String mReason;

            @Override
            public boolean matches(LogMaker argument) {
                return checkField("category", argument.getCategory(), ACTION_NAV_BUTTON_EVENT)
                        && checkField("type", argument.getType(), MetricsEvent.TYPE_ACTION)
                        && checkField("subtype", argument.getSubtype(), code)
                        && checkField("FIELD_FLAGS", argument.getTaggedData(FIELD_FLAGS), flags)
                        && checkField("FIELD_NAV_ACTION", argument.getTaggedData(FIELD_NAV_ACTION),
                                action);
            }

            private boolean checkField(String field, Object val, Object val2) {
                if (!Objects.equals(val, val2)) {
                    mReason = "Expected " + field + " " + val2 + " but was " + val;
                    return false;
                }
                return true;
            }

            @Override
            public String toString() {
                return mReason;
            }
        }));
    }

}