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
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
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
 *
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

        ((ImageView) view.findViewById(R.id.user_loading_avatar))
            .setImageBitmap(getDefaultUserIcon(mNewUser));
        ((TextView) view.findViewById(R.id.user_loading))
            .setText(res.getString(R.string.car_loading_profile));
        setView(view);
    }

    /**
     * Returns the default user icon.  This icon is a circle with a letter in it.  The letter is
     * the first character in the username.
     *
     * @param userInfo the profile of the user for which the icon should be created
     */
    private Bitmap getDefaultUserIcon(UserInfo userInfo) {
        Resources res = mContext.getResources();
        int mPodImageAvatarWidth = res.getDimensionPixelSize(
            R.dimen.car_fullscreen_user_pod_image_avatar_width);
        int mPodImageAvatarHeight = res.getDimensionPixelSize(
            R.dimen.car_fullscreen_user_pod_image_avatar_height);
        CharSequence displayText = userInfo.name.subSequence(0, 1);
        Bitmap out = Bitmap.createBitmap(mPodImageAvatarWidth, mPodImageAvatarHeight,
            Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);

        // Draw the circle background.
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RADIAL_GRADIENT);
        shape.setGradientRadius(1.0f);
        shape.setColor(mContext.getColor(R.color.car_user_switcher_user_image_bgcolor));
        shape.setBounds(0, 0, mPodImageAvatarWidth, mPodImageAvatarHeight);
        shape.draw(canvas);

        // Draw the letter in the center.
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(mContext.getColor(R.color.car_user_switcher_user_image_fgcolor));
        paint.setTextAlign(Align.CENTER);
        paint.setTextSize(res.getDimensionPixelSize(
            R.dimen.car_fullscreen_user_pod_icon_text_size));

        Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
        // The Y coordinate is measured by taking half the height of the pod, but that would
        // draw the character putting the bottom of the font in the middle of the pod.  To
        // correct this, half the difference between the top and bottom distance metrics of the
        // font gives the offset of the font.  Bottom is a positive value, top is negative, so
        // the different is actually a sum.  The "half" operation is then factored out.
        canvas.drawText(displayText.toString(), mPodImageAvatarWidth / 2,
            (mPodImageAvatarHeight - (metrics.bottom + metrics.top)) / 2, paint);

        return out;
    }
}
