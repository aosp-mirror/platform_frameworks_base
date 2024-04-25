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

package com.android.systemui.statusbar.phone;

import static com.google.common.truth.Truth.assertThat;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.res.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class KeyguardStatusBarViewTest extends SysuiTestCase {

    private KeyguardStatusBarView mKeyguardStatusBarView;

    @Before
    public void setup() throws Exception {
        allowTestableLooperAsMainThread();
        TestableLooper.get(this).runWithLooper(() -> {
            mKeyguardStatusBarView =
                    (KeyguardStatusBarView) LayoutInflater.from(mContext)
                            .inflate(R.layout.keyguard_status_bar, null);
        });
    }

    @Test
    public void userSwitcherChip_defaultVisibilityIsGone() {
        assertThat(mKeyguardStatusBarView.findViewById(
                R.id.user_switcher_container).getVisibility()).isEqualTo(
                View.GONE);
    }

    @Test
    public void setTopClipping_clippingUpdated() {
        int topClipping = 40;

        mKeyguardStatusBarView.setTopClipping(topClipping);

        assertThat(mKeyguardStatusBarView.getClipBounds().top).isEqualTo(topClipping);
    }
}
