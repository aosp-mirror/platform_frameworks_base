/*
 * Copyright (C) 2012 The Android Open Source Project
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
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.print.PrintDocumentAdapter;
import android.util.SparseArray;
import android.view.DragEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.autofill.AutofillValue;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.textclassifier.TextClassifier;
import android.webkit.WebView.HitTestResult;
import android.webkit.WebView.PictureListener;
import android.webkit.WebView.VisualStateCallback;


import java.io.BufferedWriter;
import java.io.File;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * WebView backend provider interface: this interface is the abstract backend to a WebView
 * instance; each WebView object is bound to exactly one WebViewProvider object which implements
 * the runtime behavior of that WebView.
 *
 * All methods must behave as per their namesake in {@link WebView}, unless otherwise noted.
 *
 * @hide Not part of the public API; only required by system implementors.
 */
@SystemApi
public interface WebViewProvider {
    //-------------------------------------------------------------------------
    // Main interface for backend provider of the WebView class.
    //-------------------------------------------------------------------------
    /**
     * Initialize this WebViewProvider instance. Called after the WebView has fully constructed.
     * @param javaScriptInterfaces is a Map of interface names, as keys, and
     * object implementing those interfaces, as values.
     * @param privateBrowsing If {@code true} the web view will be initialized in private /
     * incognito mode.
     */
    public void init(Map<String, Object> javaScriptInterfaces,
            boolean privateBrowsing);

    // Deprecated - should never be called
    public void setHorizontalScrollbarOverlay(boolean overlay);

    // Deprecated - should never be called
    public void setVerticalScrollbarOverlay(boolean overlay);

    // Deprecated - should never be called
    public boolean overlayHorizontalScrollbar();

    // Deprecated - should never be called
    public boolean overlayVerticalScrollbar();

    public int getVisibleTitleHeight();

    public SslCertificate getCertificate();

    public void setCertificate(SslCertificate certificate);

    public void savePassword(String host, String username, String password);

    public void setHttpAuthUsernamePassword(String host, String realm,
            String username, String password);

    public String[] getHttpAuthUsernamePassword(String host, String realm);

    /**
     * See {@link WebView#destroy()}.
     * As well as releasing the internal state and resources held by the implementation,
     * the provider should null all references it holds on the WebView proxy class, and ensure
     * no further method calls are made to it.
     */
    public void destroy();

    public void setNetworkAvailable(boolean networkUp);

    public WebBackForwardList saveState(Bundle outState);

    public boolean savePicture(Bundle b, final File dest);

    public boolean restorePicture(Bundle b, File src);

    public WebBackForwardList restoreState(Bundle inState);

    public void loadUrl(String url, Map<String, String> additionalHttpHeaders);

    public void loadUrl(String url);

    public void postUrl(String url, byte[] postData);

    public void loadData(String data, String mimeType, String encoding);

    public void loadDataWithBaseURL(String baseUrl, String data,
            String mimeType, String encoding, String historyUrl);

    public void evaluateJavaScript(String script, ValueCallback<String> resultCallback);

    public void saveWebArchive(String filename);

    public void saveWebArchive(String basename, boolean autoname, ValueCallback<String> callback);

    public void stopLoading();

    public void reload();

    public boolean canGoBack();

    public void goBack();

    public boolean canGoForward();

    public void goForward();

    public boolean canGoBackOrForward(int steps);

    public void goBackOrForward(int steps);

    public boolean isPrivateBrowsingEnabled();

    public boolean pageUp(boolean top);

    public boolean pageDown(boolean bottom);

    public void insertVisualStateCallback(long requestId, VisualStateCallback callback);

    public void clearView();

    public Picture capturePicture();

    public PrintDocumentAdapter createPrintDocumentAdapter(String documentName);

    public float getScale();

    public void setInitialScale(int scaleInPercent);

    public void invokeZoomPicker();

    public HitTestResult getHitTestResult();

    public void requestFocusNodeHref(Message hrefMsg);

    public void requestImageRef(Message msg);

    public String getUrl();

    public String getOriginalUrl();

    public String getTitle();

    public Bitmap getFavicon();

