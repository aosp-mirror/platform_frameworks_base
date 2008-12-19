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

package android.view;

import android.util.Log;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.os.Environment;

import java.io.File;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.DataOutputStream;
import java.io.OutputStreamWriter;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.LinkedList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

/**
 * Various debugging/tracing tools related to {@link View} and the view hierarchy.
 */
public class ViewDebug {
    /**
     * Enables or disables view hierarchy tracing. Any invoker of
     * {@link #trace(View, android.view.ViewDebug.HierarchyTraceType)} should first
     * check that this value is set to true as not to affect performance.
     */
    public static final boolean TRACE_HIERARCHY = false;

    /**
     * Enables or disables view recycler tracing. Any invoker of
     * {@link #trace(View, android.view.ViewDebug.RecyclerTraceType, int[])} should first
     * check that this value is set to true as not to affect performance.
     */
    public static final boolean TRACE_RECYCLER = false;
    
    /**
     * The system property of dynamic switch for capturing view information
     * when it is set, we dump interested fields and methods for the view on focus
     */    
    static final String SYSTEM_PROPERTY_CAPTURE_VIEW = "debug.captureview";
        
    /**
     * The system property of dynamic switch for capturing event information
     * when it is set, we log key events, touch/motion and trackball events
     */    
    static final String SYSTEM_PROPERTY_CAPTURE_EVENT = "debug.captureevent";
    
    /**
     * This annotation can be used to mark fields and methods to be dumped by
     * the view server. Only non-void methods with no arguments can be annotated
     * by this annotation.
     */
    @Target({ ElementType.FIELD, ElementType.METHOD })
    @Retention(RetentionPolicy.RUNTIME)
    public @interface ExportedProperty {
        /**
         * When resolveId is true, and if the annotated field/method return value
         * is an int, the value is converted to an Android's resource name.
         *
         * @return true if the property's value must be transformed into an Android
         *         resource name, false otherwise
         */
        boolean resolveId() default false;

        /**
         * A mapping can be defined to map int values to specific strings. For
         * instance, View.getVisibility() returns 0, 4 or 8. However, these values
         * actually mean VISIBLE, INVISIBLE and GONE. A mapping can be used to see
         * these human readable values:
         *
         * <pre>
         * @ViewDebug.ExportedProperty(mapping = {
         *     @ViewDebug.IntToString(from = 0, to = "VISIBLE"),
         *     @ViewDebug.IntToString(from = 4, to = "INVISIBLE"),
         *     @ViewDebug.IntToString(from = 8, to = "GONE")
         * })
         * public int getVisibility() { ...
         * <pre>
         *
         * @return An array of int to String mappings
         *
         * @see android.view.ViewDebug.IntToString
         */
        IntToString[] mapping() default { };

        /**
         * When deep export is turned on, this property is not dumped. Instead, the
         * properties contained in this property are dumped. Each child property
         * is prefixed with the name of this property.
         *
         * @return true if the properties of this property should be dumped
         *
         * @see #prefix() 
         */
        boolean deepExport() default false;

        /**
         * The prefix to use on child properties when deep export is enabled
         *
         * @return a prefix as a String
         *
         * @see #deepExport()
         */
        String prefix() default "";
    }

    /**
     * Defines a mapping from an int value to a String. Such a mapping can be used
     * in a @ExportedProperty to provide more meaningful values to the end user.
     *
     * @see android.view.ViewDebug.ExportedProperty
     */
    @Target({ ElementType.TYPE })
    @Retention(RetentionPolicy.RUNTIME)
    public @interface IntToString {
        /**
         * The original int value to map to a String.
         *
         * @return An arbitrary int value.
         */
        int from();

        /**
         * The String to use in place of the original int value.
         *
         * @return An arbitrary non-null String.
         */
        String to();
    }
    
    /**
     * This annotation can be used to mark fields and methods to be dumped when
     * the view is captured. Methods with this annotation must have no arguments
     * and must return <some type of data>.
     * 
     * @hide pending API Council approval
     */
    @Target({ ElementType.FIELD, ElementType.METHOD })
    @Retention(RetentionPolicy.RUNTIME)
    public @interface CapturedViewProperty {
        /**
         * When retrieveReturn is true, we need to retrieve second level methods 
         * e.g., we need myView.getFirstLevelMethod().getSecondLevelMethod()
         * we will set retrieveReturn = true on the annotation of 
         * myView.getFirstLevelMethod()
         * @return true if we need the second level methods 
         */
        boolean retrieveReturn() default false;        
    }
        
