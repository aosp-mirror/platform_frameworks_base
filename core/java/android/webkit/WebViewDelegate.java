/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.webkit;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.UnsupportedAppUsage;
import android.app.ActivityThread;
import android.app.Application;
import android.app.ResourcesManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewRootImpl;

import com.android.internal.util.ArrayUtils;

/**
 * Delegate used by the WebView provider implementation to access
 * the required framework functionality needed to implement a {@link WebView}.
 *
 * @hide
 */
@SystemApi
public final class WebViewDelegate {

    @UnsupportedAppUsage
    /* package */ WebViewDelegate() { }

    /**
     * Listener that gets notified whenever tracing has been enabled/disabled.
     */
    public interface OnTraceEnabledChangeListener {
        void onTraceEnabledChange(boolean enabled);
    }

    /**
     * Register a callback to be invoked when tracing for the WebView component has been
     * enabled/disabled.
     */
    public void setOnTraceEnabledChangeListener(final OnTraceEnabledChangeListener listener) {
        SystemProperties.addChangeCallback(new Runnable() {
            @Override
            public void run() {
                listener.onTraceEnabledChange(isTraceTagEnabled());
            }
        });
    }

    /**
     * Returns {@code true} if the WebView trace tag is enabled and {@code false} otherwise.
     */
    public boolean isTraceTagEnabled() {
        return Trace.isTagEnabled(Trace.TRACE_TAG_WEBVIEW);
    }

    /**
     * Returns {@code true} if the draw GL functor can be invoked (see {@link #invokeDrawGlFunctor})
     * and {@code false} otherwise.
     *
     * @deprecated Use {@link #drawWebViewFunctor(Canvas, int)}
     */
    @Deprecated
    public boolean canInvokeDrawGlFunctor(View containerView) {
        return true;
    }

    /**
     * Invokes the draw GL functor. If waitForCompletion is {@code false} the functor
     * may be invoked asynchronously.
     *
     * @param nativeDrawGLFunctor the pointer to the native functor that implements
     *        system/core/include/utils/Functor.h
     * @deprecated Use {@link #drawWebViewFunctor(Canvas, int)}
     */
    @Deprecated
    public void invokeDrawGlFunctor(View containerView, long nativeDrawGLFunctor,
            boolean waitForCompletion) {
        ViewRootImpl.invokeFunctor(nativeDrawGLFunctor, waitForCompletion);
    }

    /**
     * Calls the function specified with the nativeDrawGLFunctor functor pointer. This
     * functionality is used by the WebView for calling into their renderer from the
     * framework display lists.
     *
     * @param canvas a hardware accelerated canvas (see {@link Canvas#isHardwareAccelerated()})
     * @param nativeDrawGLFunctor the pointer to the native functor that implements
     *        system/core/include/utils/Functor.h
     * @throws IllegalArgumentException if the canvas is not hardware accelerated
     * @deprecated Use {@link #drawWebViewFunctor(Canvas, int)}
     */
    @Deprecated
    public void callDrawGlFunction(Canvas canvas, long nativeDrawGLFunctor) {
        if (!(canvas instanceof RecordingCanvas)) {
            // Canvas#isHardwareAccelerated() is only true for subclasses of HardwareCanvas.
            throw new IllegalArgumentException(canvas.getClass().getName()
                    + " is not a DisplayList canvas");
        }
        ((RecordingCanvas) canvas).drawGLFunctor2(nativeDrawGLFunctor, null);
    }

    /**
     * Calls the function specified with the nativeDrawGLFunctor functor pointer. This
     * functionality is used by the WebView for calling into their renderer from the
     * framework display lists.
     *
     * @param canvas a hardware accelerated canvas (see {@link Canvas#isHardwareAccelerated()})
     * @param nativeDrawGLFunctor the pointer to the native functor that implements
     *        system/core/include/utils/Functor.h
     * @param releasedRunnable Called when this nativeDrawGLFunctor is no longer referenced by this
     *        canvas, so is safe to be destroyed.
     * @throws IllegalArgumentException if the canvas is not hardware accelerated
     * @deprecated Use {@link #drawWebViewFunctor(Canvas, int)}
     */
    @Deprecated
    public void callDrawGlFunction(@NonNull Canvas canvas, long nativeDrawGLFunctor,
            @Nullable Runnable releasedRunnable) {
        if (!(canvas instanceof RecordingCanvas)) {
            // Canvas#isHardwareAccelerated() is only true for subclasses of HardwareCanvas.
            throw new IllegalArgumentException(canvas.getClass().getName()
                    + " is not a DisplayList canvas");
        }
        ((RecordingCanvas) canvas).drawGLFunctor2(nativeDrawGLFunctor, releasedRunnable);
    }

