/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.wm;

import android.app.ActivityOptions;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.os.Debug;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.util.Slog;
import android.view.WindowManager;
import android.view.WindowManagerPolicy;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.ScaleAnimation;

import com.android.internal.util.DumpUtils.Dump;
import com.android.server.AttributeCache;
import com.android.server.wm.WindowManagerService.H;

import java.io.PrintWriter;

import static android.view.WindowManagerPolicy.TRANSIT_NONE;
import static android.view.WindowManagerPolicy.TRANSIT_UNSET;

// State management of app transitions.  When we are preparing for a
// transition, mNextAppTransition will be the kind of transition to
// perform or TRANSIT_NONE if we are not waiting.  If we are waiting,
// mOpeningApps and mClosingApps are the lists of tokens that will be
// made visible or hidden at the next transition.
public class AppTransition implements Dump {
    private static final String TAG = "AppTransition";
    private static final float THUMBNAIL_ANIMATION_DECELERATE_FACTOR = 1.5f;
    private static final boolean DEBUG_APP_TRANSITIONS = WindowManagerService.DEBUG_APP_TRANSITIONS;
    private static final boolean DEBUG_ANIM = WindowManagerService.DEBUG_APP_TRANSITIONS;

    final Context mContext;
    final Handler mH;

    int mNextAppTransition = TRANSIT_UNSET;
    int mNextAppTransitionType = ActivityOptions.ANIM_NONE;
    String mNextAppTransitionPackage;
    Bitmap mNextAppTransitionThumbnail;
    // Used for thumbnail transitions. True if we're scaling up, false if scaling down
    boolean mNextAppTransitionScaleUp;
    IRemoteCallback mNextAppTransitionCallback;
    int mNextAppTransitionEnter;
    int mNextAppTransitionExit;
    int mNextAppTransitionStartX;
    int mNextAppTransitionStartY;
    int mNextAppTransitionStartWidth;
    int mNextAppTransitionStartHeight;
    boolean mAppTransitionReady = false;
    boolean mAppTransitionRunning = false;
    boolean mAppTransitionTimeout = false;

    final int mConfigShortAnimTime;
    final Interpolator mInterpolator;

