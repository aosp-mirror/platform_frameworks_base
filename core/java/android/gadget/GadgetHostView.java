/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.gadget;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.util.Config;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.widget.ViewAnimator;

/**
 * Provides the glue to show gadget views. This class offers automatic animation
 * between updates, and will try recycling old views for each incoming
 * {@link RemoteViews}.
 */
public class GadgetHostView extends ViewAnimator implements Animation.AnimationListener {
    static final String TAG = "GadgetHostView";
    static final boolean LOGD = Config.LOGD || true;

    // When we're inflating the initialLayout for a gadget, we only allow
    // views that are allowed in RemoteViews.
    static final LayoutInflater.Filter sInflaterFilter = new LayoutInflater.Filter() {
        public boolean onLoadClass(Class clazz) {
            return clazz.isAnnotationPresent(RemoteViews.RemoteView.class);
        }
    };
    
    Context mLocalContext;
    
    int mGadgetId;
    GadgetProviderInfo mInfo;
    
    View mActiveView = null;
    View mStaleView = null;
    
    int mActiveLayoutId = -1;
    int mStaleLayoutId = -1;
    
    /**
     * Last set of {@link RemoteViews} applied to {@link #mActiveView}
     */
    RemoteViews mActiveActions = null;
    
    /**
     * Flag indicating that {@link #mActiveActions} has been applied to
     * {@link #mStaleView}, meaning it's readyto recycle.
     */
    boolean mStalePrepared = false;
    
    /**
     * Create a host view.  Uses default fade animations.
     */
    public GadgetHostView(Context context) {
        this(context, android.R.anim.fade_in, android.R.anim.fade_out);
    }

    /**
     * Create a host view. Uses specified animations when pushing
     * {@link #updateGadget(RemoteViews)}.
     * 
     * @param animationIn Resource ID of in animation to use
     * @param animationOut Resource ID of out animation to use
     */
    public GadgetHostView(Context context, int animationIn, int animationOut) {
        super(context);
        mLocalContext = context;
        
        // Prepare our default transition animations
        setAnimateFirstView(true);
        setInAnimation(context, animationIn);
        setOutAnimation(context, animationOut);

        // Watch for animation events to prepare recycling
        Animation inAnimation = getInAnimation();
        if (inAnimation != null) {
            inAnimation.setAnimationListener(this);
        }
    }
    
    /**
     * Set the gadget that will be displayed by this view.
     */
    public void setGadget(int gadgetId, GadgetProviderInfo info) {
        if (mInfo != null) {
            // TODO: remove the old view, or whatever
        }
        mGadgetId = gadgetId;
        mInfo = info;
    }
    
    public int getGadgetId() {
        return mGadgetId;
    }
    
    public GadgetProviderInfo getGadgetInfo() {
        return mInfo;
    }

    public void onAnimationEnd(Animation animation) {
        // When our transition animation finishes, we should try bringing our
        // newly-stale view up to the current view.
        if (mActiveActions != null &&
                mStaleLayoutId == mActiveActions.getLayoutId()) {
            if (LOGD) Log.d(TAG, "after animation, layoutId matched so we're recycling old view");
            mActiveActions.reapply(mLocalContext, mStaleView);
            mStalePrepared = true;
        }
    }

    public void onAnimationRepeat(Animation animation) {
    }

    public void onAnimationStart(Animation animation) {
    }

    /**
     * Process a set of {@link RemoteViews} coming in as an update from the
     * gadget provider. Will animate into these new views as needed.
     */
    public void updateGadget(RemoteViews remoteViews) {
        if (LOGD) Log.d(TAG, "updateGadget called");
        
        boolean recycled = false;
        View newContent = null;
        Exception exception = null;
        
        if (remoteViews == null) {
            newContent = getDefaultView();
        }
        
        // If our stale view has been prepared to match active, and the new
        // layout matches, try recycling it
        if (newContent == null && mStalePrepared &&
                remoteViews.getLayoutId() == mStaleLayoutId) {
            try {
                remoteViews.reapply(mLocalContext, mStaleView);
                newContent = mStaleView;
                recycled = true;
                if (LOGD) Log.d(TAG, "was able to recycled existing layout");
            } catch (RuntimeException e) {
                exception = e;
            }
        }
        
        // Try normal RemoteView inflation
        if (newContent == null) {
            try {
                newContent = remoteViews.apply(mLocalContext, this);
                if (LOGD) Log.d(TAG, "had to inflate new layout");
            } catch (RuntimeException e) {
                exception = e;
            }
        }
        
        if (exception != null && LOGD) {
            Log.w(TAG, "Error inflating gadget " + getGadgetInfo(), exception);
        }
        
        if (newContent == null) {
            // TODO: Should we throw an exception here for the host activity to catch?
            // Maybe we should show a generic error widget.
            if (LOGD) Log.d(TAG, "updateGadget couldn't find any view, so inflating error");
            newContent = getErrorView();
        }
        
        if (!recycled) {
            prepareView(newContent);
            addView(newContent);
        }
        
        showNext();
        
        if (!recycled) {
            removeView(mStaleView);
        }
        
        mStalePrepared = false;
        mActiveActions = remoteViews;
        
        mStaleView = mActiveView;
        mActiveView = newContent;
        
        mStaleLayoutId = mActiveLayoutId;
        mActiveLayoutId = (remoteViews == null) ? -1 : remoteViews.getLayoutId();
    }
    
    /**
     * Prepare the given view to be shown. This might include adjusting
     * {@link FrameLayout.LayoutParams} before inserting.
     */
    protected void prepareView(View view) {
        // Take requested dimensions from parent, but apply default gravity.
        ViewGroup.LayoutParams requested = view.getLayoutParams();
        if (requested == null) {
            requested = new FrameLayout.LayoutParams(LayoutParams.FILL_PARENT,
                    LayoutParams.FILL_PARENT);
        }
        
        FrameLayout.LayoutParams params =
            new FrameLayout.LayoutParams(requested.width, requested.height);
        params.gravity = Gravity.CENTER;
        view.setLayoutParams(params);
    }
    
    /**
     * Inflate and return the default layout requested by gadget provider.
     */
    protected View getDefaultView() {
        View defaultView = null;
        Exception exception = null;
        
        try {
            if (mInfo != null) {
                Context theirContext = mLocalContext.createPackageContext(
                        mInfo.provider.getPackageName(), 0 /* no flags */);
                LayoutInflater inflater = (LayoutInflater)
                        theirContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                inflater = inflater.cloneInContext(theirContext);
                inflater.setFilter(sInflaterFilter);
                defaultView = inflater.inflate(mInfo.initialLayout, this, false);
            } else {
                Log.w(TAG, "can't inflate defaultView because mInfo is missing");
            }
        } catch (PackageManager.NameNotFoundException e) {
            exception = e;
        } catch (RuntimeException e) {
            exception = e;
        }
        
        if (exception != null && LOGD) {
            Log.w(TAG, "Error inflating gadget " + mInfo, exception);
        }
        
        if (defaultView == null) {
            if (LOGD) Log.d(TAG, "getDefaultView couldn't find any view, so inflating error");
            defaultView = getErrorView();
        }
        
        return defaultView;
    }
    
    /**
     * Inflate and return a view that represents an error state.
     */
    protected View getErrorView() {
        TextView tv = new TextView(mLocalContext);
        // TODO: move this error string and background color into resources
        tv.setText("Error inflating gadget");
        tv.setBackgroundColor(Color.argb(127, 0, 0, 0));
        return tv;
    }
}
