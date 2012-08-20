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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;

public class ShirtPocket extends ImageView {
    private static final boolean DEBUG = false;
    private static final String  TAG = "StatusBar/ShirtPocket";

    private ClipData mClipping = null;

    private ImageView mPreviewIcon;

    public static class DropZone extends View {
        ShirtPocket mPocket;
        public DropZone(Context context, AttributeSet attrs) {
            super(context, attrs);
        }
        public void setPocket(ShirtPocket p) {
            mPocket = p;
        }

        public void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (mPocket.holding()) {
                show(false);
            } else {
                hide(false);
            }
        }

        // Drag API notes: we must be visible to receive drag events
        private void show(boolean animate) {
            setTranslationY(0f);
            if (animate) {
                setAlpha(0f);
                ObjectAnimator.ofFloat(this, "alpha", 0f, 1f).start();
            } else {
                setAlpha(1f);
            }
        }

        private void hide(boolean animate) {
            AnimatorListenerAdapter onEnd = new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator _a) {
                    DropZone.this.setTranslationY(getHeight() + 2);
                    DropZone.this.setAlpha(0f);
                }
            };
            if (animate) {
                Animator a = ObjectAnimator.ofFloat(this, "alpha", getAlpha(), 0f);
                a.addListener(onEnd);
                a.start();
            } else {
                onEnd.onAnimationEnd(null);
            }
        }

        @Override
        public boolean onDragEvent(DragEvent event) {
            if (DEBUG) Slog.d(TAG, "onDragEvent: " + event);
            switch (event.getAction()) {
                // We want to appear whenever a potential drag takes off from anywhere in the UI.
                case DragEvent.ACTION_DRAG_STARTED:
                    show(true);
                    break;
                case DragEvent.ACTION_DRAG_ENTERED:
                    if (DEBUG) Slog.d(TAG, "entered!");
                    // XXX: TODO
                    break;
                case DragEvent.ACTION_DRAG_EXITED:
                    if (DEBUG) Slog.d(TAG, "exited!");
                    break;
                case DragEvent.ACTION_DROP:
                    if (DEBUG) Slog.d(TAG, "dropped!");
                    mPocket.stash(event.getClipData());
                    break;
                case DragEvent.ACTION_DRAG_ENDED:
                    hide(true);
                    break;
            }
            return true; // we want everything, thank you
        }
    }

    public ShirtPocket(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    // TODO: "pin area" panel, dragging things out
    ObjectAnimator mAnimHide, mAnimShow;
    
    protected void onAttachedToWindow() {
    }

    public boolean holding() {
        return (mClipping != null);
    }

    private void stash(ClipData clipping) {
        mClipping = clipping;
        if (mClipping != null) {
            setVisibility(View.VISIBLE);
            Bitmap icon = mClipping.getIcon();
//            mDescription.setText(mClipping.getDescription().getLabel());
            if (icon != null) {
                setImageBitmap(icon);
            } else {
                if (mClipping.getItemCount() > 0) {
                    // TODO: figure out how to visualize every kind of ClipData!
                    //mAltText.setText(mClipping.getItemAt(0).coerceToText(getContext()));
                }
            }
        } else {
            setVisibility(View.GONE);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        final int action = ev.getAction();
        if (action == MotionEvent.ACTION_DOWN) {
            final ClipData clip = mClipping;
            if (clip != null) {
                final Bitmap icon = clip.getIcon();
                DragShadowBuilder shadow;
                if (icon != null) {
                    shadow = new DragShadowBuilder(this) {
                        public void onProvideShadowMetrics(Point shadowSize, Point shadowTouchPoint) {
                            shadowSize.set(icon.getWidth(), icon.getHeight());
                            shadowTouchPoint.set(shadowSize.x / 2, shadowSize.y / 2);
                        }
                        public void onDrawShadow(Canvas canvas) {
                            canvas.drawBitmap(icon, 0, 0, new Paint());
                        }
                    };
                } else {
                    // uhhh, what now?
                    shadow = new DragShadowBuilder(this);
                }

                startDrag(clip, shadow, null, 0);

                // TODO: only discard the clipping if it was accepted
                stash(null);

                return true;
            }
        }
        return false;
    }

    /*
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
                final ClipData clip = mClipping;
                if (clip != null) {
                    final Bitmap icon = clip.getIcon();
                    DragShadowBuilder shadow;
                    if (icon != null) {
                        shadow = new DragShadowBuilder(v) {
                            public void onProvideShadowMetrics(Point shadowSize, Point shadowTouchPoint) {
                                shadowSize.set(icon.getWidth(), icon.getHeight());
                                shadowTouchPoint.set(shadowSize.x / 2, shadowSize.y / 2);
                            }
                            public void onDrawShadow(Canvas canvas) {
                                canvas.drawBitmap(icon, 0, 0, new Paint());
                            }
                        };
                    } else {
                        // uhhh, what now?
                        shadow = new DragShadowBuilder(mWindow.findViewById(R.id.preview));
                    }

                    v.startDrag(clip, shadow, null, 0);

                    // TODO: only discard the clipping if it was accepted
                    stash(null);

                    return true;
                }
            }
            return false;
        }
    };
    */
}

