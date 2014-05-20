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
 * limitations under the License.
 */
package android.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.ResultReceiver;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.util.ArrayMap;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroupOverlay;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.Collection;

/**
 * This ActivityTransitionCoordinator is created by the Activity to manage
 * the enter scene and shared element transfer into the Scene, either during
 * launch of an Activity or returning from a launched Activity.
 */
class EnterTransitionCoordinator extends ActivityTransitionCoordinator {
    private static final String TAG = "EnterTransitionCoordinator";

    private static final long MAX_WAIT_MS = 1500;

    private boolean mSharedElementTransitionStarted;
    private Activity mActivity;
    private boolean mHasStopped;
    private Handler mHandler;
    private boolean mIsCanceled;
    private boolean mIsReturning;

    public EnterTransitionCoordinator(Activity activity, ResultReceiver resultReceiver,
            ArrayList<String> sharedElementNames,
            ArrayList<String> acceptedNames, ArrayList<String> mappedNames) {
        super(activity.getWindow(), sharedElementNames, acceptedNames, mappedNames,
                getListener(activity, acceptedNames));
        mActivity = activity;
        mIsReturning = acceptedNames != null;
        setResultReceiver(resultReceiver);
        prepareEnter();
        Bundle resultReceiverBundle = new Bundle();
        resultReceiverBundle.putParcelable(KEY_REMOTE_RECEIVER, this);
        mResultReceiver.send(MSG_SET_REMOTE_RECEIVER, resultReceiverBundle);
        if (mIsReturning) {
            mHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    cancel();
                }
            };
            mHandler.sendEmptyMessageDelayed(MSG_CANCEL, MAX_WAIT_MS);
        }
    }

    private static SharedElementListener getListener(Activity activity,
            ArrayList<String> acceptedNames) {
        boolean isReturning = acceptedNames != null;
        return isReturning ? activity.mExitTransitionListener : activity.mEnterTransitionListener;
    }

    @Override
    protected void onReceiveResult(int resultCode, Bundle resultData) {
        switch (resultCode) {
            case MSG_TAKE_SHARED_ELEMENTS:
                if (!mIsCanceled) {
                    if (mHandler != null) {
                        mHandler.removeMessages(MSG_CANCEL);
                    }
                    onTakeSharedElements(resultData);
                }
                break;
            case MSG_EXIT_TRANSITION_COMPLETE:
                if (!mIsCanceled) {
                    if (!mSharedElementTransitionStarted) {
                        send(resultCode, resultData);
                    } else {
                        onRemoteExitTransitionComplete();
                    }
                }
                break;
            case MSG_CANCEL:
                cancel();
                break;
        }
    }

    private void cancel() {
        if (!mIsCanceled) {
            mIsCanceled = true;
            if (getViewsTransition() == null) {
                setViewVisibility(mSharedElements, View.VISIBLE);
            } else {
                mTransitioningViews.addAll(mSharedElements);
            }
            mSharedElementNames.clear();
            mSharedElements.clear();
            mAllSharedElementNames.clear();
            onTakeSharedElements(null);
            onRemoteExitTransitionComplete();
        }
    }

    public boolean isReturning() {
        return mIsReturning;
    }

    protected void prepareEnter() {
        setViewVisibility(mSharedElements, View.INVISIBLE);
        if (getViewsTransition() != null) {
            setViewVisibility(mTransitioningViews, View.INVISIBLE);
        }
        mActivity.overridePendingTransition(0, 0);
        if (!mIsReturning) {
            mActivity.convertToTranslucent(null, null);
            Drawable background = getDecor().getBackground();
            if (background != null) {
                getWindow().setBackgroundDrawable(null);
                background = background.mutate();
                background.setAlpha(0);
                getWindow().setBackgroundDrawable(background);
            }
        } else {
            mActivity = null; // all done with it now.
        }
    }

    @Override
    protected Transition getViewsTransition() {
        if (mIsReturning) {
            return getWindow().getExitTransition();
        } else {
            return getWindow().getEnterTransition();
        }
    }

    protected Transition getSharedElementTransition() {
        if (mIsReturning) {
            return getWindow().getSharedElementExitTransition();
        } else {
            return getWindow().getSharedElementEnterTransition();
        }
    }

    protected void onTakeSharedElements(Bundle sharedElementState) {
        setEpicenter();
        // Remove rejected shared elements
        ArrayList<String> rejectedNames = new ArrayList<String>(mAllSharedElementNames);
        rejectedNames.removeAll(mSharedElementNames);
        ArrayList<View> rejectedSnapshots = createSnapshots(sharedElementState, rejectedNames);
        mListener.handleRejectedSharedElements(rejectedSnapshots);
        startRejectedAnimations(rejectedSnapshots);

        // Now start shared element transition
        ArrayList<View> sharedElementSnapshots = createSnapshots(sharedElementState,
                mSharedElementNames);
        setViewVisibility(mSharedElements, View.VISIBLE);
        ArrayMap<ImageView, Pair<ImageView.ScaleType, Matrix>> originalImageViewState =
                setSharedElementState(sharedElementState, sharedElementSnapshots);

        boolean startEnterTransition = allowOverlappingTransitions();
        boolean startSharedElementTransition = true;
        Transition transition = beginTransition(startEnterTransition, startSharedElementTransition);

        if (startEnterTransition) {
            startEnterTransition(transition);
        }

        setOriginalImageViewState(originalImageViewState);

        if (mResultReceiver != null) {
            mResultReceiver.send(MSG_HIDE_SHARED_ELEMENTS, null);
        }
        mResultReceiver = null; // all done sending messages.
    }

    private Transition beginTransition(boolean startEnterTransition,
            boolean startSharedElementTransition) {
        Transition sharedElementTransition = null;
        if (startSharedElementTransition && !mSharedElementNames.isEmpty()) {
            sharedElementTransition = configureTransition(getSharedElementTransition());
        }
        Transition viewsTransition = null;
        if (startEnterTransition && !mTransitioningViews.isEmpty()) {
            viewsTransition = configureTransition(getViewsTransition());
            viewsTransition = addTargets(viewsTransition, mTransitioningViews);
        }

        Transition transition = mergeTransitions(sharedElementTransition, viewsTransition);
        if (transition != null) {
            TransitionManager.beginDelayedTransition(getDecor(), transition);
            if (startSharedElementTransition && !mSharedElementNames.isEmpty()) {
                mSharedElements.get(0).invalidate();
            } else if (startEnterTransition && !mTransitioningViews.isEmpty()) {
                mTransitioningViews.get(0).invalidate();
            }
        }
        return transition;
    }

    private void startEnterTransition(Transition transition) {
        setViewVisibility(mTransitioningViews, View.VISIBLE);
        if (!mIsReturning) {
            Drawable background = getDecor().getBackground();
            if (background != null) {
                background = background.mutate();
                ObjectAnimator animator = ObjectAnimator.ofInt(background, "alpha", 255);
                animator.setDuration(FADE_BACKGROUND_DURATION_MS);
                animator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        makeOpaque();
                    }
                });
                animator.start();
            } else if (transition != null) {
                transition.addListener(new Transition.TransitionListenerAdapter() {
                    @Override
                    public void onTransitionEnd(Transition transition) {
                        transition.removeListener(this);
                        makeOpaque();
                    }
                });
            } else {
                makeOpaque();
            }
        }
    }

    public void stop() {
        mHasStopped = true;
        mActivity = null;
        mIsCanceled = true;
        mResultReceiver = null;
    }

    private void makeOpaque() {
        if (!mHasStopped) {
            mActivity.convertFromTranslucent();
            mActivity = null;
        }
    }

    private boolean allowOverlappingTransitions() {
        return mIsReturning ? getWindow().getAllowExitTransitionOverlap()
                : getWindow().getAllowEnterTransitionOverlap();
    }

    private void startRejectedAnimations(final ArrayList<View> rejectedSnapshots) {
        if (rejectedSnapshots == null || rejectedSnapshots.isEmpty()) {
            return;
        }
        ViewGroupOverlay overlay = getDecor().getOverlay();
        ObjectAnimator animator = null;
        int numRejected = rejectedSnapshots.size();
        for (int i = 0; i < numRejected; i++) {
            View snapshot = rejectedSnapshots.get(i);
            overlay.add(snapshot);
            animator = ObjectAnimator.ofFloat(snapshot, View.ALPHA, 1, 0);
            animator.start();
        }
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                ViewGroupOverlay overlay = getDecor().getOverlay();
                int numRejected = rejectedSnapshots.size();
                for (int i = 0; i < numRejected; i++) {
                    overlay.remove(rejectedSnapshots.get(i));
                }
            }
        });
    }

    protected void onRemoteExitTransitionComplete() {
        if (!allowOverlappingTransitions()) {
            boolean startEnterTransition = true;
            boolean startSharedElementTransition = false;
            Transition transition = beginTransition(startEnterTransition,
                    startSharedElementTransition);
            startEnterTransition(transition);
        }
    }

    private ArrayList<View> createSnapshots(Bundle state, Collection<String> names) {
        int numSharedElements = names.size();
        if (numSharedElements == 0) {
            return null;
        }
        ArrayList<View> snapshots = new ArrayList<View>(numSharedElements);
        Context context = getWindow().getContext();
        int[] parentLoc = new int[2];
        getDecor().getLocationOnScreen(parentLoc);
        for (String name: names) {
            Bundle sharedElementBundle = state.getBundle(name);
            if (sharedElementBundle != null) {
                Bitmap bitmap = sharedElementBundle.getParcelable(KEY_BITMAP);
                View snapshot = new View(context);
                Resources resources = getWindow().getContext().getResources();
                snapshot.setBackground(new BitmapDrawable(resources, bitmap));
                snapshot.setViewName(name);
                setSharedElementState(snapshot, name, state, parentLoc);
                snapshots.add(snapshot);
            }
        }
        return snapshots;
    }

    private static void setSharedElementState(View view, String name, Bundle transitionArgs,
            int[] parentLoc) {
        Bundle sharedElementBundle = transitionArgs.getBundle(name);
        if (sharedElementBundle == null) {
            return;
        }

        if (view instanceof ImageView) {
            int scaleTypeInt = sharedElementBundle.getInt(KEY_SCALE_TYPE, -1);
            if (scaleTypeInt >= 0) {
                ImageView imageView = (ImageView) view;
                ImageView.ScaleType scaleType = SCALE_TYPE_VALUES[scaleTypeInt];
                imageView.setScaleType(scaleType);
                if (scaleType == ImageView.ScaleType.MATRIX) {
                    float[] matrixValues = sharedElementBundle.getFloatArray(KEY_IMAGE_MATRIX);
                    Matrix matrix = new Matrix();
                    matrix.setValues(matrixValues);
                    imageView.setImageMatrix(matrix);
                }
            }
        }

        float z = sharedElementBundle.getFloat(KEY_TRANSLATION_Z);
        view.setTranslationZ(z);

        int x = sharedElementBundle.getInt(KEY_SCREEN_X);
        int y = sharedElementBundle.getInt(KEY_SCREEN_Y);
        int width = sharedElementBundle.getInt(KEY_WIDTH);
        int height = sharedElementBundle.getInt(KEY_HEIGHT);

        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY);
        view.measure(widthSpec, heightSpec);

        int left = x - parentLoc[0];
        int top = y - parentLoc[1];
        int right = left + width;
        int bottom = top + height;
        view.layout(left, top, right, bottom);
    }

    private ArrayMap<ImageView, Pair<ImageView.ScaleType, Matrix>> setSharedElementState(
            Bundle sharedElementState, final ArrayList<View> snapshots) {
        ArrayMap<ImageView, Pair<ImageView.ScaleType, Matrix>> originalImageState =
                new ArrayMap<ImageView, Pair<ImageView.ScaleType, Matrix>>();
        if (sharedElementState != null) {
            int[] tempLoc = new int[2];
            for (int i = 0; i < mSharedElementNames.size(); i++) {
                View sharedElement = mSharedElements.get(i);
                String name = mSharedElementNames.get(i);
                Pair<ImageView.ScaleType, Matrix> originalState = getOldImageState(sharedElement,
                        name, sharedElementState);
                if (originalState != null) {
                    originalImageState.put((ImageView) sharedElement, originalState);
                }
                View parent = (View) sharedElement.getParent();
                parent.getLocationOnScreen(tempLoc);
                setSharedElementState(sharedElement, name, sharedElementState, tempLoc);
                sharedElement.requestLayout();
            }
        }
        mListener.setSharedElementStart(mSharedElementNames, mSharedElements, snapshots);

        getDecor().getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        getDecor().getViewTreeObserver().removeOnPreDrawListener(this);
                        mListener.setSharedElementEnd(mSharedElementNames, mSharedElements,
                                snapshots);
                        mSharedElementTransitionStarted = true;
                        return true;
                    }
                }
        );
        return originalImageState;
    }

    private static Pair<ImageView.ScaleType, Matrix> getOldImageState(View view, String name,
            Bundle transitionArgs) {
        if (!(view instanceof ImageView)) {
            return null;
        }
        Bundle bundle = transitionArgs.getBundle(name);
        if (bundle == null) {
            return null;
        }
        int scaleTypeInt = bundle.getInt(KEY_SCALE_TYPE, -1);
        if (scaleTypeInt < 0) {
            return null;
        }

        ImageView imageView = (ImageView) view;
        ImageView.ScaleType originalScaleType = imageView.getScaleType();

        Matrix originalMatrix = null;
        if (originalScaleType == ImageView.ScaleType.MATRIX) {
            originalMatrix = new Matrix(imageView.getImageMatrix());
        }

        return Pair.create(originalScaleType, originalMatrix);
    }

    private static void setOriginalImageViewState(
            ArrayMap<ImageView, Pair<ImageView.ScaleType, Matrix>> originalState) {
        for (int i = 0; i < originalState.size(); i++) {
            ImageView imageView = originalState.keyAt(i);
            Pair<ImageView.ScaleType, Matrix> state = originalState.valueAt(i);
            imageView.setScaleType(state.first);
            imageView.setImageMatrix(state.second);
        }
    }

}
