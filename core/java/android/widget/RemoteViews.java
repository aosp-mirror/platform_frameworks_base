/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.widget;

import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.LayoutInflater.Filter;
import android.view.View.OnClickListener;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;


/**
 * A class that describes a view hierarchy that can be displayed in
 * another process. The hierarchy is inflated from a layout resource
 * file, and this class provides some basic operations for modifying
 * the content of the inflated hierarchy.
 */
public class RemoteViews implements Parcelable, Filter {
    
    private static final String LOG_TAG = "RemoteViews";
    
    /**
     * The package name of the package containing the layout 
     * resource. (Added to the parcel)
     */
    private String mPackage;
    
    /**
     * The resource ID of the layout file. (Added to the parcel)
     */
    private int mLayoutId;
    
    /**
     * The Context object used to inflate the layout file. Also may
     * be used by actions if they need access to the senders resources.
     */
    private Context mContext;
    
    /**
     * An array of actions to perform on the view tree once it has been
     * inflated
     */
    private ArrayList<Action> mActions;
    
    
    /**
     * This annotation indicates that a subclass of View is alllowed to be used with the
     * {@link android.widget.RemoteViews} mechanism.
     */
    @Target({ ElementType.TYPE })
    @Retention(RetentionPolicy.RUNTIME)
    public @interface RemoteView {
    }
    
    /**
     * Exception to send when something goes wrong executing an action
     *
     */
    public static class ActionException extends RuntimeException {
        public ActionException(String message) {
            super(message);
        }
    }
    
    /**
     * Base class for all actions that can be performed on an 
     * inflated view.
     *
     */
    private abstract static class Action implements Parcelable {
        public abstract void apply(View root) throws ActionException;

        public int describeContents() {
            return 0;
        }
    };
    
    /**
     * Equivalent to calling View.setVisibility
     */
    private class SetViewVisibility extends Action {
        public SetViewVisibility(int id, int vis) {
            viewId = id;
            visibility = vis;
        }
        
        public SetViewVisibility(Parcel parcel) {
            viewId = parcel.readInt();
            visibility = parcel.readInt();
        }
        
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(TAG);
            dest.writeInt(viewId);
            dest.writeInt(visibility);   
        }
        
        @Override
        public void apply(View root) {
            View target = root.findViewById(viewId);
            if (target != null) {
                target.setVisibility(visibility);
            }
        }
        