    private static HashMap<Class<?>, Method[]> mCapturedViewMethodsForClasses = null;
    private static HashMap<Class<?>, Field[]> mCapturedViewFieldsForClasses = null;

    // Maximum delay in ms after which we stop trying to capture a View's drawing
    private static final int CAPTURE_TIMEOUT = 4000;

    private static final String REMOTE_COMMAND_CAPTURE = "CAPTURE";
    private static final String REMOTE_COMMAND_DUMP = "DUMP";
    private static final String REMOTE_COMMAND_INVALIDATE = "INVALIDATE";
    private static final String REMOTE_COMMAND_REQUEST_LAYOUT = "REQUEST_LAYOUT";

    private static HashMap<Class<?>, Field[]> sFieldsForClasses;
    private static HashMap<Class<?>, Method[]> sMethodsForClasses;
        
    /**
     * Defines the type of hierarhcy trace to output to the hierarchy traces file.
     */
    public enum HierarchyTraceType {
        INVALIDATE,
        INVALIDATE_CHILD,
        INVALIDATE_CHILD_IN_PARENT,
        REQUEST_LAYOUT,
        ON_LAYOUT,
        ON_MEASURE,
        DRAW,
        BUILD_CACHE
    }

    private static BufferedWriter sHierarchyTraces;
    private static ViewRoot sHierarhcyRoot;
    private static String sHierarchyTracePrefix;

    /**
     * Defines the type of recycler trace to output to the recycler traces file.
     */
    public enum RecyclerTraceType {
        NEW_VIEW,
        BIND_VIEW,
        RECYCLE_FROM_ACTIVE_HEAP,
        RECYCLE_FROM_SCRAP_HEAP,
        MOVE_TO_ACTIVE_HEAP,
        MOVE_TO_SCRAP_HEAP,
        MOVE_FROM_ACTIVE_TO_SCRAP_HEAP
    }

    private static class RecyclerTrace {
        public int view;
        public RecyclerTraceType type;
        public int position;
        public int indexOnScreen;
    }

    private static View sRecyclerOwnerView;
    private static List<View> sRecyclerViews;
    private static List<RecyclerTrace> sRecyclerTraces;
    private static String sRecyclerTracePrefix;

    /**
     * Returns the number of instanciated Views.
     *
     * @return The number of Views instanciated in the current process.
     *
     * @hide
     */
    public static long getViewInstanceCount() {
        return View.sInstanceCount;
    }

    /**
     * Returns the number of instanciated ViewRoots.
     *
     * @return The number of ViewRoots instanciated in the current process.
     *
     * @hide
     */
    public static long getViewRootInstanceCount() {
        return ViewRoot.getInstanceCount();
    }    

    /**
     * Outputs a trace to the currently opened recycler traces. The trace records the type of
     * recycler action performed on the supplied view as well as a number of parameters.
     *
     * @param view the view to trace
     * @param type the type of the trace
     * @param parameters parameters depending on the type of the trace
     */
    public static void trace(View view, RecyclerTraceType type, int... parameters) {
        if (sRecyclerOwnerView == null || sRecyclerViews == null) {
            return;
        }

        if (!sRecyclerViews.contains(view)) {
            sRecyclerViews.add(view);
        }

        final int index = sRecyclerViews.indexOf(view);

        RecyclerTrace trace = new RecyclerTrace();
        trace.view = index;
        trace.type = type;
        trace.position = parameters[0];
        trace.indexOnScreen = parameters[1];

        sRecyclerTraces.add(trace);
    }

