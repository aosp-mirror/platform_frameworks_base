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
import static org.junit.Assert.assertThrows;

import android.annotation.NonNull;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.CancellationSignal;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class ParcelableHandwritingGestureTest {

    @Test
    public void testCreationFailWithNullPointerException() {
        assertThrows(NullPointerException.class, () -> ParcelableHandwritingGesture.of(null));
    }

    @Test
    public void testInvalidTypeHeader() {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            // GESTURE_TYPE_NONE is not a supported header.
            parcel.writeInt(HandwritingGesture.GESTURE_TYPE_NONE);
            final Parcel initializedParcel = parcel;
            assertThrows(UnsupportedOperationException.class,
                    () -> ParcelableHandwritingGesture.CREATOR.createFromParcel(initializedParcel));
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
    }

    @Test
    public void  testSelectGesture() {
        verifyEqualityAfterUnparcel(new SelectGesture.Builder()
                .setGranularity(HandwritingGesture.GRANULARITY_WORD)
                .setSelectionArea(new RectF(1, 2, 3, 4))
                .setFallbackText("")
                .build());
    }

    @Test
    public void  testSelectRangeGesture() {
        verifyEqualityAfterUnparcel(new SelectRangeGesture.Builder()
                .setGranularity(HandwritingGesture.GRANULARITY_WORD)
                .setSelectionStartArea(new RectF(1, 2, 3, 4))
                .setSelectionEndArea(new RectF(5, 6, 7, 8))
                .setFallbackText("")
                .build());
    }

    @Test
    public void  testInsertGestureGesture() {
        verifyEqualityAfterUnparcel(new InsertGesture.Builder()
                .setTextToInsert("text")
                .setInsertionPoint(new PointF(1, 1)).setFallbackText("")
                .build());
    }

    @Test
    public void testInsertModeGesture() {
        verifyEqualityAfterUnparcel(new InsertModeGesture.Builder()
                .setInsertionPoint(new PointF(1, 1)).setFallbackText("")
                .setCancellationSignal(new CancellationSignal())
                .build());
    }

    @Test
    public void  testDeleteGestureGesture() {
        verifyEqualityAfterUnparcel(new DeleteGesture.Builder()
                .setGranularity(HandwritingGesture.GRANULARITY_WORD)
                .setDeletionArea(new RectF(1, 2, 3, 4))
                .setFallbackText("")
                .build());
    }

    @Test
    public void  testDeleteRangeGestureGesture() {
        verifyEqualityAfterUnparcel(new DeleteRangeGesture.Builder()
                .setGranularity(HandwritingGesture.GRANULARITY_WORD)
                .setDeletionStartArea(new RectF(1, 2, 3, 4))
                .setDeletionEndArea(new RectF(5, 6, 7, 8))
                .setFallbackText("")
                .build());
    }

    @Test
    public void  testRemoveSpaceGestureGesture() {
        verifyEqualityAfterUnparcel(new RemoveSpaceGesture.Builder()
                .setPoints(new PointF(1f, 2f), new PointF(3f, 4f))
                .setFallbackText("")
                .build());
    }

    @Test
    public void  testJoinOrSplitGestureGesture() {
        verifyEqualityAfterUnparcel(new JoinOrSplitGesture.Builder()
                .setJoinOrSplitPoint(new PointF(1f, 2f))
                .setFallbackText("")
                .build());
    }

    static void verifyEqualityAfterUnparcel(@NonNull HandwritingGesture gesture) {
        assertEquals(gesture, cloneViaParcel(ParcelableHandwritingGesture.of(gesture)).get());
    }

    private static ParcelableHandwritingGesture cloneViaParcel(
            @NonNull ParcelableHandwritingGesture original) {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return ParcelableHandwritingGesture.CREATOR.createFromParcel(parcel);
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
    }
}
