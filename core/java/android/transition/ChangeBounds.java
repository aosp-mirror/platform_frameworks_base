/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.transition;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.RectEvaluator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Property;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.R;

import java.util.Map;

/**
 * This transition captures the layout bounds of target views before and after
 * the scene change and animates those changes during the transition.
 *
 * <p>A ChangeBounds transition can be described in a resource file by using the
 * tag <code>changeBounds</code>, using its attributes of
 * {@link android.R.styleable#ChangeBounds} along with the other standard
 * attributes of {@link android.R.styleable#Transition}.</p>
 */
public class ChangeBounds extends Transition {

    private static final String PROPNAME_BOUNDS = "android:changeBounds:bounds";
    private static final String PROPNAME_CLIP = "android:changeBounds:clip";
    private static final String PROPNAME_PARENT = "android:changeBounds:parent";
    private static final String PROPNAME_WINDOW_X = "android:changeBounds:windowX";
    private static final String PROPNAME_WINDOW_Y = "android:changeBounds:windowY";
    private static final String[] sTransitionProperties = {
            PROPNAME_BOUNDS,
            PROPNAME_CLIP,
            PROPNAME_PARENT,
            PROPNAME_WINDOW_X,
            PROPNAME_WINDOW_Y
    };

    private static final Property<Drawable, PointF> DRAWABLE_ORIGIN_PROPERTY =
            new Property<Drawable, PointF>(PointF.class, "boundsOrigin") {
                private Rect mBounds = new Rect();

                @Override
                public void set(Drawable object, PointF value) {
                    object.copyBounds(mBounds);
                    mBounds.offsetTo(Math.round(value.x), Math.round(value.y));
                    object.setBounds(mBounds);
                }

                @Override
                public PointF get(Drawable object) {
                    object.copyBounds(mBounds);
                    return new PointF(mBounds.left, mBounds.top);
                }
    };

    private static final Property<ViewBounds, PointF> TOP_LEFT_PROPERTY =
            new Property<ViewBounds, PointF>(PointF.class, "topLeft") {
                @Override
                public void set(ViewBounds viewBounds, PointF topLeft) {
                    viewBounds.setTopLeft(topLeft);
                }

                @Override
                public PointF get(ViewBounds viewBounds) {
                    return null;
                }
            };

    private static final Property<ViewBounds, PointF> BOTTOM_RIGHT_PROPERTY =
            new Property<ViewBounds, PointF>(PointF.class, "bottomRight") {
                @Override
                public void set(ViewBounds viewBounds, PointF bottomRight) {
                    viewBounds.setBottomRight(bottomRight);
                }

                @Override
                public PointF get(ViewBounds viewBounds) {
                    return null;
                }
            };

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    private static final Property<View, PointF> BOTTOM_RIGHT_ONLY_PROPERTY =
            new Property<View, PointF>(PointF.class, "bottomRight") {
                @Override
                public void set(View view, PointF bottomRight) {
                    int left = view.getLeft();
                    int top = view.getTop();
                    int right = Math.round(bottomRight.x);
                    int bottom = Math.round(bottomRight.y);
                    view.setLeftTopRightBottom(left, top, right, bottom);
                }

                @Override
                public PointF get(View view) {
                    return null;
                }
            };

    private static final Property<View, PointF> TOP_LEFT_ONLY_PROPERTY =
            new Property<View, PointF>(PointF.class, "topLeft") {
                @Override
                public void set(View view, PointF topLeft) {
                    int left = Math.round(topLeft.x);
                    int top = Math.round(topLeft.y);
                    int right = view.getRight();
                    int bottom = view.getBottom();
                    view.setLeftTopRightBottom(left, top, right, bottom);
                }

                @Override
                public PointF get(View view) {
                    return null;
                }
            };

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    private static final Property<View, PointF> POSITION_PROPERTY =
            new Property<View, PointF>(PointF.class, "position") {
                @Override
                public void set(View view, PointF topLeft) {
                    int left = Math.round(topLeft.x);
                    int top = Math.round(topLeft.y);
                    int right = left + view.getWidth();
                    int bottom = top + view.getHeight();
                    view.setLeftTopRightBottom(left, top, right, bottom);
                }

                @Override
                public PointF get(View view) {
                    return null;
                }
            };

    int[] tempLocation = new int[2];
    boolean mResizeClip = false;
    boolean mReparent = false;
    private static final String LOG_TAG = "ChangeBounds";