    /**
     * Starts tracing the view recycler of the specified view. The trace is identified by a prefix,
     * used to build the traces files names: <code>/EXTERNAL/view-recycler/PREFIX.traces</code> and
     * <code>/EXTERNAL/view-recycler/PREFIX.recycler</code>.
     *
     * Only one view recycler can be traced at the same time. After calling this method, any
     * other invocation will result in a <code>IllegalStateException</code> unless
     * {@link #stopRecyclerTracing()} is invoked before.
     *
     * Traces files are created only after {@link #stopRecyclerTracing()} is invoked.
     *
     * This method will return immediately if TRACE_RECYCLER is false.
     *
     * @param prefix the traces files name prefix
     * @param view the view whose recycler must be traced
     *
     * @see #stopRecyclerTracing()
     * @see #trace(View, android.view.ViewDebug.RecyclerTraceType, int[])
     */
    public static void startRecyclerTracing(String prefix, View view) {
        //noinspection PointlessBooleanExpression,ConstantConditions
        if (!TRACE_RECYCLER) {
            return;
        }

        if (sRecyclerOwnerView != null) {
            throw new IllegalStateException("You must call stopRecyclerTracing() before running" +
                " a new trace!");
        }

        sRecyclerTracePrefix = prefix;
        sRecyclerOwnerView = view;
        sRecyclerViews = new ArrayList<View>();
        sRecyclerTraces = new LinkedList<RecyclerTrace>();
    }

    /**
     * Stops the current view recycer tracing.
     *
     * Calling this method creates the file <code>/EXTERNAL/view-recycler/PREFIX.traces</code>
     * containing all the traces (or method calls) relative to the specified view's recycler.
     *
     * Calling this method creates the file <code>/EXTERNAL/view-recycler/PREFIX.recycler</code>
     * containing all of the views used by the recycler of the view supplied to
     * {@link #startRecyclerTracing(String, View)}.
     *
     * This method will return immediately if TRACE_RECYCLER is false.
     *
     * @see #startRecyclerTracing(String, View)
     * @see #trace(View, android.view.ViewDebug.RecyclerTraceType, int[])
     */
    public static void stopRecyclerTracing() {
        //noinspection PointlessBooleanExpression,ConstantConditions
        if (!TRACE_RECYCLER) {
            return;
        }

        if (sRecyclerOwnerView == null || sRecyclerViews == null) {
            throw new IllegalStateException("You must call startRecyclerTracing() before" +
                " stopRecyclerTracing()!");
        }

        File recyclerDump = new File(Environment.getExternalStorageDirectory(), "view-recycler/");
        recyclerDump.mkdirs();

        recyclerDump = new File(recyclerDump, sRecyclerTracePrefix + ".recycler");
        try {
            final BufferedWriter out = new BufferedWriter(new FileWriter(recyclerDump), 8 * 1024);

            for (View view : sRecyclerViews) {
                final String name = view.getClass().getName();
                out.write(name);
                out.newLine();
            }

            out.close();
        } catch (IOException e) {
            Log.e("View", "Could not dump recycler content");
            return;
        }

        recyclerDump = new File(Environment.getExternalStorageDirectory(), "view-recycler/");
        recyclerDump = new File(recyclerDump, sRecyclerTracePrefix + ".traces");
        try {
            final FileOutputStream file = new FileOutputStream(recyclerDump);
            final DataOutputStream out = new DataOutputStream(file);

            for (RecyclerTrace trace : sRecyclerTraces) {
                out.writeInt(trace.view);
                out.writeInt(trace.type.ordinal());
                out.writeInt(trace.position);
                out.writeInt(trace.indexOnScreen);
                out.flush();
            }

            out.close();
        } catch (IOException e) {
            Log.e("View", "Could not dump recycler traces");
            return;
        }

        sRecyclerViews.clear();
        sRecyclerViews = null;

        sRecyclerTraces.clear();
        sRecyclerTraces = null;

        sRecyclerOwnerView = null;
    }

    /**
     * Outputs a trace to the currently opened traces file. The trace contains the class name
     * and instance's hashcode of the specified view as well as the supplied trace type.
     *
     * @param view the view to trace
     * @param type the type of the trace
     */
    public static void trace(View view, HierarchyTraceType type) {
        if (sHierarchyTraces == null) {
            return;
        }

        try {
            sHierarchyTraces.write(type.name());
            sHierarchyTraces.write(' ');
            sHierarchyTraces.write(view.getClass().getName());
            sHierarchyTraces.write('@');
            sHierarchyTraces.write(Integer.toHexString(view.hashCode()));
            sHierarchyTraces.newLine();
        } catch (IOException e) {
            Log.w("View", "Error while dumping trace of type " + type + " for view " + view);
        }
    }

