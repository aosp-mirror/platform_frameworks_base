/*
 * Copyright 2023 The Android Open Source Project
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

package android.hardware.input;

import android.annotation.ColorInt;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.Slog;
import android.util.TypedValue;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A custom drawable class that draws preview of a Physical keyboard layout.
 */
final class KeyboardLayoutPreviewDrawable extends Drawable {

    private static final String TAG = "KeyboardLayoutPreview";
    private static final int GRAVITY_LEFT = 0x1;
    private static final int GRAVITY_RIGHT = 0x2;
    private static final int GRAVITY_TOP = 0x4;
    private static final int GRAVITY_BOTTOM = 0x8;
    private static final int TEXT_PADDING_IN_DP = 1;
    private static final int KEY_PADDING_IN_DP = 3;
    private static final int KEYBOARD_PADDING_IN_DP = 10;
    private static final int KEY_RADIUS_IN_DP = 5;
    private static final int KEYBOARD_RADIUS_IN_DP = 10;
    private static final int MIN_GLYPH_TEXT_SIZE_IN_SP = 10;
    private static final int MAX_GLYPH_TEXT_SIZE_IN_SP = 20;

    private final List<KeyDrawable> mKeyDrawables = new ArrayList<>();

    private final int mWidth;
    private final int mHeight;
    private final RectF mKeyboardBackground = new RectF();
    private final ResourceProvider mResourceProvider;
    private final PhysicalKeyLayout mKeyLayout;

    public KeyboardLayoutPreviewDrawable(Context context, PhysicalKeyLayout keyLayout, int width,
            int height) {
        mWidth = width;
        mHeight = height;
        mResourceProvider = new ResourceProvider(context);
        mKeyLayout = keyLayout;
    }

    @Override
    public int getIntrinsicWidth() {
        return mWidth;
    }

