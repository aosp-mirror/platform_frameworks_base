/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar.tablet;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.util.Slog;
import android.view.View;
import android.util.AttributeSet;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.DragEvent;
import android.view.MotionEvent;
import android.content.ClipData;
import android.content.ClipDescription;
import android.graphics.Paint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Point;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.view.WindowManagerImpl;
import android.graphics.PixelFormat;
import android.view.Gravity;

import com.android.systemui.R;

public class ShirtPocket extends FrameLayout {
    private static final boolean DEBUG = false;
    private static final String  TAG = "StatusBar/ShirtPocket";

    private ClipData mClipping = null;

    private View mWindow = null;
    private ImageView mIcon;
    private ImageView mPreviewIcon;
    private TextView mDescription;
    private TextView mAltText;

    public ShirtPocket(Context context, AttributeSet attrs) {
        super(context, attrs);
        setupWindow();
    }

    // TODO: "pin area" panel, dragging things out
    ObjectAnimator mAnimHide, mAnimShow;
    
    protected void onAttachedToWindow() {
        // Drag API notes: we must be visible to receive drag events
        setVisibility(View.VISIBLE);

        mIcon = (ImageView) findViewById(R.id.pocket_icon);
        refreshStatusIcon();

        setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (mClipping != null) {
                    if (mWindow.getVisibility() == View.VISIBLE) hideWindow(); 
                    else showWindow();
                }
            }
        });
    }

    private void refreshStatusIcon() {
        setClickable(mClipping != null);
        mIcon.setImageResource(mClipping == null
                ? R.drawable.ic_sysbar_pocket_hidden
                : R.drawable.ic_sysbar_pocket_holding);
        mIcon.setVisibility(mClipping == null ? View.INVISIBLE : View.VISIBLE);
    }
    
    private void showWindow() {
        getHandler().post(new Runnable() {
            public void run() {
                mWindow.setVisibility(View.VISIBLE);
                refreshStatusIcon();
            }
        });
    }

    private void hideWindow() {
        getHandler().post(new Runnable() {
            public void run() {
                mWindow.setVisibility(View.GONE);
                refreshStatusIcon();
            }
        });
    }
    
    private void hideWindowInJustASec() {
        getHandler().postDelayed(new Runnable() {
            public void run() {
                mWindow.setVisibility(View.GONE);
                refreshStatusIcon();
            }
        },
        250);
    }

    private void stash(ClipData clipping) {
        mClipping = clipping;
        if (mClipping != null) {
            Bitmap icon = mClipping.getIcon();
            mDescription.setText(mClipping.getDescription().getLabel());
            if (icon != null) {
                mPreviewIcon.setImageBitmap(icon);
                mPreviewIcon.setVisibility(View.VISIBLE);
                mAltText.setVisibility(View.GONE);
            } else {
                mPreviewIcon.setVisibility(View.GONE);
                mAltText.setVisibility(View.VISIBLE);
                if (mClipping.getItemCount() > 0) {
                    // TODO: figure out how to visualize every kind of ClipData!
                    mAltText.setText(mClipping.getItem(0).coerceToText(getContext()));
                }
            }
        }
    }

    private boolean isInViewContentArea(View v, int x, int y) {
        final int l = v.getPaddingLeft();
        final int r = v.getWidth() - v.getPaddingRight();
        final int t = v.getPaddingTop();
        final int b = v.getHeight() - v.getPaddingBottom();
        return x >= l && x < r && y >= t && y < b;
    }

    View.OnTouchListener mWindowTouchListener = new View.OnTouchListener() {
        public boolean onTouch(View v, MotionEvent ev) {
            final int action = ev.getAction();
            if (action == MotionEvent.ACTION_OUTSIDE
                    || (action == MotionEvent.ACTION_DOWN
                        && !isInViewContentArea(mWindow, (int)ev.getX(), (int)ev.getY()))) {
                hideWindow();
                return true;
            } else if (action == MotionEvent.ACTION_DOWN) {
                Slog.d(TAG, "ACTION_DOWN");
                final ClipData clip = mClipping;
                if (clip != null) {
                    final Bitmap icon = clip.getIcon();
                    DragThumbnailBuilder thumb;
                    if (icon != null) {
                        thumb = new DragThumbnailBuilder(v) {
                            public void onProvideThumbnailMetrics(Point thumbnailSize, Point thumbnailTouchPoint) {
                                thumbnailSize.set(icon.getWidth(), icon.getHeight());
                                thumbnailTouchPoint.set(thumbnailSize.x / 2, thumbnailSize.y / 2);
                            }
                            public void onDrawThumbnail(Canvas canvas) {
                                canvas.drawBitmap(icon, 0, 0, new Paint());
                            }
                        };
                    } else {
                        // uhhh, what now?
                        thumb = new DragThumbnailBuilder(mWindow.findViewById(R.id.preview));
                    }

                    v.startDrag(clip, thumb, false, null);

                    // TODO: only discard the clipping if it was accepted
                    stash(null);

                    hideWindowInJustASec(); // will refresh the icon

                    return true;
                }
            }
            return false;
        }
    };

    private void setupWindow() {
        mWindow = View.inflate(getContext(), R.layout.status_bar_pocket_panel, null);

        mPreviewIcon = (ImageView) mWindow.findViewById(R.id.icon);
        mDescription = (TextView) mWindow.findViewById(R.id.description);
        mAltText = (TextView) mWindow.findViewById(R.id.alt);

        mWindow.setVisibility(View.GONE);
        mWindow.setOnTouchListener(mWindowTouchListener);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                400,
                250,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                    | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
//        int pos[] = new int[2];
//        getLocationOnScreen(pos);
//        lp.x = pos[1];
//        lp.y = 0;
        lp.setTitle("ShirtPocket");
        lp.windowAnimations = R.style.Animation_ShirtPocketPanel;

        WindowManagerImpl.getDefault().addView(mWindow, lp);

    }

    public boolean onDragEvent(DragEvent event) {
        if (DEBUG) Slog.d(TAG, "onDragEvent: " + event);
        if (mIcon != null) {
            switch (event.getAction()) {
                // We want to appear whenever a potential drag takes off from anywhere in the UI.
                case DragEvent.ACTION_DRAG_STARTED:
                    mIcon.setImageResource(mClipping == null
                            ? R.drawable.ic_sysbar_pocket
                            : R.drawable.ic_sysbar_pocket_holding);
                    mIcon.setVisibility(View.VISIBLE);
                    break;
                case DragEvent.ACTION_DRAG_ENTERED:
                    if (DEBUG) Slog.d(TAG, "entered!");
                    mIcon.setImageResource(R.drawable.ic_sysbar_pocket_drag);
                    break;
                case DragEvent.ACTION_DRAG_EXITED:
                    if (DEBUG) Slog.d(TAG, "exited!");
                    mIcon.setImageResource(mClipping == null
                            ? R.drawable.ic_sysbar_pocket
                            : R.drawable.ic_sysbar_pocket_holding);
                    break;
                case DragEvent.ACTION_DROP:
                    if (DEBUG) Slog.d(TAG, "dropped!");
                    stash(event.getClipData());
                    refreshStatusIcon();
                    break;
                case DragEvent.ACTION_DRAG_ENDED:
                    refreshStatusIcon();
                    break;
            }
        }
        return true; // we want everything, thank you
    }
}

