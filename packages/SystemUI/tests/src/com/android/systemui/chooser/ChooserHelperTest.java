/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.chooser;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.os.Binder;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import com.android.systemui.chooser.ChooserHelper;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyFloat;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ChooserHelperTest extends SysuiTestCase {

    @Test
    public void testOnChoose_CallsStartActivityAsCallerWithToken() {
        final Intent intent = new Intent();
        final Binder token = new Binder();
        intent.putExtra(ActivityManager.EXTRA_PERMISSION_TOKEN, token);

        final Activity mockActivity = mock(Activity.class);
        when(mockActivity.getIntent()).thenReturn(intent);

        ChooserHelper.onChoose(mockActivity);
        verify(mockActivity, times(1)).startActivityAsCaller(
                any(), any(), eq(token), anyBoolean(), anyInt());
    }
}
