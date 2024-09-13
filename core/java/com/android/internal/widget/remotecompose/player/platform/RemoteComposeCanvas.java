/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.player.platform;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.android.internal.widget.remotecompose.core.CoreDocument;
import com.android.internal.widget.remotecompose.core.operations.RootContentBehavior;
import com.android.internal.widget.remotecompose.core.operations.Theme;
import com.android.internal.widget.remotecompose.player.RemoteComposeDocument;

import java.util.Set;

/**
 * Internal view handling the actual painting / interactions
 */
public class RemoteComposeCanvas extends FrameLayout implements View.OnAttachStateChangeListener {

    static final boolean USE_VIEW_AREA_CLICK = true; // Use views to represent click areas
    RemoteComposeDocument mDocument = null;
    int mTheme = Theme.LIGHT;
    boolean mInActionDown = false;
    boolean mDebug = false;
    Point mActionDownPoint = new Point(0, 0);
    AndroidRemoteContext mARContext = new AndroidRemoteContext();

    public RemoteComposeCanvas(Context context) {
        super(context);
        if (USE_VIEW_AREA_CLICK) {
            addOnAttachStateChangeListener(this);
        }
    }

    public RemoteComposeCanvas(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (USE_VIEW_AREA_CLICK) {
            addOnAttachStateChangeListener(this);
        }
    }

    public RemoteComposeCanvas(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setBackgroundColor(Color.WHITE);
        if (USE_VIEW_AREA_CLICK) {
            addOnAttachStateChangeListener(this);
        }
    }

    public void setDebug(boolean value) {
        if (mDebug != value) {
            mDebug = value;
            if (USE_VIEW_AREA_CLICK) {
                for (int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    if (child instanceof ClickAreaView) {
                        ((ClickAreaView) child).setDebug(mDebug);
                    }
                }
            }
            invalidate();
        }
    }

    public void setDocument(RemoteComposeDocument value) {
        mDocument = value;
        mDocument.initializeContext(mARContext);
        setContentDescription(mDocument.getDocument().getContentDescription());
        requestLayout();
        invalidate();
    }

    @Override
    public void onViewAttachedToWindow(View view) {
        if (mDocument == null) {
            return;
        }
        Set<CoreDocument.ClickAreaRepresentation> clickAreas = mDocument
                .getDocument().getClickAreas();
        removeAllViews();
        for (CoreDocument.ClickAreaRepresentation area : clickAreas) {
            ClickAreaView viewArea = new ClickAreaView(getContext(), mDebug,
                    area.getId(), area.getContentDescription(),
                    area.getMetadata());
            int w = (int) area.width();
            int h = (int) area.height();
            FrameLayout.LayoutParams param = new FrameLayout.LayoutParams(w, h);
            param.width = w;
            param.height = h;
            param.leftMargin = (int) area.getLeft();
            param.topMargin = (int) area.getTop();
            viewArea.setOnClickListener(view1
                    -> mDocument.getDocument().performClick(area.getId()));
            addView(viewArea, param);
        }
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        removeAllViews();
    }

    public String[] getNamedColors() {
        return mDocument.getNamedColors();
    }

    /**
     * set the color associated with this name.
     *
     * @param colorName Name of color typically "android.xxx"
     * @param colorValue "the argb value"
     */
    public void setColor(String colorName, int colorValue) {
        mARContext.setNamedColorOverride(colorName, colorValue);
    }

    public interface ClickCallbacks {
        void click(int id, String metadata);
    }

    public void addClickListener(ClickCallbacks callback) {
        if (mDocument == null) {
            return;
        }
        mDocument.getDocument().addClickListener((id, metadata) -> callback.click(id, metadata));
    }

    public int getTheme() {
        return mTheme;
    }

    public void setTheme(int theme) {
        this.mTheme = theme;
    }

    public boolean onTouchEvent(MotionEvent event) {
        if (USE_VIEW_AREA_CLICK) {
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                mActionDownPoint.x = (int) event.getX();
                mActionDownPoint.y = (int) event.getY();
                mInActionDown = true;
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                mInActionDown = false;
                return true;
            }
            case MotionEvent.ACTION_UP: {
                mInActionDown = false;
                performClick();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
            }
        }
        return false;
    }

    @Override
    public boolean performClick() {
        if (USE_VIEW_AREA_CLICK) {
            return super.performClick();
        }
        mDocument.getDocument().onClick((float) mActionDownPoint.x, (float) mActionDownPoint.y);
        super.performClick();
        return true;
    }

    public int measureDimension(int measureSpec, int intrinsicSize) {
        int result = intrinsicSize;
        int mode = MeasureSpec.getMode(measureSpec);
        int size = MeasureSpec.getSize(measureSpec);
        switch (mode) {
            case MeasureSpec.EXACTLY:
                result = size;
                break;
            case MeasureSpec.AT_MOST:
                result = Integer.min(size, intrinsicSize);
                break;
            case MeasureSpec.UNSPECIFIED:
                result = intrinsicSize;
        }
        return result;
    }

    private static final float[] sScaleOutput = new float[2];

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (mDocument == null) {
            return;
        }
        int w = measureDimension(widthMeasureSpec, mDocument.getWidth());
        int h = measureDimension(heightMeasureSpec, mDocument.getHeight());
        mDocument.getDocument().invalidateMeasure();

        if (!USE_VIEW_AREA_CLICK) {
            if (mDocument.getDocument().getContentSizing() == RootContentBehavior.SIZING_SCALE) {
                mDocument.getDocument().computeScale(w, h, sScaleOutput);
                w = (int) (mDocument.getWidth() * sScaleOutput[0]);
                h = (int) (mDocument.getHeight() * sScaleOutput[1]);
            }
        }
        setMeasuredDimension(w, h);
    }

    private int mCount;
    private long mTime = System.nanoTime();

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mDocument == null) {
            return;
        }
        mARContext.setAnimationEnabled(true);
        mARContext.currentTime = System.currentTimeMillis();
        mARContext.setDebug(mDebug);
        mARContext.useCanvas(canvas);
        mARContext.mWidth = getWidth();
        mARContext.mHeight = getHeight();
        mDocument.paint(mARContext, mTheme);
        if (mDebug) {
            mCount++;
            if (System.nanoTime() - mTime > 1000000000L) {
                System.out.println(" count " + mCount + " fps");
                mCount = 0;
                mTime = System.nanoTime();
            }
        }
        if (mDocument.needsRepaint() > 0) {
            invalidate();
        }
    }

}

