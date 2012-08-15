package com.android.systemui.statusbar.phone;

import java.util.ArrayList;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.MotionEvent;
import android.widget.FrameLayout;

public class PanelBar extends FrameLayout {
    public static final boolean DEBUG = true;
    public static final String TAG = PanelView.class.getSimpleName();
    public static final void LOG(String fmt, Object... args) {
        if (!DEBUG) return;
        Slog.v(TAG, String.format(fmt, args));
    }

    private PanelHolder mPanelHolder;
    private ArrayList<PanelView> mPanels = new ArrayList<PanelView>();
    protected PanelView mTouchingPanel;

    public PanelBar(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
    }

    public void addPanel(PanelView pv) {
        mPanels.add(pv);
        pv.setBar(this);
    }

    public void setPanelHolder(PanelHolder ph) {
        if (ph == null) {
            Slog.e(TAG, "setPanelHolder: null PanelHolder", new Throwable());
            return;
        }
        ph.setBar(this);
        mPanelHolder = ph;
        final int N = ph.getChildCount();
        for (int i=0; i<N; i++) {
            final PanelView v = (PanelView) ph.getChildAt(i);
            if (v != null) {
                addPanel(v);
            }
        }
    }

    public float getBarHeight() {
        return getMeasuredHeight();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // figure out which panel needs to be talked to here
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            final int N = mPanels.size();
            final int i = (int)(N * event.getX() / getMeasuredWidth());
            mTouchingPanel = mPanels.get(i);
            mPanelHolder.setSelectedPanel(mTouchingPanel);
            LOG("PanelBar.onTouch: ACTION_DOWN: panel %d", i);
            onPanelPeeked();
        }
        final boolean result = mTouchingPanel.getHandle().dispatchTouchEvent(event);
        return result;
    }

    public void panelExpansionChanged(PanelView panel, float frac) {
        boolean fullyClosed = true;
        PanelView fullyOpenedPanel = null;
        for (PanelView pv : mPanels) {
            if (pv.getExpandedHeight() > 0f) {
                fullyClosed = false;
                final float thisFrac = pv.getExpandedFraction();
                LOG("panel %s: f=%.1f", pv, thisFrac);
                if (panel == pv) {
                    if (thisFrac == 1f) fullyOpenedPanel = panel;
                } else {
                    pv.setExpandedFraction(1f-frac);
                }
            }
        }
        if (fullyOpenedPanel != null) onPanelFullyOpened(fullyOpenedPanel);
        if (fullyClosed) onAllPanelsCollapsed();
        else onPanelPeeked();

        LOG("panelExpansionChanged: [%s%s ]", 
                (fullyOpenedPanel!=null)?" fullyOpened":"", fullyClosed?" fullyClosed":"");
    }

    public void collapseAllPanels(boolean animate) {
        for (PanelView pv : mPanels) {
            if (animate && pv == mTouchingPanel) {
                mTouchingPanel.collapse();
            } else {
                pv.setExpandedFraction(0); // just in case
            }
        }
    }

    public void onPanelPeeked() {
        LOG("onPanelPeeked");
    }

    public void onAllPanelsCollapsed() {
        LOG("onAllPanelsCollapsed");
    }

    public void onPanelFullyOpened(PanelView openPanel) {
        LOG("onPanelFullyOpened");
    }
}