    /**
     * Starts tracing the view hierarchy of the specified view. The trace is identified by a prefix,
     * used to build the traces files names: <code>/EXTERNAL/view-hierarchy/PREFIX.traces</code> and
     * <code>/EXTERNAL/view-hierarchy/PREFIX.tree</code>.
     *
     * Only one view hierarchy can be traced at the same time. After calling this method, any
     * other invocation will result in a <code>IllegalStateException</code> unless
     * {@link #stopHierarchyTracing()} is invoked before.
     *
     * Calling this method creates the file <code>/EXTERNAL/view-hierarchy/PREFIX.traces</code>
     * containing all the traces (or method calls) relative to the specified view's hierarchy.
     *
     * This method will return immediately if TRACE_HIERARCHY is false.
     *
     * @param prefix the traces files name prefix
     * @param view the view whose hierarchy must be traced
     *
     * @see #stopHierarchyTracing()
     * @see #trace(View, android.view.ViewDebug.HierarchyTraceType)
     */
    public static void startHierarchyTracing(String prefix, View view) {
        //noinspection PointlessBooleanExpression,ConstantConditions
        if (!TRACE_HIERARCHY) {
            return;
        }

        if (sHierarhcyRoot != null) {
            throw new IllegalStateException("You must call stopHierarchyTracing() before running" +
                " a new trace!");
        }

        File hierarchyDump = new File(Environment.getExternalStorageDirectory(), "view-hierarchy/");
        hierarchyDump.mkdirs();

        hierarchyDump = new File(hierarchyDump, prefix + ".traces");
        sHierarchyTracePrefix = prefix;

        try {
            sHierarchyTraces = new BufferedWriter(new FileWriter(hierarchyDump), 8 * 1024);
        } catch (IOException e) {
            Log.e("View", "Could not dump view hierarchy");
            return;
        }

        sHierarhcyRoot = (ViewRoot) view.getRootView().getParent();
    }

    /**
     * Stops the current view hierarchy tracing. This method closes the file
     * <code>/EXTERNAL/view-hierarchy/PREFIX.traces</code>.
     *
     * Calling this method creates the file <code>/EXTERNAL/view-hierarchy/PREFIX.tree</code>
     * containing the view hierarchy of the view supplied to
     * {@link #startHierarchyTracing(String, View)}.
     *
     * This method will return immediately if TRACE_HIERARCHY is false.
     *
     * @see #startHierarchyTracing(String, View) 
     * @see #trace(View, android.view.ViewDebug.HierarchyTraceType)
     */
    public static void stopHierarchyTracing() {
        //noinspection PointlessBooleanExpression,ConstantConditions
        if (!TRACE_HIERARCHY) {
            return;
        }

        if (sHierarhcyRoot == null || sHierarchyTraces == null) {
            throw new IllegalStateException("You must call startHierarchyTracing() before" +
                " stopHierarchyTracing()!");
        }

        try {
            sHierarchyTraces.close();
        } catch (IOException e) {
            Log.e("View", "Could not write view traces");
        }
        sHierarchyTraces = null;

        File hierarchyDump = new File(Environment.getExternalStorageDirectory(), "view-hierarchy/");
        hierarchyDump.mkdirs();
        hierarchyDump = new File(hierarchyDump, sHierarchyTracePrefix + ".tree");

        BufferedWriter out;
        try {
            out = new BufferedWriter(new FileWriter(hierarchyDump), 8 * 1024);
        } catch (IOException e) {
            Log.e("View", "Could not dump view hierarchy");
            return;
        }

        View view = sHierarhcyRoot.getView();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            dumpViewHierarchy(group, out, 0);
            try {
                out.close();
            } catch (IOException e) {
                Log.e("View", "Could not dump view hierarchy");
            }
        }

