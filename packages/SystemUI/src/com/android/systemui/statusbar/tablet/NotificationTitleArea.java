/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar.tablet;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.util.Slog;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.view.View;
import android.widget.FrameLayout;

import com.android.systemui.R;

public class NotificationTitleArea extends RelativeLayout {
    static final String TAG = "NotificationTitleArea";

    View mSettingsButton;
    View mNotificationButton;
    View mNotificationScroller;
    FrameLayout mSettingsFrame;
    View mSettingsPanel;

    // for drawing the background
    Bitmap mTexture;
    Paint mPaint;
    int mTextureWidth;
    int mTextureHeight;
    

    public NotificationTitleArea(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationTitleArea(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        // for drawing the background
        mTexture = BitmapFactory.decodeResource(getResources(), R.drawable.panel_notification);
        mTextureWidth = mTexture.getWidth();
        mTextureHeight = mTexture.getHeight();

        mPaint = new Paint();
        mPaint.setDither(false);
    }

    public void onFinishInflate() {
        super.onFinishInflate();
        setWillNotDraw(false);
    }

    @Override
    public void onDraw(Canvas canvas) {
        final Bitmap texture = mTexture;
        final Paint paint = mPaint;

        final int width = getWidth();
        final int height = getHeight();

        final int textureWidth = mTextureWidth;
        final int textureHeight = mTextureHeight;

        int x = 0;
        int y;

        while (x < width) {
            y = 0;
            while (y < height) {
                canvas.drawBitmap(texture, x, y, paint);
                y += textureHeight;
            }
            x += textureWidth;
        }
    }
}

