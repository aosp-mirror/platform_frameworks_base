/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.volume;

import static com.android.systemui.volume.Events.DISMISS_REASON_UNKNOWN;
import static com.android.systemui.volume.Events.SHOW_REASON_UNKNOWN;
import static com.android.systemui.volume.VolumeDialogControllerImpl.STREAMS;

import static junit.framework.Assert.assertTrue;

import android.app.KeyguardManager;
import android.media.AudioManager;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.VolumeDialogController;
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.function.Predicate;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class VolumeDialogImplTest extends SysuiTestCase {

    VolumeDialogImpl mDialog;

    @Mock
    VolumeDialogController mController;

    @Mock
    KeyguardManager mKeyguard;

    @Mock
    AccessibilityManagerWrapper mAccessibilityMgr;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        mController = mDependency.injectMockDependency(VolumeDialogController.class);
        mAccessibilityMgr = mDependency.injectMockDependency(AccessibilityManagerWrapper.class);
        getContext().addMockSystemService(KeyguardManager.class, mKeyguard);

        mDialog = new VolumeDialogImpl(getContext());
        mDialog.init(0, null);
        VolumeDialogController.State state = new VolumeDialogController.State();
        for (int i = AudioManager.STREAM_VOICE_CALL; i <= AudioManager.STREAM_ACCESSIBILITY; i++) {
            VolumeDialogController.StreamState ss = new VolumeDialogController.StreamState();
            ss.name = STREAMS.get(i);
            state.states.append(i, ss);
        }
        mDialog.onStateChangedH(state);
    }

    private void navigateViews(View view, Predicate<View> condition) {
        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                navigateViews(viewGroup.getChildAt(i), condition);
            }
        } else {
            String resourceName = null;
            try {
                resourceName = getContext().getResources().getResourceName(view.getId());
            } catch (Exception e) {}
            assertTrue("View " + resourceName != null ? resourceName : view.getId()
                    + " failed test", condition.test(view));
        }
    }

    @Test
    public void testContentDescriptions() {
        mDialog.show(SHOW_REASON_UNKNOWN);
        ViewGroup dialog = mDialog.getDialogView();

        navigateViews(dialog, view -> {
            if (view instanceof ImageView) {
                return !TextUtils.isEmpty(view.getContentDescription());
            } else {
                return true;
            }
        });

        mDialog.dismiss(DISMISS_REASON_UNKNOWN);
    }

}
