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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.HardwareRenderer;
import android.graphics.Picture;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;

import libcore.util.HexEncoding;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Various debugging/tracing tools related to {@link View} and the view hierarchy.
 */
public class ViewDebug {
    /**
     * @deprecated This flag is now unused
     */
    @Deprecated
    public static final boolean TRACE_HIERARCHY = false;

    /**
     * @deprecated This flag is now unused
     */
    @Deprecated
    public static final boolean TRACE_RECYCLER = false;

    /**
     * Enables detailed logging of drag/drop operations.
     * @hide
     */
    public static final boolean DEBUG_DRAG = false;

    /**
     * Enables detailed logging of task positioning operations.
     * @hide
     */
    public static final boolean DEBUG_POSITIONING = false;

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
         * {@literal @}ViewDebug.ExportedProperty(mapping = {
         *     {@literal @}ViewDebug.IntToString(from = 0, to = "VISIBLE"),
         *     {@literal @}ViewDebug.IntToString(from = 4, to = "INVISIBLE"),
         *     {@literal @}ViewDebug.IntToString(from = 8, to = "GONE")
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
         * A mapping can be defined to map array indices to specific strings.
         * A mapping can be used to see human readable values for the indices
         * of an array:
         *
         * <pre>
         * {@literal @}ViewDebug.ExportedProperty(indexMapping = {
         *     {@literal @}ViewDebug.IntToString(from = 0, to = "INVALID"),
         *     {@literal @}ViewDebug.IntToString(from = 1, to = "FIRST"),
         *     {@literal @}ViewDebug.IntToString(from = 2, to = "SECOND")
         * })
         * private int[] mElements;
         * <pre>
         *
         * @return An array of int to String mappings
         *
         * @see android.view.ViewDebug.IntToString
         * @see #mapping()
         */
        IntToString[] indexMapping() default { };

        /**
         * A flags mapping can be defined to map flags encoded in an integer to
         * specific strings. A mapping can be used to see human readable values
         * for the flags of an integer:
         *
         * <pre>
         * {@literal @}ViewDebug.ExportedProperty(flagMapping = {
         *     {@literal @}ViewDebug.FlagToString(mask = ENABLED_MASK, equals = ENABLED,
         *             name = "ENABLED"),
         *     {@literal @}ViewDebug.FlagToString(mask = ENABLED_MASK, equals = DISABLED,
         *             name = "DISABLED"),
         * })
         * private int mFlags;
         * <pre>
         *
         * A specified String is output when the following is true:
         *
         * @return An array of int to String mappings
         */
        FlagToString[] flagMapping() default { };

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

        /**
         * Specifies the category the property falls into, such as measurement,
         * layout, drawing, etc.
         *
         * @return the category as String
         */
        String category() default "";

        /**
         * Indicates whether or not to format an {@code int} or {@code byte} value as a hex string.
         *
         * @return true if the supported values should be formatted as a hex string.
         */
        boolean formatToHexString() default false;

        /**
         * Indicates whether or not the key to value mappings are held in adjacent indices.
         *
         * Note: Applies only to fields and methods that return String[].
         *
         * @return true if the key to value mappings are held in adjacent indices.
         */
        boolean hasAdjacentMapping() default false;
    }

    /**
     * Defines a mapping from an int value to a String. Such a mapping can be used
     * in an @ExportedProperty to provide more meaningful values to the end user.
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
     * Defines a mapping from a flag to a String. Such a mapping can be used
     * in an @ExportedProperty to provide more meaningful values to the end user.
     *
     * @see android.view.ViewDebug.ExportedProperty
     */
    @Target({ ElementType.TYPE })
    @Retention(RetentionPolicy.RUNTIME)
    public @interface FlagToString {
        /**
         * The mask to apply to the original value.
         *
         * @return An arbitrary int value.
         */
        int mask();

        /**
         * The value to compare to the result of:
         * <code>original value &amp; {@link #mask()}</code>.
         *
         * @return An arbitrary value.
         */
        int equals();

        /**
         * The String to use in place of the original int value.
         *
         * @return An arbitrary non-null String.
         */
        String name();

        /**
         * Indicates whether to output the flag when the test is true,
         * or false. Defaults to true.
         */
        boolean outputIf() default true;
    }

    /**
     * This annotation can be used to mark fields and methods to be dumped when
     * the view is captured. Methods with this annotation must have no arguments
     * and must return a valid type of data.
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

    /**
     * Allows a View to inject custom children into HierarchyViewer. For example,
     * WebView uses this to add its internal layer tree as a child to itself
     * @hide
     */
    public interface HierarchyHandler {
        /**
         * Dumps custom children to hierarchy viewer.
         * See ViewDebug.dumpViewWithProperties(Context, View, BufferedWriter, int)
         * for the format
         *
         * An empty implementation should simply do nothing
         *
         * @param out The output writer
         * @param level The indentation level
         */
        public void dumpViewHierarchyWithProperties(BufferedWriter out, int level);

        /**
         * Returns a View to enable grabbing screenshots from custom children
         * returned in dumpViewHierarchyWithProperties.
         *
         * @param className The className of the view to find
         * @param hashCode The hashCode of the view to find
         * @return the View to capture from, or null if not found
         */
        public View findHierarchyView(String className, int hashCode);
    }

    private abstract static class PropertyInfo<T extends Annotation,
            R extends AccessibleObject & Member> {

        public final R member;
        public final T property;
        public final String name;
        public final Class<?> returnType;

        public String entrySuffix = "";
        public String valueSuffix = "";

        PropertyInfo(Class<T> property, R member, Class<?> returnType) {
            this.member = member;
            this.name = member.getName();
            this.property = member.getAnnotation(property);
            this.returnType = returnType;
        }

        public abstract Object invoke(Object target) throws Exception;

        static <T extends Annotation> PropertyInfo<T, ?> forMethod(Method method,
                Class<T> property) {
            // Ensure the method return and parameter types can be resolved.
            try {
                if ((method.getReturnType() == Void.class)
                        || (method.getParameterTypes().length != 0)) {
                    return null;
                }
            } catch (NoClassDefFoundError e) {
                return null;
            }
            if (!method.isAnnotationPresent(property)) {
                return null;
            }
            method.setAccessible(true);

            PropertyInfo info = new MethodPI(method, property);
            info.entrySuffix = "()";
            info.valueSuffix = ";";
            return info;
        }

        static <T extends Annotation> PropertyInfo<T, ?> forField(Field field, Class<T> property) {
            if (!field.isAnnotationPresent(property)) {
                return null;
            }
            field.setAccessible(true);
            return new FieldPI<>(field, property);
        }
    }

    private static class MethodPI<T extends Annotation> extends PropertyInfo<T, Method> {

        MethodPI(Method method, Class<T> property) {
            super(property, method, method.getReturnType());
        }

        @Override
        public Object invoke(Object target) throws Exception {
            return member.invoke(target);
        }
    }

    private static class FieldPI<T extends Annotation> extends PropertyInfo<T, Field> {

        FieldPI(Field field, Class<T> property) {
            super(property, field, field.getType());
        }

        @Override
        public Object invoke(Object target) throws Exception {
            return member.get(target);
        }
    }

    // Maximum delay in ms after which we stop trying to capture a View's drawing
    private static final int CAPTURE_TIMEOUT = 6000;

    private static final String REMOTE_COMMAND_CAPTURE = "CAPTURE";
    private static final String REMOTE_COMMAND_DUMP = "DUMP";
    private static final String REMOTE_COMMAND_DUMP_THEME = "DUMP_THEME";
    /**
     * Similar to REMOTE_COMMAND_DUMP but uses ViewHierarchyEncoder instead of flat text
     * @hide
     */
    public static final String REMOTE_COMMAND_DUMP_ENCODED = "DUMP_ENCODED";
    private static final String REMOTE_COMMAND_INVALIDATE = "INVALIDATE";
    private static final String REMOTE_COMMAND_REQUEST_LAYOUT = "REQUEST_LAYOUT";
    private static final String REMOTE_PROFILE = "PROFILE";
    private static final String REMOTE_COMMAND_CAPTURE_LAYERS = "CAPTURE_LAYERS";
    private static final String REMOTE_COMMAND_OUTPUT_DISPLAYLIST = "OUTPUT_DISPLAYLIST";

    private static HashMap<Class<?>, PropertyInfo<ExportedProperty, ?>[]> sExportProperties;
    private static HashMap<Class<?>, PropertyInfo<CapturedViewProperty, ?>[]>
            sCapturedViewProperties;

    /**
     * @deprecated This enum is now unused
     */
    @Deprecated
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

    /**
     * @deprecated This enum is now unused
     */
    @Deprecated
    public enum RecyclerTraceType {
        NEW_VIEW,
        BIND_VIEW,
        RECYCLE_FROM_ACTIVE_HEAP,
        RECYCLE_FROM_SCRAP_HEAP,
        MOVE_TO_SCRAP_HEAP,
        MOVE_FROM_ACTIVE_TO_SCRAP_HEAP
    }

    /**
     * Returns the number of instanciated Views.
     *
     * @return The number of Views instanciated in the current process.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static long getViewInstanceCount() {
        return Debug.countInstancesOfClass(View.class);
    }

    /**
     * Returns the number of instanciated ViewAncestors.
     *
     * @return The number of ViewAncestors instanciated in the current process.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static long getViewRootImplCount() {
        return Debug.countInstancesOfClass(ViewRootImpl.class);
    }

    /**
     * @deprecated This method is now unused and invoking it is a no-op
     */
    @Deprecated
    @SuppressWarnings({ "UnusedParameters", "deprecation" })
    public static void trace(View view, RecyclerTraceType type, int... parameters) {
    }

    /**
     * @deprecated This method is now unused and invoking it is a no-op
     */
    @Deprecated
    @SuppressWarnings("UnusedParameters")
    public static void startRecyclerTracing(String prefix, View view) {
    }

    /**
     * @deprecated This method is now unused and invoking it is a no-op
     */
    @Deprecated
    @SuppressWarnings("UnusedParameters")
    public static void stopRecyclerTracing() {
    }

    /**
     * @deprecated This method is now unused and invoking it is a no-op
     */
    @Deprecated
    @SuppressWarnings({ "UnusedParameters", "deprecation" })
    public static void trace(View view, HierarchyTraceType type) {
    }

    /**
     * @deprecated This method is now unused and invoking it is a no-op
     */
    @Deprecated
    @SuppressWarnings("UnusedParameters")
    public static void startHierarchyTracing(String prefix, View view) {
    }

    /**
     * @deprecated This method is now unused and invoking it is a no-op
     */
    @Deprecated
    public static void stopHierarchyTracing() {
    }

    @UnsupportedAppUsage
    static void dispatchCommand(View view, String command, String parameters,
            OutputStream clientStream) throws IOException {
        // Just being cautious...
        view = view.getRootView();

        if (REMOTE_COMMAND_DUMP.equalsIgnoreCase(command)) {
            dump(view, false, true, clientStream);
        } else if (REMOTE_COMMAND_DUMP_THEME.equalsIgnoreCase(command)) {
            dumpTheme(view, clientStream);
        } else if (REMOTE_COMMAND_DUMP_ENCODED.equalsIgnoreCase(command)) {
            dumpEncoded(view, clientStream);
        } else if (REMOTE_COMMAND_CAPTURE_LAYERS.equalsIgnoreCase(command)) {
            captureLayers(view, new DataOutputStream(clientStream));
        } else {
            final String[] params = parameters.split(" ");
            if (REMOTE_COMMAND_CAPTURE.equalsIgnoreCase(command)) {
                capture(view, clientStream, params[0]);
            } else if (REMOTE_COMMAND_OUTPUT_DISPLAYLIST.equalsIgnoreCase(command)) {
                outputDisplayList(view, params[0]);
            } else if (REMOTE_COMMAND_INVALIDATE.equalsIgnoreCase(command)) {
                invalidate(view, params[0]);
            } else if (REMOTE_COMMAND_REQUEST_LAYOUT.equalsIgnoreCase(command)) {
                requestLayout(view, params[0]);
            } else if (REMOTE_PROFILE.equalsIgnoreCase(command)) {
                profile(view, clientStream, params[0]);
            }
        }
    }

    /** @hide */
    public static View findView(View root, String parameter) {
        // Look by type/hashcode
        if (parameter.indexOf('@') != -1) {
            final String[] ids = parameter.split("@");
            final String className = ids[0];
            final int hashCode = (int) Long.parseLong(ids[1], 16);

            View view = root.getRootView();
            if (view instanceof ViewGroup) {
                return findView((ViewGroup) view, className, hashCode);
            }
        } else {
            // Look by id
            final int id = root.getResources().getIdentifier(parameter, null, null);
            return root.getRootView().findViewById(id);
        }

        return null;
    }

    private static void invalidate(View root, String parameter) {
        final View view = findView(root, parameter);
        if (view != null) {
            view.postInvalidate();
        }
    }

    private static void requestLayout(View root, String parameter) {
        final View view = findView(root, parameter);
        if (view != null) {
            root.post(new Runnable() {
                public void run() {
                    view.requestLayout();
                }
            });
        }
    }

    private static void profile(View root, OutputStream clientStream, String parameter)
            throws IOException {

        final View view = findView(root, parameter);
        BufferedWriter out = null;
        try {
            out = new BufferedWriter(new OutputStreamWriter(clientStream), 32 * 1024);

            if (view != null) {
                profileViewAndChildren(view, out);
            } else {
                out.write("-1 -1 -1");
                out.newLine();
            }
            out.write("DONE.");
            out.newLine();
        } catch (Exception e) {
            android.util.Log.w("View", "Problem profiling the view:", e);
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    /** @hide */
    public static void profileViewAndChildren(final View view, BufferedWriter out)
            throws IOException {
        RenderNode node = RenderNode.create("ViewDebug", null);
        profileViewAndChildren(view, node, out, true);
    }

    private static void profileViewAndChildren(View view, RenderNode node, BufferedWriter out,
            boolean root) throws IOException {
        long durationMeasure =
                (root || (view.mPrivateFlags & View.PFLAG_MEASURED_DIMENSION_SET) != 0)
                        ? profileViewMeasure(view) : 0;
        long durationLayout =
                (root || (view.mPrivateFlags & View.PFLAG_LAYOUT_REQUIRED) != 0)
                        ? profileViewLayout(view) : 0;
        long durationDraw =
                (root || !view.willNotDraw() || (view.mPrivateFlags & View.PFLAG_DRAWN) != 0)
                        ? profileViewDraw(view, node) : 0;

        out.write(String.valueOf(durationMeasure));
        out.write(' ');
        out.write(String.valueOf(durationLayout));
        out.write(' ');
        out.write(String.valueOf(durationDraw));
        out.newLine();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            final int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                profileViewAndChildren(group.getChildAt(i), node, out, false);
            }
        }
    }

    private static long profileViewMeasure(final View view) {
        return profileViewOperation(view, new ViewOperation() {
            @Override
            public void pre() {
                forceLayout(view);
            }

            private void forceLayout(View view) {
                view.forceLayout();
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    final int count = group.getChildCount();
                    for (int i = 0; i < count; i++) {
                        forceLayout(group.getChildAt(i));
                    }
                }
            }

            @Override
            public void run() {
                view.measure(view.mOldWidthMeasureSpec, view.mOldHeightMeasureSpec);
            }
        });
    }

