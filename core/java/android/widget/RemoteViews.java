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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.ArrayList;

import android.app.PendingIntent;
import android.appwidget.AppWidgetHostView;
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
import android.widget.AdapterView.OnItemClickListener;


/**
 * A class that describes a view hierarchy that can be displayed in
 * another process. The hierarchy is inflated from a layout resource
 * file, and this class provides some basic operations for modifying
 * the content of the inflated hierarchy.
 */
public class RemoteViews implements Parcelable, Filter {
    
    private static final String LOG_TAG = "RemoteViews";
    
    /**
     * The intent extra that contains the appWidgetId.
     * @hide
     */
    static final String EXTRA_REMOTEADAPTER_APPWIDGET_ID = "remoteAdapterAppWidgetId";

    /**
     * The package name of the package containing the layout 
     * resource. (Added to the parcel)
     */
    private final String mPackage;
    
    /**
     * The resource ID of the layout file. (Added to the parcel)
     */
    private final int mLayoutId;

    /**
     * An array of actions to perform on the view tree once it has been
     * inflated
     */
    private ArrayList<Action> mActions;
    
    /**
     * A class to keep track of memory usage by this RemoteViews
     */
    private MemoryUsageCounter mMemoryUsageCounter;

    
    /**
     * This flag indicates whether this RemoteViews object is being created from a
     * RemoteViewsService for use as a child of a widget collection. This flag is used
     * to determine whether or not certain features are available, in particular,
     * setting on click extras and setting on click pending intents. The former is enabled,
     * and the latter disabled when this flag is true.
     */
     private boolean mIsWidgetCollectionChild = false;

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
     *  SUBCLASSES MUST BE IMMUTABLE SO CLONE WORKS!!!!!
     */
    private abstract static class Action implements Parcelable {
        public abstract void apply(View root, ViewGroup rootParent) throws ActionException;

        public int describeContents() {
            return 0;
        }

        /**
         * Overridden by each class to report on it's own memory usage
         */
        public void updateMemoryUsageEstimate(MemoryUsageCounter counter) {
            // We currently only calculate Bitmap memory usage, so by default, don't do anything
            // here
            return;
        }

        protected boolean startIntentSafely(Context context, PendingIntent pendingIntent,
                Intent fillInIntent) {
            try {
                // TODO: Unregister this handler if PendingIntent.FLAG_ONE_SHOT?
                context.startIntentSender(
                        pendingIntent.getIntentSender(), fillInIntent,
                        Intent.FLAG_ACTIVITY_NEW_TASK,
                        Intent.FLAG_ACTIVITY_NEW_TASK, 0);
            } catch (IntentSender.SendIntentException e) {
                android.util.Log.e(LOG_TAG, "Cannot send pending intent: ", e);
                return false;
            } catch (Exception e) {
                android.util.Log.e(LOG_TAG, "Cannot send pending intent due to " +
                        "unknown exception: ", e);
                return false;
            }
            return true;
        }
    }

    private class SetEmptyView extends Action {
        int viewId;
        int emptyViewId;

        public final static int TAG = 6;

        SetEmptyView(int viewId, int emptyViewId) {
            this.viewId = viewId;
            this.emptyViewId = emptyViewId;
        }

        SetEmptyView(Parcel in) {
            this.viewId = in.readInt();
            this.emptyViewId = in.readInt();
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(TAG);
            out.writeInt(this.viewId);
            out.writeInt(this.emptyViewId);
        }

        @Override
        public void apply(View root, ViewGroup rootParent) {
            final View view = root.findViewById(viewId);
            if (!(view instanceof AdapterView<?>)) return;

            AdapterView<?> adapterView = (AdapterView<?>) view;

            final View emptyView = root.findViewById(emptyViewId);
            if (emptyView == null) return;

            adapterView.setEmptyView(emptyView);
        }
    }

    private class SetOnClickFillInIntent extends Action {
        public SetOnClickFillInIntent(int id, Intent fillInIntent) {
            this.viewId = id;
            this.fillInIntent = fillInIntent;
        }

