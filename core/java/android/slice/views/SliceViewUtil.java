/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.slice.views;

import android.annotation.ColorInt;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * A bunch of utilities for slice UI.
 *
 * @hide
 */
public class SliceViewUtil {

    /**
     * @hide
     */
    @ColorInt
    public static int getColorAccent(Context context) {
        return getColorAttr(context, android.R.attr.colorAccent);
    }

    /**
     * @hide
     */
    @ColorInt
    public static int getColorError(Context context) {
        return getColorAttr(context, android.R.attr.colorError);
    }

    /**
     * @hide
     */
    @ColorInt
    public static int getDefaultColor(Context context, int resId) {
        final ColorStateList list = context.getResources().getColorStateList(resId,
                context.getTheme());

        return list.getDefaultColor();
    }

    /**
     * @hide
     */
    @ColorInt
    public static int getDisabled(Context context, int inputColor) {
        return applyAlphaAttr(context, android.R.attr.disabledAlpha, inputColor);
    }

    /**
     * @hide
     */
    @ColorInt
    public static int applyAlphaAttr(Context context, int attr, int inputColor) {
        TypedArray ta = context.obtainStyledAttributes(new int[] {
                attr
        });
        float alpha = ta.getFloat(0, 0);
        ta.recycle();
        return applyAlpha(alpha, inputColor);
    }

    /**
     * @hide
     */
    @ColorInt
    public static int applyAlpha(float alpha, int inputColor) {
        alpha *= Color.alpha(inputColor);
        return Color.argb((int) (alpha), Color.red(inputColor), Color.green(inputColor),
                Color.blue(inputColor));
    }

    /**
     * @hide
     */
    @ColorInt
    public static int getColorAttr(Context context, int attr) {
        TypedArray ta = context.obtainStyledAttributes(new int[] {
                attr
        });
        @ColorInt
        int colorAccent = ta.getColor(0, 0);
        ta.recycle();
        return colorAccent;
    }

    /**
     * @hide
     */
    public static int getThemeAttr(Context context, int attr) {
        TypedArray ta = context.obtainStyledAttributes(new int[] {
                attr
        });
        int theme = ta.getResourceId(0, 0);
        ta.recycle();
        return theme;
    }

    /**
     * @hide
     */
    public static Drawable getDrawable(Context context, int attr) {
        TypedArray ta = context.obtainStyledAttributes(new int[] {
                attr
        });
        Drawable drawable = ta.getDrawable(0);
        ta.recycle();
        return drawable;
    }

    /**
     * @hide
     */
    public static void createCircledIcon(Context context, int color, int iconSize, Icon icon,
            boolean isLarge, ViewGroup parent) {
        ImageView v = new ImageView(context);
        v.setImageIcon(icon);
        parent.addView(v);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        if (isLarge) {
            // XXX better way to convert from icon -> bitmap or crop an icon (?)
            Bitmap iconBm = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888);
            Canvas iconCanvas = new Canvas(iconBm);
            v.layout(0, 0, iconSize, iconSize);
            v.draw(iconCanvas);
            v.setImageBitmap(getCircularBitmap(iconBm));
        } else {
            v.setColorFilter(Color.WHITE);
        }
        lp.width = iconSize;
        lp.height = iconSize;
        lp.gravity = Gravity.CENTER;
    }

    /**
     * @hide
     */
    public static Bitmap getCircularBitmap(Bitmap bitmap) {
        Bitmap output = Bitmap.createBitmap(bitmap.getWidth(),
                bitmap.getHeight(), Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        final Paint paint = new Paint();
        final Rect rect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        paint.setAntiAlias(true);
        canvas.drawARGB(0, 0, 0, 0);
        canvas.drawCircle(bitmap.getWidth() / 2, bitmap.getHeight() / 2,
                bitmap.getWidth() / 2, paint);
        paint.setXfermode(new PorterDuffXfermode(Mode.SRC_IN));
        canvas.drawBitmap(bitmap, rect, rect, paint);
        return output;
    }
}
