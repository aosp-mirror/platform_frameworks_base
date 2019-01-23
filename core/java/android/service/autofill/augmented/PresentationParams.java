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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.graphics.Rect;
import android.service.autofill.augmented.AugmentedAutofillService.AutofillProxy;
import android.util.DebugUtils;
import android.view.View;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Abstraction of a "Smart Suggestion" component responsible to embed the autofill UI provided by
 * the intelligence service.
 *
 * <p>The Smart Suggestion can embed the autofill UI in 3 distinct places:
 *
 * <ul>
 *   <li>A small area associated with suggestions (like a small strip in the top of the IME),
 *   returned by {@link #getSuggestionArea()}
 *   <li>The full area (like the full IME window), returned by {@link #getFullArea()}
 *   <li>A subset of the aforementioned areas, returned by {@link Area#getSubArea(Rect)}
 * </ul>
 *
 * <p>The Smart Suggestion is represented by a {@link Area} object that contains the
 * dimensions the smart suggestion window, so the service can use it to calculate the size of the
 * view that will be passed to {@link FillWindow#update(Area, View, long)}.
 *
 * @hide
 */
@SystemApi
@TestApi
//TODO(b/122654591): @TestApi is needed because CtsAutoFillServiceTestCases hosts the service
//in the same package as the test, and that module is compiled with SDK=test_current
public abstract class PresentationParams {

    /**
     * Flag indicating the Smart Suggestion is hosted in the top of its container.
     */
    public static final int FLAG_HINT_GRAVITY_TOP = 0x1;

    /**
     * Flag indicating the Smart Suggestion is hosted in the bottom of its container.
     */
    public static final int FLAG_HINT_GRAVITY_BOTTOM = 0x2;

    /**
     * Flag indicating the Smart Suggestion is hosted in the left of its container.
     */
    public static final int FLAG_HINT_GRAVITY_LEFT = 0x4;

    /**
     * Flag indicating the Smart Suggestion is hosted in the right of its container.
     */
    public static final int FLAG_HINT_GRAVITY_RIGHT = 0x8;

    /**
     * Flag indicating the Smart Suggestion is hosted by the IME.
     */
    public static final int FLAG_HOST_IME = 0x10;

    /**
     * Flag indicating the Smart Suggestion is hosted by the Android System as a floating popup
     * window.
     */
    public static final int FLAG_HOST_SYSTEM = 0x20;

    /** @hide */
    @IntDef(flag = true, prefix = { "FLAG_" }, value = {
            FLAG_HINT_GRAVITY_TOP,
            FLAG_HINT_GRAVITY_BOTTOM,
            FLAG_HINT_GRAVITY_LEFT,
            FLAG_HINT_GRAVITY_RIGHT,
            FLAG_HOST_IME,
            FLAG_HOST_SYSTEM
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Flags {}


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

    /**
     * Gets the full area for the of the Smart Suggestion provider.
     *
     * @return full dimensions, or {@code null} if the Smart Suggestion provider does not support
     * embeding the UI on its full area.
     */
    @Nullable
    public Area getFullArea() {
        return null;
    }

    /**
     * Gets flags associated with the Smart Suggestion.
     *
     * @return any combination of {@link #FLAG_HINT_GRAVITY_TOP},
     * {@link #FLAG_HINT_GRAVITY_BOTTOM}, {@link #FLAG_HINT_GRAVITY_LEFT},
     * {@link #FLAG_HINT_GRAVITY_RIGHT}, {@link #FLAG_HOST_IME}, or
     * {@link #FLAG_HOST_SYSTEM},
     */
    public @Flags int getFlags() {
        return 0;
    }

    /** @hide */
    void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
        final int flags = getFlags();
        if (flags > 0) {
            pw.print(prefix); pw.print("flags: "); pw.println(flagsToString(flags));
        }
    }

    private static String flagsToString(int flags) {
        return DebugUtils.flagsToString(PresentationParams.class, "FLAG_", flags);
    }

    /**
     * Area associated with a {@link PresentationParams Smart Suggestions} provider.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    //TODO(b/122654591): @TestApi is needed because CtsAutoFillServiceTestCases hosts the service
    //in the same package as the test, and that module is compiled with SDK=test_current
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

        /**
         * Gets a subarea limited by given boundaries.
         *
         * @param bounds boundaries relative to this Area.
         *
         * @return new subarea, or {@code null} if the Smart Suggestion host does not support such
         * subaarea.
         *
         * @throws IllegalArgumentException if the {@code bounds} is not fully-contained inside this
         * full Area.
         *
         */
        @Nullable
        public Area getSubArea(@NonNull Rect bounds) {
            // TODO(b/123100712): implement / check boundaries / throw IAE / add unit test
            return null;
        }

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
        public int getFlags() {
            return FLAG_HOST_SYSTEM | FLAG_HINT_GRAVITY_BOTTOM;
        }

        @Override
        void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
            super.dump(prefix, pw);
            pw.print(prefix); pw.print("area: "); pw.println(mSuggestionArea);
        }
    }
}
