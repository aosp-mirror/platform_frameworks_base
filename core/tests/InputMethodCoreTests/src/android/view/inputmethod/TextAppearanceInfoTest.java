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
import static org.junit.Assert.assertNotNull;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.fonts.FontStyle;
import android.graphics.text.LineBreakConfig;
import android.os.LocaleList;
import android.os.Parcel;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.ScaleXSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.util.Linkify;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TextAppearanceInfoTest {
    private static final float EPSILON = 0.0000001f;
    private static final String TEST_TEXT = "Hello: google.com";
    private static final float TEXT_SIZE = 16.5f;
    private static final LocaleList TEXT_LOCALES = LocaleList.forLanguageTags("en,ja");
    private static final String FONT_FAMILY_NAME = "sans-serif";
    private static final int TEXT_WEIGHT = FontStyle.FONT_WEIGHT_MEDIUM;
    private static final int TEXT_STYLE = Typeface.ITALIC;
    private static final boolean ALL_CAPS = true;
    private static final float SHADOW_DX = 2.0f;
    private static final float SHADOW_DY = 2.0f;
    private static final float SHADOW_RADIUS = 2.0f;
    private static final int SHADOW_COLOR = Color.GRAY;
    private static final boolean ELEGANT_TEXT_HEIGHT = true;
    private static final boolean FALLBACK_LINE_SPACING = true;
    private static final float LETTER_SPACING = 5.0f;
    private static final String FONT_FEATURE_SETTINGS = "smcp";
    private static final String FONT_VARIATION_SETTINGS = "'wdth' 1.0";
    private static final int LINE_BREAK_STYLE = LineBreakConfig.LINE_BREAK_STYLE_LOOSE;
    private static final int LINE_BREAK_WORD_STYLE = LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE;
    private static final float TEXT_SCALEX = 1.5f;
    private static final int HIGHLIGHT_TEXT_COLOR = Color.YELLOW;
    private static final int TEXT_COLOR = Color.RED;
    private static final int HINT_TEXT_COLOR = Color.GREEN;
    private static final int LINK_TEXT_COLOR = Color.BLUE;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    private final EditText mEditText = new EditText(mContext);
    private final SpannableStringBuilder mSpannableText = new SpannableStringBuilder(TEST_TEXT);
    private Canvas mCanvas;

    @Before
    public void setUp() {
        initEditText(mSpannableText);
    }

    @Test
    public void testCreateFromTextView_noSpan() {
        assertTextAppearanceInfoContentsEqual(TextAppearanceInfo.createFromTextView(mEditText));
    }

    @Test
    public void testCreateFromTextView_withSpan1() {
        AbsoluteSizeSpan sizeSpan = new AbsoluteSizeSpan(30);
        mSpannableText.setSpan(sizeSpan, 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        ForegroundColorSpan colorSpan = new ForegroundColorSpan(Color.CYAN);
        mSpannableText.setSpan(colorSpan, 1, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        TypefaceSpan typefaceSpan = new TypefaceSpan("cursive");
        mSpannableText.setSpan(typefaceSpan, 2, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        mEditText.setText(mSpannableText);

        // |Happy birthday!
        mEditText.setSelection(0);
        TextAppearanceInfo info1 = TextAppearanceInfo.createFromTextView(mEditText);
        assertEquals(info1.getTextSize(), TEXT_SIZE, EPSILON);
        assertEquals(info1.getTextColor(), TEXT_COLOR);
        assertEquals(info1.getSystemFontFamilyName(), FONT_FAMILY_NAME);

        // H|appy birthday!
        mEditText.setSelection(1);
        TextAppearanceInfo info2 = TextAppearanceInfo.createFromTextView(mEditText);
        assertEquals(info2.getTextSize(), 30f, EPSILON);
        assertEquals(info2.getTextColor(), TEXT_COLOR);
        assertEquals(info2.getSystemFontFamilyName(), FONT_FAMILY_NAME);

        // Ha|ppy birthday!
        mEditText.setSelection(2);
        TextAppearanceInfo info3 = TextAppearanceInfo.createFromTextView(mEditText);
        assertEquals(info3.getTextSize(), 30f, EPSILON);
        assertEquals(info3.getTextColor(), Color.CYAN);
        assertEquals(info3.getSystemFontFamilyName(), FONT_FAMILY_NAME);

        // Ha[ppy birthday!]
        mEditText.setSelection(2, mSpannableText.length());
        TextAppearanceInfo info4 = TextAppearanceInfo.createFromTextView(mEditText);
        assertEquals(info4.getTextSize(), 30f, EPSILON);
        assertEquals(info4.getTextColor(), Color.CYAN);
        assertEquals(info4.getSystemFontFamilyName(), FONT_FAMILY_NAME);

        // Happy| birthday!
        mEditText.setSelection(5);
        TextAppearanceInfo info5 = TextAppearanceInfo.createFromTextView(mEditText);
        assertEquals(info5.getTextSize(), 30f, EPSILON);
        assertEquals(info5.getTextColor(), Color.CYAN);
        assertEquals(info5.getSystemFontFamilyName(), "cursive");
    }

    @Test
    public void testCreateFromTextView_withSpan2() {
        // aab|
        SpannableStringBuilder spannableText = new SpannableStringBuilder("aab");

        AbsoluteSizeSpan sizeSpan = new AbsoluteSizeSpan(30);
        spannableText.setSpan(sizeSpan, 0, 3, Spanned.SPAN_INCLUSIVE_INCLUSIVE);

        ForegroundColorSpan colorSpan = new ForegroundColorSpan(Color.CYAN);
        spannableText.setSpan(colorSpan, 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        StyleSpan styleSpan = new StyleSpan(Typeface.BOLD);
        spannableText.setSpan(styleSpan, 1, 2, Spanned.SPAN_EXCLUSIVE_INCLUSIVE);

        TypefaceSpan typefaceSpan = new TypefaceSpan("cursive");
        spannableText.setSpan(typefaceSpan, 3, 3, Spanned.SPAN_INCLUSIVE_INCLUSIVE);

        ScaleXSpan scaleXSpan = new ScaleXSpan(2.0f);
        spannableText.setSpan(scaleXSpan, 3, 3, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);

        mEditText.setText(spannableText);
        mEditText.setSelection(3);
        TextAppearanceInfo info = TextAppearanceInfo.createFromTextView(mEditText);

        // The character before cursor 'b' should only have an AbsoluteSizeSpan.
        assertEquals(info.getTextSize(), 30f, EPSILON);
        assertEquals(info.getTextColor(), TEXT_COLOR);
        assertEquals(info.getTextStyle(), TEXT_STYLE);
        assertEquals(info.getSystemFontFamilyName(), FONT_FAMILY_NAME);
        assertEquals(info.getTextScaleX(), TEXT_SCALEX, EPSILON);
    }

    @Test
    public void testCreateFromTextView_contradictorySpans() {
        // Set multiple contradictory spans
        AbsoluteSizeSpan sizeSpan1 = new AbsoluteSizeSpan(30);
        CustomForegroundColorSpan colorSpan1 = new CustomForegroundColorSpan(Color.BLUE);
        AbsoluteSizeSpan sizeSpan2 = new AbsoluteSizeSpan(10);
        CustomForegroundColorSpan colorSpan2 = new CustomForegroundColorSpan(Color.GREEN);

        mSpannableText.setSpan(sizeSpan1, 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        mSpannableText.setSpan(colorSpan1, 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        mSpannableText.setSpan(sizeSpan2, 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        mSpannableText.setSpan(colorSpan2, 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        mEditText.setText(mSpannableText);
        mEditText.draw(mCanvas);
        mEditText.setSelection(3);
        // Get a copy of the real TextPaint after setting the last span
        TextPaint realTextPaint = colorSpan2.lastTextPaint;
        assertNotNull(realTextPaint);
        TextAppearanceInfo info1 = TextAppearanceInfo.createFromTextView(mEditText);
        // Verify the real TextPaint equals the last span of multiple contradictory spans
        assertEquals(info1.getTextSize(), 10f, EPSILON);
        assertEquals(info1.getTextSize(), realTextPaint.getTextSize(), EPSILON);
        assertEquals(info1.getTextColor(), Color.GREEN);
        assertEquals(info1.getTextColor(), realTextPaint.getColor());
        assertEquals(info1.getSystemFontFamilyName(), FONT_FAMILY_NAME);
    }

    private void initEditText(CharSequence text) {
        mEditText.setText(text);
        mEditText.getPaint().setTextSize(TEXT_SIZE);
        mEditText.setTextLocales(TEXT_LOCALES);
        Typeface family = Typeface.create(FONT_FAMILY_NAME, Typeface.NORMAL);
        mEditText.setTypeface(
                Typeface.create(family, TEXT_WEIGHT, (TEXT_STYLE & Typeface.ITALIC) != 0));
        mEditText.setAllCaps(ALL_CAPS);
        mEditText.setShadowLayer(SHADOW_RADIUS, SHADOW_DX, SHADOW_DY, SHADOW_COLOR);
        mEditText.setElegantTextHeight(ELEGANT_TEXT_HEIGHT);
        mEditText.setFallbackLineSpacing(FALLBACK_LINE_SPACING);
        mEditText.setLetterSpacing(LETTER_SPACING);
        mEditText.setFontFeatureSettings(FONT_FEATURE_SETTINGS);
        mEditText.setFontVariationSettings(FONT_VARIATION_SETTINGS);
        mEditText.setLineBreakStyle(LINE_BREAK_STYLE);
        mEditText.setLineBreakWordStyle(LINE_BREAK_WORD_STYLE);
        mEditText.setTextScaleX(TEXT_SCALEX);
        mEditText.setHighlightColor(HIGHLIGHT_TEXT_COLOR);
        mEditText.setTextColor(TEXT_COLOR);
        mEditText.setHintTextColor(HINT_TEXT_COLOR);
        mEditText.setHint("Hint text");
        mEditText.setLinkTextColor(LINK_TEXT_COLOR);
        mEditText.setAutoLinkMask(Linkify.WEB_URLS);
        ViewGroup.LayoutParams params =
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mEditText.setLayoutParams(params);
        mEditText.measure(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        Bitmap bitmap =
                Bitmap.createBitmap(
                        Math.max(1, mEditText.getMeasuredWidth()),
                        Math.max(1, mEditText.getMeasuredHeight()),
                        Bitmap.Config.ARGB_8888);
        mEditText.layout(0, 0, mEditText.getMeasuredWidth(), mEditText.getMeasuredHeight());
        mCanvas = new Canvas(bitmap);
        mEditText.draw(mCanvas);
    }
    private void assertTextAppearanceInfoContentsEqual(TextAppearanceInfo textAppearanceInfo) {
        assertEquals(textAppearanceInfo.getTextSize(), TEXT_SIZE, EPSILON);
        assertEquals(textAppearanceInfo.getTextLocales(), TEXT_LOCALES);
        assertEquals(textAppearanceInfo.getSystemFontFamilyName(), FONT_FAMILY_NAME);
        assertEquals(textAppearanceInfo.getTextFontWeight(), TEXT_WEIGHT);
        assertEquals(textAppearanceInfo.getTextStyle(), TEXT_STYLE);
        assertEquals(textAppearanceInfo.isAllCaps(), ALL_CAPS);
        assertEquals(textAppearanceInfo.getShadowRadius(), SHADOW_RADIUS, EPSILON);
        assertEquals(textAppearanceInfo.getShadowDx(), SHADOW_DX, EPSILON);
        assertEquals(textAppearanceInfo.getShadowDy(), SHADOW_DY, EPSILON);
        assertEquals(textAppearanceInfo.getShadowColor(), SHADOW_COLOR);
        assertEquals(textAppearanceInfo.isElegantTextHeight(), ELEGANT_TEXT_HEIGHT);
        assertEquals(textAppearanceInfo.isFallbackLineSpacing(), FALLBACK_LINE_SPACING);
        assertEquals(textAppearanceInfo.getLetterSpacing(), LETTER_SPACING, EPSILON);
        assertEquals(textAppearanceInfo.getFontFeatureSettings(), FONT_FEATURE_SETTINGS);
        assertEquals(textAppearanceInfo.getFontVariationSettings(), FONT_VARIATION_SETTINGS);
        assertEquals(textAppearanceInfo.getLineBreakStyle(), LINE_BREAK_STYLE);
        assertEquals(textAppearanceInfo.getLineBreakWordStyle(), LINE_BREAK_WORD_STYLE);
        assertEquals(textAppearanceInfo.getTextScaleX(), TEXT_SCALEX, EPSILON);
        assertEquals(textAppearanceInfo.getTextColor(), TEXT_COLOR);
        assertEquals(textAppearanceInfo.getHighlightTextColor(), HIGHLIGHT_TEXT_COLOR);
        assertEquals(textAppearanceInfo.getHintTextColor(), HINT_TEXT_COLOR);
        assertEquals(textAppearanceInfo.getLinkTextColor(), LINK_TEXT_COLOR);
    }

    @Test
    public void testCreateFromTextView_withHintText() {
        // Make hint text display
        initEditText("");

        // The text color should not be hint color
        assertTextAppearanceInfoContentsEqual(TextAppearanceInfo.createFromTextView(mEditText));
    }

    static class CustomForegroundColorSpan extends ForegroundColorSpan {
        @Nullable public TextPaint lastTextPaint = null;

        CustomForegroundColorSpan(int color) {
            super(color);
        }

        CustomForegroundColorSpan(@NonNull Parcel src) {
            super(src);
        }

        @Override
        public void updateDrawState(@NonNull TextPaint tp) {
            super.updateDrawState(tp);
            // Copy the real TextPaint
            TextPaint tpCopy = new TextPaint();
            tpCopy.set(tp);
            lastTextPaint = tpCopy;
        }
    }
}
