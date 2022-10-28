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
 * limitations under the License.
 */

package android.view;

import static org.junit.Assert.assertEquals;

import android.os.Parcel;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;

@Presubmit
@SmallTest
public class WindowParamsTest {

    @Test
    public void testParamsForRotation() {
        final WindowManager.LayoutParams paramsA = new WindowManager.LayoutParams();
        initParamsForRotation(paramsA);
        final Parcel parcel = Parcel.obtain();
        paramsA.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        final WindowManager.LayoutParams paramsB = new WindowManager.LayoutParams(parcel);
        assertEquals(0, paramsA.copyFrom(paramsB));

        for (int i = 1; i <= 12; i++) {
            initParamsForRotation(paramsA);
            changeField(i, paramsA.paramsForRotation[0]);
            assertEquals("Change not found for case " + i,
                    WindowManager.LayoutParams.LAYOUT_CHANGED, paramsA.copyFrom(paramsB));
        }

        parcel.recycle();
    }

    private static void initParamsForRotation(WindowManager.LayoutParams params) {
        params.paramsForRotation = new WindowManager.LayoutParams[4];
        for (int i = 0; i < 4; i++) {
            params.paramsForRotation[i] = new WindowManager.LayoutParams();
        }
    }

    private static void changeField(int fieldCase, WindowManager.LayoutParams params) {
        switch (fieldCase) {
            case 1:
                params.width++;
                break;
            case 2:
                params.height++;
                break;
            case 3:
                params.x++;
                break;
            case 4:
                params.y++;
                break;
            case 5:
                params.horizontalMargin++;
                break;
            case 6:
                params.verticalMargin++;
                break;
            case 7:
                params.layoutInDisplayCutoutMode++;
                break;
            case 8:
                params.gravity++;
                break;
            case 9:
                params.providedInsets = new InsetsFrameProvider[0];
                break;
            case 10:
                params.setFitInsetsTypes(0);
                break;
            case 11:
                params.setFitInsetsSides(0);
                break;
            case 12:
                params.setFitInsetsIgnoringVisibility(!params.isFitInsetsIgnoringVisibility());
                break;
        }
    }
}
