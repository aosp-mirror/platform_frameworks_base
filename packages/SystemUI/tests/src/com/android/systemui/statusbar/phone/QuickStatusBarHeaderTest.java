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

package com.android.systemui.statusbar.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.R.layout;
import com.android.systemui.SysUIRunner;
import com.android.systemui.utils.TestableLooper;
import com.android.systemui.utils.TestableLooper.RunWithLooper;
import com.android.systemui.utils.ViewUtils;
import com.android.systemui.utils.leaks.LeakCheckedTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

@RunWith(SysUIRunner.class)
@RunWithLooper(setAsMainLooper = true)
public class QuickStatusBarHeaderTest extends LeakCheckedTest {

    @Before
    public void setup() throws NoSuchFieldException, IllegalAccessException {
        injectLeakCheckedDependencies(ALL_SUPPORTED_CLASSES);
    }

    @Test
    public void testRoamingStuck() throws Exception {
        TestableLooper looper = TestableLooper.get(this);
        assertEquals(Looper.myLooper(), looper.getLooper());
        assertEquals(Looper.myLooper(), Looper.getMainLooper());
        QuickStatusBarHeader header = (QuickStatusBarHeader) LayoutInflater.from(mContext).inflate(
                layout.quick_status_bar_expanded_header, null);
        header.setExpanded(true);

        ViewUtils.attachView(header);
        looper.processMessages(1);
        TextView emergencyText = (TextView) header.findViewById(
                R.id.header_emergency_calls_only);
        int subId = 0;
        header.setMobileDataIndicators(null, null, 0, 0, false,
                false, null, null, false, subId, true);
        looper.processAllMessages();
        assertEquals(mContext.getString(R.string.accessibility_data_connection_roaming),
                emergencyText.getText());
        assertEquals(View.VISIBLE, emergencyText.getVisibility());

        header.setSubs(new ArrayList<>());
        subId = 1;
        header.setMobileDataIndicators(null, null, 0, 0, false,
                false, null, null, false, subId, false);
        looper.processAllMessages();

        assertNotEquals(View.VISIBLE, emergencyText.getVisibility());
        assertEquals(Looper.myLooper(), Looper.getMainLooper());
        ViewUtils.detachView(header);
        looper.processAllMessages();
    }

}
