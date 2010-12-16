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

public class ShirtPocket extends ImageView {
    private static final boolean DEBUG = false;
    private static final String  TAG = "StatusBar/ShirtPocket";

    private ClipData mClipping = null;

    private View mWindow = null;
    private ImageView mPreviewIcon;
    private TextView mDescription;
    private TextView mAltText;

    public ShirtPocket(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    // TODO: "pin area" panel, dragging things out
    ObjectAnimator mAnimHide, mAnimShow;
    
    protected void onAttachedToWindow() {
        // Drag API notes: we must be visible to receive drag events
        setVisibility(View.VISIBLE);

        refresh();

        setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (mClipping != null) {
                    if (mWindow.getVisibility() == View.VISIBLE) hideWindow(); 
                    else showWindow();
                }
            }
        });
    }

    private void refresh() {
        setClickable(mClipping != null);
        // XXX: TODO
    }
    
    private void showWindow() {
        getHandler().post(new Runnable() {
            public void run() {
                mWindow.setVisibility(View.VISIBLE);
                refresh();
            }
        });
    }

    private void hideWindow() {
        getHandler().post(new Runnable() {
            public void run() {
                mWindow.setVisibility(View.GONE);
                refresh();
            }
        });
    }
    
    private void hideWindowInJustASec() {
        getHandler().postDelayed(new Runnable() {
            public void run() {
                mWindow.setVisibility(View.GONE);
                refresh();
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

    public boolean onDragEvent(DragEvent event) {
        if (DEBUG) Slog.d(TAG, "onDragEvent: " + event);
        switch (event.getAction()) {
            // We want to appear whenever a potential drag takes off from anywhere in the UI.
            case DragEvent.ACTION_DRAG_STARTED:
                // XXX: TODO
                break;
            case DragEvent.ACTION_DRAG_ENTERED:
                if (DEBUG) Slog.d(TAG, "entered!");
                // XXX: TODO
                break;
            case DragEvent.ACTION_DRAG_EXITED:
                if (DEBUG) Slog.d(TAG, "exited!");
                setVisibility(mClipping == null ? View.GONE : View.VISIBLE);
                break;
            case DragEvent.ACTION_DROP:
                if (DEBUG) Slog.d(TAG, "dropped!");
                stash(event.getClipData());
                break;
            case DragEvent.ACTION_DRAG_ENDED:
                break;
        }
        return true; // we want everything, thank you
    }
}

