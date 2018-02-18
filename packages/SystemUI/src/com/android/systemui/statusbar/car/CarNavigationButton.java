package com.android.systemui.statusbar.car;

import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.ImageView;

import com.android.systemui.R;

import java.net.URISyntaxException;

/**
 * CarNavigationButton is an image button that allows for a bit more configuration at the
 * xml file level. This allows for more control via overlays instead of having to update
 * code.
 */
public class CarNavigationButton extends com.android.keyguard.AlphaOptimizedImageButton {

    private static final float SELECTED_ALPHA = 1;
    private static final float UNSELECTED_ALPHA = 0.7f;

    private Context mContext;
    private String mIntent = null;
    private String mLongIntent = null;
    private boolean mBroadcastIntent = false;
    private boolean mSelected = false;


    public CarNavigationButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        TypedArray typedArray = context.obtainStyledAttributes(attrs, R.styleable.CarNavigationButton);
        mIntent = typedArray.getString(R.styleable.CarNavigationButton_intent);
        mLongIntent = typedArray.getString(R.styleable.CarNavigationButton_longIntent);
        mBroadcastIntent = typedArray.getBoolean(R.styleable.CarNavigationButton_broadcast, false);
    }


    /**
     * After the standard inflate this then adds the xml defined intents to click and long click
     * actions if defined.
     */
    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        setScaleType(ImageView.ScaleType.CENTER);
        setAlpha(UNSELECTED_ALPHA);
        try {
            if (mIntent != null) {
                final Intent intent = Intent.parseUri(mIntent, Intent.URI_INTENT_SCHEME);
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                setOnClickListener(v -> {
                    if (mBroadcastIntent) {
                        mContext.sendBroadcast(intent);
                        return;
                    }
                    mContext.startActivity(intent);
                });
            }
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to attach intent", e);
        }

        try {
            if (mLongIntent != null) {
                final Intent intent = Intent.parseUri(mLongIntent, Intent.URI_INTENT_SCHEME);
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                setOnLongClickListener(v -> {
                    mContext.startActivity(intent);
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
        setAlpha(mSelected ? SELECTED_ALPHA : UNSELECTED_ALPHA);
    }
}