        public SetOnClickFillInIntent(Parcel parcel) {
            viewId = parcel.readInt();
            fillInIntent = Intent.CREATOR.createFromParcel(parcel);
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(TAG);
            dest.writeInt(viewId);
            fillInIntent.writeToParcel(dest, 0 /* no flags */);
        }

        @Override
        public void apply(View root, ViewGroup rootParent) {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            if (!mIsWidgetCollectionChild) {
                Log.e("RemoteViews", "The method setOnClickFillInIntent is available " +
                        "only from RemoteViewsFactory (ie. on collection items).");
                return;
            }
            if (target == root) {
                target.setTagInternal(com.android.internal.R.id.fillInIntent, fillInIntent);
            } else if (target != null && fillInIntent != null) {
                OnClickListener listener = new OnClickListener() {
                    public void onClick(View v) {
                        // Insure that this view is a child of an AdapterView
                        View parent = (View) v.getParent();
                        while (!(parent instanceof AdapterView<?>)
                                && !(parent instanceof AppWidgetHostView)) {
                            parent = (View) parent.getParent();
                        }

                        if (parent instanceof AppWidgetHostView) {
                            // Somehow they've managed to get this far without having
                            // and AdapterView as a parent.
                            Log.e("RemoteViews", "Collection item doesn't have AdapterView parent");
                            return;
                        }

                        // Insure that a template pending intent has been set on an ancestor
                        if (!(parent.getTag() instanceof PendingIntent)) {
                            Log.e("RemoteViews", "Attempting setOnClickFillInIntent without" +
                                    " calling setPendingIntentTemplate on parent.");
                            return;
                        }

                        PendingIntent pendingIntent = (PendingIntent) parent.getTag();

                        final float appScale = v.getContext().getResources()
                                .getCompatibilityInfo().applicationScale;
                        final int[] pos = new int[2];
                        v.getLocationOnScreen(pos);

                        final Rect rect = new Rect();
                        rect.left = (int) (pos[0] * appScale + 0.5f);
                        rect.top = (int) (pos[1] * appScale + 0.5f);
                        rect.right = (int) ((pos[0] + v.getWidth()) * appScale + 0.5f);
                        rect.bottom = (int) ((pos[1] + v.getHeight()) * appScale + 0.5f);

                        fillInIntent.setSourceBounds(rect);
                        startIntentSafely(v.getContext(), pendingIntent, fillInIntent);
                    }

                };
                target.setOnClickListener(listener);
            }
        }

        int viewId;
        Intent fillInIntent;

        public final static int TAG = 9;
    }

    private class SetPendingIntentTemplate extends Action {
        public SetPendingIntentTemplate(int id, PendingIntent pendingIntentTemplate) {
            this.viewId = id;
            this.pendingIntentTemplate = pendingIntentTemplate;
        }

        public SetPendingIntentTemplate(Parcel parcel) {
            viewId = parcel.readInt();
            pendingIntentTemplate = PendingIntent.readPendingIntentOrNullFromParcel(parcel);
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(TAG);
            dest.writeInt(viewId);
            pendingIntentTemplate.writeToParcel(dest, 0 /* no flags */);
        }

