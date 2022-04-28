/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import android.app.ActivityTaskManager;
import android.graphics.Matrix;
import android.os.IBinder;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Class for testing {@link WindowInfo}.
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public class WindowInfoTest {

    @SmallTest
    @Test
    public void testObtain() {
        WindowInfo w1 = WindowInfo.obtain();
        assertNotNull(w1);
        initTestWindowInfo(w1);

        WindowInfo w2 = WindowInfo.obtain(w1);

        assertNotSame(w1, w2);
        areWindowsEqual(w1, w2);
    }

    @SmallTest
    @Test
    public void testParceling() {
        Parcel parcel = Parcel.obtain();
        WindowInfo w1 = WindowInfo.obtain();
        initTestWindowInfo(w1);
        w1.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        WindowInfo w2 = WindowInfo.CREATOR.createFromParcel(parcel);

        assertNotSame(w1, w2);
        areWindowsEqual(w1, w2);
        parcel.recycle();
    }

    @SmallTest
    @Test
    public void testDefaultValues() {
        WindowInfo w = WindowInfo.obtain();

        assertEquals(0, w.type);
        assertEquals(0, w.layer);
        assertEquals(AccessibilityNodeInfo.UNDEFINED_NODE_ID, w.accessibilityIdOfAnchor);
        assertEquals(Display.INVALID_DISPLAY, w.displayId);
        assertEquals(ActivityTaskManager.INVALID_TASK_ID, w.taskId);
        assertNull(w.title);
        assertNull(w.token);
        assertNull(w.childTokens);
        assertNull(w.parentToken);
        assertNull(w.activityToken);
        assertFalse(w.focused);
        assertFalse(w.inPictureInPicture);
        assertFalse(w.hasFlagWatchOutsideTouch);
        assertTrue(w.regionInScreen.isEmpty());
        assertEquals(w.mTransformMatrix.length, 9);
        assertTrue(w.mMagnificationSpec.isNop());
    }

    @SmallTest
    @Test
    public void testRecycle() {
        WindowInfo w = WindowInfo.obtain();
        w.recycle();

        try {
            w.recycle();
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            // Expected.
        }
    }

    private boolean areWindowsEqual(WindowInfo w1, WindowInfo w2) {
        boolean equality = w1.toString().contentEquals(w2.toString());
        equality &= w1.token == w2.token;
        equality &= w1.childTokens.equals(w2.childTokens);
        equality &= w1.parentToken == w2.parentToken;
        equality &= w1.activityToken == w2.activityToken;
        equality &= w1.regionInScreen.equals(w2.regionInScreen);
        equality &= w1.mMagnificationSpec.equals(w2.mMagnificationSpec);
        equality &= Arrays.equals(w1.mTransformMatrix, w2.mTransformMatrix);
        return equality;
    }

    private void initTestWindowInfo(WindowInfo windowInfo) {
        windowInfo.type = 1;
        windowInfo.displayId = 2;
        windowInfo.layer = 3;
        windowInfo.accessibilityIdOfAnchor = 4L;
        windowInfo.taskId = 5;
        windowInfo.title = "title";
        windowInfo.token = mock(IBinder.class);
        windowInfo.childTokens = new ArrayList<>();
        windowInfo.childTokens.add(mock(IBinder.class));
        windowInfo.parentToken = mock(IBinder.class);
        windowInfo.activityToken = mock(IBinder.class);
        windowInfo.focused = true;
        windowInfo.inPictureInPicture = true;
        windowInfo.hasFlagWatchOutsideTouch = true;
        windowInfo.regionInScreen.set(0, 0, 1080, 1080);
        windowInfo.mMagnificationSpec.scale = 2.0f;
        windowInfo.mMagnificationSpec.offsetX = 100f;
        windowInfo.mMagnificationSpec.offsetY = 200f;
        Matrix.IDENTITY_MATRIX.getValues(windowInfo.mTransformMatrix);
    }
}
