/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row.wrapper;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.os.Build;
import android.view.View;

import com.android.internal.graphics.ColorUtils;
import com.android.systemui.R;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

/**
 * Wraps a notification containing a custom view.
 */
public class NotificationCustomViewWrapper extends NotificationViewWrapper {

    private boolean mIsLegacy;
    private int mLegacyColor;

    protected NotificationCustomViewWrapper(Context ctx, View view, ExpandableNotificationRow row) {
        super(ctx, view, row);
        mLegacyColor = row.getContext().getColor(R.color.notification_legacy_background_color);
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        mView.setAlpha(visible ? 1.0f : 0.0f);
    }

    @Override
    public void onReinflated() {
        super.onReinflated();

        Configuration configuration = mView.getResources().getConfiguration();
        boolean nightMode = (configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;

        float[] hsl = new float[] {0f, 0f, 0f};
        ColorUtils.colorToHSL(mBackgroundColor, hsl);
        boolean backgroundIsDark = Color.alpha(mBackgroundColor) == 0
                || hsl[1] == 0 && hsl[2] < 0.5;
        boolean backgroundHasColor = hsl[1] > 0;

        // Let's invert the notification colors when we're in night mode and
        // the notification background isn't colorized.
        if (!backgroundIsDark && !backgroundHasColor && nightMode
                && mRow.getEntry().targetSdk < Build.VERSION_CODES.Q) {
            Paint paint = new Paint();
            ColorMatrix matrix = new ColorMatrix();
            ColorMatrix tmp = new ColorMatrix();
            // Inversion should happen on Y'UV space to conseve the colors and
            // only affect the luminosity.
            matrix.setRGB2YUV();
            tmp.set(new float[]{
                    -1f, 0f, 0f, 0f, 255f,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 1f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
            });
            matrix.postConcat(tmp);
            tmp.setYUV2RGB();
            matrix.postConcat(tmp);
            paint.setColorFilter(new ColorMatrixColorFilter(matrix));
            mView.setLayerType(View.LAYER_TYPE_HARDWARE, paint);

            hsl[2] = 1f - hsl[2];
            mBackgroundColor = ColorUtils.HSLToColor(hsl);
        }
    }

    @Override
    protected boolean shouldClearBackgroundOnReapply() {
        return false;
    }

    @Override
    public int getCustomBackgroundColor() {
        int customBackgroundColor = super.getCustomBackgroundColor();
        if (customBackgroundColor == 0 && mIsLegacy) {
            return mLegacyColor;
        }
        return customBackgroundColor;
    }

    public void setLegacy(boolean legacy) {
        super.setLegacy(legacy);
        mIsLegacy = legacy;
    }

    @Override
    public boolean shouldClipToRounding(boolean topRounded, boolean bottomRounded) {
        return true;
    }
}