    /**
     * Call webview draw functor. See API in draw_fn.h.
     * @param canvas a {@link RecordingCanvas}.
     * @param functor created by AwDrawFn_CreateFunctor in draw_fn.h.
     */
    public void drawWebViewFunctor(@NonNull Canvas canvas, int functor) {
        if (!(canvas instanceof RecordingCanvas)) {
            // Canvas#isHardwareAccelerated() is only true for subclasses of RecordingCanvas.
            throw new IllegalArgumentException(canvas.getClass().getName()
                    + " is not a RecordingCanvas canvas");
        }
        ((RecordingCanvas) canvas).drawWebViewFunctor(functor);
    }

    /**
     * Detaches the draw GL functor.
     *
     * @param nativeDrawGLFunctor the pointer to the native functor that implements
     *        system/core/include/utils/Functor.h
     * @deprecated Use {@link #drawWebViewFunctor(Canvas, int)}
     */
    @Deprecated
    public void detachDrawGlFunctor(View containerView, long nativeDrawGLFunctor) {
        ViewRootImpl viewRootImpl = containerView.getViewRootImpl();
        if (nativeDrawGLFunctor != 0 && viewRootImpl != null) {
            viewRootImpl.detachFunctor(nativeDrawGLFunctor);
        }
    }

    /**
     * Returns the package id of the given {@code packageName}.
     */
    public int getPackageId(Resources resources, String packageName) {
        SparseArray<String> packageIdentifiers =
                resources.getAssets().getAssignedPackageIdentifiers();
        for (int i = 0; i < packageIdentifiers.size(); i++) {
            final String name = packageIdentifiers.valueAt(i);

            if (packageName.equals(name)) {
                return packageIdentifiers.keyAt(i);
            }
        }
        throw new RuntimeException("Package not found: " + packageName);
    }

    /**
     * Returns the application which is embedding the WebView.
     */
    public Application getApplication() {
        return ActivityThread.currentApplication();
    }

    /**
     * Returns the error string for the given {@code errorCode}.
     */
    public String getErrorString(Context context, int errorCode) {
        return LegacyErrorStrings.getString(errorCode, context);
    }

    /**
     * Adds the WebView asset path to {@link android.content.res.AssetManager}.
     */
    public void addWebViewAssetPath(Context context) {
        final String newAssetPath = WebViewFactory.getLoadedPackageInfo().applicationInfo.sourceDir;

        final ApplicationInfo appInfo = context.getApplicationInfo();
        final String[] libs = appInfo.sharedLibraryFiles;
        if (!ArrayUtils.contains(libs, newAssetPath)) {
            // Build the new library asset path list.
            final int newLibAssetsCount = 1 + (libs != null ? libs.length : 0);
            final String[] newLibAssets = new String[newLibAssetsCount];
            if (libs != null) {
                System.arraycopy(libs, 0, newLibAssets, 0, libs.length);
            }
            newLibAssets[newLibAssetsCount - 1] = newAssetPath;

            // Update the ApplicationInfo object with the new list.
            // We know this will persist and future Resources created via ResourcesManager
            // will include the shared library because this ApplicationInfo comes from the
            // underlying LoadedApk in ContextImpl, which does not change during the life of the
            // application.
            appInfo.sharedLibraryFiles = newLibAssets;

            // Update existing Resources with the WebView library.
            ResourcesManager.getInstance().appendLibAssetForMainAssetPath(
                    appInfo.getBaseResourcePath(), newAssetPath);
        }
    }

    /**
     * Returns whether WebView should run in multiprocess mode.
     */
    public boolean isMultiProcessEnabled() {
        try {
            return WebViewFactory.getUpdateService().isMultiProcessEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the data directory suffix to use, or null for none.
     */
    public String getDataDirectorySuffix() {
        return WebViewFactory.getDataDirectorySuffix();
    }
}
