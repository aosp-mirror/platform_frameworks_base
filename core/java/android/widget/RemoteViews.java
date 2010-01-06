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
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.RemotableViewMethod;
import android.view.View;
import android.view.ViewGroup;
import android.view.LayoutInflater.Filter;
import android.view.View.OnClickListener;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
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
     * An array of actions to perform on the view tree once it has been
     * inflated
     */
    private ArrayList<Action> mActions;
    
    
    /**
     * This annotation indicates that a subclass of View is alllowed to be used
     * with the {@link RemoteViews} mechanism.
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
        public ActionException(Exception ex) {
            super(ex);
        }
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
                        // Find target view location in screen coordinates and
                        // fill into PendingIntent before sending.
                        final float appScale = v.getContext().getResources()
                                .getCompatibilityInfo().applicationScale;
                        final int[] pos = new int[2];
                        v.getLocationOnScreen(pos);

                        final Rect rect = new Rect();
                        rect.left = (int) (pos[0] * appScale + 0.5f);
                        rect.top = (int) (pos[1] * appScale + 0.5f);
                        rect.right = (int) ((pos[0] + v.getWidth()) * appScale + 0.5f);
                        rect.bottom = (int) ((pos[1] + v.getHeight()) * appScale + 0.5f);

                        final Intent intent = new Intent();
                        intent.setSourceBounds(rect);
                        try {
                            // TODO: Unregister this handler if PendingIntent.FLAG_ONE_SHOT?
                            v.getContext().startIntentSender(
                                    pendingIntent.getIntentSender(), intent,
                                    Intent.FLAG_ACTIVITY_NEW_TASK,
                                    Intent.FLAG_ACTIVITY_NEW_TASK, 0);
                        } catch (IntentSender.SendIntentException e) {
                            android.util.Log.e(LOG_TAG, "Cannot send pending intent: ", e);
                        }
                    }
                };
                target.setOnClickListener(listener);
            }
        }
        
        int viewId;
        PendingIntent pendingIntent;

        public final static int TAG = 1;
    }

    /**
     * Equivalent to calling a combination of {@link Drawable#setAlpha(int)},
     * {@link Drawable#setColorFilter(int, android.graphics.PorterDuff.Mode)},
     * and/or {@link Drawable#setLevel(int)} on the {@link Drawable} of a given view.
     * <p>
     * These operations will be performed on the {@link Drawable} returned by the
     * target {@link View#getBackground()} by default.  If targetBackground is false,
     * we assume the target is an {@link ImageView} and try applying the operations
     * to {@link ImageView#getDrawable()}.
     * <p>
     * You can omit specific calls by marking their values with null or -1.
     */
    private class SetDrawableParameters extends Action {
        public SetDrawableParameters(int id, boolean targetBackground, int alpha,
                int colorFilter, PorterDuff.Mode mode, int level) {
            this.viewId = id;
            this.targetBackground = targetBackground;
            this.alpha = alpha;
            this.colorFilter = colorFilter;
            this.filterMode = mode;
            this.level = level;
        }
        
        public SetDrawableParameters(Parcel parcel) {
            viewId = parcel.readInt();
            targetBackground = parcel.readInt() != 0;
            alpha = parcel.readInt();
            colorFilter = parcel.readInt();
            boolean hasMode = parcel.readInt() != 0;
            if (hasMode) {
                filterMode = PorterDuff.Mode.valueOf(parcel.readString());
            } else {
                filterMode = null;
            }
            level = parcel.readInt();
        }
        
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(TAG);
            dest.writeInt(viewId);
            dest.writeInt(targetBackground ? 1 : 0);
            dest.writeInt(alpha);
            dest.writeInt(colorFilter);
            if (filterMode != null) {
                dest.writeInt(1);
                dest.writeString(filterMode.toString());
            } else {
                dest.writeInt(0);
            }
            dest.writeInt(level);
        }
        
        @Override
        public void apply(View root) {
            final View target = root.findViewById(viewId);
            if (target == null) {
                return;
            }
            
            // Pick the correct drawable to modify for this view
            Drawable targetDrawable = null;
            if (targetBackground) {
                targetDrawable = target.getBackground();
            } else if (target instanceof ImageView) {
                ImageView imageView = (ImageView) target;
                targetDrawable = imageView.getDrawable();
            }
            
            if (targetDrawable != null) {
                // Perform modifications only if values are set correctly
                if (alpha != -1) {
                    targetDrawable.setAlpha(alpha);
                }
                if (colorFilter != -1 && filterMode != null) {
                    targetDrawable.setColorFilter(colorFilter, filterMode);
                }
                if (level != -1) {
                    targetDrawable.setLevel(level);
                }
            }
        }
        
        int viewId;
        boolean targetBackground;
        int alpha;
        int colorFilter;
        PorterDuff.Mode filterMode;
        int level;

        public final static int TAG = 3;
    }
    
    /**
     * Base class for the reflection actions.
     */
    private class ReflectionAction extends Action {
        static final int TAG = 2;

        static final int BOOLEAN = 1;
        static final int BYTE = 2;
        static final int SHORT = 3;
        static final int INT = 4;
        static final int LONG = 5;
        static final int FLOAT = 6;
        static final int DOUBLE = 7;
        static final int CHAR = 8;
        static final int STRING = 9;
        static final int CHAR_SEQUENCE = 10;
        static final int URI = 11;
        static final int BITMAP = 12;
        static final int BUNDLE = 13;

        int viewId;
        String methodName;
        int type;
        Object value;

        ReflectionAction(int viewId, String methodName, int type, Object value) {
            this.viewId = viewId;
            this.methodName = methodName;
            this.type = type;
            this.value = value;
        }

        ReflectionAction(Parcel in) {
            this.viewId = in.readInt();
            this.methodName = in.readString();
            this.type = in.readInt();
            //noinspection ConstantIfStatement
            if (false) {
                Log.d("RemoteViews", "read viewId=0x" + Integer.toHexString(this.viewId)
                        + " methodName=" + this.methodName + " type=" + this.type);
            }
            switch (this.type) {
                case BOOLEAN:
                    this.value = in.readInt() != 0;
                    break;
                case BYTE:
                    this.value = in.readByte();
                    break;
                case SHORT:
                    this.value = (short)in.readInt();
                    break;
                case INT:
                    this.value = in.readInt();
                    break;
                case LONG:
                    this.value = in.readLong();
                    break;
                case FLOAT:
                    this.value = in.readFloat();
                    break;
                case DOUBLE:
                    this.value = in.readDouble();
                    break;
                case CHAR:
                    this.value = (char)in.readInt();
                    break;
                case STRING:
                    this.value = in.readString();
                    break;
                case CHAR_SEQUENCE:
                    this.value = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
                    break;
                case URI:
                    this.value = Uri.CREATOR.createFromParcel(in);
                    break;
                case BITMAP:
                    this.value = Bitmap.CREATOR.createFromParcel(in);
                    break;
                case BUNDLE:
                    this.value = in.readBundle();
                    break;
                default:
                    break;
            }
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(TAG);
            out.writeInt(this.viewId);
            out.writeString(this.methodName);
            out.writeInt(this.type);
            //noinspection ConstantIfStatement
            if (false) {
                Log.d("RemoteViews", "write viewId=0x" + Integer.toHexString(this.viewId)
                        + " methodName=" + this.methodName + " type=" + this.type);
            }
            switch (this.type) {
                case BOOLEAN:
                    out.writeInt((Boolean) this.value ? 1 : 0);
                    break;
                case BYTE:
                    out.writeByte((Byte) this.value);
                    break;
                case SHORT:
                    out.writeInt((Short) this.value);
                    break;
                case INT:
                    out.writeInt((Integer) this.value);
                    break;
                case LONG:
                    out.writeLong((Long) this.value);
                    break;
                case FLOAT:
                    out.writeFloat((Float) this.value);
                    break;
                case DOUBLE:
                    out.writeDouble((Double) this.value);
                    break;
                case CHAR:
                    out.writeInt((int)((Character)this.value).charValue());
                    break;
                case STRING:
                    out.writeString((String)this.value);
                    break;
                case CHAR_SEQUENCE:
                    TextUtils.writeToParcel((CharSequence)this.value, out, flags);   
                    break;
                case URI:
                    ((Uri)this.value).writeToParcel(out, flags);
                    break;
                case BITMAP:
                    ((Bitmap)this.value).writeToParcel(out, flags);
                    break;
                case BUNDLE:
                    out.writeBundle((Bundle) this.value);
                    break;
                default:
                    break;
            }
        }

        private Class getParameterType() {
            switch (this.type) {
                case BOOLEAN:
                    return boolean.class;
                case BYTE:
                    return byte.class;
                case SHORT:
                    return short.class;
                case INT:
                    return int.class;
                case LONG:
                    return long.class;
                case FLOAT:
                    return float.class;
                case DOUBLE:
                    return double.class;
                case CHAR:
                    return char.class;
                case STRING:
                    return String.class;
                case CHAR_SEQUENCE:
                    return CharSequence.class;
                case URI:
                    return Uri.class;
                case BITMAP:
                    return Bitmap.class;
                case BUNDLE:
                    return Bundle.class;
                default:
                    return null;
            }
        }

        @Override
        public void apply(View root) {
            final View view = root.findViewById(viewId);
            if (view == null) {
                throw new ActionException("can't find view: 0x" + Integer.toHexString(viewId));
            }

            Class param = getParameterType();
            if (param == null) {
                throw new ActionException("bad type: " + this.type);
            }

            Class klass = view.getClass();
            Method method;
            try {
                method = klass.getMethod(this.methodName, getParameterType());
            }
            catch (NoSuchMethodException ex) {
                throw new ActionException("view: " + klass.getName() + " doesn't have method: "
                        + this.methodName + "(" + param.getName() + ")");
            }

            if (!method.isAnnotationPresent(RemotableViewMethod.class)) {
                throw new ActionException("view: " + klass.getName()
                        + " can't use method with RemoteViews: "
                        + this.methodName + "(" + param.getName() + ")");
            }

            try {
                //noinspection ConstantIfStatement
                if (false) {
                    Log.d("RemoteViews", "view: " + klass.getName() + " calling method: "
                        + this.methodName + "(" + param.getName() + ") with "
                        + (this.value == null ? "null" : this.value.getClass().getName()));
                }
                method.invoke(view, this.value);
            }
            catch (Exception ex) {
                throw new ActionException(ex);
            }
        }
    }

    /**
     * Equivalent to calling {@link ViewGroup#addView(View)} after inflating the
     * given {@link RemoteViews}, or calling {@link ViewGroup#removeAllViews()}
     * when null. This allows users to build "nested" {@link RemoteViews}.
     */
    private class ViewGroupAction extends Action {
        public ViewGroupAction(int viewId, RemoteViews nestedViews) {
            this.viewId = viewId;
            this.nestedViews = nestedViews;
        }

        public ViewGroupAction(Parcel parcel) {
            viewId = parcel.readInt();
            nestedViews = parcel.readParcelable(null);
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(TAG);
            dest.writeInt(viewId);
            dest.writeParcelable(nestedViews, 0 /* no flags */);
        }

        @Override
        public void apply(View root) {
            final Context context = root.getContext();
            final ViewGroup target = (ViewGroup) root.findViewById(viewId);
            if (nestedViews != null) {
                // Inflate nested views and add as children
                target.addView(nestedViews.apply(context, target));
            } else if (target != null) {
                // Clear all children when nested views omitted
                target.removeAllViews();
            }
        }

        int viewId;
        RemoteViews nestedViews;

        public final static int TAG = 4;
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
                case SetOnClickPendingIntent.TAG:
                    mActions.add(new SetOnClickPendingIntent(parcel));
                    break;
                case SetDrawableParameters.TAG:
                    mActions.add(new SetDrawableParameters(parcel));
                    break;
                case ReflectionAction.TAG:
                    mActions.add(new ReflectionAction(parcel));
                    break;
                case ViewGroupAction.TAG:
                    mActions.add(new ViewGroupAction(parcel));
                    break;
                default:
                    throw new ActionException("Tag " + tag + " not found");
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
     * Equivalent to calling {@link ViewGroup#addView(View)} after inflating the
     * given {@link RemoteViews}. This allows users to build "nested"
     * {@link RemoteViews}. In cases where consumers of {@link RemoteViews} may
     * recycle layouts, use {@link #removeAllViews(int)} to clear any existing
     * children.
     *
     * @param viewId The id of the parent {@link ViewGroup} to add child into.
     * @param nestedView {@link RemoteViews} that describes the child.
     */
    public void addView(int viewId, RemoteViews nestedView) {
        addAction(new ViewGroupAction(viewId, nestedView));
    }

    /**
     * Equivalent to calling {@link ViewGroup#removeAllViews()}.
     *
     * @param viewId The id of the parent {@link ViewGroup} to remove all
     *            children from.
     */
    public void removeAllViews(int viewId) {
        addAction(new ViewGroupAction(viewId, null));
    }

    /**
     * Equivalent to calling View.setVisibility
     * 
     * @param viewId The id of the view whose visibility should change
     * @param visibility The new visibility for the view
     */
    public void setViewVisibility(int viewId, int visibility) {
        setInt(viewId, "setVisibility", visibility);
    }
    
    /**
     * Equivalent to calling TextView.setText
     * 
     * @param viewId The id of the view whose text should change
     * @param text The new text for the view
     */
    public void setTextViewText(int viewId, CharSequence text) {
        setCharSequence(viewId, "setText", text);
    }
    
    /**
     * Equivalent to calling ImageView.setImageResource
     * 
     * @param viewId The id of the view whose drawable should change
     * @param srcId The new resource id for the drawable
     */
    public void setImageViewResource(int viewId, int srcId) {   
        setInt(viewId, "setImageResource", srcId);
    }

    /**
     * Equivalent to calling ImageView.setImageURI
     * 
     * @param viewId The id of the view whose drawable should change
     * @param uri The Uri for the image
     */
    public void setImageViewUri(int viewId, Uri uri) {
        setUri(viewId, "setImageURI", uri);
    }

    /**
     * Equivalent to calling ImageView.setImageBitmap
     * 
     * @param viewId The id of the view whose drawable should change
     * @param bitmap The new Bitmap for the drawable
     */
    public void setImageViewBitmap(int viewId, Bitmap bitmap) {
        setBitmap(viewId, "setImageBitmap", bitmap);
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
     * @param started True if you want the clock to be started, false if not.
     */
    public void setChronometer(int viewId, long base, String format, boolean started) {
        setLong(viewId, "setBase", base);
        setString(viewId, "setFormat", format);
        setBoolean(viewId, "setStarted", started);
    }
    
    /**
     * Equivalent to calling {@link ProgressBar#setMax ProgressBar.setMax},
     * {@link ProgressBar#setProgress ProgressBar.setProgress}, and
     * {@link ProgressBar#setIndeterminate ProgressBar.setIndeterminate}
     *
     * If indeterminate is true, then the values for max and progress are ignored.
     * 
     * @param viewId The id of the view whose text should change
     * @param max The 100% value for the progress bar
     * @param progress The current value of the progress bar.
     * @param indeterminate True if the progress bar is indeterminate, 
     *                false if not.
     */
    public void setProgressBar(int viewId, int max, int progress, 
            boolean indeterminate) {
        setBoolean(viewId, "setIndeterminate", indeterminate);
        if (!indeterminate) {
            setInt(viewId, "setMax", max);
            setInt(viewId, "setProgress", progress);
        }
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
     * @hide
     * Equivalent to calling a combination of {@link Drawable#setAlpha(int)},
     * {@link Drawable#setColorFilter(int, android.graphics.PorterDuff.Mode)},
     * and/or {@link Drawable#setLevel(int)} on the {@link Drawable} of a given
     * view.
     * <p>
     * You can omit specific calls by marking their values with null or -1.
     * 
     * @param viewId The id of the view that contains the target
     *            {@link Drawable}
     * @param targetBackground If true, apply these parameters to the
     *            {@link Drawable} returned by
     *            {@link android.view.View#getBackground()}. Otherwise, assume
     *            the target view is an {@link ImageView} and apply them to
     *            {@link ImageView#getDrawable()}.
     * @param alpha Specify an alpha value for the drawable, or -1 to leave
     *            unchanged.
     * @param colorFilter Specify a color for a
     *            {@link android.graphics.ColorFilter} for this drawable, or -1
     *            to leave unchanged.
     * @param mode Specify a PorterDuff mode for this drawable, or null to leave
     *            unchanged.
     * @param level Specify the level for the drawable, or -1 to leave
     *            unchanged.
     */
    public void setDrawableParameters(int viewId, boolean targetBackground, int alpha,
            int colorFilter, PorterDuff.Mode mode, int level) {
        addAction(new SetDrawableParameters(viewId, targetBackground, alpha,
                colorFilter, mode, level));
    }

    /**
     * Equivalent to calling {@link android.widget.TextView#setTextColor(int)}.
     * 
     * @param viewId The id of the view whose text should change
     * @param color Sets the text color for all the states (normal, selected,
     *            focused) to be this color.
     */
    public void setTextColor(int viewId, int color) {
        setInt(viewId, "setTextColor", color);
    }

    /**
     * Call a method taking one boolean on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view whose text should change
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setBoolean(int viewId, String methodName, boolean value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.BOOLEAN, value));
    }

    /**
     * Call a method taking one byte on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view whose text should change
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setByte(int viewId, String methodName, byte value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.BYTE, value));
    }

    /**
     * Call a method taking one short on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view whose text should change
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setShort(int viewId, String methodName, short value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.SHORT, value));
    }

    /**
     * Call a method taking one int on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view whose text should change
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setInt(int viewId, String methodName, int value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.INT, value));
    }

    /**
     * Call a method taking one long on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view whose text should change
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setLong(int viewId, String methodName, long value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.LONG, value));
    }

    /**
     * Call a method taking one float on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view whose text should change
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setFloat(int viewId, String methodName, float value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.FLOAT, value));
    }

    /**
     * Call a method taking one double on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view whose text should change
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setDouble(int viewId, String methodName, double value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.DOUBLE, value));
    }

    /**
     * Call a method taking one char on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view whose text should change
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setChar(int viewId, String methodName, char value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.CHAR, value));
    }

    /**
     * Call a method taking one String on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view whose text should change
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setString(int viewId, String methodName, String value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.STRING, value));
    }

    /**
     * Call a method taking one CharSequence on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view whose text should change
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setCharSequence(int viewId, String methodName, CharSequence value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.CHAR_SEQUENCE, value));
    }

    /**
     * Call a method taking one Uri on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view whose text should change
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setUri(int viewId, String methodName, Uri value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.URI, value));
    }

    /**
     * Call a method taking one Bitmap on a view in the layout for this RemoteViews.
     * @more
     * <p class="note">The bitmap will be flattened into the parcel if this object is
     * sent across processes, so it may end up using a lot of memory, and may be fairly slow.</p>
     *
     * @param viewId The id of the view whose text should change
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setBitmap(int viewId, String methodName, Bitmap value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.BITMAP, value));
    }

    /**
     * Call a method taking one Bundle on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view whose text should change
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setBundle(int viewId, String methodName, Bundle value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.BUNDLE, value));
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
        View result;

        Context c = prepareContext(context);

        LayoutInflater inflater = (LayoutInflater)
                c.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

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
        Context c;
        String packageName = mPackage;

        if (packageName != null) {
            try {
                c = context.createPackageContext(packageName, Context.CONTEXT_RESTRICTED);
            } catch (NameNotFoundException e) {
                Log.e(LOG_TAG, "Package name " + packageName + " not found");
                c = context;
            }
        } else {
            c = context;
        }

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