        @Override
        public void apply(View root, ViewGroup rootParent) {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            // If the view isn't an AdapterView, setting a PendingIntent template doesn't make sense
            if (target instanceof AdapterView<?>) {
                AdapterView<?> av = (AdapterView<?>) target;
                // The PendingIntent template is stored in the view's tag.
                OnItemClickListener listener = new OnItemClickListener() {
                    public void onItemClick(AdapterView<?> parent, View view,
                            int position, long id) {
                        // The view should be a frame layout
                        if (view instanceof ViewGroup) {
                            ViewGroup vg = (ViewGroup) view;

                            // AdapterViews contain their children in a frame
                            // so we need to go one layer deeper here.
                            if (parent instanceof AdapterViewAnimator) {
                                vg = (ViewGroup) vg.getChildAt(0);
                            }
                            if (vg == null) return;

                            Intent fillInIntent = null;
                            int childCount = vg.getChildCount();
                            for (int i = 0; i < childCount; i++) {
                                Object tag = vg.getChildAt(i).getTag(com.android.internal.R.id.fillInIntent);
                                if (tag instanceof Intent) {
                                    fillInIntent = (Intent) tag;
                                    break;
                                }
                            }
                            if (fillInIntent == null) return;

                            final float appScale = view.getContext().getResources()
                                    .getCompatibilityInfo().applicationScale;
                            final int[] pos = new int[2];
                            view.getLocationOnScreen(pos);

                            final Rect rect = new Rect();
                            rect.left = (int) (pos[0] * appScale + 0.5f);
                            rect.top = (int) (pos[1] * appScale + 0.5f);
                            rect.right = (int) ((pos[0] + view.getWidth()) * appScale + 0.5f);
                            rect.bottom = (int) ((pos[1] + view.getHeight()) * appScale + 0.5f);

                            final Intent intent = new Intent();
                            intent.setSourceBounds(rect);
                            startIntentSafely(view.getContext(), pendingIntentTemplate, fillInIntent);
                        }
                    }
                };
                av.setOnItemClickListener(listener);
                av.setTag(pendingIntentTemplate);
            } else {
                Log.e("RemoteViews", "Cannot setPendingIntentTemplate on a view which is not" +
                        "an AdapterView (id: " + viewId + ")");
                return;
            }
        }

        int viewId;
        PendingIntent pendingIntentTemplate;

        public final static int TAG = 8;
    }

    private class SetRemoteViewsAdapterIntent extends Action {
        public SetRemoteViewsAdapterIntent(int id, Intent intent) {
            this.viewId = id;
            this.intent = intent;
        }

        public SetRemoteViewsAdapterIntent(Parcel parcel) {
            viewId = parcel.readInt();
            intent = Intent.CREATOR.createFromParcel(parcel);
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(TAG);
            dest.writeInt(viewId);
            intent.writeToParcel(dest, flags);
        }

        @Override
        public void apply(View root, ViewGroup rootParent) {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            // Ensure that we are applying to an AppWidget root
            if (!(rootParent instanceof AppWidgetHostView)) {
                Log.e("RemoteViews", "SetRemoteViewsAdapterIntent action can only be used for " +
                        "AppWidgets (root id: " + viewId + ")");
                return;
            }
            // Ensure that we are calling setRemoteAdapter on an AdapterView that supports it
            if (!(target instanceof AbsListView) && !(target instanceof AdapterViewAnimator)) {
                Log.e("RemoteViews", "Cannot setRemoteViewsAdapter on a view which is not " +
                        "an AbsListView or AdapterViewAnimator (id: " + viewId + ")");
                return;
            }

            // Embed the AppWidget Id for use in RemoteViewsAdapter when connecting to the intent
            // RemoteViewsService
            AppWidgetHostView host = (AppWidgetHostView) rootParent;
            intent.putExtra(EXTRA_REMOTEADAPTER_APPWIDGET_ID, host.getAppWidgetId());
            if (target instanceof AbsListView) {
                AbsListView v = (AbsListView) target;
                v.setRemoteViewsAdapter(intent);
            } else if (target instanceof AdapterViewAnimator) {
                AdapterViewAnimator v = (AdapterViewAnimator) target;
                v.setRemoteViewsAdapter(intent);
            }
        }

        int viewId;
        Intent intent;

