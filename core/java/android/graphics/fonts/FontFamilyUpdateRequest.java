/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.graphics.fonts;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Request for updating or adding a font family on the system.
 *
 * <p>You can update or add a font family with custom style parameters. The following example
 * defines a font family called "roboto" using "Roboto-Regular" font file that is already available
 * on the system by preloading or {@link FontManager#updateFontFile}.
 * <pre>
 * FontManager fm = getContext().getSystemService(FontManager.class);
 * fm.updateFontFamily(new FontFamilyUpdateRequest.Builder()
 *     .addFontFamily(new FontFamilyUpdateRequest.FontFamily("roboto", Arrays.asList(
 *         new FontFamilyUpdateRequest.Font(
 *             "Roboto-Regular",
 *             new FontStyle(FONT_WEIGHT_NORMAL, FONT_SLANT_UPRIGHT),
 *             Collections.emptyList()),
 *         new FontFamilyUpdateRequest.Font(
 *             "Roboto-Regular",
 *             new FontStyle(FONT_WEIGHT_NORMAL, FONT_SLANT_ITALIC),
 *             Collections.emptyList()))))
 *     .build(), fm.getFontConfig().getConfigVersion());
 * </pre>
 *
 * <p>You can update or add font files in the same request by calling
 * {@link FontFamilyUpdateRequest.Builder#addFontFileUpdateRequest(FontFileUpdateRequest)}.
 * The following example adds "YourFont" font file and defines "your-font" font family in the same
 * request. In this case, the font file represented by {@code yourFontFd} should be an OpenType
 * compliant font file and have "YourFont" as PostScript name (ID=6) in 'name' table.
 * <pre>
 * FontManager fm = getContext().getSystemService(FontManager.class);
 * fm.updateFontFamily(new FontFamilyUpdateRequest.Builder()
 *     .addFontFileUpdateRequest(new FontFileUpdateRequest(yourFontFd, signature))
 *     .addFontFamily(new FontFamilyUpdateRequest.FontFamily("your-font", Arrays.asList(
 *         new FontFamilyUpdateRequest.Font(
 *             "YourFont",
 *             new FontStyle(FONT_WEIGHT_NORMAL, FONT_SLANT_UPRIGHT),
 *             Collections.emptyList()))))
 *     .build(), fm.getFontConfig().getConfigVersion());
 * </pre>
 *
 * @hide
 */
@SystemApi
public final class FontFamilyUpdateRequest {

    /**
     * A font family definition.
     */
    public static final class FontFamily {

        /**
         * Builds a {@link FontFamily}.
         */
        public static final class Builder {
            @NonNull private final String mName;
            @NonNull private final List<Font> mFonts;

            /**
             * Constructs a {@link FontFamily.Builder}.
             */
            public Builder(@NonNull String name, @NonNull List<Font> fonts) {
                Objects.requireNonNull(name);
                Preconditions.checkStringNotEmpty(name);
                Objects.requireNonNull(fonts);
                Preconditions.checkCollectionElementsNotNull(fonts, "fonts");
                Preconditions.checkCollectionNotEmpty(fonts, "fonts");
                mName = name;
                mFonts = new ArrayList<>(fonts);
            }

            /**
             * Adds a {@link Font} to the builder.
             *
             * @return This builder object.
             */
            public @NonNull Builder addFont(@NonNull Font font) {
                mFonts.add(font);
                return this;
            }

            /**
             * Builds a {@link FontFamily}.
             */
            public @NonNull FontFamily build() {
                return new FontFamily(mName, mFonts);
            }
        }

        @NonNull
        private final String mName;
        @NonNull
        private final List<Font> mFonts;

        /**
         * Constructs a FontFamily.
         *
         * <p>A font family has a name to identify the font family. Apps can use
         * {@link android.graphics.Typeface#create(String, int)} or XML resources to use a specific
         * font family.
         *
         * <p>A font family consists of multiple fonts with different styles. The style information
         * can be specified by {@link Font}.
         *
         * @see android.graphics.Typeface#create(String, int)
         * @see Font
         */
        private FontFamily(@NonNull String name, @NonNull List<Font> fonts) {
            mName = name;
            mFonts = fonts;
        }

        /**
         * Returns the name of this family.
         */
        @NonNull
        public String getName() {
            return mName;
        }

        /**
         * Returns the fonts in this family.
         */
        @NonNull
        public List<Font> getFonts() {
            return mFonts;
        }
    }

    /**
     * A single entry in a font family representing a font.
     */
    public static final class Font {

        @NonNull
        private final String mPostScriptName;
        @NonNull
        private final FontStyle mStyle;
        @NonNull
        private final List<FontVariationAxis> mAxes;

        /**
         * Constructs a FontStyleVariation.
         *
         * <p>A font has a PostScript name to identify the font file to use, a {@link FontStyle}
         * to specify the style, and a list of {@link FontVariationAxis} to specify axis tags and
         * values for variable fonts. If the font file identified by {@code postScriptName} is not a
         * variable font, {@code axes} must be empty.
         *
         * @param postScriptName The PostScript name of the font file to use. PostScript name is in
         *                       Name ID 6 field in 'name' table, as specified by OpenType
         *                       specification.
         * @param style          The style for this font.
         * @param axes           A list of {@link FontVariationAxis} to specify axis tags and values
         *                       for variable fonts.
         */
        public Font(@NonNull String postScriptName, @NonNull FontStyle style,
                @NonNull List<FontVariationAxis> axes) {
            Objects.requireNonNull(postScriptName);
            Preconditions.checkStringNotEmpty(postScriptName);
            Objects.requireNonNull(style);
            Objects.requireNonNull(axes);
            Preconditions.checkCollectionElementsNotNull(axes, "axes");
            mPostScriptName = postScriptName;
            mStyle = style;
            mAxes = axes;
        }

        /**
         * Returns PostScript name of the font file to use.
         */
        @NonNull
        public String getPostScriptName() {
            return mPostScriptName;
        }

        /**
         * Returns the style.
         */
        @NonNull
        public FontStyle getStyle() {
            return mStyle;
        }

        /**
         * Returns the list of {@link FontVariationAxis}.
         */
        @NonNull
        public List<FontVariationAxis> getAxes() {
            return mAxes;
        }

        /**
         * Returns the index of collection
         *
         * TODO(183752879): Make font index configurable and make this SystemApi.
         * @hide
         */
        public @IntRange(from = 0) int getIndex() {
            return 0;
        }
    }

    /**
     * Builds a {@link FontFamilyUpdateRequest}.
     */
    public static final class Builder {
        @NonNull
        private final List<FontFileUpdateRequest> mFontFileUpdateRequests = new ArrayList<>();
        @NonNull
        private final List<FontFamily> mFontFamilies = new ArrayList<>();

        /**
         * Constructs a FontFamilyUpdateRequest.Builder.
         */
        public Builder() {
        }

        /**
         * Adds a {@link FontFileUpdateRequest} to execute as a part of the constructed
         * {@link FontFamilyUpdateRequest}.
         *
         * @param request A font file update request.
         * @return This builder object.
         */
        @NonNull
        public Builder addFontFileUpdateRequest(@NonNull FontFileUpdateRequest request) {
            Objects.requireNonNull(request);
            mFontFileUpdateRequests.add(request);
            return this;
        }

        /**
         * Adds a font family to update an existing font family in the system font config or
         * add as a new font family to the system font config.
         *
         * @param fontFamily An font family definition to add or update.
         * @return This builder object.
         */
        @NonNull
        public Builder addFontFamily(@NonNull FontFamily fontFamily) {
            Objects.requireNonNull(fontFamily);
            mFontFamilies.add(fontFamily);
            return this;
        }

        /**
         * Builds a {@link FontFamilyUpdateRequest}.
         */
        @NonNull
        public FontFamilyUpdateRequest build() {
            return new FontFamilyUpdateRequest(mFontFileUpdateRequests, mFontFamilies);
        }
    }

    @NonNull
    private final List<FontFileUpdateRequest> mFontFiles;

    @NonNull
    private final List<FontFamily> mFontFamilies;

    private FontFamilyUpdateRequest(@NonNull List<FontFileUpdateRequest> fontFiles,
            @NonNull List<FontFamily> fontFamilies) {
        mFontFiles = fontFiles;
        mFontFamilies = fontFamilies;
    }

    /**
     * Returns the list of {@link FontFileUpdateRequest} that will be executed as a part of this
     * request.
     */
    @NonNull
    public List<FontFileUpdateRequest> getFontFileUpdateRequests() {
        return mFontFiles;
    }

    /**
     * Returns the list of {@link FontFamily} that will be updated in this request.
     */
    @NonNull
    public List<FontFamily> getFontFamilies() {
        return mFontFamilies;
    }
}
