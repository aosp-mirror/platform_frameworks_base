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
import static android.view.InsetsState.TYPE_IME;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Bundle;
import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl.Transaction;
import android.view.WindowManager.BadTokenException;
import android.view.WindowManager.LayoutParams;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.FlakyTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

@Presubmit
@FlakyTest(detail = "Promote once confirmed non-flaky")
@RunWith(AndroidJUnit4.class)
public class ImeInsetsSourceConsumerTest {

    Context mContext = InstrumentationRegistry.getTargetContext();
    ImeInsetsSourceConsumer mImeConsumer;
    InsetsController mController;
    SurfaceControl mLeash;

    @Before
    public void setup() {
        mLeash = new SurfaceControl.Builder(new SurfaceSession())
                .setName("testSurface")
                .build();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ViewRootImpl viewRootImpl = new ViewRootImpl(mContext, mContext.getDisplay());
            try {
                viewRootImpl.setView(new TextView(mContext), new LayoutParams(), null);
            } catch (BadTokenException e) {
                // activity isn't running, we will ignore BadTokenException.
            }
            mController = new InsetsController(viewRootImpl);
            final Rect rect = new Rect(5, 5, 5, 5);
            mController.calculateInsets(
                    false,
                    false,
                    new DisplayCutout(
                            Insets.of(10, 10, 10, 10), rect, rect, rect, rect),
                    rect, rect);
            mImeConsumer = new ImeInsetsSourceConsumer(
                    new InsetsState(), Transaction::new, mController);
        });
    }

    @Test
    public void testImeVisibility() {
        final InsetsSourceControl ime = new InsetsSourceControl(TYPE_IME, mLeash);
        mController.onControlsChanged(new InsetsSourceControl[] { ime });

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // test if setVisibility can show IME
            mImeConsumer.onWindowFocusGained();
            mImeConsumer.applyImeVisibility(true);
            mController.cancelExistingAnimation();
            assertTrue(mController.getSourceConsumer(ime.getType()).isVisible());

            // test if setVisibility can hide IME
            mImeConsumer.applyImeVisibility(false);
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());
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