        public final static int TAG = 10;
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
        public void apply(View root, ViewGroup rootParent) {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            // If the view is an AdapterView, setting a PendingIntent on click doesn't make much
            // sense, do they mean to set a PendingIntent template for the AdapterView's children?
            if (mIsWidgetCollectionChild) {
                Log.e("RemoteViews", "Cannot setOnClickPendingIntent for collection item " +
				"(id: " + viewId + ")");
                // TODO: return; We'll let this slide until apps are up to date.
            }

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
                        startIntentSafely(v.getContext(), pendingIntent, intent);
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
        public void apply(View root, ViewGroup rootParent) {
            final View target = root.findViewById(viewId);
            if (target == null) return;
            
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
    
    private class ReflectionActionWithoutParams extends Action {
        int viewId;
        String methodName;

        public final static int TAG = 5;

        ReflectionActionWithoutParams(int viewId, String methodName) {
            this.viewId = viewId;
            this.methodName = methodName;
        }

        ReflectionActionWithoutParams(Parcel in) {
            this.viewId = in.readInt();
            this.methodName = in.readString();
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(TAG);
            out.writeInt(this.viewId);
            out.writeString(this.methodName);
        }

        @Override
        public void apply(View root, ViewGroup rootParent) {
            final View view = root.findViewById(viewId);
            if (view == null) return;

            Class klass = view.getClass();
            Method method;
            try {
                method = klass.getMethod(this.methodName);
            } catch (NoSuchMethodException ex) {
                throw new ActionException("view: " + klass.getName() + " doesn't have method: "
                        + this.methodName + "()");
            }

            if (!method.isAnnotationPresent(RemotableViewMethod.class)) {
                throw new ActionException("view: " + klass.getName()
                        + " can't use method with RemoteViews: "
                        + this.methodName + "()");
            }

            try {
                //noinspection ConstantIfStatement
                if (false) {
                    Log.d("RemoteViews", "view: " + klass.getName() + " calling method: "
                        + this.methodName + "()");
                }
                method.invoke(view);
            } catch (Exception ex) {
                throw new ActionException(ex);
            }
        }
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
        static final int INTENT = 14;

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
                case INTENT:
                    this.value = Intent.CREATOR.createFromParcel(in);
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
                case INTENT:
                    ((Intent)this.value).writeToParcel(out, flags);
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
                case INTENT:
                    return Intent.class;
                default:
                    return null;
            }
        }

        @Override
        public void apply(View root, ViewGroup rootParent) {
            final View view = root.findViewById(viewId);
            if (view == null) return;

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

        @Override
        public void updateMemoryUsageEstimate(MemoryUsageCounter counter) {
            // We currently only calculate Bitmap memory usage
            switch (this.type) {
                case BITMAP:
                    if (this.value != null) {
                        final Bitmap b = (Bitmap) this.value;
                        final Bitmap.Config c = b.getConfig();
                        // If we don't know, be pessimistic and assume 4
                        int bpp = 4;
                        if (c != null) {
                            switch (c) {
                            case ALPHA_8:
                                bpp = 1;
                                break;
                            case RGB_565:
                            case ARGB_4444:
                                bpp = 2;
                                break;
                            case ARGB_8888:
                                bpp = 4;
                                break;
                            }
                        }
                        counter.bitmapIncrement(b.getWidth() * b.getHeight() * bpp);
                    }
                    break;
                default:
                    break;
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
        public void apply(View root, ViewGroup rootParent) {
            final Context context = root.getContext();
            final ViewGroup target = (ViewGroup) root.findViewById(viewId);
            if (target == null) return;
            if (nestedViews != null) {
                // Inflate nested views and add as children
                target.addView(nestedViews.apply(context, target));
            } else {
                // Clear all children when nested views omitted
                target.removeAllViews();
            }
        }

        @Override
        public void updateMemoryUsageEstimate(MemoryUsageCounter counter) {
            if (nestedViews != null) {
                counter.bitmapIncrement(nestedViews.estimateBitmapMemoryUsage());
            }
        }

        int viewId;
        RemoteViews nestedViews;

        public final static int TAG = 4;
    }

    /**
     * Simple class used to keep track of memory usage in a RemoteViews.
     *
     */
    private class MemoryUsageCounter {
        public void clear() {
            mBitmapHeapMemoryUsage = 0;
        }

        public void bitmapIncrement(int numBytes) {
            mBitmapHeapMemoryUsage += numBytes;
        }

        public int getBitmapHeapMemoryUsage() {
            return mBitmapHeapMemoryUsage;
        }

        int mBitmapHeapMemoryUsage;
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

        // setup the memory usage statistics
        mMemoryUsageCounter = new MemoryUsageCounter();
        recalculateMemoryUsage();
    }

    /**
     * Reads a RemoteViews object from a parcel.
     * 
     * @param parcel
     */
    public RemoteViews(Parcel parcel) {
        mPackage = parcel.readString();
        mLayoutId = parcel.readInt();
        mIsWidgetCollectionChild = parcel.readInt() == 1 ? true : false;

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
                case ReflectionActionWithoutParams.TAG:
                    mActions.add(new ReflectionActionWithoutParams(parcel));
                    break;
                case SetEmptyView.TAG:
                    mActions.add(new SetEmptyView(parcel));
                    break;
                case SetPendingIntentTemplate.TAG:
                    mActions.add(new SetPendingIntentTemplate(parcel));
                    break;
                case SetOnClickFillInIntent.TAG:
                    mActions.add(new SetOnClickFillInIntent(parcel));
                    break;
                case SetRemoteViewsAdapterIntent.TAG:
                    mActions.add(new SetRemoteViewsAdapterIntent(parcel));
                    break;
                default:
                    throw new ActionException("Tag " + tag + " not found");
                }
            }
        }

        // setup the memory usage statistics
        mMemoryUsageCounter = new MemoryUsageCounter();
        recalculateMemoryUsage();
    }