    private static long profileViewLayout(View view) {
        return profileViewOperation(view,
                () -> view.layout(view.mLeft, view.mTop, view.mRight, view.mBottom));
    }

    private static long profileViewDraw(View view, RenderNode node) {
        DisplayMetrics dm = view.getResources().getDisplayMetrics();
        if (dm == null) {
            return 0;
        }

        if (view.isHardwareAccelerated()) {
            RecordingCanvas canvas = node.beginRecording(dm.widthPixels, dm.heightPixels);
            try {
                return profileViewOperation(view, () -> view.draw(canvas));
            } finally {
                node.endRecording();
            }
        } else {
            Bitmap bitmap = Bitmap.createBitmap(
                    dm, dm.widthPixels, dm.heightPixels, Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(bitmap);
            try {
                return profileViewOperation(view, () -> view.draw(canvas));
            } finally {
                canvas.setBitmap(null);
                bitmap.recycle();
            }
        }
    }

    interface ViewOperation {
        default void pre() {}

        void run();
    }

    private static long profileViewOperation(View view, final ViewOperation operation) {
        final CountDownLatch latch = new CountDownLatch(1);
        final long[] duration = new long[1];

        view.post(() -> {
            try {
                operation.pre();
                long start = Debug.threadCpuTimeNanos();
                //noinspection unchecked
                operation.run();
                duration[0] = Debug.threadCpuTimeNanos() - start;
            } finally {
                latch.countDown();
            }
        });

        try {
            if (!latch.await(CAPTURE_TIMEOUT, TimeUnit.MILLISECONDS)) {
                Log.w("View", "Could not complete the profiling of the view " + view);
                return -1;
            }
        } catch (InterruptedException e) {
            Log.w("View", "Could not complete the profiling of the view " + view);
            Thread.currentThread().interrupt();
            return -1;
        }

        return duration[0];
    }

    /** @hide */
    public static void captureLayers(View root, final DataOutputStream clientStream)
            throws IOException {

        try {
            Rect outRect = new Rect();
            try {
                root.mAttachInfo.mSession.getDisplayFrame(root.mAttachInfo.mWindow, outRect);
            } catch (RemoteException e) {
                // Ignore
            }

            clientStream.writeInt(outRect.width());
            clientStream.writeInt(outRect.height());

            captureViewLayer(root, clientStream, true);

            clientStream.write(2);
        } finally {
            clientStream.close();
        }
    }

    private static void captureViewLayer(View view, DataOutputStream clientStream, boolean visible)
            throws IOException {

        final boolean localVisible = view.getVisibility() == View.VISIBLE && visible;

        if ((view.mPrivateFlags & View.PFLAG_SKIP_DRAW) != View.PFLAG_SKIP_DRAW) {
            final int id = view.getId();
            String name = view.getClass().getSimpleName();
            if (id != View.NO_ID) {
                name = resolveId(view.getContext(), id).toString();
            }

            clientStream.write(1);
            clientStream.writeUTF(name);
            clientStream.writeByte(localVisible ? 1 : 0);

            int[] position = new int[2];
            // XXX: Should happen on the UI thread
            view.getLocationInWindow(position);

            clientStream.writeInt(position[0]);
            clientStream.writeInt(position[1]);
            clientStream.flush();

            Bitmap b = performViewCapture(view, true);
            if (b != null) {
                ByteArrayOutputStream arrayOut = new ByteArrayOutputStream(b.getWidth() *
                        b.getHeight() * 2);
                b.compress(Bitmap.CompressFormat.PNG, 100, arrayOut);
                clientStream.writeInt(arrayOut.size());
                arrayOut.writeTo(clientStream);
            }
            clientStream.flush();
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = group.getChildCount();

            for (int i = 0; i < count; i++) {
                captureViewLayer(group.getChildAt(i), clientStream, localVisible);
            }
        }

        if (view.mOverlay != null) {
            ViewGroup overlayContainer = view.getOverlay().mOverlayViewGroup;
            captureViewLayer(overlayContainer, clientStream, localVisible);
        }
    }

    private static void outputDisplayList(View root, String parameter) throws IOException {
        final View view = findView(root, parameter);
        view.getViewRootImpl().outputDisplayList(view);
    }

    /** @hide */
    public static void outputDisplayList(View root, View target) {
        root.getViewRootImpl().outputDisplayList(target);
    }

    private static class PictureCallbackHandler implements AutoCloseable,
            HardwareRenderer.PictureCapturedCallback, Runnable {
        private final HardwareRenderer mRenderer;
        private final Function<Picture, Boolean> mCallback;
        private final Executor mExecutor;
        private final ReentrantLock mLock = new ReentrantLock(false);
        private final ArrayDeque<Picture> mQueue = new ArrayDeque<>(3);
        private boolean mStopListening;
        private Thread mRenderThread;

        private PictureCallbackHandler(HardwareRenderer renderer,
                Function<Picture, Boolean> callback, Executor executor) {
            mRenderer = renderer;
            mCallback = callback;
            mExecutor = executor;
            mRenderer.setPictureCaptureCallback(this);
        }

        @Override
        public void close() {
            mLock.lock();
            mStopListening = true;
            mLock.unlock();
            mRenderer.setPictureCaptureCallback(null);
        }

        @Override
        public void onPictureCaptured(Picture picture) {
            mLock.lock();
            if (mStopListening) {
                mLock.unlock();
                mRenderer.setPictureCaptureCallback(null);
                return;
            }
            if (mRenderThread == null) {
                mRenderThread = Thread.currentThread();
            }
            Picture toDestroy = null;
            if (mQueue.size() == 3) {
                toDestroy = mQueue.removeLast();
            }
            mQueue.add(picture);
            mLock.unlock();
            if (toDestroy == null) {
                mExecutor.execute(this);
            } else {
                toDestroy.close();
            }
        }

        @Override
        public void run() {
            mLock.lock();
            final Picture picture = mQueue.poll();
            final boolean isStopped = mStopListening;
            mLock.unlock();
            if (Thread.currentThread() == mRenderThread) {
                close();
                throw new IllegalStateException(
                        "ViewDebug#startRenderingCommandsCapture must be given an executor that "
                        + "invokes asynchronously");
            }
            if (isStopped) {
                picture.close();
                return;
            }
            final boolean keepReceiving = mCallback.apply(picture);
            if (!keepReceiving) {
                close();
            }
        }
    }

    /**
     * Begins capturing the entire rendering commands for the view tree referenced by the given
     * view. The view passed may be any View in the tree as long as it is attached. That is,
     * {@link View#isAttachedToWindow()} must be true.
     *
     * Every time a frame is rendered a Picture will be passed to the given callback via the given
     * executor. As long as the callback returns 'true' it will continue to receive new frames.
     * The system will only invoke the callback at a rate that the callback is able to keep up with.
     * That is, if it takes 48ms for the callback to complete and there is a 60fps animation running
     * then the callback will only receive 33% of the frames produced.
     *
     * This method must be called on the same thread as the View tree.
     *
     * @param tree The View tree to capture the rendering commands.
     * @param callback The callback to invoke on every frame produced. Should return true to
     *                 continue receiving new frames, false to stop capturing.
     * @param executor The executor to invoke the callback on. Recommend using a background thread
     *                 to avoid stalling the UI thread. Must be an asynchronous invoke or an
     *                 exception will be thrown.
     * @return a closeable that can be used to stop capturing. May be invoked on any thread. Note
     * that the callback may continue to receive another frame or two depending on thread timings.
     * Returns null if the capture stream cannot be started, such as if there's no
     * HardwareRenderer for the given view tree.
     * @hide
     * @deprecated use {@link #startRenderingCommandsCapture(View, Executor, Callable)} instead.
     */
    @TestApi
    @Nullable
    @Deprecated
    public static AutoCloseable startRenderingCommandsCapture(View tree, Executor executor,
            Function<Picture, Boolean> callback) {
        final View.AttachInfo attachInfo = tree.mAttachInfo;
        if (attachInfo == null) {
            throw new IllegalArgumentException("Given view isn't attached");
        }
        if (attachInfo.mHandler.getLooper() != Looper.myLooper()) {
            throw new IllegalStateException("Called on the wrong thread."
                    + " Must be called on the thread that owns the given View");
        }
        final HardwareRenderer renderer = attachInfo.mThreadedRenderer;
        if (renderer != null) {
            return new PictureCallbackHandler(renderer, callback, executor);
        }
        return null;
    }

    private static class StreamingPictureCallbackHandler implements AutoCloseable,
            HardwareRenderer.PictureCapturedCallback, Runnable {
        private final HardwareRenderer mRenderer;
        private final Callable<OutputStream> mCallback;
        private final Executor mExecutor;
        private final ReentrantLock mLock = new ReentrantLock(false);
        private final ArrayDeque<byte[]> mQueue = new ArrayDeque<>(3);
        private final ByteArrayOutputStream mByteStream = new ByteArrayOutputStream();
        private boolean mStopListening;
        private Thread mRenderThread;

        private StreamingPictureCallbackHandler(HardwareRenderer renderer,
                Callable<OutputStream> callback, Executor executor) {
            mRenderer = renderer;
            mCallback = callback;
            mExecutor = executor;
            mRenderer.setPictureCaptureCallback(this);
        }

        @Override
        public void close() {
            mLock.lock();
            mStopListening = true;
            mLock.unlock();
            mRenderer.setPictureCaptureCallback(null);
        }

        @Override
        public void onPictureCaptured(Picture picture) {
            mLock.lock();
            if (mStopListening) {
                mLock.unlock();
                mRenderer.setPictureCaptureCallback(null);
                return;
            }
            if (mRenderThread == null) {
                mRenderThread = Thread.currentThread();
            }
            boolean needsInvoke = true;
            if (mQueue.size() == 3) {
                mQueue.removeLast();
                needsInvoke = false;
            }
            picture.writeToStream(mByteStream);
            mQueue.add(mByteStream.toByteArray());
            mByteStream.reset();
            mLock.unlock();

            if (needsInvoke) {
                mExecutor.execute(this);
            }
        }

        @Override
        public void run() {
            mLock.lock();
            final byte[] picture = mQueue.poll();
            final boolean isStopped = mStopListening;
            mLock.unlock();
            if (Thread.currentThread() == mRenderThread) {
                close();
                throw new IllegalStateException(
                        "ViewDebug#startRenderingCommandsCapture must be given an executor that "
                        + "invokes asynchronously");
            }
            if (isStopped) {
                return;
            }
            OutputStream stream = null;
            try {
                stream = mCallback.call();
            } catch (Exception ex) {
                Log.w("ViewDebug", "Aborting rendering commands capture "
                        + "because callback threw exception", ex);
            }
            if (stream != null) {
                try {
                    stream.write(picture);
                } catch (IOException ex) {
                    Log.w("ViewDebug", "Aborting rendering commands capture "
                            + "due to IOException writing to output stream", ex);
                }
            } else {
                close();
            }
        }
    }

    /**
     * Begins capturing the entire rendering commands for the view tree referenced by the given
     * view. The view passed may be any View in the tree as long as it is attached. That is,
     * {@link View#isAttachedToWindow()} must be true.
     *
     * Every time a frame is rendered the callback will be invoked on the given executor to
     * provide an OutputStream to serialize to. As long as the callback returns a valid
     * OutputStream the capturing will continue. The system will only invoke the callback at a rate
     * that the callback & OutputStream is able to keep up with. That is, if it takes 48ms for the
     * callback & serialization to complete and there is a 60fps animation running
     * then the callback will only receive 33% of the frames produced.
     *
     * This method must be called on the same thread as the View tree.
     *
     * @param tree The View tree to capture the rendering commands.
     * @param callback The callback to invoke on every frame produced. Should return an
     *                 OutputStream to write the data to. Return null to cancel capture. The
     *                 same stream may be returned each time as the serialized data contains
     *                 start & end markers. The callback will not be invoked while a previous
     *                 serialization is being performed, so if a single continuous stream is being
     *                 used it is valid for the callback to write its own metadata to that stream
     *                 in response to callback invocation.
     * @param executor The executor to invoke the callback on. Recommend using a background thread
     *                 to avoid stalling the UI thread. Must be an asynchronous invoke or an
     *                 exception will be thrown.
     * @return a closeable that can be used to stop capturing. May be invoked on any thread. Note
     * that the callback may continue to receive another frame or two depending on thread timings.
     * Returns null if the capture stream cannot be started, such as if there's no
     * HardwareRenderer for the given view tree.
     * @hide
     */
    @TestApi
    @Nullable
    public static AutoCloseable startRenderingCommandsCapture(View tree, Executor executor,
            Callable<OutputStream> callback) {
        final View.AttachInfo attachInfo = tree.mAttachInfo;
        if (attachInfo == null) {
            throw new IllegalArgumentException("Given view isn't attached");
        }
        if (attachInfo.mHandler.getLooper() != Looper.myLooper()) {
            throw new IllegalStateException("Called on the wrong thread."
                    + " Must be called on the thread that owns the given View");
        }
        final HardwareRenderer renderer = attachInfo.mThreadedRenderer;
        if (renderer != null) {
            return new StreamingPictureCallbackHandler(renderer, callback, executor);
        }
        return null;
    }

    private static void capture(View root, final OutputStream clientStream, String parameter)
            throws IOException {

        final View captureView = findView(root, parameter);
        capture(root, clientStream, captureView);
    }

    /** @hide */
    public static void capture(View root, final OutputStream clientStream, View captureView)
            throws IOException {
        Bitmap b = performViewCapture(captureView, false);

        if (b == null) {
            Log.w("View", "Failed to create capture bitmap!");
            // Send an empty one so that it doesn't get stuck waiting for
            // something.
            b = Bitmap.createBitmap(root.getResources().getDisplayMetrics(),
                    1, 1, Bitmap.Config.ARGB_8888);
        }

        BufferedOutputStream out = null;
        try {
            out = new BufferedOutputStream(clientStream, 32 * 1024);
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
        } finally {
            if (out != null) {
                out.close();
            }
            b.recycle();
        }
    }

    private static Bitmap performViewCapture(final View captureView, final boolean skipChildren) {
        if (captureView != null) {
            final CountDownLatch latch = new CountDownLatch(1);
            final Bitmap[] cache = new Bitmap[1];

            captureView.post(() -> {
                try {
                    CanvasProvider provider = captureView.isHardwareAccelerated()
                            ? new HardwareCanvasProvider() : new SoftwareCanvasProvider();
                    cache[0] = captureView.createSnapshot(provider, skipChildren);
                } catch (OutOfMemoryError e) {
                    Log.w("View", "Out of memory for bitmap");
                } finally {
                    latch.countDown();
                }
            });

            try {
                latch.await(CAPTURE_TIMEOUT, TimeUnit.MILLISECONDS);
                return cache[0];
            } catch (InterruptedException e) {
                Log.w("View", "Could not complete the capture of the view " + captureView);
                Thread.currentThread().interrupt();
            }
        }

        return null;
    }

    /**
     * Dumps the view hierarchy starting from the given view.
     * @deprecated See {@link #dumpv2(View, ByteArrayOutputStream)} below.
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static void dump(View root, boolean skipChildren, boolean includeProperties,
            OutputStream clientStream) throws IOException {
        BufferedWriter out = null;
        try {
            out = new BufferedWriter(new OutputStreamWriter(clientStream, "utf-8"), 32 * 1024);
            View view = root.getRootView();
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                dumpViewHierarchy(group.getContext(), group, out, 0,
                        skipChildren, includeProperties);
            }
            out.write("DONE.");
            out.newLine();
        } catch (Exception e) {
            android.util.Log.w("View", "Problem dumping the view:", e);
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    /**
     * Dumps the view hierarchy starting from the given view.
     * Rather than using reflection, it uses View's encode method to obtain all the properties.
     * @hide
     */
    public static void dumpv2(@NonNull final View view, @NonNull ByteArrayOutputStream out)
            throws InterruptedException {
        final ViewHierarchyEncoder encoder = new ViewHierarchyEncoder(out);
        final CountDownLatch latch = new CountDownLatch(1);

        view.post(new Runnable() {
            @Override
            public void run() {
                encoder.addProperty("window:left", view.mAttachInfo.mWindowLeft);
                encoder.addProperty("window:top", view.mAttachInfo.mWindowTop);
                view.encode(encoder);
                latch.countDown();
            }
        });

        latch.await(2, TimeUnit.SECONDS);
        encoder.endStream();
    }

    private static void dumpEncoded(@NonNull final View view, @NonNull OutputStream out)
            throws IOException {
        ByteArrayOutputStream baOut = new ByteArrayOutputStream();

        final ViewHierarchyEncoder encoder = new ViewHierarchyEncoder(baOut);
        encoder.setUserPropertiesEnabled(false);
        encoder.addProperty("window:left", view.mAttachInfo.mWindowLeft);
        encoder.addProperty("window:top", view.mAttachInfo.mWindowTop);
        view.encode(encoder);
        encoder.endStream();
        out.write(baOut.toByteArray());
    }

    /**
     * Dumps the theme attributes from the given View.
     * @hide
     */
    public static void dumpTheme(View view, OutputStream clientStream) throws IOException {
        BufferedWriter out = null;
        try {
            out = new BufferedWriter(new OutputStreamWriter(clientStream, "utf-8"), 32 * 1024);
            String[] attributes = getStyleAttributesDump(view.getContext().getResources(),
                    view.getContext().getTheme());
            if (attributes != null) {
                for (int i = 0; i < attributes.length; i += 2) {
                    if (attributes[i] != null) {
                        out.write(attributes[i] + "\n");
                        out.write(attributes[i + 1] + "\n");
                    }
                }
            }
            out.write("DONE.");
            out.newLine();
        } catch (Exception e) {
            android.util.Log.w("View", "Problem dumping View Theme:", e);
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    /**
     * Gets the style attributes from the {@link Resources.Theme}. For debugging only.
     *
     * @param resources Resources to resolve attributes from.
     * @param theme Theme to dump.
     * @return a String array containing pairs of adjacent Theme attribute data: name followed by
     * its value.
     *
     * @hide
     */
    private static String[] getStyleAttributesDump(Resources resources, Resources.Theme theme) {
        TypedValue outValue = new TypedValue();
        String nullString = "null";
        int i = 0;
        int[] attributes = theme.getAllAttributes();
        String[] data = new String[attributes.length * 2];
        for (int attributeId : attributes) {
            try {
                data[i] = resources.getResourceName(attributeId);
                data[i + 1] = theme.resolveAttribute(attributeId, outValue, true) ?
                        outValue.coerceToString().toString() :  nullString;
                i += 2;

                // attempt to replace reference data with its name
                if (outValue.type == TypedValue.TYPE_REFERENCE) {
                    data[i - 1] = resources.getResourceName(outValue.resourceId);
                }
            } catch (Resources.NotFoundException e) {
                // ignore resources we can't resolve
            }
        }
        return data;
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
            if (view.mOverlay != null) {
                final View found = findView((ViewGroup) view.mOverlay.mOverlayViewGroup,
                        className, hashCode);
                if (found != null) {
                    return found;
                }
            }
            if (view instanceof HierarchyHandler) {
                final View found = ((HierarchyHandler)view)
                        .findHierarchyView(className, hashCode);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean isRequestedView(View view, String className, int hashCode) {
        if (view.hashCode() == hashCode) {
            String viewClassName = view.getClass().getName();
            if (className.equals("ViewOverlay")) {
                return viewClassName.equals("android.view.ViewOverlay$OverlayViewGroup");
            } else {
                return className.equals(viewClassName);
            }
        }
        return false;
    }

    private static void dumpViewHierarchy(Context context, ViewGroup group,
            BufferedWriter out, int level, boolean skipChildren, boolean includeProperties) {
        cacheExportedProperties(group.getClass());
        if (!skipChildren) {
            cacheExportedPropertiesForChildren(group);
        }
        // Try to use the handler provided by the view
        Handler handler = group.getHandler();
        // Fall back on using the main thread
        if (handler == null) {
            handler = new Handler(Looper.getMainLooper());
        }

        if (handler.getLooper() == Looper.myLooper()) {
            dumpViewHierarchyOnUIThread(context, group, out, level, skipChildren,
                    includeProperties);
        } else {
            FutureTask task = new FutureTask(() ->
                    dumpViewHierarchyOnUIThread(context, group, out, level, skipChildren,
                            includeProperties), null);
            Message msg = Message.obtain(handler, task);
            msg.setAsynchronous(true);
            handler.sendMessage(msg);
            while (true) {
                try {
                    task.get(CAPTURE_TIMEOUT, java.util.concurrent.TimeUnit.MILLISECONDS);
                    return;
                } catch (InterruptedException e) {
                    // try again
                } catch (ExecutionException | TimeoutException e) {
                    // Something unexpected happened.
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private static void cacheExportedPropertiesForChildren(ViewGroup group) {
        final int count = group.getChildCount();
        for (int i = 0; i < count; i++) {
            final View view = group.getChildAt(i);
            cacheExportedProperties(view.getClass());
            if (view instanceof ViewGroup) {
                cacheExportedPropertiesForChildren((ViewGroup) view);
            }
        }
    }

    private static void cacheExportedProperties(Class<?> klass) {
        if (sExportProperties != null && sExportProperties.containsKey(klass)) {
            return;
        }
        do {
            for (PropertyInfo<ExportedProperty, ?> info : getExportedProperties(klass)) {
                if (!info.returnType.isPrimitive() && info.property.deepExport()) {
                    cacheExportedProperties(info.returnType);
                }
            }
            klass = klass.getSuperclass();
        } while (klass != Object.class);
    }


    private static void dumpViewHierarchyOnUIThread(Context context, ViewGroup group,
            BufferedWriter out, int level, boolean skipChildren, boolean includeProperties) {
        if (!dumpView(context, group, out, level, includeProperties)) {
            return;
        }

        if (skipChildren) {
            return;
        }

        final int count = group.getChildCount();
        for (int i = 0; i < count; i++) {
            final View view = group.getChildAt(i);
            if (view instanceof ViewGroup) {
                dumpViewHierarchyOnUIThread(context, (ViewGroup) view, out, level + 1,
                        skipChildren, includeProperties);
            } else {
                dumpView(context, view, out, level + 1, includeProperties);
            }
            if (view.mOverlay != null) {
                ViewOverlay overlay = view.getOverlay();
                ViewGroup overlayContainer = overlay.mOverlayViewGroup;
                dumpViewHierarchyOnUIThread(context, overlayContainer, out, level + 2,
                        skipChildren, includeProperties);
            }
        }
        if (group instanceof HierarchyHandler) {
            ((HierarchyHandler)group).dumpViewHierarchyWithProperties(out, level + 1);
        }
    }

    private static boolean dumpView(Context context, View view,
            BufferedWriter out, int level, boolean includeProperties) {

        try {
            for (int i = 0; i < level; i++) {
                out.write(' ');
            }
            String className = view.getClass().getName();
            if (className.equals("android.view.ViewOverlay$OverlayViewGroup")) {
                className = "ViewOverlay";
            }
            out.write(className);
            out.write('@');
            out.write(Integer.toHexString(view.hashCode()));
            out.write(' ');
            if (includeProperties) {
                dumpViewProperties(context, view, out);
            }
            out.newLine();
        } catch (IOException e) {
            Log.w("View", "Error while dumping hierarchy tree");
            return false;
        }
        return true;
    }

    private static <T extends Annotation> PropertyInfo<T, ?>[] convertToPropertyInfos(
            Method[] methods, Field[] fields, Class<T> property) {
        return Stream.of(Arrays.stream(methods).map(m -> PropertyInfo.forMethod(m, property)),
                Arrays.stream(fields).map(f -> PropertyInfo.forField(f, property)))
                .flatMap(Function.identity())
                .filter(i -> i != null)
                .toArray(PropertyInfo[]::new);
    }

    private static PropertyInfo<ExportedProperty, ?>[] getExportedProperties(Class<?> klass) {
        if (sExportProperties == null) {
            sExportProperties = new HashMap<>();
        }
        final HashMap<Class<?>, PropertyInfo<ExportedProperty, ?>[]> map = sExportProperties;
        PropertyInfo<ExportedProperty, ?>[] properties = sExportProperties.get(klass);

        if (properties == null) {
            properties = convertToPropertyInfos(klass.getDeclaredMethods(),
                    klass.getDeclaredFields(), ExportedProperty.class);
            map.put(klass, properties);
        }
        return properties;
    }

    private static void dumpViewProperties(Context context, Object view,
            BufferedWriter out) throws IOException {

        dumpViewProperties(context, view, out, "");
    }

    private static void dumpViewProperties(Context context, Object view,
            BufferedWriter out, String prefix) throws IOException {

        if (view == null) {
            out.write(prefix + "=4,null ");
            return;
        }

        Class<?> klass = view.getClass();
        do {
            writeExportedProperties(context, view, out, klass, prefix);
            klass = klass.getSuperclass();
        } while (klass != Object.class);
    }

    private static String formatIntToHexString(int value) {
        return "0x" + Integer.toHexString(value).toUpperCase();
    }

    private static void writeExportedProperties(Context context, Object view, BufferedWriter out,
            Class<?> klass, String prefix) throws IOException {
        for (PropertyInfo<ExportedProperty, ?> info : getExportedProperties(klass)) {
            //noinspection EmptyCatchBlock
            Object value;
            try {
                value = info.invoke(view);
            } catch (Exception e) {
                // ignore
                continue;
            }

            String categoryPrefix =
                    info.property.category().length() != 0 ? info.property.category() + ":" : "";

            if (info.returnType == int.class || info.returnType == byte.class) {
                if (info.property.resolveId() && context != null) {
                    final int id = (Integer) value;
                    value = resolveId(context, id);

                } else if (info.property.formatToHexString()) {
                    if (info.returnType == int.class) {
                        value = formatIntToHexString((Integer) value);
                    } else if (info.returnType == byte.class) {
                        value = "0x"
                                + HexEncoding.encodeToString((Byte) value, true);
                    }
                } else {
                    final ViewDebug.FlagToString[] flagsMapping = info.property.flagMapping();
                    if (flagsMapping.length > 0) {
                        final int intValue = (Integer) value;
                        final String valuePrefix =
                                categoryPrefix + prefix + info.name + '_';
                        exportUnrolledFlags(out, flagsMapping, intValue, valuePrefix);
                    }

                    final ViewDebug.IntToString[] mapping = info.property.mapping();
                    if (mapping.length > 0) {
                        final int intValue = (Integer) value;
                        boolean mapped = false;
                        int mappingCount = mapping.length;
                        for (int j = 0; j < mappingCount; j++) {
                            final ViewDebug.IntToString mapper = mapping[j];
                            if (mapper.from() == intValue) {
                                value = mapper.to();
                                mapped = true;
                                break;
                            }
                        }

                        if (!mapped) {
                            value = intValue;
                        }
                    }
                }
            } else if (info.returnType == int[].class) {
                final int[] array = (int[]) value;
                final String valuePrefix = categoryPrefix + prefix + info.name + '_';
                exportUnrolledArray(context, out, info.property, array, valuePrefix,
                        info.entrySuffix);

                continue;
            } else if (info.returnType == String[].class) {
                final String[] array = (String[]) value;
                if (info.property.hasAdjacentMapping() && array != null) {
                    for (int j = 0; j < array.length; j += 2) {
                        if (array[j] != null) {
                            writeEntry(out, categoryPrefix + prefix, array[j],
                                    info.entrySuffix, array[j + 1] == null ? "null" : array[j + 1]);
                        }
                    }
                }

                continue;
            } else if (!info.returnType.isPrimitive()) {
                if (info.property.deepExport()) {
                    dumpViewProperties(context, value, out, prefix + info.property.prefix());
                    continue;
                }
            }

            writeEntry(out, categoryPrefix + prefix, info.name, info.entrySuffix, value);
        }
    }

    private static void writeEntry(BufferedWriter out, String prefix, String name,
            String suffix, Object value) throws IOException {

        out.write(prefix);
        out.write(name);
        out.write(suffix);
        out.write("=");
        writeValue(out, value);
        out.write(' ');
    }

    private static void exportUnrolledFlags(BufferedWriter out, FlagToString[] mapping,
            int intValue, String prefix) throws IOException {

        final int count = mapping.length;
        for (int j = 0; j < count; j++) {
            final FlagToString flagMapping = mapping[j];
            final boolean ifTrue = flagMapping.outputIf();
            final int maskResult = intValue & flagMapping.mask();
            final boolean test = maskResult == flagMapping.equals();
            if ((test && ifTrue) || (!test && !ifTrue)) {
                final String name = flagMapping.name();
                final String value = formatIntToHexString(maskResult);
                writeEntry(out, prefix, name, "", value);
            }
        }
    }

    /**
     * Converts an integer from a field that is mapped with {@link IntToString} to its string
     * representation.
     *
     * @param clazz The class the field is defined on.
     * @param field The field on which the {@link ExportedProperty} is defined on.
     * @param integer The value to convert.
     * @return The value converted into its string representation.
     * @hide
     */
    public static String intToString(Class<?> clazz, String field, int integer) {
        final IntToString[] mapping = getMapping(clazz, field);
        if (mapping == null) {
            return Integer.toString(integer);
        }
        final int count = mapping.length;
        for (int j = 0; j < count; j++) {
            final IntToString map = mapping[j];
            if (map.from() == integer) {
                return map.to();
            }
        }
        return Integer.toString(integer);
    }

    /**
     * Converts a set of flags from a field that is mapped with {@link FlagToString} to its string
     * representation.
     *
     * @param clazz The class the field is defined on.
     * @param field The field on which the {@link ExportedProperty} is defined on.
     * @param flags The flags to convert.
     * @return The flags converted into their string representations.
     * @hide
     */
    public static String flagsToString(Class<?> clazz, String field, int flags) {
        final FlagToString[] mapping = getFlagMapping(clazz, field);
        if (mapping == null) {
            return Integer.toHexString(flags);
        }
        final StringBuilder result = new StringBuilder();
        final int count = mapping.length;
        for (int j = 0; j < count; j++) {
            final FlagToString flagMapping = mapping[j];
            final boolean ifTrue = flagMapping.outputIf();
            final int maskResult = flags & flagMapping.mask();
            final boolean test = maskResult == flagMapping.equals();
            if (test && ifTrue) {
                final String name = flagMapping.name();
                result.append(name).append(' ');
            }
        }
        if (result.length() > 0) {
            result.deleteCharAt(result.length() - 1);
        }
        return result.toString();
    }

    private static FlagToString[] getFlagMapping(Class<?> clazz, String field) {
        try {
            return clazz.getDeclaredField(field).getAnnotation(ExportedProperty.class)
                    .flagMapping();
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static IntToString[] getMapping(Class<?> clazz, String field) {
        try {
            return clazz.getDeclaredField(field).getAnnotation(ExportedProperty.class).mapping();
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static void exportUnrolledArray(Context context, BufferedWriter out,
            ExportedProperty property, int[] array, String prefix, String suffix)
            throws IOException {

        final IntToString[] indexMapping = property.indexMapping();
        final boolean hasIndexMapping = indexMapping.length > 0;

        final IntToString[] mapping = property.mapping();
        final boolean hasMapping = mapping.length > 0;

        final boolean resolveId = property.resolveId() && context != null;
        final int valuesCount = array.length;

        for (int j = 0; j < valuesCount; j++) {
            String name;
            String value = null;

            final int intValue = array[j];

            name = String.valueOf(j);
            if (hasIndexMapping) {
                int mappingCount = indexMapping.length;
                for (int k = 0; k < mappingCount; k++) {
                    final IntToString mapped = indexMapping[k];
                    if (mapped.from() == j) {
                        name = mapped.to();
                        break;
                    }
                }
            }

            if (hasMapping) {
                int mappingCount = mapping.length;
                for (int k = 0; k < mappingCount; k++) {
                    final IntToString mapped = mapping[k];
                    if (mapped.from() == intValue) {
                        value = mapped.to();
                        break;
                    }
                }
            }

            if (resolveId) {
                if (value == null) value = (String) resolveId(context, intValue);
            } else {
                value = String.valueOf(intValue);
            }

            writeEntry(out, prefix, name, suffix, value);
        }
    }

    static Object resolveId(Context context, int id) {
        Object fieldValue;
        final Resources resources = context.getResources();
        if (id >= 0) {
            try {
                fieldValue = resources.getResourceTypeName(id) + '/' +
                        resources.getResourceEntryName(id);
            } catch (Resources.NotFoundException e) {
                fieldValue = "id/" + formatIntToHexString(id);
            }
        } else {
            fieldValue = "NO_ID";
        }
        return fieldValue;
    }

    private static void writeValue(BufferedWriter out, Object value) throws IOException {
        if (value != null) {
            String output = "[EXCEPTION]";
            try {
                output = value.toString().replace("\n", "\\n");
            } finally {
                out.write(String.valueOf(output.length()));
                out.write(",");
                out.write(output);
            }
        } else {
            out.write("4,null");
        }
    }

    private static PropertyInfo<CapturedViewProperty, ?>[] getCapturedViewProperties(
            Class<?> klass) {
        if (sCapturedViewProperties == null) {
            sCapturedViewProperties = new HashMap<>();
        }
        final HashMap<Class<?>, PropertyInfo<CapturedViewProperty, ?>[]> map =
                sCapturedViewProperties;

        PropertyInfo<CapturedViewProperty, ?>[] infos = map.get(klass);
        if (infos == null) {
            infos = convertToPropertyInfos(klass.getMethods(), klass.getFields(),
                    CapturedViewProperty.class);
            map.put(klass, infos);
        }
        return infos;
    }

    private static String exportCapturedViewProperties(Object obj, Class<?> klass, String prefix) {
        if (obj == null) {
            return "null";
        }

        StringBuilder sb = new StringBuilder();

        for (PropertyInfo<CapturedViewProperty, ?> pi : getCapturedViewProperties(klass)) {
            try {
                Object methodValue = pi.invoke(obj);

                if (pi.property.retrieveReturn()) {
                    //we are interested in the second level data only
                    sb.append(exportCapturedViewProperties(methodValue, pi.returnType,
                            pi.name + "#"));
                } else {
                    sb.append(prefix).append(pi.name).append(pi.entrySuffix).append("=");

                    if (methodValue != null) {
                        final String value = methodValue.toString().replace("\n", "\\n");
                        sb.append(value);
                    } else {
                        sb.append("null");
                    }
                    sb.append(pi.valueSuffix).append(" ");
                }
            } catch (Exception e) {
                //It is OK here, we simply ignore this property
            }
        }
        return sb.toString();
    }

    /**
     * Dump view info for id based instrument test generation
     * (and possibly further data analysis). The results are dumped
     * to the log.
     * @param tag for log
     * @param view for dump
     */
    public static void dumpCapturedView(String tag, Object view) {
        Class<?> klass = view.getClass();
        StringBuilder sb = new StringBuilder(klass.getName() + ": ");
        sb.append(exportCapturedViewProperties(view, klass, ""));
        Log.d(tag, sb.toString());
    }

    /**
     * Invoke a particular method on given view.
     * The given method is always invoked on the UI thread. The caller thread will stall until the
     * method invocation is complete. Returns an object equal to the result of the method
     * invocation, null if the method is declared to return void
     * @throws Exception if the method invocation caused any exception
     * @hide
     */
    public static Object invokeViewMethod(final View view, final Method method,
            final Object[] args) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Object> result = new AtomicReference<Object>();
        final AtomicReference<Throwable> exception = new AtomicReference<Throwable>();

        view.post(new Runnable() {
            @Override
            public void run() {
                try {
                    result.set(method.invoke(view, args));
                } catch (InvocationTargetException e) {
                    exception.set(e.getCause());
                } catch (Exception e) {
                    exception.set(e);
                }

                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        if (exception.get() != null) {
            throw new RuntimeException(exception.get());
        }

        return result.get();
    }

    /**
     * @hide
     */
    public static void setLayoutParameter(final View view, final String param, final int value)
            throws NoSuchFieldException, IllegalAccessException {
        final ViewGroup.LayoutParams p = view.getLayoutParams();
        final Field f = p.getClass().getField(param);
        if (f.getType() != int.class) {
            throw new RuntimeException("Only integer layout parameters can be set. Field "
                    + param + " is of type " + f.getType().getSimpleName());
        }

        f.set(p, Integer.valueOf(value));

        view.post(new Runnable() {
            @Override
            public void run() {
                view.setLayoutParams(p);
            }
        });
    }

    /**
     * @hide
     */
    public static class SoftwareCanvasProvider implements CanvasProvider {

        private Canvas mCanvas;
        private Bitmap mBitmap;
        private boolean mEnabledHwBitmapsInSwMode;

        @Override
        public Canvas getCanvas(View view, int width, int height) {
            mBitmap = Bitmap.createBitmap(view.getResources().getDisplayMetrics(),
                    width, height, Bitmap.Config.ARGB_8888);
            if (mBitmap == null) {
                throw new OutOfMemoryError();
            }
            mBitmap.setDensity(view.getResources().getDisplayMetrics().densityDpi);

            if (view.mAttachInfo != null) {
                mCanvas = view.mAttachInfo.mCanvas;
            }
            if (mCanvas == null) {
                mCanvas = new Canvas();
            }
            mEnabledHwBitmapsInSwMode = mCanvas.isHwBitmapsInSwModeEnabled();
            mCanvas.setBitmap(mBitmap);
            return mCanvas;
        }

        @Override
        public Bitmap createBitmap() {
            mCanvas.setBitmap(null);
            mCanvas.setHwBitmapsInSwModeEnabled(mEnabledHwBitmapsInSwMode);
            return mBitmap;
        }
    }

    /**
     * @hide
     */
    public static class HardwareCanvasProvider implements CanvasProvider {
        private Picture mPicture;

        @Override
        public Canvas getCanvas(View view, int width, int height) {
            mPicture = new Picture();
            return mPicture.beginRecording(width, height);
        }

        @Override
        public Bitmap createBitmap() {
            mPicture.endRecording();
            return Bitmap.createBitmap(mPicture);
        }
    }

    /**
     * @hide
     */
    public interface CanvasProvider {

        /**
         * Returns a canvas which can be used to draw {@param view}
         */
        Canvas getCanvas(View view, int width, int height);

        /**
         * Creates a bitmap from previously returned canvas
         * @return
         */
        Bitmap createBitmap();
    }
}