    @Override
    public int getIntrinsicHeight() {
        return mHeight;
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        super.onBoundsChange(bounds);
        mKeyDrawables.clear();
        final PhysicalKeyLayout.LayoutKey[][] keys = mKeyLayout.getKeys();
        if (keys == null) {
            return;
        }
        final PhysicalKeyLayout.EnterKey enterKey = mKeyLayout.getEnterKey();
        int width = bounds.width();
        int height = bounds.height();
        final int keyboardPadding = mResourceProvider.getKeyboardPadding();
        final int keyPadding = mResourceProvider.getKeyPadding();
        final float keyRadius = mResourceProvider.getKeyRadius();
        mKeyboardBackground.set(0, 0, width, height);
        width -= keyboardPadding * 2;
        height -= keyboardPadding * 2;
        if (width <= 0 || height <= 0) {
            Slog.e(TAG, "Invalid width and height to draw layout preview, width = " + width
                    + ", height = " + height);
            return;
        }
        int rowCount = keys.length;
        float keyHeight = (float) (height - rowCount * 2 * keyPadding) / rowCount;
        // Based on key height calculate the max text size that can fit for typing keys
        mResourceProvider.calculateBestTextSizeForKey(keyHeight);
        float isoEnterKeyLeft = 0;
        float isoEnterKeyTop = 0;
        float isoEnterWidthUnit = 0;
        for (int i = 0; i < rowCount; i++) {
            PhysicalKeyLayout.LayoutKey[] row = keys[i];
            float totalRowWeight = 0;
            int keysInRow = row.length;
            for (PhysicalKeyLayout.LayoutKey layoutKey : row) {
                totalRowWeight += layoutKey.keyWeight();
            }
            float keyWidthInPx = (width - keysInRow * 2 * keyPadding) / totalRowWeight;
            float rowWeightOnLeft = 0;
            float top = keyboardPadding + keyPadding * (2 * i + 1) + i * keyHeight;
            for (int j = 0; j < keysInRow; j++) {
                float left =
                        keyboardPadding + keyPadding * (2 * j + 1) + rowWeightOnLeft * keyWidthInPx;
                rowWeightOnLeft += row[j].keyWeight();
                RectF keyRect = new RectF(left, top, left + keyWidthInPx * row[j].keyWeight(),
                        top + keyHeight);
                if (enterKey != null && row[j].keyCode() == KeyEvent.KEYCODE_ENTER) {
                    if (enterKey.row() == i && enterKey.column() == j) {
                        isoEnterKeyLeft = keyRect.left;
                        isoEnterKeyTop = keyRect.top;
                        isoEnterWidthUnit = keyWidthInPx;
                    }
                    continue;
                }
                if (PhysicalKeyLayout.isSpecialKey(row[j])) {
                    mKeyDrawables.add(new TypingKey(null, keyRect, keyRadius,
                            mResourceProvider.getTextPadding(),
                            mResourceProvider.getSpecialKeyPaint(),
                            mResourceProvider.getSpecialKeyPaint(),
                            mResourceProvider.getSpecialKeyPaint()));
                } else if (PhysicalKeyLayout.isKeyPositionUnsure(row[j])) {
                    mKeyDrawables.add(new UnsureTypingKey(row[j].glyph(), keyRect,
                            keyRadius, mResourceProvider.getTextPadding(),
                            mResourceProvider.getTypingKeyPaint(),
                            mResourceProvider.getPrimaryGlyphPaint(),
                            mResourceProvider.getSecondaryGlyphPaint()));
                } else {
                    mKeyDrawables.add(new TypingKey(row[j].glyph(), keyRect, keyRadius,
                            mResourceProvider.getTextPadding(),
                            mResourceProvider.getTypingKeyPaint(),
                            mResourceProvider.getPrimaryGlyphPaint(),
                            mResourceProvider.getSecondaryGlyphPaint()));
                }
            }
        }
        if (enterKey != null) {
            IsoEnterKey.Builder isoEnterKeyBuilder = new IsoEnterKey.Builder(keyRadius,
                    mResourceProvider.getSpecialKeyPaint());
            isoEnterKeyBuilder.setTopWidth(enterKey.topKeyWeight() * isoEnterWidthUnit)
                    .setStartPoint(isoEnterKeyLeft, isoEnterKeyTop)
                    .setVerticalEdges(keyHeight, 2 * (keyHeight + keyPadding))
                    .setBottomWidth(enterKey.bottomKeyWeight() * isoEnterWidthUnit);
            mKeyDrawables.add(isoEnterKeyBuilder.build());
        }
    }