    @Override
    public RemoteViews clone() {
        final RemoteViews that = new RemoteViews(mPackage, mLayoutId);
        if (mActions != null) {
            that.mActions = (ArrayList<Action>)mActions.clone();
        }

        // update the memory usage stats of the cloned RemoteViews
        that.recalculateMemoryUsage();
        return that;
    }

    public String getPackage() {
        return mPackage;
    }

    public int getLayoutId() {
        return mLayoutId;
    }

    /*
     * This flag indicates whether this RemoteViews object is being created from a
     * RemoteViewsService for use as a child of a widget collection. This flag is used
     * to determine whether or not certain features are available, in particular,
     * setting on click extras and setting on click pending intents. The former is enabled,
     * and the latter disabled when this flag is true.
     */
    void setIsWidgetCollectionChild(boolean isWidgetCollectionChild) {
        mIsWidgetCollectionChild = isWidgetCollectionChild;
    }

    /**
     * Updates the memory usage statistics.
     */
    private void recalculateMemoryUsage() {
        mMemoryUsageCounter.clear();

        // Accumulate the memory usage for each action
        if (mActions != null) {
            final int count = mActions.size();
            for (int i= 0; i < count; ++i) {
                mActions.get(i).updateMemoryUsageEstimate(mMemoryUsageCounter);
            }
        }
    }