        private int viewId;
        private int visibility;
        public final static int TAG = 0;
    }
    
    /**
     * Equivalent to calling TextView.setText
     */
    private class SetTextViewText extends Action {
        public SetTextViewText(int id, CharSequence t) {
            viewId = id;
            text = t;
        }
        
        public SetTextViewText(Parcel parcel) {
            viewId = parcel.readInt();
            text = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
        }
        
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(TAG);
            dest.writeInt(viewId);
            TextUtils.writeToParcel(text, dest, flags);   
        }
        
        @Override
        public void apply(View root) {
            TextView target = (TextView) root.findViewById(viewId);
            if (target != null) {
                target.setText(text);
            }
        }
        
        int viewId;
        CharSequence text;
        public final static int TAG = 1;
    }

    /**
     * Equivalent to calling ImageView.setResource
     */
    private class SetImageViewResource extends Action {
        public SetImageViewResource(int id, int src) {
            viewId = id;
            srcId = src;
        }
        
        public SetImageViewResource(Parcel parcel) {
            viewId = parcel.readInt();
            srcId = parcel.readInt();
        }
        
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(TAG);
            dest.writeInt(viewId);
            dest.writeInt(srcId);   
        }
        
        @Override
        public void apply(View root) {
            ImageView target = (ImageView) root.findViewById(viewId);
            Drawable d = mContext.getResources().getDrawable(srcId);
            if (target != null) {
                target.setImageDrawable(d);
            }
        }
        
        int viewId;
        int srcId;
        public final static int TAG = 2;
    }
    
    /**
     * Equivalent to calling ImageView.setImageURI
     */
    private class SetImageViewUri extends Action {
        public SetImageViewUri(int id, Uri u) {
            viewId = id;
            uri = u;
        }
        
        public SetImageViewUri(Parcel parcel) {
            viewId = parcel.readInt();
            uri = Uri.CREATOR.createFromParcel(parcel);
        }
        
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(TAG);
            dest.writeInt(viewId);
            Uri.writeToParcel(dest, uri);
        }
        
        @Override
        public void apply(View root) {
            ImageView target = (ImageView) root.findViewById(viewId);
            if (target != null) {
                target.setImageURI(uri);
            }
        }
        
        int viewId;
        Uri uri;
        public final static int TAG = 3;
    }

    /**
     * Equivalent to calling ImageView.setImageBitmap
     */
    private class SetImageViewBitmap extends Action {
        public SetImageViewBitmap(int id, Bitmap src) {
            viewId = id;
            bitmap = src;
        }

        public SetImageViewBitmap(Parcel parcel) {
            viewId = parcel.readInt();
            bitmap = Bitmap.CREATOR.createFromParcel(parcel);
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(TAG);
            dest.writeInt(viewId);
            if (bitmap != null) {
                bitmap.writeToParcel(dest, flags);
            }
        }

        @Override
        public void apply(View root) {
            if (bitmap != null) {
                ImageView target = (ImageView) root.findViewById(viewId);
                Drawable d = new BitmapDrawable(bitmap);
                if (target != null) {
                    target.setImageDrawable(d);
                }
            }
        }

        int viewId;
        Bitmap bitmap;
        public final static int TAG = 4;
    }
    
    /**
     * Equivalent to calling Chronometer.setBase, Chronometer.setFormat,
     * and Chronometer.start/stop.
     */
    private class SetChronometer extends Action {
        public SetChronometer(int id, long base, String format, boolean running) {
            this.viewId = id;
            this.base = base;
            this.format = format;
            this.running = running;
        }
        
        public SetChronometer(Parcel parcel) {
            viewId = parcel.readInt();
            base = parcel.readLong();
            format = parcel.readString();
            running = parcel.readInt() != 0;
        }
        
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(TAG);
            dest.writeInt(viewId);
            dest.writeLong(base);
            dest.writeString(format);
            dest.writeInt(running ? 1 : 0);
        }
        
        @Override
        public void apply(View root) {
            Chronometer target = (Chronometer) root.findViewById(viewId);
            if (target != null) {
                target.setBase(base);
                target.setFormat(format);
                if (running) {
                    target.start();
                } else {
                    target.stop();
                }
            }
        }
        
        int viewId;
        boolean running;
        long base;
        String format;

        public final static int TAG = 5;
    }
    
    /**
     * Equivalent to calling ProgressBar.setMax, ProgressBar.setProgress and
     * ProgressBar.setIndeterminate
     */
    private class SetProgressBar extends Action {
        public SetProgressBar(int id, int max, int progress, boolean indeterminate) {
            this.viewId = id;
            this.progress = progress;
            this.max = max;
            this.indeterminate = indeterminate;
        }
        
        public SetProgressBar(Parcel parcel) {
            viewId = parcel.readInt();
            progress = parcel.readInt();
            max = parcel.readInt();
            indeterminate = parcel.readInt() != 0;
        }
        
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(TAG);
            dest.writeInt(viewId);
            dest.writeInt(progress);
            dest.writeInt(max);
            dest.writeInt(indeterminate ? 1 : 0);
        }
        
        @Override
        public void apply(View root) {
            ProgressBar target = (ProgressBar) root.findViewById(viewId);
            if (target != null) {
                target.setIndeterminate(indeterminate);
                if (!indeterminate) {
                    target.setMax(max);
                    target.setProgress(progress);
                }
            }
        }
        
        int viewId;
        boolean indeterminate;
        int progress;
        int max;

        public final static int TAG = 6;
    }
    
    /**
     * Equivalent to calling
     * {@link android.view.View#setOnClickListener(android.view.View.OnClickListener)}
     * to launch the provided {@link PendingIntent}.
     */
    private class SetOnClickPendingIntent extends Action {
        public SetOnClickPendingIntent(int id, PendingIntent pendingIntent) {
            this.viewId = id;
            this.pendingIntent = pendingIntent;
        }
        
        public SetOnClickPendingIntent(Parcel parcel) {
            viewId = parcel.readInt();
            pendingIntent = PendingIntent.readPendingIntentOrNullFromParcel(parcel);
        }
        
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(TAG);
            dest.writeInt(viewId);
            pendingIntent.writeToParcel(dest, 0 /* no flags */);
        }
        
        @Override
        public void apply(View root) {
            final View target = root.findViewById(viewId);
            if (target != null && pendingIntent != null) {
                OnClickListener listener = new OnClickListener() {
                    public void onClick(View v) {
                        try {
                            // TODO: Unregister this handler if PendingIntent.FLAG_ONE_SHOT?
                            pendingIntent.send();
                        } catch (CanceledException e) {
                            throw new ActionException(e.toString());
                        }
                    }
                };
                target.setOnClickListener(listener);
            }
        }
        
        int viewId;
        PendingIntent pendingIntent;

        public final static int TAG = 7;
    }

    /**
     * Create a new RemoteViews object that will display the views contained
     * in the specified layout file.
     * 
     * @param packageName Name of the package that contains the layout resource
     * @param layoutId The id of the layout resource
     */
    public RemoteViews(String packageName, int layoutId) {
        mPackage = packageName;
        mLayoutId = layoutId;
    }

    /**
     * Reads a RemoteViews object from a parcel.
     * 
     * @param parcel
     */
    public RemoteViews(Parcel parcel) {
        mPackage = parcel.readString();
        mLayoutId = parcel.readInt();
        int count = parcel.readInt();
        if (count > 0) {
            mActions = new ArrayList<Action>(count);
            for (int i=0; i<count; i++) {
                int tag = parcel.readInt();
                switch (tag) {
                case SetViewVisibility.TAG:
                    mActions.add(new SetViewVisibility(parcel));
                    break;
                case SetTextViewText.TAG:
                    mActions.add(new SetTextViewText(parcel));
                    break;
                case SetImageViewResource.TAG:
                    mActions.add(new SetImageViewResource(parcel));
                    break;
                case SetImageViewUri.TAG:
                    mActions.add(new SetImageViewUri(parcel));
                    break;
                case SetImageViewBitmap.TAG:
                    mActions.add(new SetImageViewBitmap(parcel));
                    break;
                case SetChronometer.TAG:
                    mActions.add(new SetChronometer(parcel));
                    break;
                case SetProgressBar.TAG:
                    mActions.add(new SetProgressBar(parcel));
                    break;
                case SetOnClickPendingIntent.TAG:
                    mActions.add(new SetOnClickPendingIntent(parcel));
                    break;
                default:
                    throw new ActionException("Tag " + tag + "not found");
                }
            }
        }
    }

    public String getPackage() {
        return mPackage;
    }

    public int getLayoutId() {
        return mLayoutId;
    }

    /**
     * Add an action to be executed on the remote side when apply is called.
     * 
     * @param a The action to add
     */
    private void addAction(Action a) {
        if (mActions == null) {
            mActions = new ArrayList<Action>();
        }
        mActions.add(a);
    }
    
    /**
     * Equivalent to calling View.setVisibility
     * 
     * @param viewId The id of the view whose visibility should change
     * @param visibility The new visibility for the view
     */
    public void setViewVisibility(int viewId, int visibility) {
        addAction(new SetViewVisibility(viewId, visibility));
    }
    
    /**
     * Equivalent to calling TextView.setText
     * 
     * @param viewId The id of the view whose text should change
     * @param text The new text for the view
     */
    public void setTextViewText(int viewId, CharSequence text) {
        addAction(new SetTextViewText(viewId, text));
    }
    
    /**
     * Equivalent to calling ImageView.setImageResource
     * 
     * @param viewId The id of the view whose drawable should change
     * @param srcId The new resource id for the drawable
     */
    public void setImageViewResource(int viewId, int srcId) {   
        addAction(new SetImageViewResource(viewId, srcId));
    }

    /**
     * Equivalent to calling ImageView.setImageURI
     * 
     * @param viewId The id of the view whose drawable should change
     * @param uri The Uri for the image
     */
    public void setImageViewUri(int viewId, Uri uri) {
        addAction(new SetImageViewUri(viewId, uri));
    }

    /**
     * Equivalent to calling ImageView.setImageBitmap
     * 
     * @param viewId The id of the view whose drawable should change
     * @param bitmap The new Bitmap for the drawable
     */
    public void setImageViewBitmap(int viewId, Bitmap bitmap) {
        addAction(new SetImageViewBitmap(viewId, bitmap));
    }

    /**
     * Equivalent to calling {@link Chronometer#setBase Chronometer.setBase},
     * {@link Chronometer#setFormat Chronometer.setFormat},
     * and {@link Chronometer#start Chronometer.start()} or
     * {@link Chronometer#stop Chronometer.stop()}.
     * 
     * @param viewId The id of the view whose text should change
     * @param base The time at which the timer would have read 0:00.  This
     *             time should be based off of
     *             {@link android.os.SystemClock#elapsedRealtime SystemClock.elapsedRealtime()}.
     * @param format The Chronometer format string, or null to
     *               simply display the timer value.
     * @param running True if you want the clock to be running, false if not.
     */
    public void setChronometer(int viewId, long base, String format, boolean running) {
        addAction(new SetChronometer(viewId, base, format, running));
    }
    
    /**
     * Equivalent to calling {@link ProgressBar#setMax ProgressBar.setMax},
     * {@link ProgressBar#setProgress ProgressBar.setProgress}, and
     * {@link ProgressBar#setIndeterminate ProgressBar.setIndeterminate}
     * 
     * @param viewId The id of the view whose text should change
     * @param max The 100% value for the progress bar
     * @param progress The current value of the progress bar.
     * @param indeterminate True if the progress bar is indeterminate, 
     *                false if not.
     */
    public void setProgressBar(int viewId, int max, int progress, 
            boolean indeterminate) {
        addAction(new SetProgressBar(viewId, max, progress, indeterminate));
    }
    
    /**
     * Equivalent to calling
     * {@link android.view.View#setOnClickListener(android.view.View.OnClickListener)}
     * to launch the provided {@link PendingIntent}.
     * 
     * @param viewId The id of the view that will trigger the {@link PendingIntent} when clicked
     * @param pendingIntent The {@link PendingIntent} to send when user clicks
     */
    public void setOnClickPendingIntent(int viewId, PendingIntent pendingIntent) {
        addAction(new SetOnClickPendingIntent(viewId, pendingIntent));
    }

    /**
     * Inflates the view hierarchy represented by this object and applies
     * all of the actions.
     * 
     * <p><strong>Caller beware: this may throw</strong>
     * 
     * @param context Default context to use
     * @param parent Parent that the resulting view hierarchy will be attached to. This method
     * does <strong>not</strong> attach the hierarchy. The caller should do so when appropriate.
     * @return The inflated view hierarchy
     */
    public View apply(Context context, ViewGroup parent) {
        View result = null;

        Context c = prepareContext(context);

        Resources r = c.getResources();
        LayoutInflater inflater = (LayoutInflater) c
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        inflater = inflater.cloneInContext(c);
        inflater.setFilter(this);

        result = inflater.inflate(mLayoutId, parent, false);

        performApply(result);

        return result;
    }
    
    /**
     * Applies all of the actions to the provided view.
     *
     * <p><strong>Caller beware: this may throw</strong>
     * 
     * @param v The view to apply the actions to.  This should be the result of
     * the {@link #apply(Context,ViewGroup)} call.
     */
    public void reapply(Context context, View v) {
        prepareContext(context);
        performApply(v);
    }

    private void performApply(View v) {
        if (mActions != null) {
            final int count = mActions.size();
            for (int i = 0; i < count; i++) {
                Action a = mActions.get(i);
                a.apply(v);
            }
        }
    }

    private Context prepareContext(Context context) {
        Context c = null;
        String packageName = mPackage;

        if (packageName != null) {
            try {
                c = context.createPackageContext(packageName, 0);
            } catch (NameNotFoundException e) {
                Log.e(LOG_TAG, "Package name " + packageName + " not found");
                c = context;
            }
        } else {
            c = context;
        }

        mContext = c;

        return c;
    }

    /* (non-Javadoc)
     * Used to restrict the views which can be inflated
     * 
     * @see android.view.LayoutInflater.Filter#onLoadClass(java.lang.Class)
     */
    public boolean onLoadClass(Class clazz) {
        return clazz.isAnnotationPresent(RemoteView.class);
    }
    
    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mPackage);
        dest.writeInt(mLayoutId);
        int count;
        if (mActions != null) {
            count = mActions.size();
        } else {
            count = 0;
        }
        dest.writeInt(count);
        for (int i=0; i<count; i++) {
            Action a = mActions.get(i);
            a.writeToParcel(dest, 0);
        }
    }

    /**
     * Parcelable.Creator that instantiates RemoteViews objects
     */
    public static final Parcelable.Creator<RemoteViews> CREATOR = new Parcelable.Creator<RemoteViews>() {
        public RemoteViews createFromParcel(Parcel parcel) {
            return new RemoteViews(parcel);
        }

        public RemoteViews[] newArray(int size) {
            return new RemoteViews[size];
        }
    };
}
