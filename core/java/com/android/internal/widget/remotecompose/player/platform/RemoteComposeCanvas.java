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
import android.view.VelocityTracker;
import android.view.View;
import android.widget.FrameLayout;

import com.android.internal.widget.remotecompose.core.CoreDocument;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.operations.RootContentBehavior;
import com.android.internal.widget.remotecompose.core.operations.Theme;
import com.android.internal.widget.remotecompose.player.RemoteComposeDocument;

import java.util.Set;

/** Internal view handling the actual painting / interactions */
public class RemoteComposeCanvas extends FrameLayout implements View.OnAttachStateChangeListener {

    static final boolean USE_VIEW_AREA_CLICK = true; // Use views to represent click areas
    RemoteComposeDocument mDocument = null;
    int mTheme = Theme.LIGHT;
    boolean mInActionDown = false;
    int mDebug = 0;
    boolean mHasClickAreas = false;
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

    public void setDebug(int value) {
        if (mDebug != value) {
            mDebug = value;
            if (USE_VIEW_AREA_CLICK) {
                for (int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    if (child instanceof ClickAreaView) {
                        ((ClickAreaView) child).setDebug(mDebug == 1);
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
        updateClickAreas();
        requestLayout();
        invalidate();
    }

    @Override
    public void onViewAttachedToWindow(View view) {
        if (mDocument == null) {
            return;
        }
        updateClickAreas();
    }

    private void updateClickAreas() {
        if (USE_VIEW_AREA_CLICK && mDocument != null) {
            mHasClickAreas = false;
            Set<CoreDocument.ClickAreaRepresentation> clickAreas =
                    mDocument.getDocument().getClickAreas();
            removeAllViews();
            for (CoreDocument.ClickAreaRepresentation area : clickAreas) {
                ClickAreaView viewArea =
                        new ClickAreaView(
                                getContext(),
                                mDebug == 1,
                                area.getId(),
                                area.getContentDescription(),
                                area.getMetadata());
                int w = (int) area.width();
                int h = (int) area.height();
                FrameLayout.LayoutParams param = new FrameLayout.LayoutParams(w, h);
                param.width = w;
                param.height = h;
                param.leftMargin = (int) area.getLeft();
                param.topMargin = (int) area.getTop();
                viewArea.setOnClickListener(
                        view1 -> mDocument.getDocument().performClick(area.getId()));
                addView(viewArea, param);
            }
            if (!clickAreas.isEmpty()) {
                mHasClickAreas = true;
            }
        }
    }

    public void setHapticEngine(CoreDocument.HapticEngine engine) {
        mDocument.getDocument().setHapticEngine(engine);
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        removeAllViews();
    }

    public String[] getNamedColors() {
        return mDocument.getNamedColors();
    }

    /**
     * Gets a array of Names of the named variables of a specific type defined in the loaded doc.
     *
     * @param type the type of variable NamedVariable.COLOR_TYPE, STRING_TYPE, etc
     * @return array of name or null
     */
    public String[] getNamedVariables(int type) {
        return mDocument.getNamedVariables(type);
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

    public RemoteComposeDocument getDocument() {
        return mDocument;
    }

    public void setLocalString(String name, String content) {
        mARContext.setNamedStringOverride(name, content);
        if (mDocument != null) {
            mDocument.invalidate();
        }
    }

    public void clearLocalString(String name) {
        mARContext.clearNamedStringOverride(name);
        if (mDocument != null) {
            mDocument.invalidate();
        }
    }

    public void setLocalInt(String name, int content) {
        mARContext.setNamedIntegerOverride(name, content);
        if (mDocument != null) {
            mDocument.invalidate();
        }
    }

    public void clearLocalInt(String name) {
        mARContext.clearNamedIntegerOverride(name);
        if (mDocument != null) {
            mDocument.invalidate();
        }
    }

    public int hasSensorListeners(int[] ids) {
        int count = 0;
        for (int id = RemoteContext.ID_ACCELERATION_X; id <= RemoteContext.ID_LIGHT; id++) {
            if (mARContext.mRemoteComposeState.hasListener(id)) {
                ids[count++] = id;
            }
        }
        return count;
    }

    /**
     * set a float externally
     *
     * @param id
     * @param value
     */
    public void setExternalFloat(int id, float value) {
        mARContext.loadFloat(id, value);
    }

    /**
     * Returns true if the document supports drag touch events
     *
     * @return true if draggable content, false otherwise
     */
    public boolean isDraggable() {
        if (mDocument == null) {
            return false;
        }
        return mDocument.getDocument().hasTouchListener();
    }

    /**
     * Check shaders and disable them
     *
     * @param shaderControl the callback to validate the shader
     */
    public void checkShaders(CoreDocument.ShaderControl shaderControl) {
        mDocument.getDocument().checkShaders(mARContext, shaderControl);
    }

    public interface ClickCallbacks {
        void click(int id, String metadata);
    }

    public void addIdActionListener(ClickCallbacks callback) {
        if (mDocument == null) {
            return;
        }
        mDocument.getDocument().addIdActionListener((id, metadata) -> callback.click(id, metadata));
    }

    public int getTheme() {
        return mTheme;
    }

    public void setTheme(int theme) {
        this.mTheme = theme;
    }

    private VelocityTracker mVelocityTracker = null;

    public boolean onTouchEvent(MotionEvent event) {
        int index = event.getActionIndex();
        int action = event.getActionMasked();
        int pointerId = event.getPointerId(index);
        if (USE_VIEW_AREA_CLICK && mHasClickAreas) {
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mActionDownPoint.x = (int) event.getX();
                mActionDownPoint.y = (int) event.getY();
                CoreDocument doc = mDocument.getDocument();
                if (doc.hasTouchListener()) {
                    mInActionDown = true;
                    if (mVelocityTracker == null) {
                        mVelocityTracker = VelocityTracker.obtain();
                    } else {
                        mVelocityTracker.clear();
                    }
                    mVelocityTracker.addMovement(event);
                    doc.touchDown(mARContext, event.getX(), event.getY());
                    invalidate();
                    return true;
                }
                return false;

            case MotionEvent.ACTION_CANCEL:
                mInActionDown = false;
                doc = mDocument.getDocument();
                if (doc.hasTouchListener()) {
                    mVelocityTracker.computeCurrentVelocity(1000);
                    float dx = mVelocityTracker.getXVelocity(pointerId);
                    float dy = mVelocityTracker.getYVelocity(pointerId);
                    doc.touchCancel(mARContext, event.getX(), event.getY(), dx, dy);
                    invalidate();
                    return true;
                }
                return false;

            case MotionEvent.ACTION_UP:
                mInActionDown = false;
                performClick();
                doc = mDocument.getDocument();
                if (doc.hasTouchListener()) {
                    mVelocityTracker.computeCurrentVelocity(1000);
                    float dx = mVelocityTracker.getXVelocity(pointerId);
                    float dy = mVelocityTracker.getYVelocity(pointerId);
                    doc.touchUp(mARContext, event.getX(), event.getY(), dx, dy);
                    invalidate();
                    return true;
                }
                return false;

            case MotionEvent.ACTION_MOVE:
                if (mInActionDown) {
                    if (mVelocityTracker != null) {
                        mVelocityTracker.addMovement(event);
                        doc = mDocument.getDocument();
                        boolean repaint = doc.touchDrag(mARContext, event.getX(), event.getY());
                        if (repaint) {
                            invalidate();
                        }
                    }
                    return true;
                }
                return false;
        }
        return false;
    }

    @Override
    public boolean performClick() {
        if (USE_VIEW_AREA_CLICK && mHasClickAreas) {
            return super.performClick();
        }
        mDocument
                .getDocument()
                .onClick(mARContext, (float) mActionDownPoint.x, (float) mActionDownPoint.y);
        super.performClick();
        invalidate();
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
        int preWidth = getWidth();
        int preHeight = getHeight();
        int w = measureDimension(widthMeasureSpec, mDocument.getWidth());
        int h = measureDimension(heightMeasureSpec, mDocument.getHeight());

        if (!USE_VIEW_AREA_CLICK) {
            if (mDocument.getDocument().getContentSizing() == RootContentBehavior.SIZING_SCALE) {
                mDocument.getDocument().computeScale(w, h, sScaleOutput);
                w = (int) (mDocument.getWidth() * sScaleOutput[0]);
                h = (int) (mDocument.getHeight() * sScaleOutput[1]);
            }
        }
        setMeasuredDimension(w, h);
        if (preWidth != w || preHeight != h) {
            mDocument.getDocument().invalidateMeasure();
        }
    }

    private int mCount;
    private long mTime = System.nanoTime();
    private long mDuration;
    private boolean mEvalTime = false;

    /**
     * This returns the amount of time in ms the player used to evalueate a pass it is averaged over
     * a number of evaluations.
     *
     * @return time in ms
     */
    public float getEvalTime() {
        if (!mEvalTime) {
            mEvalTime = true;
            return 0.0f;
        }
        double avg = mDuration / (double) mCount;
        if (mCount > 100) {
            mDuration /= 2;
            mCount /= 2;
        }
        return (float) (avg * 1E-6); // ms
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mDocument == null) {
            return;
        }
        long start = mEvalTime ? System.nanoTime() : 0;
        mARContext.setAnimationEnabled(true);
        mARContext.currentTime = System.currentTimeMillis();
        mARContext.setDebug(mDebug);
        float density = getContext().getResources().getDisplayMetrics().density;
        mARContext.useCanvas(canvas);
        mARContext.setDensity(density);
        mARContext.mWidth = getWidth();
        mARContext.mHeight = getHeight();
        mDocument.paint(mARContext, mTheme);
        if (mDebug == 1) {
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
        if (mEvalTime) {
            mDuration += System.nanoTime() - start;
            mCount++;
        }
    }
}