    AppTransition(Context context, Handler h) {
        mContext = context;
        mH = h;
        mConfigShortAnimTime = context.getResources().getInteger(
                com.android.internal.R.integer.config_shortAnimTime);
        mInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.decelerate_quad);
    }

    boolean isTransitionSet() {
        return mNextAppTransition != TRANSIT_UNSET;
    }

    boolean isTransitionNone() {
        return mNextAppTransition == TRANSIT_NONE;
    }

    boolean isTransitionEqual(int transit) {
        return mNextAppTransition == transit;
    }

    int getAppTransition() {
        return mNextAppTransition; 
     }

    void setAppTransition(int transit) {
        mNextAppTransition = transit;
    }

    boolean isReady() {
        return mAppTransitionReady;
    }

    void setReady() {
        mAppTransitionReady = true;
    }

    boolean isRunning() {
        return mAppTransitionRunning;
    }

    void setRunning(boolean running) {
        mAppTransitionRunning = running;
    }

    boolean isTimeout() {
        return mAppTransitionTimeout;
    }

    void setTimeout(boolean timeout) {
        mAppTransitionTimeout = timeout;
    }

    Bitmap getNextAppTransitionThumbnail() {
        return mNextAppTransitionThumbnail;
    }

    void getStartingPoint(Point outPoint) {
        outPoint.x = mNextAppTransitionStartX;
        outPoint.y = mNextAppTransitionStartY;
    }

    int getType() {
        return mNextAppTransitionType;
    }

    void prepare() {
        mAppTransitionReady = false;
        mAppTransitionTimeout = false;
    }

    void goodToGo() {
        mNextAppTransition = WindowManagerPolicy.TRANSIT_UNSET;
        mAppTransitionReady = false;
        mAppTransitionRunning = true;
        mAppTransitionTimeout = false;
    }

    void clear() {
        mNextAppTransitionType = ActivityOptions.ANIM_NONE;
        mNextAppTransitionPackage = null;
        mNextAppTransitionThumbnail = null;
    }

    private AttributeCache.Entry getCachedAnimations(WindowManager.LayoutParams lp) {
        if (DEBUG_ANIM) Slog.v(TAG, "Loading animations: layout params pkg="
                + (lp != null ? lp.packageName : null)
                + " resId=0x" + (lp != null ? Integer.toHexString(lp.windowAnimations) : null));
        if (lp != null && lp.windowAnimations != 0) {
            // If this is a system resource, don't try to load it from the
            // application resources.  It is nice to avoid loading application
            // resources if we can.
            String packageName = lp.packageName != null ? lp.packageName : "android";
            int resId = lp.windowAnimations;
            if ((resId&0xFF000000) == 0x01000000) {
                packageName = "android";
            }
            if (DEBUG_ANIM) Slog.v(TAG, "Loading animations: picked package="
                    + packageName);
            return AttributeCache.instance().get(packageName, resId,
                    com.android.internal.R.styleable.WindowAnimation);
        }
        return null;
    }

    private AttributeCache.Entry getCachedAnimations(String packageName, int resId) {
        if (DEBUG_ANIM) Slog.v(TAG, "Loading animations: package="
                + packageName + " resId=0x" + Integer.toHexString(resId));
        if (packageName != null) {
            if ((resId&0xFF000000) == 0x01000000) {
                packageName = "android";
            }
            if (DEBUG_ANIM) Slog.v(TAG, "Loading animations: picked package="
                    + packageName);
            return AttributeCache.instance().get(packageName, resId,
                    com.android.internal.R.styleable.WindowAnimation);
        }
        return null;
    }

    Animation loadAnimation(WindowManager.LayoutParams lp, int animAttr) {
        int anim = 0;
        Context context = mContext;
        if (animAttr >= 0) {
            AttributeCache.Entry ent = getCachedAnimations(lp);
            if (ent != null) {
                context = ent.context;
                anim = ent.array.getResourceId(animAttr, 0);
            }
        }
        if (anim != 0) {
            return AnimationUtils.loadAnimation(context, anim);
        }
        return null;
    }

    private Animation loadAnimation(String packageName, int resId) {
        int anim = 0;
        Context context = mContext;
        if (resId >= 0) {
            AttributeCache.Entry ent = getCachedAnimations(packageName, resId);
            if (ent != null) {
                context = ent.context;
                anim = resId;
            }
        }
        if (anim != 0) {
            return AnimationUtils.loadAnimation(context, anim);
        }
        return null;
    }

    private Animation createExitAnimationLocked(int transit, int duration) {
        if (transit == WindowManagerPolicy.TRANSIT_WALLPAPER_INTRA_OPEN ||
                transit == WindowManagerPolicy.TRANSIT_WALLPAPER_INTRA_CLOSE) {
            // If we are on top of the wallpaper, we need an animation that
            // correctly handles the wallpaper staying static behind all of
            // the animated elements.  To do this, will just have the existing
            // element fade out.
            Animation a = new AlphaAnimation(1, 0);
            a.setDetachWallpaper(true);
            a.setDuration(duration);
            return a;
        }
        // For normal animations, the exiting element just holds in place.
        Animation a = new AlphaAnimation(1, 1);
        a.setDuration(duration);
        return a;
    }

    /**
     * Compute the pivot point for an animation that is scaling from a small
     * rect on screen to a larger rect.  The pivot point varies depending on
     * the distance between the inner and outer edges on both sides.  This
     * function computes the pivot point for one dimension.
     * @param startPos  Offset from left/top edge of outer rectangle to
     * left/top edge of inner rectangle.
     * @param finalScale The scaling factor between the size of the outer
     * and inner rectangles.
     */
    private static float computePivot(int startPos, float finalScale) {
        final float denom = finalScale-1;
        if (Math.abs(denom) < .0001f) {
            return startPos;
        }
        return -startPos / denom;
    }

    private Animation createScaleUpAnimationLocked(int transit, boolean enter,
                                                   int appWidth, int appHeight) {
        Animation a = null;
        // Pick the desired duration.  If this is an inter-activity transition,
        // it  is the standard duration for that.  Otherwise we use the longer
        // task transition duration.
        int duration;
        switch (transit) {
            case WindowManagerPolicy.TRANSIT_ACTIVITY_OPEN:
            case WindowManagerPolicy.TRANSIT_ACTIVITY_CLOSE:
                duration = mContext.getResources().getInteger(
                        com.android.internal.R.integer.config_shortAnimTime);
                break;
            default:
                duration = 300;
                break;
        }
        if (enter) {
            // Entering app zooms out from the center of the initial rect.
            float scaleW = mNextAppTransitionStartWidth / (float) appWidth;
            float scaleH = mNextAppTransitionStartHeight / (float) appHeight;
            Animation scale = new ScaleAnimation(scaleW, 1, scaleH, 1,
                    computePivot(mNextAppTransitionStartX, scaleW),
                    computePivot(mNextAppTransitionStartY, scaleH));
            scale.setDuration(duration);
            AnimationSet set = new AnimationSet(true);
            Animation alpha = new AlphaAnimation(0, 1);
            scale.setDuration(duration);
            set.addAnimation(scale);
            alpha.setDuration(duration);
            set.addAnimation(alpha);
            set.setDetachWallpaper(true);
            a = set;
        } else {
            a = createExitAnimationLocked(transit, duration);
        }
        a.setFillAfter(true);
        final Interpolator interpolator = AnimationUtils.loadInterpolator(mContext,
                com.android.internal.R.interpolator.decelerate_cubic);
        a.setInterpolator(interpolator);
        a.initialize(appWidth, appHeight, appWidth, appHeight);
        return a;
    }

    Animation createThumbnailAnimationLocked(int transit, boolean enter, boolean thumb,
                                    int appWidth, int appHeight) {
        Animation a;
        final int thumbWidthI = mNextAppTransitionThumbnail.getWidth();
        final float thumbWidth = thumbWidthI > 0 ? thumbWidthI : 1;
        final int thumbHeightI = mNextAppTransitionThumbnail.getHeight();
        final float thumbHeight = thumbHeightI > 0 ? thumbHeightI : 1;
        // Pick the desired duration.  If this is an inter-activity transition,
        // it  is the standard duration for that.  Otherwise we use the longer
        // task transition duration.
        int duration;
        switch (transit) {
            case WindowManagerPolicy.TRANSIT_ACTIVITY_OPEN:
            case WindowManagerPolicy.TRANSIT_ACTIVITY_CLOSE:
                duration = mConfigShortAnimTime;
                break;
            default:
                duration = 250;
                break;
        }
        if (thumb) {
            // Animation for zooming thumbnail from its initial size to
            // filling the screen.
            if (mNextAppTransitionScaleUp) {
                float scaleW = appWidth / thumbWidth;
                float scaleH = appHeight / thumbHeight;

                Animation scale = new ScaleAnimation(1, scaleW, 1, scaleH,
                        computePivot(mNextAppTransitionStartX, 1 / scaleW),
                        computePivot(mNextAppTransitionStartY, 1 / scaleH));
                AnimationSet set = new AnimationSet(true);
                Animation alpha = new AlphaAnimation(1, 0);
                scale.setDuration(duration);
                scale.setInterpolator(
                        new DecelerateInterpolator(THUMBNAIL_ANIMATION_DECELERATE_FACTOR));
                set.addAnimation(scale);
                alpha.setDuration(duration);
                set.addAnimation(alpha);
                set.setFillBefore(true);
                a = set;
            } else {
                float scaleW = appWidth / thumbWidth;
                float scaleH = appHeight / thumbHeight;

                Animation scale = new ScaleAnimation(scaleW, 1, scaleH, 1,
                        computePivot(mNextAppTransitionStartX, 1 / scaleW),
                        computePivot(mNextAppTransitionStartY, 1 / scaleH));
                AnimationSet set = new AnimationSet(true);
                Animation alpha = new AlphaAnimation(1, 1);
                scale.setDuration(duration);
                scale.setInterpolator(
                        new DecelerateInterpolator(THUMBNAIL_ANIMATION_DECELERATE_FACTOR));
                set.addAnimation(scale);
                alpha.setDuration(duration);
                set.addAnimation(alpha);
                set.setFillBefore(true);

                a = set;
            }
        } else if (enter) {
            // Entering app zooms out from the center of the thumbnail.
            if (mNextAppTransitionScaleUp) {
                float scaleW = thumbWidth / appWidth;
                float scaleH = thumbHeight / appHeight;
                Animation scale = new ScaleAnimation(scaleW, 1, scaleH, 1,
                        computePivot(mNextAppTransitionStartX, scaleW),
                        computePivot(mNextAppTransitionStartY, scaleH));
                scale.setDuration(duration);
                scale.setInterpolator(
                        new DecelerateInterpolator(THUMBNAIL_ANIMATION_DECELERATE_FACTOR));
                scale.setFillBefore(true);
                a = scale;
            } else {
                // noop animation
                a = new AlphaAnimation(1, 1);
                a.setDuration(duration);
            }
        } else {
            // Exiting app
            if (mNextAppTransitionScaleUp) {
                if (transit == WindowManagerPolicy.TRANSIT_WALLPAPER_INTRA_OPEN) {
                    // Fade out while bringing up selected activity. This keeps the
                    // current activity from showing through a launching wallpaper
                    // activity.
                    a = new AlphaAnimation(1, 0);
                } else {
                    // noop animation
                    a = new AlphaAnimation(1, 1);
                }
                a.setDuration(duration);
            } else {
                float scaleW = thumbWidth / appWidth;
                float scaleH = thumbHeight / appHeight;
                Animation scale = new ScaleAnimation(1, scaleW, 1, scaleH,
                        computePivot(mNextAppTransitionStartX, scaleW),
                        computePivot(mNextAppTransitionStartY, scaleH));
                scale.setDuration(duration);
                scale.setInterpolator(
                        new DecelerateInterpolator(THUMBNAIL_ANIMATION_DECELERATE_FACTOR));
                scale.setFillBefore(true);
                AnimationSet set = new AnimationSet(true);
                Animation alpha = new AlphaAnimation(1, 0);
                set.addAnimation(scale);
                alpha.setDuration(duration);
                alpha.setInterpolator(new DecelerateInterpolator(
                        THUMBNAIL_ANIMATION_DECELERATE_FACTOR));
                set.addAnimation(alpha);
                set.setFillBefore(true);
                set.setZAdjustment(Animation.ZORDER_TOP);
                a = set;
            }
        }
        a.setFillAfter(true);
        a.setInterpolator(mInterpolator);
        a.initialize(appWidth, appHeight, appWidth, appHeight);
        return a;
    }


    Animation loadAnimation(WindowManager.LayoutParams lp, int transit, boolean enter,
                            int appWidth, int appHeight) {
        Animation a;
        if (mNextAppTransitionType == ActivityOptions.ANIM_CUSTOM) {
            a = loadAnimation(mNextAppTransitionPackage, enter ?
                    mNextAppTransitionEnter : mNextAppTransitionExit);
            if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) Slog.v(TAG,
                    "applyAnimation:"
                    + " anim=" + a + " nextAppTransition=ANIM_CUSTOM"
                    + " transit=" + transit + " isEntrance=" + enter
                    + " Callers=" + Debug.getCallers(3));
        } else if (mNextAppTransitionType == ActivityOptions.ANIM_SCALE_UP) {
            a = createScaleUpAnimationLocked(transit, enter, appWidth, appHeight);
            if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) Slog.v(TAG,
                    "applyAnimation:"
                    + " anim=" + a + " nextAppTransition=ANIM_SCALE_UP"
                    + " transit=" + transit + " isEntrance=" + enter
                    + " Callers=" + Debug.getCallers(3));
        } else if (mNextAppTransitionType == ActivityOptions.ANIM_THUMBNAIL_SCALE_UP ||
                mNextAppTransitionType == ActivityOptions.ANIM_THUMBNAIL_SCALE_DOWN) {
            mNextAppTransitionScaleUp =
                    (mNextAppTransitionType == ActivityOptions.ANIM_THUMBNAIL_SCALE_UP);
            a = createThumbnailAnimationLocked(transit, enter, false, appWidth, appHeight);
            if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) {
                String animName = mNextAppTransitionScaleUp ?
                        "ANIM_THUMBNAIL_SCALE_UP" : "ANIM_THUMBNAIL_SCALE_DOWN";
                Slog.v(TAG, "applyAnimation:"
                        + " anim=" + a + " nextAppTransition=" + animName
                        + " transit=" + transit + " isEntrance=" + enter
                        + " Callers=" + Debug.getCallers(3));
            }
        } else {
            int animAttr = 0;
            switch (transit) {
                case WindowManagerPolicy.TRANSIT_ACTIVITY_OPEN:
                    animAttr = enter
                            ? com.android.internal.R.styleable.WindowAnimation_activityOpenEnterAnimation
                            : com.android.internal.R.styleable.WindowAnimation_activityOpenExitAnimation;
                    break;
                case WindowManagerPolicy.TRANSIT_ACTIVITY_CLOSE:
                    animAttr = enter
                            ? com.android.internal.R.styleable.WindowAnimation_activityCloseEnterAnimation
                            : com.android.internal.R.styleable.WindowAnimation_activityCloseExitAnimation;
                    break;
                case WindowManagerPolicy.TRANSIT_TASK_OPEN:
                    animAttr = enter
                            ? com.android.internal.R.styleable.WindowAnimation_taskOpenEnterAnimation
                            : com.android.internal.R.styleable.WindowAnimation_taskOpenExitAnimation;
                    break;
                case WindowManagerPolicy.TRANSIT_TASK_CLOSE:
                    animAttr = enter
                            ? com.android.internal.R.styleable.WindowAnimation_taskCloseEnterAnimation
                            : com.android.internal.R.styleable.WindowAnimation_taskCloseExitAnimation;
                    break;
                case WindowManagerPolicy.TRANSIT_TASK_TO_FRONT:
                    animAttr = enter
                            ? com.android.internal.R.styleable.WindowAnimation_taskToFrontEnterAnimation
                            : com.android.internal.R.styleable.WindowAnimation_taskToFrontExitAnimation;
                    break;
                case WindowManagerPolicy.TRANSIT_TASK_TO_BACK:
                    animAttr = enter
                            ? com.android.internal.R.styleable.WindowAnimation_taskToBackEnterAnimation
                            : com.android.internal.R.styleable.WindowAnimation_taskToBackExitAnimation;
                    break;
                case WindowManagerPolicy.TRANSIT_WALLPAPER_OPEN:
                    animAttr = enter
                            ? com.android.internal.R.styleable.WindowAnimation_wallpaperOpenEnterAnimation
                            : com.android.internal.R.styleable.WindowAnimation_wallpaperOpenExitAnimation;
                    break;
                case WindowManagerPolicy.TRANSIT_WALLPAPER_CLOSE:
                    animAttr = enter
                            ? com.android.internal.R.styleable.WindowAnimation_wallpaperCloseEnterAnimation
                            : com.android.internal.R.styleable.WindowAnimation_wallpaperCloseExitAnimation;
                    break;
                case WindowManagerPolicy.TRANSIT_WALLPAPER_INTRA_OPEN:
                    animAttr = enter
                            ? com.android.internal.R.styleable.WindowAnimation_wallpaperIntraOpenEnterAnimation
                            : com.android.internal.R.styleable.WindowAnimation_wallpaperIntraOpenExitAnimation;
                    break;
                case WindowManagerPolicy.TRANSIT_WALLPAPER_INTRA_CLOSE:
                    animAttr = enter
                            ? com.android.internal.R.styleable.WindowAnimation_wallpaperIntraCloseEnterAnimation
                            : com.android.internal.R.styleable.WindowAnimation_wallpaperIntraCloseExitAnimation;
                    break;
            }
            a = animAttr != 0 ? loadAnimation(lp, animAttr) : null;
            if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) Slog.v(TAG,
                    "applyAnimation:"
                    + " anim=" + a
                    + " animAttr=0x" + Integer.toHexString(animAttr)
                    + " transit=" + transit + " isEntrance=" + enter
                    + " Callers=" + Debug.getCallers(3));
        }
        return a;
    }

    void postAnimationCallback() {
        if (mNextAppTransitionCallback != null) {
            mH.sendMessage(mH.obtainMessage(H.DO_ANIMATION_CALLBACK, mNextAppTransitionCallback));
            mNextAppTransitionCallback = null;
        }
    }

    void overridePendingAppTransition(String packageName, int enterAnim, int exitAnim,
                                             IRemoteCallback startedCallback) {
        if (isTransitionSet()) {
            mNextAppTransitionType = ActivityOptions.ANIM_CUSTOM;
            mNextAppTransitionPackage = packageName;
            mNextAppTransitionThumbnail = null;
            mNextAppTransitionEnter = enterAnim;
            mNextAppTransitionExit = exitAnim;
            postAnimationCallback();
            mNextAppTransitionCallback = startedCallback;
        } else {
            postAnimationCallback();
        }
    }

    void overridePendingAppTransitionScaleUp(int startX, int startY, int startWidth,
                                                    int startHeight) {
        if (isTransitionSet()) {
            mNextAppTransitionType = ActivityOptions.ANIM_SCALE_UP;
            mNextAppTransitionPackage = null;
            mNextAppTransitionThumbnail = null;
            mNextAppTransitionStartX = startX;
            mNextAppTransitionStartY = startY;
            mNextAppTransitionStartWidth = startWidth;
            mNextAppTransitionStartHeight = startHeight;
            postAnimationCallback();
            mNextAppTransitionCallback = null;
        }
    }

    void overridePendingAppTransitionThumb(Bitmap srcThumb, int startX, int startY,
                                           IRemoteCallback startedCallback, boolean scaleUp) {
        if (isTransitionSet()) {
            mNextAppTransitionType = scaleUp ? ActivityOptions.ANIM_THUMBNAIL_SCALE_UP
                    : ActivityOptions.ANIM_THUMBNAIL_SCALE_DOWN;
            mNextAppTransitionPackage = null;
            mNextAppTransitionThumbnail = srcThumb;
            mNextAppTransitionScaleUp = scaleUp;
            mNextAppTransitionStartX = startX;
            mNextAppTransitionStartY = startY;
            postAnimationCallback();
            mNextAppTransitionCallback = startedCallback;
        } else {
            postAnimationCallback();
        }
    }

    @Override
    public String toString() {
        return "mNextAppTransition=0x" + Integer.toHexString(mNextAppTransition);
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.print(" " + this);
        pw.print(" mAppTransitionReady="); pw.println(mAppTransitionReady);
        pw.print("  mAppTransitionRunning="); pw.print(mAppTransitionRunning);
        pw.print(" mAppTransitionTimeout="); pw.println(mAppTransitionTimeout);
        if (mNextAppTransitionType != ActivityOptions.ANIM_NONE) {
            pw.print("  mNextAppTransitionType="); pw.println(mNextAppTransitionType);
        }
        switch (mNextAppTransitionType) {
            case ActivityOptions.ANIM_CUSTOM:
                pw.print("  mNextAppTransitionPackage=");
                        pw.println(mNextAppTransitionPackage);
                pw.print("  mNextAppTransitionEnter=0x");
                        pw.print(Integer.toHexString(mNextAppTransitionEnter));
                        pw.print(" mNextAppTransitionExit=0x");
                        pw.println(Integer.toHexString(mNextAppTransitionExit));
                break;
            case ActivityOptions.ANIM_SCALE_UP:
                pw.print("  mNextAppTransitionStartX="); pw.print(mNextAppTransitionStartX);
                        pw.print(" mNextAppTransitionStartY=");
                        pw.println(mNextAppTransitionStartY);
                pw.print("  mNextAppTransitionStartWidth=");
                        pw.print(mNextAppTransitionStartWidth);
                        pw.print(" mNextAppTransitionStartHeight=");
                        pw.println(mNextAppTransitionStartHeight);
                break;
            case ActivityOptions.ANIM_THUMBNAIL_SCALE_UP:
            case ActivityOptions.ANIM_THUMBNAIL_SCALE_DOWN:
                pw.print("  mNextAppTransitionThumbnail=");
                        pw.print(mNextAppTransitionThumbnail);
                        pw.print(" mNextAppTransitionStartX=");
                        pw.print(mNextAppTransitionStartX);
                        pw.print(" mNextAppTransitionStartY=");
                        pw.println(mNextAppTransitionStartY);
                pw.print("  mNextAppTransitionScaleUp="); pw.println(mNextAppTransitionScaleUp);
                break;
        }
        if (mNextAppTransitionCallback != null) {
            pw.print("  mNextAppTransitionCallback=");
            pw.println(mNextAppTransitionCallback);
        }
    }
}
