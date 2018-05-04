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

package com.android.server.am;

import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.UserManager;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import com.android.internal.R;


/**
 * Dialog to show when a user switch it about to happen for the car. The intent is to snapshot the
 * screen immediately after the dialog shows so that the user is informed that something is
 * happening in the background rather than just freeze the screen and not know if the user-switch
 * affordance was being handled.
 */
final class CarUserSwitchingDialog extends UserSwitchingDialog {

    private static final String TAG = "ActivityManagerCarUserSwitchingDialog";

    public CarUserSwitchingDialog(ActivityManagerService service, Context context, UserInfo oldUser,
            UserInfo newUser, boolean aboveSystem, String switchingFromSystemUserMessage,
            String switchingToSystemUserMessage) {
        super(service, context, oldUser, newUser, aboveSystem, switchingFromSystemUserMessage,
                switchingToSystemUserMessage);

        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }

    @Override
    void inflateContent() {
        // Set up the dialog contents
        setCancelable(false);
        Resources res = getContext().getResources();
        // Custom view due to alignment and font size requirements
        View view = LayoutInflater.from(getContext()).inflate(R.layout.car_user_switching_dialog,
                null);

        UserManager userManager =
                (UserManager) getContext().getSystemService(Context.USER_SERVICE);
        Bitmap bitmap = userManager.getUserIcon(mNewUser.id);
        if (bitmap != null) {
            CircleFramedDrawable drawable = CircleFramedDrawable.getInstance(bitmap,
                    res.getDimension(R.dimen.car_fullscreen_user_pod_image_avatar_height));
            ((ImageView) view.findViewById(R.id.user_loading_avatar))
                    .setImageDrawable(drawable);
        }

        ((TextView) view.findViewById(R.id.user_loading))
                .setText(res.getString(R.string.car_loading_profile));
        setView(view);
    }

    /**
     * Converts the user icon to a circularly clipped one.  This is used in the User Picker and
     * Settings.
     */
    static class CircleFramedDrawable extends Drawable {

        private final Bitmap mBitmap;
        private final int mSize;
        private final Paint mPaint;

        private float mScale;
        private Rect mSrcRect;
        private RectF mDstRect;

        public static CircleFramedDrawable getInstance(Bitmap icon, float iconSize) {
            CircleFramedDrawable instance = new CircleFramedDrawable(icon, (int) iconSize);
            return instance;
        }

        public CircleFramedDrawable(Bitmap icon, int size) {
            super();
            mSize = size;

            mBitmap = Bitmap.createBitmap(mSize, mSize, Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(mBitmap);

            final int width = icon.getWidth();
            final int height = icon.getHeight();
            final int square = Math.min(width, height);

            final Rect cropRect = new Rect((width - square) / 2, (height - square) / 2,
                    square, square);
            final RectF circleRect = new RectF(0f, 0f, mSize, mSize);

            final Path fillPath = new Path();
            fillPath.addArc(circleRect, 0f, 360f);

            canvas.drawColor(0, PorterDuff.Mode.CLEAR);

            // opaque circle
            mPaint = new Paint();
            mPaint.setAntiAlias(true);
            mPaint.setColor(Color.BLACK);
            mPaint.setStyle(Paint.Style.FILL);
            canvas.drawPath(fillPath, mPaint);

            // mask in the icon where the bitmap is opaque
            mPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            canvas.drawBitmap(icon, cropRect, circleRect, mPaint);

            // prepare paint for frame drawing
            mPaint.setXfermode(null);

            mScale = 1f;

            mSrcRect = new Rect(0, 0, mSize, mSize);
            mDstRect = new RectF(0, 0, mSize, mSize);
        }

        @Override
        public void draw(Canvas canvas) {
            final float inside = mScale * mSize;
            final float pad = (mSize - inside) / 2f;

            mDstRect.set(pad, pad, mSize - pad, mSize - pad);
            canvas.drawBitmap(mBitmap, mSrcRect, mDstRect, null);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public void setAlpha(int alpha) {
            // Needed to implement abstract method.  Do nothing.
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            // Needed to implement abstract method.  Do nothing.
        }
    }
}
