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
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

public class GadgetHostView extends FrameLayout {
    static final String TAG = "GadgetHostView";

    // When we're inflating the initialLayout for a gadget, we only allow
    // views that are allowed in RemoteViews.
    static final LayoutInflater.Filter sInflaterFilter = new LayoutInflater.Filter() {
        public boolean onLoadClass(Class clazz) {
            return clazz.isAnnotationPresent(RemoteViews.RemoteView.class);
        }
    };

    int mGadgetId;
    GadgetInfo mInfo;
    View mContentView;

    public GadgetHostView(Context context) {
        super(context);
    }

    public void setGadget(int gadgetId, GadgetInfo info) {
        if (mInfo != null) {
            // TODO: remove the old view, or whatever
        }
        mGadgetId = gadgetId;
        mInfo = info;
    }

    public void updateGadget(RemoteViews remoteViews) {
        Context context = getContext();

        View contentView = null;
        Exception exception = null;
        try {
            if (remoteViews == null) {
                // there is no remoteViews (yet), so use the initial layout
                Context theirContext = context.createPackageContext(mInfo.provider.getPackageName(),
                        0);
                LayoutInflater inflater = (LayoutInflater)theirContext.getSystemService(
                        Context.LAYOUT_INFLATER_SERVICE);
                inflater = inflater.cloneInContext(theirContext);
                inflater.setFilter(sInflaterFilter);
                contentView = inflater.inflate(mInfo.initialLayout, this, false);
            } else {
                // use the RemoteViews
                contentView = remoteViews.apply(mContext, this);
            }
        }
        catch (PackageManager.NameNotFoundException e) {
            exception = e;
        }
        catch (RuntimeException e) {
            exception = e;
        }
        if (contentView == null) {
            Log.w(TAG, "Error inflating gadget " + mInfo, exception);
            // TODO: Should we throw an exception here for the host activity to catch?
            // Maybe we should show a generic error widget.
            TextView tv = new TextView(context);
            tv.setText("Error inflating gadget");
            contentView = tv;
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);

        mContentView = contentView;
        this.addView(contentView, lp);

        // TODO: do an animation (maybe even one provided by the gadget).
    }
}

