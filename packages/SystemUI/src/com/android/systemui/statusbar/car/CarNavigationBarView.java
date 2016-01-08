/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.statusbar.car;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.R.color;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView.ScaleType;
import android.widget.LinearLayout;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.ActivityStarter;
import com.android.systemui.statusbar.phone.NavigationBarView;
import com.android.systemui.statusbar.phone.NavigationBarGestureHelper;
import com.android.systemui.statusbar.policy.KeyButtonView;

import java.net.URISyntaxException;

/**
 * A custom navigation bar for the automotive use case.
 * <p>
 * The navigation bar in the automotive use case is more like a list of shortcuts, which we
 * expect to be customizable by the car OEMs. This implementation populates the nav_buttons layout
 * from resources rather than the layout file so customization would then mean updating
 * arrays_car.xml appropriately in an overlay.
 */
class CarNavigationBarView extends NavigationBarView {
    private ActivityStarter mActivityStarter;

    public CarNavigationBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void onFinishInflate() {
        // Read up arrays_car.xml and populate the navigation bar here.
        Context context = getContext();
        Resources r = getContext().getResources();
        TypedArray icons = r.obtainTypedArray(R.array.car_shortcut_icons);
        TypedArray intents = r.obtainTypedArray(R.array.car_shortcut_intent_uris);
        TypedArray longpressIntents =
                r.obtainTypedArray(R.array.car_shortcut_longpress_intent_uris);

        if (icons.length() != intents.length()) {
            throw new RuntimeException("car_shortcut_icons and car_shortcut_intents do not match");
        }

        LinearLayout navButtons = (LinearLayout) findViewById(R.id.nav_buttons);
        LinearLayout lightsOut = (LinearLayout) findViewById(R.id.lights_out);

        for (int i = 0; i < icons.length(); i++) {
            Drawable icon = icons.getDrawable(i);

            try {
                Intent intent = Intent.parseUri(intents.getString(i), Intent.URI_INTENT_SCHEME);
                Intent longpress = null;
                String longpressUri = longpressIntents.getString(i);
                if (!longpressUri.isEmpty()) {
                    longpress = Intent.parseUri(longpressUri, Intent.URI_INTENT_SCHEME);
                }

                // nav_buttons and lights_out should match exactly.
                navButtons.addView(makeButton(context, icon, intent, longpress));
                lightsOut.addView(makeButton(context, icon, intent, longpress));
            } catch (URISyntaxException e) {
                throw new RuntimeException("Malformed intent uri", e);
            }
        }
    }

    private ImageButton makeButton(Context context, Drawable icon,
            final Intent intent, final Intent longpress) {
        ImageButton button = new ImageButton(context);

        button.setImageDrawable(icon);
        button.setScaleType(ScaleType.CENTER);
        button.setBackgroundColor(color.transparent);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1);
        button.setLayoutParams(lp);

        button.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mActivityStarter != null) {
                    mActivityStarter.startActivity(intent, true);
                }
            }
        });

        // Long click handlers are optional.
        if (longpress != null) {
            button.setLongClickable(true);
            button.setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (mActivityStarter != null) {
                        mActivityStarter.startActivity(longpress, true);
                        return true;
                    }
                    return false;
                }
            });
        } else {
            button.setLongClickable(false);
        }

        return button;
    }

    public void setActivityStarter(ActivityStarter activityStarter) {
        mActivityStarter = activityStarter;
    }

    @Override
    public void setDisabledFlags(int disabledFlags, boolean force) {
        // TODO: Populate.
    }

    @Override
    public void reorient() {
        // We expect the car head unit to always have a fixed rotation so we ignore this. The super
        // class implentation expects mRotatedViews to be populated, so if you call into it, there
        // is a possibility of a NullPointerException.
    }

    @Override
    public View getCurrentView() {
        return this;
    }

    @Override
    public void setNavigationIconHints(int hints, boolean force) {
        // We do not need to set the navigation icon hints for a vehicle
        // Calling setNavigationIconHints in the base class will result in a NPE as the car
        // navigation bar does not have a back button.
    }
}