    public String getTouchIconUrl();

    public int getProgress();

    public int getContentHeight();

    public int getContentWidth();

    public void pauseTimers();

    public void resumeTimers();

    public void onPause();

    public void onResume();

    public boolean isPaused();

    public void freeMemory();

    public void clearCache(boolean includeDiskFiles);

    public void clearFormData();

    public void clearHistory();

    public void clearSslPreferences();

    public WebBackForwardList copyBackForwardList();

    public void setFindListener(WebView.FindListener listener);

    public void findNext(boolean forward);

    public int findAll(String find);

    public void findAllAsync(String find);

    public boolean showFindDialog(String text, boolean showIme);

    public void clearMatches();

    public void documentHasImages(Message response);

    public void setWebViewClient(WebViewClient client);

    public WebViewClient getWebViewClient();

    public WebViewRenderer getWebViewRenderer();

    public void setWebViewRendererClient(
            @Nullable Executor executor,
            @Nullable WebViewRendererClient client);

    public WebViewRendererClient getWebViewRendererClient();

    public void setDownloadListener(DownloadListener listener);

    public void setWebChromeClient(WebChromeClient client);

    public WebChromeClient getWebChromeClient();

    public void setPictureListener(PictureListener listener);

    public void addJavascriptInterface(Object obj, String interfaceName);

    public void removeJavascriptInterface(String interfaceName);

    public WebMessagePort[] createWebMessageChannel();

    public void postMessageToMainFrame(WebMessage message, Uri targetOrigin);

    public WebSettings getSettings();

    public void setMapTrackballToArrowKeys(boolean setMap);

    public void flingScroll(int vx, int vy);

    public View getZoomControls();

    public boolean canZoomIn();

    public boolean canZoomOut();

    public boolean zoomBy(float zoomFactor);

    public boolean zoomIn();

    public boolean zoomOut();

    public void dumpViewHierarchyWithProperties(BufferedWriter out, int level);

    public View findHierarchyView(String className, int hashCode);

    public void setRendererPriorityPolicy(int rendererRequestedPriority, boolean waivedWhenNotVisible);

    public int getRendererRequestedPriority();

    public boolean getRendererPriorityWaivedWhenNotVisible();

    @SuppressWarnings("unused")
    public default void setTextClassifier(@Nullable TextClassifier textClassifier) {}

    @NonNull
    public default TextClassifier getTextClassifier() { return TextClassifier.NO_OP; }

    //-------------------------------------------------------------------------
    // Provider internal methods
    //-------------------------------------------------------------------------

    /**
     * @return the ViewDelegate implementation. This provides the functionality to back all of
     * the name-sake functions from the View and ViewGroup base classes of WebView.
     */
    /* package */ ViewDelegate getViewDelegate();

    /**
     * @return a ScrollDelegate implementation. Normally this would be same object as is
     * returned by getViewDelegate().
     */
    /* package */ ScrollDelegate getScrollDelegate();

    /**
     * Only used by FindActionModeCallback to inform providers that the find dialog has
     * been dismissed.
     */
    public void notifyFindDialogDismissed();

    //-------------------------------------------------------------------------
    // View / ViewGroup delegation methods
    //-------------------------------------------------------------------------

    /**
     * Provides mechanism for the name-sake methods declared in View and ViewGroup to be delegated
     * into the WebViewProvider instance.
     * NOTE: For many of these methods, the WebView will provide a super.Foo() call before or after
     * making the call into the provider instance. This is done for convenience in the common case
     * of maintaining backward compatibility. For remaining super class calls (e.g. where the
     * provider may need to only conditionally make the call based on some internal state) see the
     * {@link WebView.PrivateAccess} callback class.
     */
    // TODO: See if the pattern of the super-class calls can be rationalized at all, and document
    // the remainder on the methods below.
    interface ViewDelegate {
        public boolean shouldDelayChildPressedState();

        public void onProvideVirtualStructure(android.view.ViewStructure structure);

        default void onProvideAutofillVirtualStructure(
                @SuppressWarnings("unused") android.view.ViewStructure structure,
                @SuppressWarnings("unused") int flags) {
        }