    @Override
    public void draw(Canvas canvas) {
        final float keyboardRadius = mResourceProvider.getBackgroundRadius();
        canvas.drawRoundRect(mKeyboardBackground, keyboardRadius, keyboardRadius,
                mResourceProvider.getBackgroundPaint());
        for (KeyDrawable key : mKeyDrawables) {
            key.draw(canvas);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        // Do nothing
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        // Do nothing
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }

    private static class TypingKey implements KeyDrawable {

        private final RectF mKeyRect;
        private final float mKeyRadius;
        private final float mTextPadding;
        private final Paint mKeyPaint;
        private final Paint mBaseTextPaint;
        private final Paint mModifierTextPaint;
        private final List<GlyphDrawable> mGlyphDrawables = new ArrayList<>();

        private TypingKey(@Nullable PhysicalKeyLayout.KeyGlyph glyphData, RectF keyRect,
                float keyRadius, float textPadding, Paint keyPaint, Paint baseTextPaint,
                Paint modifierTextPaint) {
            mKeyRect = keyRect;
            mKeyRadius = keyRadius;
            mTextPadding = textPadding;
            mKeyPaint = keyPaint;
            mBaseTextPaint = baseTextPaint;
            mModifierTextPaint = modifierTextPaint;
            initGlyphs(glyphData);
        }

        private void initGlyphs(@Nullable PhysicalKeyLayout.KeyGlyph glyphData) {
            createGlyphs(glyphData);
            measureGlyphs();
        }

        private void createGlyphs(@Nullable PhysicalKeyLayout.KeyGlyph glyphData) {
            if (glyphData == null) {
                return;
            }
            if (!glyphData.hasBaseText()) {
                return;
            }
            mGlyphDrawables.add(new GlyphDrawable(glyphData.getBaseText(), new RectF(),
                    GRAVITY_BOTTOM | GRAVITY_LEFT, mBaseTextPaint));
            if (glyphData.hasValidShiftText()) {
                mGlyphDrawables.add(new GlyphDrawable(glyphData.getShiftText(), new RectF(),
                        GRAVITY_TOP | GRAVITY_LEFT, mModifierTextPaint));
            }
            if (glyphData.hasValidAltGrText()) {
                mGlyphDrawables.add(new GlyphDrawable(glyphData.getAltGrText(), new RectF(),
                        GRAVITY_BOTTOM | GRAVITY_RIGHT, mModifierTextPaint));
            }
            if (glyphData.hasValidAltGrShiftText()) {
                mGlyphDrawables.add(new GlyphDrawable(glyphData.getAltGrShiftText(), new RectF(),
                        GRAVITY_TOP | GRAVITY_RIGHT, mModifierTextPaint));
            }
        }

        private void measureGlyphs() {
            float keyWidth = mKeyRect.width();
            float keyHeight = mKeyRect.height();
            for (GlyphDrawable glyph : mGlyphDrawables) {
                float centerX = keyWidth / 2;
                float centerY = keyHeight / 2;
                if ((glyph.gravity & GRAVITY_LEFT) != 0) {
                    centerX -= keyWidth / 4;
                    centerX += mTextPadding / 2;
                }
                if ((glyph.gravity & GRAVITY_RIGHT) != 0) {
                    centerX += keyWidth / 4;
                    centerX -= mTextPadding / 2;
                }
                if ((glyph.gravity & GRAVITY_TOP) != 0) {
                    centerY -= keyHeight / 4;
                    centerY += mTextPadding / 2;
                }
                if ((glyph.gravity & GRAVITY_BOTTOM) != 0) {
                    centerY += keyHeight / 4;
                    centerY -= mTextPadding / 2;
                }
                Rect textBounds = new Rect();
                glyph.paint.getTextBounds(glyph.text, 0, glyph.text.length(), textBounds);
                float textWidth = textBounds.width();
                float textHeight = textBounds.height();
                glyph.rect.set(centerX - textWidth / 2, centerY - textHeight / 2 - textBounds.top,
                        centerX + textWidth / 2, centerY + textHeight / 2 - textBounds.top);
            }
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawRoundRect(mKeyRect, mKeyRadius, mKeyRadius, mKeyPaint);
            for (GlyphDrawable glyph : mGlyphDrawables) {
                float textWidth = glyph.rect.width();
                float textHeight = glyph.rect.height();
                float keyWidth = mKeyRect.width();
                float keyHeight = mKeyRect.height();
                if (textWidth == 0 || textHeight == 0 || keyWidth == 0 || keyHeight == 0) {
                    return;
                }
                canvas.drawText(glyph.text, 0, glyph.text.length(), mKeyRect.left + glyph.rect.left,
                        mKeyRect.top + glyph.rect.top, glyph.paint);
            }
        }
    }

    private static class UnsureTypingKey extends TypingKey {

        private UnsureTypingKey(@Nullable PhysicalKeyLayout.KeyGlyph glyphData,
                RectF keyRect, float keyRadius, float textPadding, Paint keyPaint,
                Paint baseTextPaint, Paint modifierTextPaint) {
            super(glyphData, keyRect, keyRadius, textPadding, createGreyedOutPaint(keyPaint),
                    createGreyedOutPaint(baseTextPaint), createGreyedOutPaint(modifierTextPaint));
        }
    }

    private static class IsoEnterKey implements KeyDrawable {

        private final Paint mKeyPaint;
        private final Path mPath;

        private IsoEnterKey(Paint keyPaint, @NonNull Path path) {
            mKeyPaint = keyPaint;
            mPath = path;
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawPath(mPath, mKeyPaint);
        }

        private static class Builder {
            private final float mKeyRadius;
            private final Paint mKeyPaint;
            private float mLeft;
            private float mTop;
            private float mTopWidth;
            private float mBottomWidth;
            private float mLeftHeight;
            private float mRightHeight;

            private Builder(float keyRadius, Paint keyPaint) {
                mKeyRadius = keyRadius;
                mKeyPaint = keyPaint;
            }

            private Builder setStartPoint(float left, float top) {
                mLeft = left;
                mTop = top;
                return this;
            }

            private Builder setTopWidth(float width) {
                mTopWidth = width;
                return this;
            }

            private Builder setBottomWidth(float width) {
                mBottomWidth = width;
                return this;
            }

            private Builder setVerticalEdges(float leftHeight, float rightHeight) {
                mLeftHeight = leftHeight;
                mRightHeight = rightHeight;
                return this;
            }

            private IsoEnterKey build() {
                Path enterKey = new Path();
                RectF oval = new RectF(-mKeyRadius, -mKeyRadius, mKeyRadius, mKeyRadius);
                // Horizontal top line
                enterKey.moveTo(mLeft + mKeyRadius, mTop);
                enterKey.lineTo(mLeft + mTopWidth - mKeyRadius, mTop);
                // Rounded top right corner
                oval.offsetTo(mLeft + mTopWidth - 2 * mKeyRadius, mTop);
                enterKey.arcTo(oval, 270, 90);
                // Vertical right line
                enterKey.lineTo(mLeft + mTopWidth, mTop + mRightHeight - mKeyRadius);
                // Rounded bottom right corner
                oval.offsetTo(mLeft + mTopWidth - 2 * mKeyRadius,
                        mTop + mRightHeight - 2 * mKeyRadius);
                enterKey.arcTo(oval, 0, 90);
                // Horizontal bottom line
                enterKey.lineTo(mLeft + mTopWidth - mBottomWidth + mKeyRadius, mTop + mRightHeight);
                // Rounded bottom left corner
                oval.offsetTo(mLeft + mTopWidth - mBottomWidth,
                        mTop + mRightHeight - 2 * mKeyRadius);
                enterKey.arcTo(oval, 90, 90);
                // Vertical left line (bottom half)
                enterKey.lineTo(mLeft + mTopWidth - mBottomWidth, mTop + mLeftHeight - mKeyRadius);
                // Rounded corner
                oval.offsetTo(mLeft + mTopWidth - mBottomWidth - 2 * mKeyRadius,
                        mTop + mLeftHeight);
                enterKey.arcTo(oval, 0, -90);
                // Horizontal line in the mid part
                enterKey.lineTo(mLeft + mKeyRadius, mTop + mLeftHeight);
                // Rounded corner
                oval.offsetTo(mLeft, mTop + mLeftHeight - 2 * mKeyRadius);
                enterKey.arcTo(oval, 90, 90);
                // Vertical left line (top half)
                enterKey.lineTo(mLeft, mTop + mKeyRadius);
                // Rounded top left corner
                oval.offsetTo(mLeft, mTop);
                enterKey.arcTo(oval, 180, 90);
                enterKey.close();
                return new IsoEnterKey(mKeyPaint, enterKey);
            }
        }
    }

    private record GlyphDrawable(String text, RectF rect, int gravity, Paint paint) {}

    private interface KeyDrawable {
        void draw(Canvas canvas);
    }

    private static class ResourceProvider {
        // Resources
        private final Paint mBackgroundPaint;
        private final Paint mTypingKeyPaint;
        private final Paint mSpecialKeyPaint;
        private final Paint mPrimaryGlyphPaint;
        private final Paint mSecondaryGlyphPaint;
        private final int mKeyPadding;
        private final int mKeyboardPadding;
        private final float mTextPadding;
        private final float mKeyRadius;
        private final float mBackgroundRadius;
        private final float mSpToPxMultiplier;
        private final Paint.FontMetrics mFontMetrics;

        private ResourceProvider(Context context) {
            mKeyPadding = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    KEY_PADDING_IN_DP, context.getResources().getDisplayMetrics());
            mKeyboardPadding = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    KEYBOARD_PADDING_IN_DP, context.getResources().getDisplayMetrics());
            mKeyRadius = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    KEY_RADIUS_IN_DP, context.getResources().getDisplayMetrics());
            mBackgroundRadius = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    KEYBOARD_RADIUS_IN_DP, context.getResources().getDisplayMetrics());
            mSpToPxMultiplier = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1,
                    context.getResources().getDisplayMetrics());
            mTextPadding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    TEXT_PADDING_IN_DP, context.getResources().getDisplayMetrics());
            boolean isDark = (context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            int typingKeyColor = context.getColor(
                    isDark ? android.R.color.system_outline_variant_dark
                            : android.R.color.system_surface_container_lowest_light);
            int specialKeyColor = context.getColor(isDark ? android.R.color.system_neutral1_800
                    : android.R.color.system_secondary_container_light);
            int primaryGlyphColor = context.getColor(isDark ? android.R.color.system_on_surface_dark
                    : android.R.color.system_on_surface_light);
            int secondaryGlyphColor = context.getColor(isDark ? android.R.color.system_outline_dark
                    : android.R.color.system_outline_light);
            int backgroundColor = context.getColor(
                    isDark ? android.R.color.system_surface_container_dark
                            : android.R.color.system_surface_container_light);
            mPrimaryGlyphPaint = createTextPaint(primaryGlyphColor,
                    MIN_GLYPH_TEXT_SIZE_IN_SP * mSpToPxMultiplier,
                    Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            mSecondaryGlyphPaint = createTextPaint(secondaryGlyphColor,
                    MIN_GLYPH_TEXT_SIZE_IN_SP * mSpToPxMultiplier,
                    Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
            mFontMetrics = mPrimaryGlyphPaint.getFontMetrics();
            mTypingKeyPaint = createFillPaint(typingKeyColor);
            mSpecialKeyPaint = createFillPaint(specialKeyColor);
            mBackgroundPaint = createFillPaint(backgroundColor);
        }

        private void calculateBestTextSizeForKey(float keyHeight) {
            int textSize = (int) (mSpToPxMultiplier * MIN_GLYPH_TEXT_SIZE_IN_SP) + 1;
            while (textSize < mSpToPxMultiplier * MAX_GLYPH_TEXT_SIZE_IN_SP) {
                updateTextSize(textSize);
                if (mFontMetrics.bottom - mFontMetrics.top + 3 * mTextPadding > keyHeight / 2) {
                    textSize--;
                    break;
                }
                textSize++;
            }
            updateTextSize(textSize);
        }

        private void updateTextSize(float textSize) {
            mPrimaryGlyphPaint.setTextSize(textSize);
            mSecondaryGlyphPaint.setTextSize(textSize);
            mPrimaryGlyphPaint.getFontMetrics(mFontMetrics);
        }

        private Paint getBackgroundPaint() {
            return mBackgroundPaint;
        }

        private Paint getTypingKeyPaint() {
            return mTypingKeyPaint;
        }

        private Paint getSpecialKeyPaint() {
            return mSpecialKeyPaint;
        }

        private Paint getPrimaryGlyphPaint() {
            return mPrimaryGlyphPaint;
        }

        private Paint getSecondaryGlyphPaint() {
            return mSecondaryGlyphPaint;
        }

        private int getKeyPadding() {
            return mKeyPadding;
        }

        private int getKeyboardPadding() {
            return mKeyboardPadding;
        }

        private float getTextPadding() {
            return mTextPadding;
        }

        private float getKeyRadius() {
            return mKeyRadius;
        }

        private float getBackgroundRadius() {
            return mBackgroundRadius;
        }
    }

    private static Paint createTextPaint(@ColorInt int textColor, float textSize,
            Typeface typeface) {
        Paint paint = new Paint();
        paint.setColor(textColor);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(textSize);
        paint.setTypeface(typeface);
        return paint;
    }

    private static Paint createFillPaint(@ColorInt int color) {
        Paint paint = new Paint();
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        return paint;
    }

    private static Paint createGreyedOutPaint(Paint paint) {
        Paint result = new Paint(paint);
        result.setAlpha(100);
        return result;
    }
}
