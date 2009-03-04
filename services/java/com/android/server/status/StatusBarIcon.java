package com.android.server.status;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

class StatusBarIcon {
    // TODO: get this from a resource
    private static final int ICON_GAP = 8;
    private static final int ICON_WIDTH = 25;
    private static final int ICON_HEIGHT = 25;

    public View view;

    IconData mData;
    
    private TextView mTextView;
    private AnimatedImageView mImageView;
    private TextView mNumberView;

    public StatusBarIcon(Context context, IconData data, ViewGroup parent) {
        mData = data.clone();

        switch (data.type) {
            case IconData.TEXT: {
                TextView t;
                t = new TextView(context);
                mTextView = t;
                LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.FILL_PARENT);
                t.setTextSize(16);
                t.setTextColor(0xff000000);
                t.setTypeface(Typeface.DEFAULT_BOLD);
                t.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
                t.setPadding(6, 0, 0, 0);
                t.setLayoutParams(layoutParams);
                t.setText(data.text);
                this.view = t;
                break;
            }

            case IconData.ICON: {
                // container
                LayoutInflater inflater = (LayoutInflater)context.getSystemService(
                                                Context.LAYOUT_INFLATER_SERVICE);
                View v = inflater.inflate(com.android.internal.R.layout.status_bar_icon, parent, false);
                this.view = v;

                // icon
                AnimatedImageView im = (AnimatedImageView)v.findViewById(com.android.internal.R.id.image);
                im.setImageDrawable(getIcon(context, data));
                im.setImageLevel(data.iconLevel);
                mImageView = im;

                // number
                TextView nv = (TextView)v.findViewById(com.android.internal.R.id.number);
                mNumberView = nv;
                if (data.number > 0) {
                    nv.setText("" + data.number);
                    nv.setVisibility(View.VISIBLE);
                } else {
                    nv.setVisibility(View.GONE);
                }
                break;
            }
        }
    }

    public void update(Context context, IconData data) throws StatusBarException {
        if (mData.type != data.type) {
            throw new StatusBarException("status bar entry type can't change");
        }
        switch (data.type) {
        case IconData.TEXT:
            if (!TextUtils.equals(mData.text, data.text)) {
                TextView tv = mTextView;
                tv.setText(data.text);
            }
            break;
        case IconData.ICON:
            if (((mData.iconPackage != null && data.iconPackage != null)
                        && !mData.iconPackage.equals(data.iconPackage))
                    || mData.iconId != data.iconId
                    || mData.iconLevel != data.iconLevel) {
                ImageView im = mImageView;
                im.setImageDrawable(getIcon(context, data));
                im.setImageLevel(data.iconLevel);
            }
            if (mData.number != data.number) {
                TextView nv = mNumberView;
                if (data.number > 0) {
                    nv.setText("" + data.number);
                } else {
                    nv.setText("");
                }
            }
            break;
        }
        mData.copyFrom(data);
    }

    public void update(int number) {
        if (mData.number != number) {
            TextView nv = mNumberView;
            if (number > 0) {
                nv.setText("" + number);
            } else {
                nv.setText("");
            }
        }
        mData.number = number;
    }


    /**
     * Returns the right icon to use for this item, respecting the iconId and
     * iconPackage (if set)
     * 
     * @param context Context to use to get resources if iconPackage is not set
     * @return Drawable for this item, or null if the package or item could not
     *         be found
     */
    static Drawable getIcon(Context context, IconData data) {

        Resources r = null;

        if (data.iconPackage != null) {
            try {
                r = context.getPackageManager().getResourcesForApplication(data.iconPackage);
            } catch (PackageManager.NameNotFoundException ex) {
                Log.e(StatusBarService.TAG, "Icon package not found: " + data.iconPackage, ex);
                return null;
            }
        } else {
            r = context.getResources();
        }

        try {
            return r.getDrawable(data.iconId);
        } catch (RuntimeException e) {
            Log.w(StatusBarService.TAG, "Icon not found in "
                  + (data.iconPackage != null ? data.iconId : "<system>")
                  + ": " + Integer.toHexString(data.iconId));
        }

        return null;
    }

    int getNumber() {
        return mData.number;
    }
}

