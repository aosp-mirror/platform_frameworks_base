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

package com.android.systemui.statusbar.car;

import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.os.UserHandle;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.ImageView;

import com.android.systemui.R;

import java.net.URISyntaxException;

/**
 * CarNavigationButton is an image button that allows for a bit more configuration at the
 * xml file level. This allows for more control via overlays instead of having to update
 * code.
 */
public class CarNavigationButton extends com.android.keyguard.AlphaOptimizedImageButton {
    private static final String TAG = "CarNavigationButton";

    private static final int UNSEEN_ICON_RESOURCE_ID = R.drawable.car_ic_notification_unseen;
    private static final int UNSEEN_SELECTED_ICON_RESOURCE_ID =
            R.drawable.car_ic_notification_selected_unseen;

    private Context mContext;
    private String mIntent;
    private String mLongIntent;
    private boolean mBroadcastIntent;
    private boolean mHasUnseen = false;
    private boolean mSelected = false;
    private float mSelectedAlpha = 1f;
    private float mUnselectedAlpha = 1f;
    private int mSelectedIconResourceId;
    private int mIconResourceId;


    public CarNavigationButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;

        // CarNavigationButton attrs
        TypedArray typedArray = context.obtainStyledAttributes(
                attrs, R.styleable.CarNavigationButton);
        mIntent = typedArray.getString(R.styleable.CarNavigationButton_intent);
        mLongIntent = typedArray.getString(R.styleable.CarNavigationButton_longIntent);
        mBroadcastIntent = typedArray.getBoolean(R.styleable.CarNavigationButton_broadcast, false);
        mSelectedAlpha = typedArray.getFloat(
                R.styleable.CarNavigationButton_selectedAlpha, mSelectedAlpha);
        mUnselectedAlpha = typedArray.getFloat(
                R.styleable.CarNavigationButton_unselectedAlpha, mUnselectedAlpha);
        mSelectedIconResourceId = typedArray.getResourceId(
                R.styleable.CarNavigationButton_selectedIcon, mIconResourceId);
        typedArray.recycle();

        // ImageView attrs
        TypedArray a = context.obtainStyledAttributes(
                attrs, com.android.internal.R.styleable.ImageView);
        mIconResourceId = a.getResourceId(com.android.internal.R.styleable.ImageView_src, 0);
        a.recycle();
    }


    /**
     * After the standard inflate this then adds the xml defined intents to click and long click
     * actions if defined.
     */
    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        setScaleType(ImageView.ScaleType.CENTER);
        setAlpha(mUnselectedAlpha);
        try {
            if (mIntent != null) {
                final Intent intent = Intent.parseUri(mIntent, Intent.URI_INTENT_SCHEME);
                setOnClickListener(getButtonClickListener(intent));
            }
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to attach intent", e);
        }

        try {
            if (mLongIntent != null) {
                final Intent intent = Intent.parseUri(mLongIntent, Intent.URI_INTENT_SCHEME);
                setOnLongClickListener(getButtonLongClickListener(intent));
            }
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to attach long press intent", e);
        }
    }

    /** Defines the behavior of a button click. */
    protected OnClickListener getButtonClickListener(Intent toSend) {
        return v -> {
            try {
                if (mBroadcastIntent) {
                    mContext.sendBroadcastAsUser(toSend, UserHandle.CURRENT);
                    return;
                }
                mContext.startActivityAsUser(toSend, UserHandle.CURRENT);
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch intent", e);
            }
        };
    }

    /** Defines the behavior of a long click. */
    protected OnLongClickListener getButtonLongClickListener(Intent toSend) {
        return v -> {
            try {
                mContext.startActivityAsUser(toSend, UserHandle.CURRENT);
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch intent", e);
            }
            // consume event either way
            return true;
        };
    }

    /**
     * @param selected true if should indicate if this is a selected state, false otherwise
     */
    public void setSelected(boolean selected) {
        super.setSelected(selected);
        mSelected = selected;
        setAlpha(mSelected ? mSelectedAlpha : mUnselectedAlpha);
        updateImage();
    }

    /**
     * @param hasUnseen true if should indicate if this is a Unseen state, false otherwise.
     */
    public void setUnseen(boolean hasUnseen) {
        mHasUnseen = hasUnseen;
        updateImage();
    }

    private void updateImage() {
        if (mHasUnseen) {
            setImageResource(mSelected ? UNSEEN_SELECTED_ICON_RESOURCE_ID
                    : UNSEEN_ICON_RESOURCE_ID);
        } else {
            setImageResource(mSelected ? mSelectedIconResourceId : mIconResourceId);
        }
    }
}
