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
package android.service.autofill.augmented;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.graphics.Rect;
import android.service.autofill.augmented.AugmentedAutofillService.AutofillProxy;
import android.view.View;

import java.io.PrintWriter;

/**
 * Abstraction of a "Smart Suggestion" component responsible to embed the autofill UI provided by
 * the augmented autofill service.
 *
 * <p>The Smart Suggestion is represented by a {@link Area} object that contains the
 * dimensions the smart suggestion window, so the service can use it to calculate the size of the
 * view that will be passed to {@link FillWindow#update(Area, View, long)}.
 *
 * @hide
 */
@SystemApi
public abstract class PresentationParams {

    // /** @hide */
    PresentationParams() {}

    /**
     * Gets the area of the suggestion strip for the given {@code metadata}
     *
     * @return strip dimensions, or {@code null} if the Smart Suggestion provider does not support
     * suggestions strip.
     */
    @Nullable
    public Area getSuggestionArea() {
        return null;
    }

    abstract void dump(String prefix, PrintWriter pw);

    /**
     * Area associated with a {@link PresentationParams Smart Suggestions} provider.
     *
     * @hide
     */
    @SystemApi
    public abstract static class Area {

        /** @hide */
        public final AutofillProxy proxy;

        private final Rect mBounds;

        private Area(@NonNull AutofillProxy proxy, @NonNull Rect bounds) {
            this.proxy = proxy;
            mBounds = bounds;
        }

        /**
         * Gets the area boundaries.
         */
        @NonNull
        public Rect getBounds() {
            return mBounds;
        }

        @NonNull
        @Override
        public String toString() {
            return mBounds.toString();
        }
    }

    /**
     * System-provided poup window anchored to a view.
     *
     * <p>Used just for debugging purposes.
     *
     * @hide
     */
    public static final class SystemPopupPresentationParams extends PresentationParams {
        private final Area mSuggestionArea;

        public SystemPopupPresentationParams(@NonNull AutofillProxy proxy, @NonNull Rect rect) {
            mSuggestionArea = new Area(proxy, rect) {};
        }

        @Override
        public Area getSuggestionArea() {
            return mSuggestionArea;
        }

        @Override
        void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
            pw.print(prefix); pw.print("area: "); pw.println(mSuggestionArea);
        }
    }
}
