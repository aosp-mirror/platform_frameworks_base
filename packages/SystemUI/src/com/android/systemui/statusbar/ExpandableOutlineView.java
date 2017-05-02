/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import com.android.systemui.R;

/**
 * Like {@link ExpandableView}, but setting an outline for the height and clipping.
 */
public abstract class ExpandableOutlineView extends ExpandableView {

    private final Rect mOutlineRect = new Rect();
    private boolean mCustomOutline;
    private float mOutlineAlpha = -1f;
    private float mOutlineRadius;

    /**
     * {@code true} if the children views of the {@link ExpandableOutlineView} are translated when
     * it is moved. Otherwise, the translation is set on the {@code ExpandableOutlineView} itself.
     */
    protected boolean mShouldTranslateContents;

    private final ViewOutlineProvider mProvider = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            int translation = mShouldTranslateContents ? (int) getTranslation() : 0;
            if (!mCustomOutline) {
                outline.setRoundRect(translation,
                        mClipTopAmount,
                        getWidth() + translation,
                        Math.max(getActualHeight() - mClipBottomAmount, mClipTopAmount),
                        mOutlineRadius);
            } else {
                outline.setRoundRect(mOutlineRect, mOutlineRadius);
            }
            outline.setAlpha(mOutlineAlpha);
        }
    };

    public ExpandableOutlineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOutlineProvider(mProvider);
        initDimens();
    }

    private void initDimens() {
        Resources res = getResources();
        mShouldTranslateContents =
                res.getBoolean(R.bool.config_translateNotificationContentsOnSwipe);
        mOutlineRadius = res.getDimension(R.dimen.notification_shadow_radius);
        setClipToOutline(res.getBoolean(R.bool.config_clipNotificationsToOutline));
    }

    public void onDensityOrFontScaleChanged() {
        initDimens();
        invalidateOutline();
    }

    @Override
    public void setActualHeight(int actualHeight, boolean notifyListeners) {
        super.setActualHeight(actualHeight, notifyListeners);
        invalidateOutline();
    }

    @Override
    public void setClipTopAmount(int clipTopAmount) {
        super.setClipTopAmount(clipTopAmount);
        invalidateOutline();
    }

    @Override
    public void setClipBottomAmount(int clipBottomAmount) {
        super.setClipBottomAmount(clipBottomAmount);
        invalidateOutline();
    }

    protected void setOutlineAlpha(float alpha) {
        if (alpha != mOutlineAlpha) {
            mOutlineAlpha = alpha;
            invalidateOutline();
        }
    }

    @Override
    public float getOutlineAlpha() {
        return mOutlineAlpha;
    }

    protected void setOutlineRect(RectF rect) {
        if (rect != null) {
            setOutlineRect(rect.left, rect.top, rect.right, rect.bottom);
        } else {
            mCustomOutline = false;
            setClipToOutline(false);
            invalidateOutline();
        }
    }

    @Override
    public int getOutlineTranslation() {
        return mCustomOutline ? mOutlineRect.left : (int) getTranslation();
    }

    public void updateOutline() {
        if (mCustomOutline) {
            return;
        }
        boolean hasOutline = needsOutline();
        setOutlineProvider(hasOutline ? mProvider : null);
    }

    /**
     * @return Whether the view currently needs an outline. This is usually {@code false} in case
     * it doesn't have a background.
     */
    protected boolean needsOutline() {
        if (isChildInGroup()) {
            return isGroupExpanded() && !isGroupExpansionChanging();
        } else if (isSummaryWithChildren()) {
            return !isGroupExpanded() || isGroupExpansionChanging();
        }
        return true;
    }

    public boolean isOutlineShowing() {
        ViewOutlineProvider op = getOutlineProvider();
        return op != null;
    }

    protected void setOutlineRect(float left, float top, float right, float bottom) {
        mCustomOutline = true;
        setClipToOutline(true);

        mOutlineRect.set((int) left, (int) top, (int) right, (int) bottom);

        // Outlines need to be at least 1 dp
        mOutlineRect.bottom = (int) Math.max(top, mOutlineRect.bottom);
        mOutlineRect.right = (int) Math.max(left, mOutlineRect.right);

        invalidateOutline();
    }

}
