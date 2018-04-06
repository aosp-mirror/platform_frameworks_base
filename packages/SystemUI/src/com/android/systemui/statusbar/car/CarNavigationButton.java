package com.android.systemui.statusbar.car;

import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
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
    private Context mContext;
    private String mIntent;
    private String mLongIntent;
    private boolean mBroadcastIntent;
    private boolean mSelected = false;
    private float mSelectedAlpha;
    private float mUnselectedAlpha;
    private int mSelectedIconResourceId;
    private int mIconResourceId;


    public CarNavigationButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        TypedArray typedArray = context.obtainStyledAttributes(
                attrs, R.styleable.CarNavigationButton);
        mIntent = typedArray.getString(R.styleable.CarNavigationButton_intent);
        mLongIntent = typedArray.getString(R.styleable.CarNavigationButton_longIntent);
        mBroadcastIntent = typedArray.getBoolean(R.styleable.CarNavigationButton_broadcast, false);
        mSelectedAlpha = typedArray.getFloat(
                R.styleable.CarNavigationButton_selectedAlpha, mSelectedAlpha);
        mUnselectedAlpha = typedArray.getFloat(
                R.styleable.CarNavigationButton_unselectedAlpha, mUnselectedAlpha);
        mIconResourceId = typedArray.getResourceId(
                com.android.internal.R.styleable.ImageView_src, 0);
        mSelectedIconResourceId = typedArray.getResourceId(
                R.styleable.CarNavigationButton_selectedIcon, mIconResourceId);
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
                setOnClickListener(v -> {
                    try {
                        if (mBroadcastIntent) {
                            mContext.sendBroadcast(intent);
                            return;
                        }
                        mContext.startActivity(intent);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to launch intent", e);
                    }
                });
            }
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to attach intent", e);
        }

        try {
            if (mLongIntent != null) {
                final Intent intent = Intent.parseUri(mLongIntent, Intent.URI_INTENT_SCHEME);
                setOnLongClickListener(v -> {
                    try {
                        mContext.startActivity(intent);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to launch intent", e);
                    }
                    // consume event either way
                    return true;
                });
            }
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to attach long press intent", e);
        }
    }

    /**
     * @param selected true if should indicate if this is a selected state, false otherwise
     */
    public void setSelected(boolean selected) {
        super.setSelected(selected);
        mSelected = selected;
        setAlpha(mSelected ? mSelectedAlpha : mUnselectedAlpha);
        setImageResource(mSelected ? mSelectedIconResourceId : mIconResourceId);
    }
}
