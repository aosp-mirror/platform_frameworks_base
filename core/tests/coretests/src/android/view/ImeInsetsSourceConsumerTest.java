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
 * limitations under the License
 */

package android.view;

import static android.view.ImeInsetsSourceConsumer.areEditorsSimilar;
import static android.view.InsetsState.ITYPE_IME;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.platform.test.annotations.Presubmit;
import android.view.WindowManager.BadTokenException;
import android.view.WindowManager.LayoutParams;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.FlakyTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.Spy;

import java.util.ArrayList;

/**
 * Test {@link InsetsSourceConsumer} with IME type.
 *
 * Build/Install/Run:
 *  atest FrameworksCoreTests:ImeInsetsSourceConsumerTest
 */
@Presubmit
@FlakyTest(detail = "Promote once confirmed non-flaky")
@RunWith(AndroidJUnit4.class)
public class ImeInsetsSourceConsumerTest {

    Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    ImeInsetsSourceConsumer mImeConsumer;
    @Spy InsetsController mController;
    SurfaceControl mLeash;

    @Before
    public void setup() {
        mLeash = new SurfaceControl.Builder(new SurfaceSession())
                .setName("testSurface")
                .build();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ViewRootImpl viewRootImpl = new ViewRootImpl(mContext, mContext.getDisplayNoVerify());
            try {
                viewRootImpl.setView(new TextView(mContext), new LayoutParams(), null);
            } catch (BadTokenException e) {
                // activity isn't running, we will ignore BadTokenException.
            }
            mController = Mockito.spy(new InsetsController(
                    new ViewRootInsetsControllerHost(viewRootImpl)));
            final Rect rect = new Rect(5, 5, 5, 5);
            mController.calculateInsets(
                    false,
                    false,
                    new DisplayCutout(
                            Insets.of(10, 10, 10, 10), rect, rect, rect, rect),
                    SOFT_INPUT_ADJUST_RESIZE, 0, 0);
            mImeConsumer = (ImeInsetsSourceConsumer) mController.getSourceConsumer(ITYPE_IME);
        });
    }

    @Test
    public void testImeVisibility() {
        final InsetsSourceControl ime = new InsetsSourceControl(ITYPE_IME, mLeash, new Point());
        mController.onControlsChanged(new InsetsSourceControl[] { ime });

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // test if setVisibility can show IME
            mImeConsumer.onWindowFocusGained();
            mImeConsumer.applyImeVisibility(true);
            mController.cancelExistingAnimations();
            assertTrue(mController.getSourceConsumer(ime.getType()).isRequestedVisible());

            // test if setVisibility can hide IME
            mImeConsumer.applyImeVisibility(false);
            mController.cancelExistingAnimations();
            assertFalse(mController.getSourceConsumer(ime.getType()).isRequestedVisible());
        });
    }

    @Test
    public void testImeRequestedVisibleAwaitingControl() {
        // Set null control and then request show.
        mController.onControlsChanged(new InsetsSourceControl[] { null });

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // Request IME visible before control is available.
            mImeConsumer.onWindowFocusGained();
            mImeConsumer.applyImeVisibility(true /* setVisible */);

            // set control and verify visibility is applied.
            InsetsSourceControl control = new InsetsSourceControl(ITYPE_IME, mLeash, new Point());
            mController.onControlsChanged(new InsetsSourceControl[] { control });
            // IME show animation should be triggered when control becomes available.
            verify(mController).applyAnimation(
                    eq(WindowInsets.Type.ime()), eq(true) /* show */, eq(true) /* fromIme */);
            verify(mController, never()).applyAnimation(
                    eq(WindowInsets.Type.ime()), eq(false) /* show */, eq(true) /* fromIme */);
        });
    }

    @Test
    public void testAreEditorsSimilar() {
        EditorInfo info1 = new EditorInfo();
        info1.privateImeOptions = "dummy";
        EditorInfo info2 = new EditorInfo();

        assertFalse(areEditorsSimilar(info1, info2));

        info1.privateImeOptions = null;
        assertTrue(areEditorsSimilar(info1, info2));

        info1.inputType = info2.inputType = 3;
        info1.imeOptions = info2.imeOptions = 0x4;
        info1.packageName = info2.packageName = "dummy.package";
        assertTrue(areEditorsSimilar(info1, info2));

        Bundle extras1 = new Bundle();
        extras1.putByteArray("key1", "value1".getBytes());
        extras1.putChar("key2", 'c');
        Bundle extras2 = new Bundle();
        extras2.putByteArray("key1", "value1".getBytes());
        extras2.putChar("key2", 'c');
        info1.extras = extras1;
        info2.extras = extras2;
        assertTrue(areEditorsSimilar(info1, info2));

        Bundle extraBundle = new Bundle();
        ArrayList<Integer> list = new ArrayList<>();
        list.add(2);
        list.add(5);
        extraBundle.putByteArray("key1", "value1".getBytes());
        extraBundle.putChar("key2", 'c');
        extraBundle.putIntegerArrayList("key3", list);

        extras1.putAll(extraBundle);
        extras2.putAll(extraBundle);
        assertTrue(areEditorsSimilar(info1, info2));

        extras2.putChar("key2", 'd');
        assertFalse(areEditorsSimilar(info1, info2));

        extras2.putChar("key2", 'c');
        extras2.putInt("key4", 1);
        assertFalse(areEditorsSimilar(info1, info2));
    }
}
