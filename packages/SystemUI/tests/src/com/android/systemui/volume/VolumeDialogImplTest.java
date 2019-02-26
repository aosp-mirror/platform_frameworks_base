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

import static com.android.systemui.volume.VolumeDialogControllerImpl.STREAMS;

import static junit.framework.Assert.assertTrue;

import android.app.KeyguardManager;
import android.media.AudioManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.VolumeDialogController;
import com.android.systemui.plugins.VolumeDialogController.State;
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.function.Predicate;

@Ignore
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
        State state = createShellState();
        mDialog.onStateChangedH(state);
    }

    private State createShellState() {
        State state = new VolumeDialogController.State();
        for (int i = AudioManager.STREAM_VOICE_CALL; i <= AudioManager.STREAM_ACCESSIBILITY; i++) {
            VolumeDialogController.StreamState ss = new VolumeDialogController.StreamState();
            ss.name = STREAMS.get(i);
            ss.level = 1;
            state.states.append(i, ss);
        }
        return state;
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
/*
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

    @Test
    public void testNoDuplicationOfParentState() {
        mDialog.show(SHOW_REASON_UNKNOWN);
        ViewGroup dialog = mDialog.getDialogView();

        navigateViews(dialog, view -> !view.isDuplicateParentStateEnabled());

        mDialog.dismiss(DISMISS_REASON_UNKNOWN);
    }

    @Test
    public void testNoClickableViewGroups() {
        mDialog.show(SHOW_REASON_UNKNOWN);
        ViewGroup dialog = mDialog.getDialogView();

        navigateViews(dialog, view -> {
            if (view instanceof ViewGroup) {
                return !view.isClickable();
            } else {
                return true;
            }
        });

        mDialog.dismiss(DISMISS_REASON_UNKNOWN);
    }

    @Test
    public void testTristateToggle_withVibrator() {
        when(mController.hasVibrator()).thenReturn(true);

        State state = createShellState();
        state.ringerModeInternal = RINGER_MODE_NORMAL;
        mDialog.onStateChangedH(state);

        mDialog.show(SHOW_REASON_UNKNOWN);
        ViewGroup dialog = mDialog.getDialogView();

        // click once, verify updates to vibrate
        dialog.findViewById(R.id.ringer_icon).performClick();
        verify(mController, times(1)).setRingerMode(RINGER_MODE_VIBRATE, false);

        // fake the update back to the dialog with the new ringer mode
        state = createShellState();
        state.ringerModeInternal = RINGER_MODE_VIBRATE;
        mDialog.onStateChangedH(state);

        // click once, verify updates to silent
        dialog.findViewById(R.id.ringer_icon).performClick();
        verify(mController, times(1)).setRingerMode(RINGER_MODE_SILENT, false);
        verify(mController, times(1)).setStreamVolume(STREAM_RING, 0);

        // fake the update back to the dialog with the new ringer mode
        state = createShellState();
        state.states.get(STREAM_RING).level = 0;
        state.ringerModeInternal = RINGER_MODE_SILENT;
        mDialog.onStateChangedH(state);

        // click once, verify updates to normal
        dialog.findViewById(R.id.ringer_icon).performClick();
        verify(mController, times(1)).setRingerMode(RINGER_MODE_NORMAL, false);
        verify(mController, times(1)).setStreamVolume(STREAM_RING, 0);
    }

    @Test
    public void testTristateToggle_withoutVibrator() {
        when(mController.hasVibrator()).thenReturn(false);

        State state = createShellState();
        state.ringerModeInternal = RINGER_MODE_NORMAL;
        mDialog.onStateChangedH(state);

        mDialog.show(SHOW_REASON_UNKNOWN);
        ViewGroup dialog = mDialog.getDialogView();

        // click once, verify updates to silent
        dialog.findViewById(R.id.ringer_icon).performClick();
        verify(mController, times(1)).setRingerMode(RINGER_MODE_SILENT, false);
        verify(mController, times(1)).setStreamVolume(STREAM_RING, 0);

        // fake the update back to the dialog with the new ringer mode
        state = createShellState();
        state.states.get(STREAM_RING).level = 0;
        state.ringerModeInternal = RINGER_MODE_SILENT;
        mDialog.onStateChangedH(state);

        // click once, verify updates to normal
        dialog.findViewById(R.id.ringer_icon).performClick();
        verify(mController, times(1)).setRingerMode(RINGER_MODE_NORMAL, false);
        verify(mController, times(1)).setStreamVolume(STREAM_RING, 0);
    }
    */
}