    /**
     * Returns an estimate of the bitmap heap memory usage for this RemoteViews.
     */
    int estimateBitmapMemoryUsage() {
        return mMemoryUsageCounter.getBitmapHeapMemoryUsage();
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

        // update the memory usage stats
        a.updateMemoryUsageEstimate(mMemoryUsageCounter);
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
     * Equivalent to calling {@link AdapterViewAnimator#showNext()}
     *
     * @param viewId The id of the view on which to call {@link AdapterViewAnimator#showNext()}
     */
    public void showNext(int viewId) {
        addAction(new ReflectionActionWithoutParams(viewId, "showNext"));
    }

    /**
     * Equivalent to calling {@link AdapterViewAnimator#showPrevious()}
     *
     * @param viewId The id of the view on which to call {@link AdapterViewAnimator#showPrevious()}
     */
    public void showPrevious(int viewId) {
        addAction(new ReflectionActionWithoutParams(viewId, "showPrevious"));
    }

    /**
     * Equivalent to calling {@link AdapterViewAnimator#setDisplayedChild(int)}
     *
     * @param viewId The id of the view on which to call
     *               {@link AdapterViewAnimator#setDisplayedChild(int)}
     */
    public void setDisplayedChild(int viewId, int childIndex) {
        setInt(viewId, "setDisplayedChild", childIndex);
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
     * @param viewId The id of the view whose bitmap should change
     * @param bitmap The new Bitmap for the drawable
     */
    public void setImageViewBitmap(int viewId, Bitmap bitmap) {
        setBitmap(viewId, "setImageBitmap", bitmap);
    }

    /**
     * Equivalent to calling AdapterView.setEmptyView
     *
     * @param viewId The id of the view on which to set the empty view
     * @param emptyViewId The view id of the empty view
     */
    public void setEmptyView(int viewId, int emptyViewId) {
        addAction(new SetEmptyView(viewId, emptyViewId));
    }

    /**
     * Equivalent to calling {@link Chronometer#setBase Chronometer.setBase},
     * {@link Chronometer#setFormat Chronometer.setFormat},
     * and {@link Chronometer#start Chronometer.start()} or
     * {@link Chronometer#stop Chronometer.stop()}.
     * 
     * @param viewId The id of the {@link Chronometer} to change
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
     * @param viewId The id of the {@link ProgressBar} to change
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
     * When setting the on-click action of items within collections (eg. {@link ListView},
     * {@link StackView} etc.), this method will not work. Instead, use {@link
     * RemoteViews#setPendingIntentTemplate(int, PendingIntent) in conjunction with
     * RemoteViews#setOnClickFillInIntent(int, Intent).
     *
     * @param viewId The id of the view that will trigger the {@link PendingIntent} when clicked
     * @param pendingIntent The {@link PendingIntent} to send when user clicks
     */
    public void setOnClickPendingIntent(int viewId, PendingIntent pendingIntent) {
        addAction(new SetOnClickPendingIntent(viewId, pendingIntent));
    }

    /**
     * When using collections (eg. {@link ListView}, {@link StackView} etc.) in widgets, it is very
     * costly to set PendingIntents on the individual items, and is hence not permitted. Instead
     * this method should be used to set a single PendingIntent template on the collection, and
     * individual items can differentiate their on-click behavior using
     * {@link RemoteViews#setOnClickFillInIntent(int, Intent)}.
     *
     * @param viewId The id of the collection who's children will use this PendingIntent template
     *          when clicked
     * @param pendingIntentTemplate The {@link PendingIntent} to be combined with extras specified
     *          by a child of viewId and executed when that child is clicked
     */
    public void setPendingIntentTemplate(int viewId, PendingIntent pendingIntentTemplate) {
        addAction(new SetPendingIntentTemplate(viewId, pendingIntentTemplate));
    }

    /**
     * When using collections (eg. {@link ListView}, {@link StackView} etc.) in widgets, it is very
     * costly to set PendingIntents on the individual items, and is hence not permitted. Instead
     * a single PendingIntent template can be set on the collection, see {@link
     * RemoteViews#setPendingIntentTemplate(int, PendingIntent)}, and the individual on-click
     * action of a given item can be distinguished by setting a fillInIntent on that item. The
     * fillInIntent is then combined with the PendingIntent template in order to determine the final
     * intent which will be executed when the item is clicked. This works as follows: any fields
     * which are left blank in the PendingIntent template, but are provided by the fillInIntent
     * will be overwritten, and the resulting PendingIntent will be used.
     *
     *
     * of the PendingIntent template will then be filled in with the associated fields that are
     * set in fillInIntent. See {@link Intent#fillIn(Intent, int)} for more details.
     *
     * @param viewId The id of the view on which to set the fillInIntent
     * @param fillInIntent The intent which will be combined with the parent's PendingIntent
     *        in order to determine the on-click behavior of the view specified by viewId
     */
    public void setOnClickFillInIntent(int viewId, Intent fillInIntent) {
        addAction(new SetOnClickFillInIntent(viewId, fillInIntent));
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
     * @param viewId The id of the view whose text color should change
     * @param color Sets the text color for all the states (normal, selected,
     *            focused) to be this color.
     */
    public void setTextColor(int viewId, int color) {
        setInt(viewId, "setTextColor", color);
    }

    /**
     * Equivalent to calling {@link android.widget.AbsListView#setRemoteViewsAdapter(Intent)}.
     *
     * @param appWidgetId The id of the app widget which contains the specified view. (This
     *      parameter is ignored in this deprecated method)
     * @param viewId The id of the {@link AbsListView}
     * @param intent The intent of the service which will be
     *            providing data to the RemoteViewsAdapter
     * @deprecated This method has been deprecated. See
     *      {@link android.widget.RemoteViews#setRemoteAdapter(int, Intent)}
     */
    @Deprecated
    public void setRemoteAdapter(int appWidgetId, int viewId, Intent intent) {
        setRemoteAdapter(viewId, intent);
    }

    /**
     * Equivalent to calling {@link android.widget.AbsListView#setRemoteViewsAdapter(Intent)}.
     * Can only be used for App Widgets.
     *
     * @param viewId The id of the {@link AbsListView}
     * @param intent The intent of the service which will be
     *            providing data to the RemoteViewsAdapter
     */
    public void setRemoteAdapter(int viewId, Intent intent) {
        addAction(new SetRemoteViewsAdapterIntent(viewId, intent));
    }

    /**
     * Equivalent to calling {@link android.widget.AbsListView#smoothScrollToPosition(int, int)}.
     *
     * @param viewId The id of the view to change
     * @param position Scroll to this adapter position
     */
    public void setScrollPosition(int viewId, int position) {
        setInt(viewId, "smoothScrollToPosition", position);
    }

    /**
     * Equivalent to calling {@link android.widget.AbsListView#smoothScrollToPosition(int, int)}.
     *
     * @param viewId The id of the view to change
     * @param offset Scroll by this adapter position offset
     */
    public void setRelativeScrollPosition(int viewId, int offset) {
        setInt(viewId, "smoothScrollByOffset", offset);
    }

    /**
     * Call a method taking one boolean on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setBoolean(int viewId, String methodName, boolean value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.BOOLEAN, value));
    }

    /**
     * Call a method taking one byte on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setByte(int viewId, String methodName, byte value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.BYTE, value));
    }

    /**
     * Call a method taking one short on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setShort(int viewId, String methodName, short value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.SHORT, value));
    }

    /**
     * Call a method taking one int on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setInt(int viewId, String methodName, int value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.INT, value));
    }

    /**
     * Call a method taking one long on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setLong(int viewId, String methodName, long value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.LONG, value));
    }

    /**
     * Call a method taking one float on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setFloat(int viewId, String methodName, float value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.FLOAT, value));
    }

    /**
     * Call a method taking one double on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setDouble(int viewId, String methodName, double value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.DOUBLE, value));
    }

    /**
     * Call a method taking one char on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setChar(int viewId, String methodName, char value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.CHAR, value));
    }

    /**
     * Call a method taking one String on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setString(int viewId, String methodName, String value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.STRING, value));
    }

    /**
     * Call a method taking one CharSequence on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setCharSequence(int viewId, String methodName, CharSequence value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.CHAR_SEQUENCE, value));
    }

    /**
     * Call a method taking one Uri on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
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
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setBitmap(int viewId, String methodName, Bitmap value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.BITMAP, value));
    }

    /**
     * Call a method taking one Bundle on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setBundle(int viewId, String methodName, Bundle value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.BUNDLE, value));
    }

    /**
     * Call a method taking one Intent on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The {@link android.content.Intent} to pass the method.
     */
    public void setIntent(int viewId, String methodName, Intent value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.INTENT, value));
    }

    /**
     * Equivalent to calling View.setContentDescription
     *
     * @param viewId The id of the view whose content description should change
     * @param contentDescription The new content description for the view
     */
    public void setContentDescription(int viewId, CharSequence contentDescription) {
        setCharSequence(viewId, "setContentDescription", contentDescription);
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

        performApply(result, parent);

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
        performApply(v, (ViewGroup) v.getParent());
    }

    private void performApply(View v, ViewGroup parent) {
        if (mActions != null) {
            final int count = mActions.size();
            for (int i = 0; i < count; i++) {
                Action a = mActions.get(i);
                a.apply(v, parent);
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
        dest.writeInt(mIsWidgetCollectionChild ? 1 : 0);
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
