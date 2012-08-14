package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.FrameLayout;

public class PanelHolder extends FrameLayout {

    private int mSelectedPanelIndex;
    private PanelBar mBar;

    public PanelHolder(Context context, AttributeSet attrs) {
        super(context, attrs);
        setChildrenDrawingOrderEnabled(true);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        setChildrenDrawingOrderEnabled(true);
    }

    public int getPanelIndex(PanelView pv) {
        final int N = getChildCount();
        for (int i=0; i<N; i++) {
            final PanelView v = (PanelView) getChildAt(i);
            if (pv == v) return i;
        }
        return -1;
    }

    public void setSelectedPanel(PanelView pv) {
        mSelectedPanelIndex = getPanelIndex(pv);
    }

    @Override
    protected int getChildDrawingOrder(int childCount, int i) {
        if (mSelectedPanelIndex == -1) {
            return i;
        } else {
            if (i == childCount - 1) {
                return mSelectedPanelIndex;
            } else if (i >= mSelectedPanelIndex) {
                return i + 1;
            } else {
                return i;
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mBar.collapseAllPanels(true);
                break;
        }
        return false;
    }

    public void setBar(PanelBar panelBar) {
        mBar = panelBar;
    }
}