        default void autofill(@SuppressWarnings("unused") SparseArray<AutofillValue> values) {
        }

        default boolean isVisibleToUserForAutofill(@SuppressWarnings("unused") int virtualId) {
            return true; // true is the default value returned by View.isVisibleToUserForAutofill()
        }

        default void onProvideContentCaptureStructure(
                @SuppressWarnings("unused") android.view.ViewStructure structure,
                @SuppressWarnings("unused") int flags) {
        }

        public AccessibilityNodeProvider getAccessibilityNodeProvider();

        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info);

        public void onInitializeAccessibilityEvent(AccessibilityEvent event);

        public boolean performAccessibilityAction(int action, Bundle arguments);

        public void setOverScrollMode(int mode);

        public void setScrollBarStyle(int style);

        public void onDrawVerticalScrollBar(Canvas canvas, Drawable scrollBar, int l, int t,
                int r, int b);

        public void onOverScrolled(int scrollX, int scrollY, boolean clampedX, boolean clampedY);

        public void onWindowVisibilityChanged(int visibility);

        public void onDraw(Canvas canvas);

        public void setLayoutParams(LayoutParams layoutParams);

        public boolean performLongClick();

        public void onConfigurationChanged(Configuration newConfig);

        public InputConnection onCreateInputConnection(EditorInfo outAttrs);

        public boolean onDragEvent(DragEvent event);

        public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event);

        public boolean onKeyDown(int keyCode, KeyEvent event);

        public boolean onKeyUp(int keyCode, KeyEvent event);

        public void onAttachedToWindow();

        public void onDetachedFromWindow();

        public default void onMovedToDisplay(int displayId, Configuration config) {}

        public void onVisibilityChanged(View changedView, int visibility);

        public void onWindowFocusChanged(boolean hasWindowFocus);

        public void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect);

        public boolean setFrame(int left, int top, int right, int bottom);

        public void onSizeChanged(int w, int h, int ow, int oh);

        public void onScrollChanged(int l, int t, int oldl, int oldt);

        public boolean dispatchKeyEvent(KeyEvent event);

        public boolean onTouchEvent(MotionEvent ev);

        public boolean onHoverEvent(MotionEvent event);

        public boolean onGenericMotionEvent(MotionEvent event);

        public boolean onTrackballEvent(MotionEvent ev);

        public boolean requestFocus(int direction, Rect previouslyFocusedRect);

        public void onMeasure(int widthMeasureSpec, int heightMeasureSpec);

        public boolean requestChildRectangleOnScreen(View child, Rect rect, boolean immediate);

        public void setBackgroundColor(int color);

        public void setLayerType(int layerType, Paint paint);

        public void preDispatchDraw(Canvas canvas);

        public void onStartTemporaryDetach();

        public void onFinishTemporaryDetach();

        public void onActivityResult(int requestCode, int resultCode, Intent data);

        public Handler getHandler(Handler originalHandler);

        public View findFocus(View originalFocusedView);

        @SuppressWarnings("unused")
        default boolean onCheckIsTextEditor() {
            return false;
        }
    }

    interface ScrollDelegate {
        // These methods are declared protected in the ViewGroup base class. This interface
        // exists to promote them to public so they may be called by the WebView proxy class.
        // TODO: Combine into ViewDelegate?
        /**
         * See {@link android.webkit.WebView#computeHorizontalScrollRange}
         */
        public int computeHorizontalScrollRange();

        /**
         * See {@link android.webkit.WebView#computeHorizontalScrollOffset}
         */
        public int computeHorizontalScrollOffset();

        /**
         * See {@link android.webkit.WebView#computeVerticalScrollRange}
         */
        public int computeVerticalScrollRange();

        /**
         * See {@link android.webkit.WebView#computeVerticalScrollOffset}
         */
        public int computeVerticalScrollOffset();

        /**
         * See {@link android.webkit.WebView#computeVerticalScrollExtent}
         */
        public int computeVerticalScrollExtent();

        /**
         * See {@link android.webkit.WebView#computeScroll}
         */
        public void computeScroll();
    }
}
