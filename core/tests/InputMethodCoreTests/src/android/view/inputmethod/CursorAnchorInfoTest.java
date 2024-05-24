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

package android.view.inputmethod;

import static org.junit.Assert.assertEquals;

import android.graphics.Matrix;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class CursorAnchorInfoTest {
    @Test
    public void testCreateForAdditionalParentMatrix() {
        final Matrix originalMatrix = new Matrix();
        originalMatrix.setTranslate(10.0f, 20.0f);
        final CursorAnchorInfo.Builder builder = new CursorAnchorInfo.Builder();
        builder.setMatrix(originalMatrix);

        final CursorAnchorInfo originalInstance = builder.build();

        assertEquals(originalMatrix, originalInstance.getMatrix());

        final Matrix additionalParentMatrix = new Matrix();
        additionalParentMatrix.setTranslate(1.0f, 2.0f);

        final Matrix newMatrix = new Matrix(originalMatrix);
        newMatrix.postConcat(additionalParentMatrix);

        builder.reset();
        builder.setMatrix(newMatrix);
        // An instance created by the standard Builder class.
        final CursorAnchorInfo newInstanceByBuilder = builder.build();

        // An instance created by an @hide method.
        final CursorAnchorInfo newInstanceByMethod =
                CursorAnchorInfo.createForAdditionalParentMatrix(
                        originalInstance, additionalParentMatrix);

        assertEquals(newMatrix, newInstanceByBuilder.getMatrix());
        assertEquals(newMatrix, newInstanceByMethod.getMatrix());
        assertEquals(newInstanceByBuilder.hashCode(), newInstanceByMethod.hashCode());
    }
}
