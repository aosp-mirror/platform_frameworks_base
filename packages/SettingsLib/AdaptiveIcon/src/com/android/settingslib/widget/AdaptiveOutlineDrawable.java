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

package com.android.settingslib.widget;

import static com.android.settingslib.widget.AdaptiveOutlineDrawable.AdaptiveOutlineIconType.TYPE_ADVANCED;
import static com.android.settingslib.widget.AdaptiveOutlineDrawable.AdaptiveOutlineIconType.TYPE_DEFAULT;

import android.annotation.ColorInt;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.DrawableWrapper;
import android.os.RemoteException;
import android.util.PathParser;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import androidx.annotation.IntDef;
import androidx.annotation.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Adaptive outline drawable with white plain background color and black outline
 */
public class AdaptiveOutlineDrawable extends DrawableWrapper {

    private static final float ADVANCED_ICON_CENTER = 50f;
    private static final float ADVANCED_ICON_RADIUS = 48f;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({TYPE_DEFAULT, TYPE_ADVANCED})
    public @interface AdaptiveOutlineIconType {
        int TYPE_DEFAULT = 0;
        int TYPE_ADVANCED = 1;
    }

    @VisibleForTesting
    Paint mOutlinePaint;
    private Path mPath;
    private int mInsetPx;
    private int mStrokeWidth;
    private Bitmap mBitmap;
    private int mType;

    public AdaptiveOutlineDrawable(Resources resources, Bitmap bitmap) {
        super(new AdaptiveIconShapeDrawable(resources));

        init(resources, bitmap, TYPE_DEFAULT);
    }

    public AdaptiveOutlineDrawable(Resources resources, Bitmap bitmap,
            @AdaptiveOutlineIconType int type) {
        super(new AdaptiveIconShapeDrawable(resources));

        init(resources, bitmap, type);
    }

    private void init(Resources resources, Bitmap bitmap,
            @AdaptiveOutlineIconType int type) {
        mType = type;
        getDrawable().setTint(Color.WHITE);
        mPath = new Path(PathParser.createPathFromPathData(
                resources.getString(com.android.internal.R.string.config_icon_mask)));
        mStrokeWidth = resources.getDimensionPixelSize(R.dimen.adaptive_outline_stroke);
        mOutlinePaint = new Paint();
        mOutlinePaint.setColor(getColor(resources, type));
        mOutlinePaint.setStyle(Paint.Style.STROKE);
        mOutlinePaint.setStrokeWidth(mStrokeWidth);
        mOutlinePaint.setAntiAlias(true);

        mInsetPx = getDimensionPixelSize(resources, type);
        mBitmap = bitmap;
    }

    private @ColorInt int getColor(Resources resources, @AdaptiveOutlineIconType int type) {
        int resId;
        switch (type) {
            case TYPE_ADVANCED:
                resId = R.color.advanced_outline_color;
                break;
            case TYPE_DEFAULT:
            default:
                resId = R.color.bt_outline_color;
                break;
        }
        return resources.getColor(resId, /* theme */ null);
    }

    private int getDimensionPixelSize(Resources resources, @AdaptiveOutlineIconType int type) {
        int resId;
        switch (type) {
            case TYPE_ADVANCED:
                resId = R.dimen.advanced_dashboard_tile_foreground_image_inset;
                break;
            case TYPE_DEFAULT:
            default:
                resId = R.dimen.dashboard_tile_foreground_image_inset;
                break;
        }
        return resources.getDimensionPixelSize(resId);
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        final Rect bounds = getBounds();
        final float pathSize = AdaptiveIconDrawable.MASK_SIZE;

        final float scaleX = (bounds.right - bounds.left) / pathSize;
        final float scaleY = (bounds.bottom - bounds.top) / pathSize;

        final int count = canvas.save();
        canvas.scale(scaleX, scaleY);
        // Draw outline
        if (mType == TYPE_DEFAULT) {
            canvas.drawPath(mPath, mOutlinePaint);
        } else {
            canvas.drawCircle(ADVANCED_ICON_CENTER, ADVANCED_ICON_CENTER, ADVANCED_ICON_RADIUS,
                    mOutlinePaint);
        }
        canvas.restoreToCount(count);

        // Draw the foreground icon
        canvas.drawBitmap(mBitmap, bounds.left + mInsetPx, bounds.top + mInsetPx, null);
    }

    private static int getDefaultDisplayDensity(int displayId) {
        try {
            final IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
            return wm.getInitialDisplayDensity(displayId);
        } catch (RemoteException exc) {
            return -1;
        }
    }

    @Override
    public int getIntrinsicHeight() {
        return mBitmap.getHeight() + 2 * mInsetPx;
    }

    @Override
    public int getIntrinsicWidth() {
        return mBitmap.getWidth() + 2 * mInsetPx;
    }
}
