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

package com.android.tools.idea.editors.theme.widgets;

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.layoutlib.bridge.Bridge;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * {@link ViewGroup} that wraps another view and catches any possible exceptions that the child view
 * might generate.
 * This is used by the theme editor to stop custom views from breaking the preview.
 */
// TODO: This view is just a temporary solution that will be replaced by adding a try / catch
// for custom views in the ClassConverter
public class ErrorCatcher extends ViewGroup {
    public ErrorCatcher(Context context) {
        super(context);
    }

    public ErrorCatcher(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ErrorCatcher(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ErrorCatcher(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        assert getChildCount() == 1 : "ErrorCatcher can only have one child";

        View child = getChildAt(0);
        try {
            measureChild(child, widthMeasureSpec, heightMeasureSpec);

            setMeasuredDimension(resolveSize(child.getMeasuredWidth(), widthMeasureSpec),
                    resolveSize(child.getMeasuredHeight(), heightMeasureSpec));
        } catch (Throwable t) {
            Bridge.getLog().warning(LayoutLog.TAG_BROKEN, "Failed to do onMeasure for view " +
                    child.getClass().getCanonicalName(), t);
            setMeasuredDimension(resolveSize(0, widthMeasureSpec),
                    resolveSize(0, heightMeasureSpec));
        }
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        try {
            return super.drawChild(canvas, child, drawingTime);
        } catch (Throwable t) {
            Bridge.getLog().warning(LayoutLog.TAG_BROKEN, "Failed to draw for view " +
                    child.getClass().getCanonicalName(), t);
        }

        return false;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        assert getChildCount() == 1 : "ErrorCatcher can only have one child";

        View child = getChildAt(0);
        try {
            child.layout(0, 0, child.getMeasuredWidth(), child.getMeasuredHeight());
        } catch (Throwable e) {
            Bridge.getLog().warning(LayoutLog.TAG_BROKEN, "Failed to do onLayout for view " +
                    child.getClass().getCanonicalName(), e);
        }
    }
}
