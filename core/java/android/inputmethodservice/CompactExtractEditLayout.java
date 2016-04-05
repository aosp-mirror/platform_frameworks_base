package android.inputmethodservice;

import android.content.Context;
import android.content.res.Resources;
import android.annotation.FractionRes;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/**
 * A special purpose layout for the editor extract view for tiny (sub 250dp) screens.
 * The layout is based on sizes proportional to screen pixel size to provide for the
 * best layout fidelity on varying pixel sizes and densities.
 *
 * @hide
 */
public class CompactExtractEditLayout extends LinearLayout {
    private View mInputExtractEditText;
    private View mInputExtractAccessories;
    private View mInputExtractAction;
    private boolean mPerformLayoutChanges;

    public CompactExtractEditLayout(Context context) {
        super(context);
    }

    public CompactExtractEditLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CompactExtractEditLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mInputExtractEditText = findViewById(com.android.internal.R.id.inputExtractEditText);
        mInputExtractAccessories = findViewById(com.android.internal.R.id.inputExtractAccessories);
        mInputExtractAction = findViewById(com.android.internal.R.id.inputExtractAction);

        if (mInputExtractEditText != null && mInputExtractAccessories != null
                && mInputExtractAction != null) {
            mPerformLayoutChanges = true;
        }
    }

    private int applyFractionInt(@FractionRes int fraction, int whole) {
        return Math.round(getResources().getFraction(fraction, whole, whole));
    }

    private static void setLayoutHeight(View v, int px) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.height = px;
        v.setLayoutParams(lp);
    }

    private static void setLayoutMarginBottom(View v, int px) {
        ViewGroup.MarginLayoutParams lp = (MarginLayoutParams) v.getLayoutParams();
        lp.bottomMargin = px;
        v.setLayoutParams(lp);
    }

    private void applyProportionalLayout(int screenWidthPx, int screenHeightPx) {
        if (getResources().getConfiguration().isScreenRound()) {
            setGravity(Gravity.BOTTOM);
        }
        setLayoutHeight(this, applyFractionInt(
                com.android.internal.R.fraction.input_extract_layout_height, screenHeightPx));

        setPadding(
                applyFractionInt(com.android.internal.R.fraction.input_extract_layout_padding_left,
                        screenWidthPx),
                0,
                applyFractionInt(com.android.internal.R.fraction.input_extract_layout_padding_right,
                        screenWidthPx),
                0);

        setLayoutMarginBottom(mInputExtractEditText,
                applyFractionInt(com.android.internal.R.fraction.input_extract_text_margin_bottom,
                        screenHeightPx));

        setLayoutMarginBottom(mInputExtractAccessories,
                applyFractionInt(com.android.internal.R.fraction.input_extract_action_margin_bottom,
                        screenHeightPx));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mPerformLayoutChanges) {
            Resources res = getResources();
            DisplayMetrics dm = res.getDisplayMetrics();
            int heightPixels = dm.heightPixels;
            int widthPixels = dm.widthPixels;
            applyProportionalLayout(widthPixels, heightPixels);
        }
    }
}