    private static RectEvaluator sRectEvaluator = new RectEvaluator();

    public ChangeBounds() {}

    public ChangeBounds(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ChangeBounds);
        boolean resizeClip = a.getBoolean(R.styleable.ChangeBounds_resizeClip, false);
        a.recycle();
        setResizeClip(resizeClip);
    }

    @Override
    public String[] getTransitionProperties() {
        return sTransitionProperties;
    }

    /**
     * When <code>resizeClip</code> is true, ChangeBounds resizes the view using the clipBounds
     * instead of changing the dimensions of the view during the animation. When
     * <code>resizeClip</code> is false, ChangeBounds resizes the View by changing its dimensions.
     *
     * <p>When resizeClip is set to true, the clip bounds is modified by ChangeBounds. Therefore,
     * {@link android.transition.ChangeClipBounds} is not compatible with ChangeBounds
     * in this mode.</p>
     *
     * @param resizeClip Used to indicate whether the view bounds should be modified or the
     *                   clip bounds should be modified by ChangeBounds.
     * @see android.view.View#setClipBounds(android.graphics.Rect)
     * @attr ref android.R.styleable#ChangeBounds_resizeClip
     */
    public void setResizeClip(boolean resizeClip) {
        mResizeClip = resizeClip;
    }

    /**
     * Returns true when the ChangeBounds will resize by changing the clip bounds during the
     * view animation or false when bounds are changed. The default value is false.
     *
     * @return true when the ChangeBounds will resize by changing the clip bounds during the
     * view animation or false when bounds are changed. The default value is false.
     * @attr ref android.R.styleable#ChangeBounds_resizeClip
     */
    public boolean getResizeClip() {
        return mResizeClip;
    }

    /**
     * Setting this flag tells ChangeBounds to track the before/after parent
     * of every view using this transition. The flag is not enabled by
     * default because it requires the parent instances to be the same
     * in the two scenes or else all parents must use ids to allow
     * the transition to determine which parents are the same.
     *
     * @param reparent true if the transition should track the parent
     * container of target views and animate parent changes.
     * @deprecated Use {@link android.transition.ChangeTransform} to handle
     * transitions between different parents.
     */
    @Deprecated
    public void setReparent(boolean reparent) {
        mReparent = reparent;
    }

    private void captureValues(TransitionValues values) {
        View view = values.view;

        if (view.isLaidOut() || view.getWidth() != 0 || view.getHeight() != 0) {
            values.values.put(PROPNAME_BOUNDS, new Rect(view.getLeft(), view.getTop(),
                    view.getRight(), view.getBottom()));
            values.values.put(PROPNAME_PARENT, values.view.getParent());
            if (mReparent) {
                values.view.getLocationInWindow(tempLocation);
                values.values.put(PROPNAME_WINDOW_X, tempLocation[0]);
                values.values.put(PROPNAME_WINDOW_Y, tempLocation[1]);
            }
            if (mResizeClip) {
                values.values.put(PROPNAME_CLIP, view.getClipBounds());
            }
        }
    }

    @Override
    public void captureStartValues(TransitionValues transitionValues) {
        captureValues(transitionValues);
    }

    @Override
    public void captureEndValues(TransitionValues transitionValues) {
        captureValues(transitionValues);
    }

    private boolean parentMatches(View startParent, View endParent) {
        boolean parentMatches = true;
        if (mReparent) {
            TransitionValues endValues = getMatchedTransitionValues(startParent, true);
            if (endValues == null) {
                parentMatches = startParent == endParent;
            } else {
                parentMatches = endParent == endValues.view;
            }
        }
        return parentMatches;
    }

    @Nullable
    @Override
    public Animator createAnimator(@NonNull final ViewGroup sceneRoot,
            @Nullable TransitionValues startValues,
            @Nullable TransitionValues endValues) {
        if (startValues == null || endValues == null) {
            return null;
        }
        Map<String, Object> startParentVals = startValues.values;
        Map<String, Object> endParentVals = endValues.values;
        ViewGroup startParent = (ViewGroup) startParentVals.get(PROPNAME_PARENT);
        ViewGroup endParent = (ViewGroup) endParentVals.get(PROPNAME_PARENT);
        if (startParent == null || endParent == null) {
            return null;
        }
        final View view = endValues.view;
        if (parentMatches(startParent, endParent)) {
            Rect startBounds = (Rect) startValues.values.get(PROPNAME_BOUNDS);
            Rect endBounds = (Rect) endValues.values.get(PROPNAME_BOUNDS);
            final int startLeft = startBounds.left;
            final int endLeft = endBounds.left;
            final int startTop = startBounds.top;
            final int endTop = endBounds.top;
            final int startRight = startBounds.right;
            final int endRight = endBounds.right;
            final int startBottom = startBounds.bottom;
            final int endBottom = endBounds.bottom;
            final int startWidth = startRight - startLeft;
            final int startHeight = startBottom - startTop;
            final int endWidth = endRight - endLeft;
            final int endHeight = endBottom - endTop;
            Rect startClip = (Rect) startValues.values.get(PROPNAME_CLIP);
            Rect endClip = (Rect) endValues.values.get(PROPNAME_CLIP);
            int numChanges = 0;
            if ((startWidth != 0 && startHeight != 0) || (endWidth != 0 && endHeight != 0)) {
                if (startLeft != endLeft || startTop != endTop) ++numChanges;
                if (startRight != endRight || startBottom != endBottom) ++numChanges;
            }
            if ((startClip != null && !startClip.equals(endClip)) ||
                    (startClip == null && endClip != null)) {
                ++numChanges;
            }
            if (numChanges > 0) {
                if (view.getParent() instanceof ViewGroup) {
                    final ViewGroup parent = (ViewGroup) view.getParent();
                    parent.suppressLayout(true);
                    TransitionListener transitionListener = new TransitionListenerAdapter() {
                        boolean mCanceled = false;

                        @Override
                        public void onTransitionCancel(Transition transition) {
                            parent.suppressLayout(false);
                            mCanceled = true;
                        }

                        @Override
                        public void onTransitionEnd(Transition transition) {
                            if (!mCanceled) {
                                parent.suppressLayout(false);
                            }
                            transition.removeListener(this);
                        }

                        @Override
                        public void onTransitionPause(Transition transition) {
                            parent.suppressLayout(false);
                        }

                        @Override
                        public void onTransitionResume(Transition transition) {
                            parent.suppressLayout(true);
                        }
                    };
                    addListener(transitionListener);
                }
                Animator anim;
                if (!mResizeClip) {
                    view.setLeftTopRightBottom(startLeft, startTop, startRight, startBottom);
                    if (numChanges == 2) {
                        if (startWidth == endWidth && startHeight == endHeight) {
                            Path topLeftPath = getPathMotion().getPath(startLeft, startTop, endLeft,
                                    endTop);
                            anim = ObjectAnimator.ofObject(view, POSITION_PROPERTY, null,
                                    topLeftPath);
                        } else {
                            final ViewBounds viewBounds = new ViewBounds(view);
                            Path topLeftPath = getPathMotion().getPath(startLeft, startTop,
                                    endLeft, endTop);
                            ObjectAnimator topLeftAnimator = ObjectAnimator
                                    .ofObject(viewBounds, TOP_LEFT_PROPERTY, null, topLeftPath);

                            Path bottomRightPath = getPathMotion().getPath(startRight, startBottom,
                                    endRight, endBottom);
                            ObjectAnimator bottomRightAnimator = ObjectAnimator.ofObject(viewBounds,
                                    BOTTOM_RIGHT_PROPERTY, null, bottomRightPath);
                            AnimatorSet set = new AnimatorSet();
                            set.playTogether(topLeftAnimator, bottomRightAnimator);
                            anim = set;
                            set.addListener(new AnimatorListenerAdapter() {
                                // We need a strong reference to viewBounds until the
                                // animator ends.
                                private ViewBounds mViewBounds = viewBounds;
                            });
                        }
                    } else if (startLeft != endLeft || startTop != endTop) {
                        Path topLeftPath = getPathMotion().getPath(startLeft, startTop,
                                endLeft, endTop);
                        anim = ObjectAnimator.ofObject(view, TOP_LEFT_ONLY_PROPERTY, null,
                                topLeftPath);
                    } else {
                        Path bottomRight = getPathMotion().getPath(startRight, startBottom,
                                endRight, endBottom);
                        anim = ObjectAnimator.ofObject(view, BOTTOM_RIGHT_ONLY_PROPERTY, null,
                                bottomRight);
                    }
                } else {
                    int maxWidth = Math.max(startWidth, endWidth);
                    int maxHeight = Math.max(startHeight, endHeight);

                    view.setLeftTopRightBottom(startLeft, startTop, startLeft + maxWidth,
                            startTop + maxHeight);

                    ObjectAnimator positionAnimator = null;
                    if (startLeft != endLeft || startTop != endTop) {
                        Path topLeftPath = getPathMotion().getPath(startLeft, startTop, endLeft,
                                endTop);
                        positionAnimator = ObjectAnimator.ofObject(view, POSITION_PROPERTY, null,
                                topLeftPath);
                    }
                    final Rect finalClip = endClip;
                    if (startClip == null) {
                        startClip = new Rect(0, 0, startWidth, startHeight);
                    }
                    if (endClip == null) {
                        endClip = new Rect(0, 0, endWidth, endHeight);
                    }
                    ObjectAnimator clipAnimator = null;
                    if (!startClip.equals(endClip)) {
                        view.setClipBounds(startClip);
                        clipAnimator = ObjectAnimator.ofObject(view, "clipBounds", sRectEvaluator,
                                startClip, endClip);
                        clipAnimator.addListener(new AnimatorListenerAdapter() {
                            private boolean mIsCanceled;

                            @Override
                            public void onAnimationCancel(Animator animation) {
                                mIsCanceled = true;
                            }

                            @Override
                            public void onAnimationEnd(Animator animation) {
                                if (!mIsCanceled) {
                                    view.setClipBounds(finalClip);
                                    view.setLeftTopRightBottom(endLeft, endTop, endRight,
                                            endBottom);
                                }
                            }
                        });
                    }
                    anim = TransitionUtils.mergeAnimators(positionAnimator,
                            clipAnimator);
                }
                return anim;
            }
        } else {
            sceneRoot.getLocationInWindow(tempLocation);
            int startX = (Integer) startValues.values.get(PROPNAME_WINDOW_X) - tempLocation[0];
            int startY = (Integer) startValues.values.get(PROPNAME_WINDOW_Y) - tempLocation[1];
            int endX = (Integer) endValues.values.get(PROPNAME_WINDOW_X) - tempLocation[0];
            int endY = (Integer) endValues.values.get(PROPNAME_WINDOW_Y) - tempLocation[1];
            // TODO: also handle size changes: check bounds and animate size changes
            if (startX != endX || startY != endY) {
                final int width = view.getWidth();
                final int height = view.getHeight();
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                view.draw(canvas);
                final BitmapDrawable drawable = new BitmapDrawable(bitmap);
                drawable.setBounds(startX, startY, startX + width, startY + height);
                final float transitionAlpha = view.getTransitionAlpha();
                view.setTransitionAlpha(0);
                sceneRoot.getOverlay().add(drawable);
                Path topLeftPath = getPathMotion().getPath(startX, startY, endX, endY);
                PropertyValuesHolder origin = PropertyValuesHolder.ofObject(
                        DRAWABLE_ORIGIN_PROPERTY, null, topLeftPath);
                ObjectAnimator anim = ObjectAnimator.ofPropertyValuesHolder(drawable, origin);
                anim.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        sceneRoot.getOverlay().remove(drawable);
                        view.setTransitionAlpha(transitionAlpha);
                    }
                });
                return anim;
            }
        }
        return null;
    }

    private static class ViewBounds {
        private int mLeft;
        private int mTop;
        private int mRight;
        private int mBottom;
        private View mView;
        private int mTopLeftCalls;
        private int mBottomRightCalls;

        public ViewBounds(View view) {
            mView = view;
        }

        public void setTopLeft(PointF topLeft) {
            mLeft = Math.round(topLeft.x);
            mTop = Math.round(topLeft.y);
            mTopLeftCalls++;
            if (mTopLeftCalls == mBottomRightCalls) {
                setLeftTopRightBottom();
            }
        }

        public void setBottomRight(PointF bottomRight) {
            mRight = Math.round(bottomRight.x);
            mBottom = Math.round(bottomRight.y);
            mBottomRightCalls++;
            if (mTopLeftCalls == mBottomRightCalls) {
                setLeftTopRightBottom();
            }
        }

        private void setLeftTopRightBottom() {
            mView.setLeftTopRightBottom(mLeft, mTop, mRight, mBottom);
            mTopLeftCalls = 0;
            mBottomRightCalls = 0;
        }
    }
}
