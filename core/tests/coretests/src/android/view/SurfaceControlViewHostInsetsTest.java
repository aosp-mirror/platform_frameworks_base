/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import static android.view.InsetsState.ITYPE_STATUS_BAR;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.platform.test.annotations.Presubmit;
import android.view.InsetsState.InternalInsetsType;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsets.Type;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class SurfaceControlViewHostInsetsTest {
    SurfaceControlViewHost mSurfaceControlViewHost;
    private boolean mStatusBarIsVisible = false;
    private Insets mStatusBarInsets;
    private Instrumentation mInstrumentation;

    private void createViewHierarchy() {
        Context context = mInstrumentation.getTargetContext();

        View v = new View(context);
        v.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                public WindowInsets onApplyWindowInsets(View v, WindowInsets w) {
                    mStatusBarIsVisible = w.isVisible(WindowInsets.Type.statusBars());
                    mStatusBarInsets = w.getInsets(WindowInsets.Type.statusBars());
                    return w;
                }
        });
        mSurfaceControlViewHost = new SurfaceControlViewHost(context,
            context.getDisplayNoVerify(), new Binder());
        mSurfaceControlViewHost.setView(v, 100, 100);
    }

    @Before
    public void setup() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mInstrumentation.runOnMainSync(() -> { createViewHierarchy(); });
        mInstrumentation.waitForIdleSync();
    }

    private InsetsState statusBarState(boolean visible) {
        final InsetsState insetsState = new InsetsState();
        insetsState.setDisplayFrame(new Rect(0, 0, 1000, 1000));
        insetsState.getSource(ITYPE_STATUS_BAR).setVisible(visible);
        insetsState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 10));
        return insetsState;
    }

    private InsetsState statusBarVisibleState() {
        return statusBarState(true);
    }

    private void sendInsetsSync(InsetsState s, Rect f) {
        try  {
            mSurfaceControlViewHost.getSurfacePackage().getRemoteInterface()
                .onInsetsChanged(s, f);
        } catch (Exception e) {
        }
        mInstrumentation.waitForIdleSync();
    }

    @Test
    public void sendInsetsToSurfaceControlViewHost() {
        final InsetsState insetsState = statusBarVisibleState();
        sendInsetsSync(insetsState, new Rect(0, 0, 100, 100));
        assertTrue(mStatusBarIsVisible);

        final InsetsState insetsState2 = statusBarState(false);
        sendInsetsSync(insetsState2, new Rect(0, 0, 100, 100));
        assertFalse(mStatusBarIsVisible);
   }

    @Test
    public void insetsAreRelativeToFrame() {
        final InsetsState insetsState = statusBarVisibleState();
        sendInsetsSync(insetsState, new Rect(0, 0, 100, 100));

        assertTrue(mStatusBarIsVisible);
        assertEquals(10, mStatusBarInsets.top);

        sendInsetsSync(insetsState, new Rect(0, 5, 100, 100));
        assertEquals(5, mStatusBarInsets.top);
    }
}
