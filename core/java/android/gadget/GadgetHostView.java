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
import android.gadget.GadgetInfo;
import android.graphics.Color;
import android.util.Config;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.widget.ViewAnimator;

public class GadgetHostView extends ViewAnimator {
    static final String TAG = "GadgetHostView";
    static final boolean LOGD = Config.LOGD || true;

    // When we're inflating the initialLayout for a gadget, we only allow
    // views that are allowed in RemoteViews.
    static final LayoutInflater.Filter sInflaterFilter = new LayoutInflater.Filter() {
        public boolean onLoadClass(Class clazz) {
            return clazz.isAnnotationPresent(RemoteViews.RemoteView.class);
        }
    };

    int mGadgetId;
    GadgetInfo mInfo;
    View mActiveView;
    View mStaleView;
    
    protected int mDefaultGravity = Gravity.CENTER;

    public GadgetHostView(Context context) {
        super(context);
    }

    public void setGadget(int gadgetId, GadgetInfo info) {
        if (LOGD) Log.d(TAG, "setGadget is incoming with info=" + info);
        if (mInfo != null) {
            // TODO: remove the old view, or whatever
        }
        mGadgetId = gadgetId;
        mInfo = info;
        
        View defaultView = getDefaultView();
        flipUpdate(defaultView);
    }
    
    /**
     * Trigger actual animation between current and new content in the
     * {@link ViewAnimator}.
     */
    protected void flipUpdate(View newContent) {
        if (LOGD) Log.d(TAG, "pushing an update to surface");
        
        // Take requested dimensions from parent, but apply default gravity.
        ViewGroup.LayoutParams requested = newContent.getLayoutParams();
        if (requested == null) {
            requested = new FrameLayout.LayoutParams(LayoutParams.FILL_PARENT,
                    LayoutParams.FILL_PARENT);
        }
        
        FrameLayout.LayoutParams params =
            new FrameLayout.LayoutParams(requested.width, requested.height);
        params.gravity = mDefaultGravity;
        newContent.setLayoutParams(params);
        
        // Add new content and animate to it
        addView(newContent);
        showNext();
        
        // Dispose old stale view
        removeView(mStaleView);
        mStaleView = mActiveView;
        mActiveView = newContent;
    }

    /**
     * Process a set of {@link RemoteViews} coming in as an update from the
     * gadget provider. Will animate into these new views as needed.
     */
    public void updateGadget(RemoteViews remoteViews) {
        if (LOGD) Log.d(TAG, "updateGadget() with remoteViews = " + remoteViews);
        
        View newContent = null;
        Exception exception = null;
        
        try {
            if (remoteViews == null) {
                // there is no remoteViews (yet), so use the initial layout
                newContent = getDefaultView();
            } else {
                // use the RemoteViews
                // TODO: try applying RemoteViews to existing staleView if available 
                newContent = remoteViews.apply(mContext, this);
            }
        } catch (RuntimeException e) {
            exception = e;
        }
        
        if (exception != null && LOGD) {
            Log.w(TAG, "Error inflating gadget " + mInfo, exception);
        }
        
        if (newContent == null) {
            // TODO: Should we throw an exception here for the host activity to catch?
            // Maybe we should show a generic error widget.
            if (LOGD) Log.d(TAG, "updateGadget couldn't find any view, so inflating error");
            newContent = getErrorView();
        }

        flipUpdate(newContent);
    }
    
    /**
     * Inflate and return the default layout requested by gadget provider.
     */
    protected View getDefaultView() {
        View defaultView = null;
        Exception exception = null;
        
        try {
            if (mInfo != null) {
                Context theirContext = mContext.createPackageContext(
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
        TextView tv = new TextView(mContext);
        // TODO: move this error string and background color into resources
        tv.setText("Error inflating gadget");
        tv.setBackgroundColor(Color.argb(127, 0, 0, 0));
        return tv;
    }
}