        sHierarhcyRoot = null;
    }
    
    static void dispatchCommand(View view, String command, String parameters,
            OutputStream clientStream) throws IOException {

        // Paranoid but safe...
        view = view.getRootView();

        if (REMOTE_COMMAND_DUMP.equalsIgnoreCase(command)) {
            dump(view, clientStream);
        } else {
            final String[] params = parameters.split(" ");
            if (REMOTE_COMMAND_CAPTURE.equalsIgnoreCase(command)) {
                capture(view, clientStream, params[0]);
            } else if (REMOTE_COMMAND_INVALIDATE.equalsIgnoreCase(command)) {
                invalidate(view, params[0]);
            } else if (REMOTE_COMMAND_REQUEST_LAYOUT.equalsIgnoreCase(command)) {
                requestLayout(view, params[0]);
            }
        }
    }

    private static View findViewByHashCode(View root, String parameter) {
        final String[] ids = parameter.split("@");
        final String className = ids[0];
        final int hashCode = Integer.parseInt(ids[1], 16);

        View view = root.getRootView();
        if (view instanceof ViewGroup) {
            return findView((ViewGroup) view, className, hashCode);
        }

        return null;
    }

    private static void invalidate(View root, String parameter) {
        final View view = findViewByHashCode(root, parameter);
        if (view != null) {
            view.postInvalidate();
        }
    }

    private static void requestLayout(View root, String parameter) {
        final View view = findViewByHashCode(root, parameter);
        if (view != null) {
            root.post(new Runnable() {
                public void run() {
                    view.requestLayout();
                }
            });
        }
    }

    private static void capture(View root, final OutputStream clientStream, String parameter)
            throws IOException {

        final CountDownLatch latch = new CountDownLatch(1);
        final View captureView = findViewByHashCode(root, parameter);

        if (captureView != null) {
            final Bitmap[] cache = new Bitmap[1];

            final boolean hasCache = captureView.isDrawingCacheEnabled();
            final boolean willNotCache = captureView.willNotCacheDrawing();

            if (willNotCache) {
                captureView.setWillNotCacheDrawing(false);
            }

            root.post(new Runnable() {
                public void run() {
                    try {
                        if (!hasCache) {
                            captureView.buildDrawingCache();
                        }

                        cache[0] = captureView.getDrawingCache();
                    } finally {
                        latch.countDown();
                    }
                }
            });

            try {
                latch.await(CAPTURE_TIMEOUT, TimeUnit.MILLISECONDS);

                if (cache[0] != null) {
                    BufferedOutputStream out = null;
                    try {
                        out = new BufferedOutputStream(clientStream, 32 * 1024);
                        cache[0].compress(Bitmap.CompressFormat.PNG, 100, out);
                        out.flush();
                    } finally {
                        if (out != null) {
                            out.close();
                        }
                    }
                }
            } catch (InterruptedException e) {
                Log.w("View", "Could not complete the capture of the view " + captureView);                
            } finally {
                if (willNotCache) {
                    captureView.setWillNotCacheDrawing(true);
                }
                if (!hasCache) {
                    captureView.destroyDrawingCache();
                }
            }
        }
    }

    private static void dump(View root, OutputStream clientStream) throws IOException {
        BufferedWriter out = null;
        try {
            out = new BufferedWriter(new OutputStreamWriter(clientStream), 32 * 1024);
            View view = root.getRootView();
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                dumpViewHierarchyWithProperties(group, out, 0);
            }
            out.write("DONE.");
            out.newLine();
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    private static View findView(ViewGroup group, String className, int hashCode) {
        if (isRequestedView(group, className, hashCode)) {
            return group;
        }

        final int count = group.getChildCount();
        for (int i = 0; i < count; i++) {
            final View view = group.getChildAt(i);
            if (view instanceof ViewGroup) {
                final View found = findView((ViewGroup) view, className, hashCode);
                if (found != null) {
                    return found;
                }
            } else if (isRequestedView(view, className, hashCode)) {
                return view;
            }
        }

        return null;
    }

    private static boolean isRequestedView(View view, String className, int hashCode) {
        return view.getClass().getName().equals(className) && view.hashCode() == hashCode;
    }

    private static void dumpViewHierarchyWithProperties(ViewGroup group,
            BufferedWriter out, int level) {
        if (!dumpViewWithProperties(group, out, level)) {
            return;
        }

        final int count = group.getChildCount();
        for (int i = 0; i < count; i++) {
            final View view = group.getChildAt(i);
            if (view instanceof ViewGroup) {
                dumpViewHierarchyWithProperties((ViewGroup) view, out, level + 1);
            } else {
                dumpViewWithProperties(view, out, level + 1);
            }
        }
    }

    private static boolean dumpViewWithProperties(View view, BufferedWriter out, int level) {
        try {
            for (int i = 0; i < level; i++) {
                out.write(' ');
            }
            out.write(view.getClass().getName());
            out.write('@');
            out.write(Integer.toHexString(view.hashCode()));
            out.write(' ');
            dumpViewProperties(view, out);
            out.newLine();
        } catch (IOException e) {
            Log.w("View", "Error while dumping hierarchy tree");
            return false;
        }
        return true;
    }

    private static Field[] getExportedPropertyFields(Class<?> klass) {
        if (sFieldsForClasses == null) {
            sFieldsForClasses = new HashMap<Class<?>, Field[]>();
        }
        final HashMap<Class<?>, Field[]> map = sFieldsForClasses;

        Field[] fields = map.get(klass);
        if (fields != null) {
            return fields;
        }

        final ArrayList<Field> foundFields = new ArrayList<Field>();
        fields = klass.getDeclaredFields();

        int count = fields.length;
        for (int i = 0; i < count; i++) {
            final Field field = fields[i];
            if (field.isAnnotationPresent(ExportedProperty.class)) {
                field.setAccessible(true);
                foundFields.add(field);
            }
        }

        fields = foundFields.toArray(new Field[foundFields.size()]);
        map.put(klass, fields);

        return fields;
    }

    private static Method[] getExportedPropertyMethods(Class<?> klass) {
        if (sMethodsForClasses == null) {
            sMethodsForClasses = new HashMap<Class<?>, Method[]>();
        }
        final HashMap<Class<?>, Method[]> map = sMethodsForClasses;

        Method[] methods = map.get(klass);
        if (methods != null) {
            return methods;
        }

        final ArrayList<Method> foundMethods = new ArrayList<Method>();
        methods = klass.getDeclaredMethods();
        
        int count = methods.length;
        for (int i = 0; i < count; i++) {
            final Method method = methods[i];            
            if (method.getParameterTypes().length == 0 &&
                        method.isAnnotationPresent(ExportedProperty.class) &&
                        method.getReturnType() != Void.class) {
                method.setAccessible(true);
                foundMethods.add(method);
            }
        }

        methods = foundMethods.toArray(new Method[foundMethods.size()]);
        map.put(klass, methods);

        return methods;
    }

    private static void dumpViewProperties(Object view, BufferedWriter out) throws IOException {
        dumpViewProperties(view, out, "");
    }

    private static void dumpViewProperties(Object view, BufferedWriter out, String prefix)
            throws IOException {
        Class<?> klass = view.getClass();

        do {
            exportFields(view, out, klass, prefix);
            exportMethods(view, out, klass, prefix);
            klass = klass.getSuperclass();
        } while (klass != Object.class);
    }
    
    private static void exportMethods(Object view, BufferedWriter out, Class<?> klass,
            String prefix) throws IOException {

        final Method[] methods = getExportedPropertyMethods(klass);

        int count = methods.length;
        for (int i = 0; i < count; i++) {
            final Method method = methods[i];
            //noinspection EmptyCatchBlock
            try {
                // TODO: This should happen on the UI thread
                Object methodValue = method.invoke(view, (Object[]) null);
                final Class<?> returnType = method.getReturnType();

                if (returnType == int.class) {
                    ExportedProperty property = method.getAnnotation(ExportedProperty.class);
                    if (property.resolveId() && view instanceof View) {
                        final Resources resources = ((View) view).getContext().getResources();
                        final int id = (Integer) methodValue;
                        if (id >= 0) {
                            try {
                                methodValue = resources.getResourceTypeName(id) + '/' +
                                        resources.getResourceEntryName(id);
                            } catch (Resources.NotFoundException e) {
                                methodValue = "UNKNOWN";
                            }
                        } else {
                            methodValue = "NO_ID";
                        }
                    } else {
                        final IntToString[] mapping = property.mapping();
                        if (mapping.length > 0) {
                            final int intValue = (Integer) methodValue;
                            boolean mapped = false;
                            int mappingCount = mapping.length;
                            for (int j = 0; j < mappingCount; j++) {
                                final IntToString mapper = mapping[j];
                                if (mapper.from() == intValue) {
                                    methodValue = mapper.to();
                                    mapped = true;
                                    break;
                                }
                            }

                            if (!mapped) {
                                methodValue = intValue;
                            }
                        }
                    }
                } else if (!returnType.isPrimitive()) {
                    ExportedProperty property = method.getAnnotation(ExportedProperty.class);
                    if (property.deepExport()) {
                        dumpViewProperties(methodValue, out, prefix + property.prefix());
                        continue;
                    }
                }

                out.write(prefix);
                out.write(method.getName());
                out.write("()=");

                if (methodValue != null) {
                    final String value = methodValue.toString().replace("\n", "\\n");
                    out.write(String.valueOf(value.length()));
                    out.write(",");
                    out.write(value);
                } else {
                    out.write("4,null");
                }

                out.write(' ');
            } catch (IllegalAccessException e) {
            } catch (InvocationTargetException e) {
            }
        }
    }

    private static void exportFields(Object view, BufferedWriter out, Class<?> klass, String prefix)
            throws IOException {
        final Field[] fields = getExportedPropertyFields(klass);

        int count = fields.length;
        for (int i = 0; i < count; i++) {
            final Field field = fields[i];

            //noinspection EmptyCatchBlock
            try {
                Object fieldValue = null;
                final Class<?> type = field.getType();

                if (type == int.class) {
                    ExportedProperty property = field.getAnnotation(ExportedProperty.class);
                    if (property.resolveId() && view instanceof View) {
                        final Resources resources = ((View) view).getContext().getResources();
                        final int id = field.getInt(view);
                        if (id >= 0) {
                            try {
                                fieldValue = resources.getResourceTypeName(id) + '/' +
                                        resources.getResourceEntryName(id);
                            } catch (Resources.NotFoundException e) {
                                fieldValue = "UNKNOWN";
                            }
                        } else {
                            fieldValue = "NO_ID";
                        }
                    } else {
                        final IntToString[] mapping = property.mapping();
                        if (mapping.length > 0) {
                            final int intValue = field.getInt(view);
                            int mappingCount = mapping.length;
                            for (int j = 0; j < mappingCount; j++) {
                                final IntToString mapped = mapping[j];
                                if (mapped.from() == intValue) {
                                    fieldValue = mapped.to();
                                    break;
                                }
                            }

                            if (fieldValue == null) {
                                fieldValue = intValue;
                            }
                        }
                    }
                } else if (!type.isPrimitive()) {
                    ExportedProperty property = field.getAnnotation(ExportedProperty.class);
                    if (property.deepExport()) {
                        dumpViewProperties(field.get(view), out, prefix + property.prefix());
                        continue;
                    }
                }

                if (fieldValue == null) {
                    fieldValue = field.get(view);
                }                

                out.write(prefix);
                out.write(field.getName());
                out.write('=');

                if (fieldValue != null) {
                    final String value = fieldValue.toString().replace("\n", "\\n");
                    out.write(String.valueOf(value.length()));
                    out.write(",");
                    out.write(value);
                } else {
                    out.write("4,null");
                }

                out.write(' ');
            } catch (IllegalAccessException e) {
            }
        }
    }

    private static void dumpViewHierarchy(ViewGroup group, BufferedWriter out, int level) {
        if (!dumpView(group, out, level)) {
            return;
        }

        final int count = group.getChildCount();
        for (int i = 0; i < count; i++) {
            final View view = group.getChildAt(i);
            if (view instanceof ViewGroup) {
                dumpViewHierarchy((ViewGroup) view, out, level + 1);
            } else {
                dumpView(view, out, level + 1);
            }
        }
    }

    private static boolean dumpView(Object view, BufferedWriter out, int level) {
        try {
            for (int i = 0; i < level; i++) {
                out.write(' ');
            }
            out.write(view.getClass().getName());
            out.write('@');
            out.write(Integer.toHexString(view.hashCode()));
            out.newLine();
        } catch (IOException e) {
            Log.w("View", "Error while dumping hierarchy tree");
            return false;
        }
        return true;
    }

    private static Field[] capturedViewGetPropertyFields(Class<?> klass) {
        if (mCapturedViewFieldsForClasses == null) {
            mCapturedViewFieldsForClasses = new HashMap<Class<?>, Field[]>();
        }
        final HashMap<Class<?>, Field[]> map = mCapturedViewFieldsForClasses;

        Field[] fields = map.get(klass);
        if (fields != null) {
            return fields;
        }

        final ArrayList<Field> foundFields = new ArrayList<Field>();
        fields = klass.getFields();

        int count = fields.length;
        for (int i = 0; i < count; i++) {
            final Field field = fields[i];
            if (field.isAnnotationPresent(CapturedViewProperty.class)) {
                field.setAccessible(true);
                foundFields.add(field);
            }
        }

        fields = foundFields.toArray(new Field[foundFields.size()]);
        map.put(klass, fields);

        return fields;
    }

    private static Method[] capturedViewGetPropertyMethods(Class<?> klass) {
        if (mCapturedViewMethodsForClasses == null) {
            mCapturedViewMethodsForClasses = new HashMap<Class<?>, Method[]>();
        }
        final HashMap<Class<?>, Method[]> map = mCapturedViewMethodsForClasses;

        Method[] methods = map.get(klass);
        if (methods != null) {
            return methods;
        }

        final ArrayList<Method> foundMethods = new ArrayList<Method>();
        methods = klass.getMethods();
        
        int count = methods.length;
        for (int i = 0; i < count; i++) {
            final Method method = methods[i];            
            if (method.getParameterTypes().length == 0 &&
                    method.isAnnotationPresent(CapturedViewProperty.class) &&
                    method.getReturnType() != Void.class) {
                method.setAccessible(true);
                foundMethods.add(method);
            }
        }

        methods = foundMethods.toArray(new Method[foundMethods.size()]);
        map.put(klass, methods);

        return methods;
    }
              
    private static String capturedViewExportMethods(Object obj, Class<?> klass, 
            String prefix) {

        if (obj == null) {
            return "null";
        }
        
        StringBuilder sb = new StringBuilder();
        final Method[] methods = capturedViewGetPropertyMethods(klass);

        int count = methods.length;
        for (int i = 0; i < count; i++) {
            final Method method = methods[i];
            try {
                Object methodValue = method.invoke(obj, (Object[]) null);
                final Class<?> returnType = method.getReturnType();
                
                CapturedViewProperty property = method.getAnnotation(CapturedViewProperty.class);
                if (property.retrieveReturn()) {
                    //we are interested in the second level data only
                    sb.append(capturedViewExportMethods(methodValue, returnType, method.getName() + "#"));
                } else {                    
                    sb.append(prefix);
                    sb.append(method.getName());
                    sb.append("()=");
                    
                    if (methodValue != null) {
                        final String value = methodValue.toString().replace("\n", "\\n");                        
                        sb.append(value);                        
                    } else {
                        sb.append("null");
                    }
                    sb.append("; ");
                }
              } catch (IllegalAccessException e) {
                  //Exception IllegalAccess, it is OK here 
                  //we simply ignore this method
              } catch (InvocationTargetException e) {
                  //Exception InvocationTarget, it is OK here 
                  //we simply ignore this method
              }              
        }        
        return sb.toString();
    }

    private static String capturedViewExportFields(Object obj, Class<?> klass, String prefix) {
        
        if (obj == null) {
            return "null";
        }
        
        StringBuilder sb = new StringBuilder();
        final Field[] fields = capturedViewGetPropertyFields(klass);

        int count = fields.length;
        for (int i = 0; i < count; i++) {
            final Field field = fields[i];
            try {
                Object fieldValue = field.get(obj);

                sb.append(prefix);
                sb.append(field.getName());
                sb.append("=");

                if (fieldValue != null) {
                    final String value = fieldValue.toString().replace("\n", "\\n");
                    sb.append(value);
                } else {
                    sb.append("null");
                }
                sb.append(' ');
            } catch (IllegalAccessException e) {
                //Exception IllegalAccess, it is OK here 
                //we simply ignore this field
            }
        }
        return sb.toString();
    }
    
    /**
     * dump view info for id based instrument test generation 
     * (and possibly further data analysis). The results are dumped
     * to the log. 
     * @param tag for log
     * @param view for dump
     * 
     * @hide pending API Council approval
     */
    public static void dumpCapturedView(String tag, Object view) {        
        Class<?> klass = view.getClass();
        StringBuilder sb = new StringBuilder(klass.getName() + ": ");
        sb.append(capturedViewExportFields(view, klass, ""));
        sb.append(capturedViewExportMethods(view, klass, ""));        
        Log.d(tag, sb.toString());        
    }
}
