/*
 * Copyright (C) 2021 The Android Open Source Project
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

package androidx.window.extensions.organizer;

import static android.view.RemoteAnimationTarget.MODE_CLOSING;

import android.app.ActivityThread;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.os.Handler;
import android.provider.Settings;
import android.view.RemoteAnimationTarget;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AnimationUtils;
import android.view.animation.ClipRectAnimation;
import android.view.animation.Interpolator;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;

import androidx.annotation.NonNull;

import com.android.internal.R;
import com.android.internal.policy.AttributeCache;
import com.android.internal.policy.TransitionAnimation;

/** Animation spec for TaskFragment transition. */
class TaskFragmentAnimationSpec {

    private static final String TAG = "TaskFragAnimationSpec";
    private static final int CHANGE_ANIMATION_DURATION = 517;
    private static final int CHANGE_ANIMATION_FADE_DURATION = 82;
    private static final int CHANGE_ANIMATION_FADE_OFFSET = 67;

    private final Context mContext;
    private final TransitionAnimation mTransitionAnimation;
    private final Interpolator mFastOutExtraSlowInInterpolator;
    private float mTransitionAnimationScaleSetting;

    TaskFragmentAnimationSpec(@NonNull Handler handler) {
        mContext = ActivityThread.currentActivityThread().getApplication();
        mTransitionAnimation = new TransitionAnimation(mContext, false /* debug */, TAG);
        // Initialize the AttributeCache for the TransitionAnimation.
        AttributeCache.init(mContext);
        mFastOutExtraSlowInInterpolator = AnimationUtils.loadInterpolator(
                mContext, android.R.interpolator.fast_out_extra_slow_in);

        // The transition animation should be adjusted based on the developer option.
        final ContentResolver resolver = mContext.getContentResolver();
        mTransitionAnimationScaleSetting = Settings.Global.getFloat(resolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                mContext.getResources().getFloat(
                        R.dimen.config_appTransitionAnimationDurationScaleDefault));
        resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE), false,
                new SettingsObserver(handler));
    }

    /** For target that doesn't need to be animated. */
    static Animation createNoopAnimation(@NonNull RemoteAnimationTarget target) {
        // Noop but just keep the target showing/hiding.
        final float alpha = target.mode == MODE_CLOSING ? 0f : 1f;
        return new AlphaAnimation(alpha, alpha);
    }

    /** Animation for target that is opening in a change transition. */
    Animation createChangeBoundsOpenAnimation(@NonNull RemoteAnimationTarget target) {
        final Rect bounds = target.localBounds;
        // The target will be animated in from left or right depends on its position.
        final int startLeft = bounds.left == 0 ? -bounds.width() : bounds.width();

        // The position should be 0-based as we will post translate in
        // TaskFragmentAnimationAdapter#onAnimationUpdate
        final Animation animation = new TranslateAnimation(startLeft, 0, 0, 0);
        animation.setInterpolator(mFastOutExtraSlowInInterpolator);
        animation.setDuration(CHANGE_ANIMATION_DURATION);
        animation.initialize(bounds.width(), bounds.height(), bounds.width(), bounds.height());
        animation.scaleCurrentDuration(mTransitionAnimationScaleSetting);
        return animation;
    }

    /** Animation for target that is closing in a change transition. */
    Animation createChangeBoundsCloseAnimation(@NonNull RemoteAnimationTarget target) {
        final Rect bounds = target.localBounds;
        // The target will be animated out to left or right depends on its position.
        final int endLeft = bounds.left == 0 ? -bounds.width() : bounds.width();

        // The position should be 0-based as we will post translate in
        // TaskFragmentAnimationAdapter#onAnimationUpdate
        final Animation animation = new TranslateAnimation(0, endLeft, 0, 0);
        animation.setInterpolator(mFastOutExtraSlowInInterpolator);
        animation.setDuration(CHANGE_ANIMATION_DURATION);
        animation.initialize(bounds.width(), bounds.height(), bounds.width(), bounds.height());
        animation.scaleCurrentDuration(mTransitionAnimationScaleSetting);
        return animation;
    }

    /**
     * Animation for target that is changing (bounds change) in a change transition.
     * @return the return array always has two elements. The first one is for the start leash, and
     *         the second one is for the end leash.
     */
    Animation[] createChangeBoundsChangeAnimations(@NonNull RemoteAnimationTarget target) {
        final Rect startBounds = target.startBounds;
        final Rect parentBounds = target.taskInfo.configuration.windowConfiguration.getBounds();
        final Rect endBounds = target.localBounds;
        float scaleX = ((float) startBounds.width()) / endBounds.width();
        float scaleY = ((float) startBounds.height()) / endBounds.height();
        // Start leash is a child of the end leash. Reverse the scale so that the start leash won't
        // be scaled up with its parent.
        float startScaleX = 1.f / scaleX;
        float startScaleY = 1.f / scaleY;

        // The start leash will be fade out.
        final AnimationSet startSet = new AnimationSet(true /* shareInterpolator */);
        startSet.setInterpolator(mFastOutExtraSlowInInterpolator);
        final Animation startAlpha = new AlphaAnimation(1f, 0f);
        startAlpha.setDuration(CHANGE_ANIMATION_FADE_DURATION);
        startAlpha.setStartOffset(CHANGE_ANIMATION_FADE_OFFSET);
        startSet.addAnimation(startAlpha);
        final Animation startScale = new ScaleAnimation(startScaleX, startScaleX, startScaleY,
                startScaleY);
        startScale.setDuration(CHANGE_ANIMATION_DURATION);
        startSet.addAnimation(startScale);
        startSet.initialize(startBounds.width(), startBounds.height(), endBounds.width(),
                endBounds.height());
        startSet.scaleCurrentDuration(mTransitionAnimationScaleSetting);

        // The end leash will be moved into the end position while scaling.
        final AnimationSet endSet = new AnimationSet(true /* shareInterpolator */);
        endSet.setInterpolator(mFastOutExtraSlowInInterpolator);
        final Animation endScale = new ScaleAnimation(scaleX, 1, scaleY, 1);
        endScale.setDuration(CHANGE_ANIMATION_DURATION);
        endSet.addAnimation(endScale);
        // The position should be 0-based as we will post translate in
        // TaskFragmentAnimationAdapter#onAnimationUpdate
        final Animation endTranslate = new TranslateAnimation(startBounds.left - endBounds.left, 0,
                0, 0);
        endTranslate.setDuration(CHANGE_ANIMATION_DURATION);
        endSet.addAnimation(endTranslate);
        // The end leash is resizing, we should update the window crop based on the clip rect.
        final Rect startClip = new Rect(startBounds);
        final Rect endClip = new Rect(endBounds);
        startClip.offsetTo(0, 0);
        endClip.offsetTo(0, 0);
        final Animation clipAnim = new ClipRectAnimation(startClip, endClip);
        clipAnim.setDuration(CHANGE_ANIMATION_DURATION);
        endSet.addAnimation(clipAnim);
        endSet.initialize(startBounds.width(), startBounds.height(), parentBounds.width(),
                parentBounds.height());
        endSet.scaleCurrentDuration(mTransitionAnimationScaleSetting);

        return new Animation[]{startSet, endSet};
    }

    Animation loadOpenAnimation(boolean isEnter) {
        // TODO(b/196173550) We need to customize the animation to handle two open window as one.
        return mTransitionAnimation.loadDefaultAnimationAttr(isEnter
                ? R.styleable.WindowAnimation_activityOpenEnterAnimation
                : R.styleable.WindowAnimation_activityOpenExitAnimation);
    }

    Animation loadCloseAnimation(boolean isEnter) {
        // TODO(b/196173550) We need to customize the animation to handle two open window as one.
        return mTransitionAnimation.loadDefaultAnimationAttr(isEnter
                ? R.styleable.WindowAnimation_activityCloseEnterAnimation
                : R.styleable.WindowAnimation_activityCloseExitAnimation);
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(@NonNull Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            mTransitionAnimationScaleSetting = Settings.Global.getFloat(
                    mContext.getContentResolver(), Settings.Global.TRANSITION_ANIMATION_SCALE,
                    mTransitionAnimationScaleSetting);
        }
    }
}
