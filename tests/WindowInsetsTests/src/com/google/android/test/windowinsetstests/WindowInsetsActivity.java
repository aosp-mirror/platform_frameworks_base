/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.google.android.test.windowinsetstests;

import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsetsAnimation.Callback.DISPATCH_MODE_STOP;
import static java.lang.Math.max;
import static java.lang.Math.min;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Insets;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.WindowInsetsAnimation.Callback;
import android.view.WindowInsetsAnimationControlListener;
import android.view.WindowInsetsAnimationController;
import android.view.WindowInsetsController;
import android.view.WindowInsetsController.OnControllableInsetsChangedListener;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

import androidx.appcompat.app.AppCompatActivity;

public class WindowInsetsActivity extends AppCompatActivity {

    private View mRoot;

    final ArrayList<Transition> mTransitions = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.window_inset_activity);

        setSupportActionBar(findViewById(R.id.toolbar));

        mRoot = findViewById(R.id.root);
        mRoot.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        mTransitions.add(new Transition(findViewById(R.id.scrollView)));
        mTransitions.add(new Transition(findViewById(R.id.editText)));

        mRoot.setOnTouchListener(new View.OnTouchListener() {
            private final ViewConfiguration mViewConfiguration =
                    ViewConfiguration.get(WindowInsetsActivity.this);
            WindowInsetsAnimationController mAnimationController;
            WindowInsetsAnimationControlListener mCurrentRequest;
            boolean mRequestedController = false;
            float mDown = 0;
            float mCurrent = 0;
            Insets mDownInsets = Insets.NONE;
            boolean mShownAtDown;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                mCurrent = event.getY();
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        mDown = event.getY();
                        mDownInsets = v.getRootWindowInsets().getInsets(ime());
                        mShownAtDown = v.getRootWindowInsets().isVisible(ime());
                        mRequestedController = false;
                        mCurrentRequest = null;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (mAnimationController != null) {
                            updateInset();
                        } else if (Math.abs(mDown - event.getY())
                                > mViewConfiguration.getScaledTouchSlop()
                                && !mRequestedController) {
                            mRequestedController = true;
                            v.getWindowInsetsController().controlWindowInsetsAnimation(ime(),
                                    1000, new LinearInterpolator(), null /* cancellationSignal */,
                                    mCurrentRequest = new WindowInsetsAnimationControlListener() {
                                        @Override
                                        public void onReady(
                                                @NonNull WindowInsetsAnimationController controller,
                                                int types) {
                                            if (mCurrentRequest == this) {
                                                mAnimationController = controller;
                                                updateInset();
                                            } else {
                                                controller.finish(mShownAtDown);
                                            }
                                        }

                                        @Override
                                        public void onFinished(
                                                WindowInsetsAnimationController controller) {
                                            mAnimationController = null;
                                        }

                                        @Override
                                        public void onCancelled(
                                                WindowInsetsAnimationController controller) {
                                            mAnimationController = null;
                                        }
                                    });
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (mAnimationController != null) {
                            boolean isCancel = event.getAction() == MotionEvent.ACTION_CANCEL;
                            mAnimationController.finish(isCancel ? mShownAtDown : !mShownAtDown);
                            mAnimationController = null;
                        }
                        mRequestedController = false;
                        mCurrentRequest = null;
                        break;
                }
                return true;
            }

            private void updateInset() {
                int inset = (int) (mDownInsets.bottom + (mDown - mCurrent));
                final int hidden = mAnimationController.getHiddenStateInsets().bottom;
                final int shown = mAnimationController.getShownStateInsets().bottom;
                final int start = mShownAtDown ? shown : hidden;
                final int end = mShownAtDown ? hidden : shown;
                inset = max(inset, hidden);
                inset = min(inset, shown);
                mAnimationController.setInsetsAndAlpha(
                        Insets.of(0, 0, 0, inset),
                        1f, (inset - start) / (float)(end - start));
            }
        });

        mRoot.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                mRoot.setPadding(insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom());
                return WindowInsets.CONSUMED;
            }
        });

        mRoot.setWindowInsetsAnimationCallback(new Callback(DISPATCH_MODE_STOP) {

            @Override
            public void onPrepare(WindowInsetsAnimation animation) {
                mTransitions.forEach(it -> it.onPrepare(animation));
            }

            @Override
            public WindowInsets onProgress(WindowInsets insets,
                    @NonNull List<WindowInsetsAnimation> runningAnimations) {
                mTransitions.forEach(it -> it.onProgress(insets));
                return insets;
            }

            @Override
            public WindowInsetsAnimation.Bounds onStart(WindowInsetsAnimation animation,
                    WindowInsetsAnimation.Bounds bounds) {
                mTransitions.forEach(Transition::onStart);
                return bounds;
            }

            @Override
            public void onEnd(WindowInsetsAnimation animation) {
                mTransitions.forEach(it -> it.onFinish(animation));
            }
        });

        findViewById(R.id.floating_action_button).setOnClickListener(
                v -> v.getWindowInsetsController().controlWindowInsetsAnimation(ime(), -1,
                new LinearInterpolator(), null /* cancellationSignal */,
                new WindowInsetsAnimationControlListener() {
                    @Override
                    public void onReady(
                            WindowInsetsAnimationController controller,
                            int types) {
                        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
                        anim.setDuration(1500);
                        anim.addUpdateListener(animation
                                -> controller.setInsetsAndAlpha(
                                controller.getShownStateInsets(),
                                (float) animation.getAnimatedValue(),
                                anim.getAnimatedFraction()));
                        anim.addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                super.onAnimationEnd(animation);
                                controller.finish(true);
                            }
                        });
                        anim.start();
                    }

                    @Override
                    public void onCancelled(WindowInsetsAnimationController controller) {
                    }

                    @Override
                    public void onFinished(WindowInsetsAnimationController controller) {
                    }
                }));
    }

    @Override
    public void onResume() {
        super.onResume();
        // TODO: move this to onCreate once setDecorFitsSystemWindows can be safely called there.
        getWindow().getDecorView().post(() -> getWindow().setDecorFitsSystemWindows(false));
    }

    static class Transition {
        private int mEndBottom;
        private int mStartBottom;
        private final View mView;
        private WindowInsetsAnimation mInsetsAnimation;

        Transition(View root) {
            mView = root;
        }

        void onPrepare(WindowInsetsAnimation animation) {
            if ((animation.getTypeMask() & ime()) != 0) {
                mInsetsAnimation = animation;
            }
            mStartBottom = mView.getBottom();
        }

        void onProgress(WindowInsets insets) {
            mView.setY(mStartBottom + (mEndBottom - mStartBottom)
                    * mInsetsAnimation.getInterpolatedFraction()
                    - mView.getHeight());
        }

        void onStart() {
            mEndBottom = mView.getBottom();
        }

        void onFinish(WindowInsetsAnimation animation) {
            if (mInsetsAnimation == animation) {
                mInsetsAnimation = null;
            }
        }
    }

    static class ImeLinearLayout extends LinearLayout {

        public ImeLinearLayout(Context context,
                @Nullable AttributeSet attrs) {
            super(context, attrs);
        }
    }
}
