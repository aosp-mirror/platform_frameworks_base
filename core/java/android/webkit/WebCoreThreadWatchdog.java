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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.webkit.WebViewCore.EventHub;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

// A Runnable that will monitor if the WebCore thread is still
// processing messages by pinging it every so often. It is safe
// to call the public methods of this class from any thread.
class WebCoreThreadWatchdog implements Runnable {

    // A message with this id is sent by the WebCore thread to notify the
    // Watchdog that the WebCore thread is still processing messages
    // (i.e. everything is OK).
    private static final int IS_ALIVE = 100;

    // This message is placed in the Watchdog's queue and removed when we
    // receive an IS_ALIVE. If it is ever processed, we consider the
    // WebCore thread unresponsive.
    private static final int TIMED_OUT = 101;

    // Wait 10s after hearing back from the WebCore thread before checking it's still alive.
    private static final int HEARTBEAT_PERIOD = 10 * 1000;

    // If there's no callback from the WebCore thread for 30s, prompt the user the page has
    // become unresponsive.
    private static final int TIMEOUT_PERIOD = 30 * 1000;

    // After the first timeout, use a shorter period before re-prompting the user.
    private static final int SUBSEQUENT_TIMEOUT_PERIOD = 15 * 1000;

    private Handler mWebCoreThreadHandler;
    private Handler mHandler;
    private boolean mPaused;

    private Set<WebViewClassic> mWebViews;

    private static WebCoreThreadWatchdog sInstance;

    public synchronized static WebCoreThreadWatchdog start(Handler webCoreThreadHandler) {
        if (sInstance == null) {
            sInstance = new WebCoreThreadWatchdog(webCoreThreadHandler);
            new Thread(sInstance, "WebCoreThreadWatchdog").start();
        }
        return sInstance;
    }

    public synchronized static void registerWebView(WebViewClassic w) {
        if (sInstance != null) {
            sInstance.addWebView(w);
        }
    }

    public synchronized static void unregisterWebView(WebViewClassic w) {
        if (sInstance != null) {
            sInstance.removeWebView(w);
        }
    }

    public synchronized static void pause() {
        if (sInstance != null) {
            sInstance.pauseWatchdog();
        }
    }

    public synchronized static void resume() {
        if (sInstance != null) {
            sInstance.resumeWatchdog();
        }
    }

    private void addWebView(WebViewClassic w) {
        if (mWebViews == null) {
            mWebViews = new HashSet<WebViewClassic>();
        }
        mWebViews.add(w);
    }

    private void removeWebView(WebViewClassic w) {
        mWebViews.remove(w);
    }

    private WebCoreThreadWatchdog(Handler webCoreThreadHandler) {
        mWebCoreThreadHandler = webCoreThreadHandler;
    }

    private void pauseWatchdog() {
        mPaused = true;

        if (mHandler == null) {
            return;
        }

        mHandler.removeMessages(TIMED_OUT);
        mHandler.removeMessages(IS_ALIVE);
        mWebCoreThreadHandler.removeMessages(EventHub.HEARTBEAT);
    }

    private void resumeWatchdog() {
        if (!mPaused) {
            // Do nothing if we get a call to resume without being paused.
            // This can happen during the initialisation of the WebView.
            return;
        }

        mPaused = false;

        if (mHandler == null) {
            return;
        }

        mWebCoreThreadHandler.obtainMessage(EventHub.HEARTBEAT,
                mHandler.obtainMessage(IS_ALIVE)).sendToTarget();
        mHandler.sendMessageDelayed(mHandler.obtainMessage(TIMED_OUT), TIMEOUT_PERIOD);
    }

    private void createHandler() {
        synchronized (WebCoreThreadWatchdog.class) {
            mHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                    case IS_ALIVE:
                        synchronized(WebCoreThreadWatchdog.class) {
                            if (mPaused) {
                                return;
                            }

                            // The WebCore thread still seems alive. Reset the countdown timer.
                            removeMessages(TIMED_OUT);
                            sendMessageDelayed(obtainMessage(TIMED_OUT), TIMEOUT_PERIOD);
                            mWebCoreThreadHandler.sendMessageDelayed(
                                    mWebCoreThreadHandler.obtainMessage(EventHub.HEARTBEAT,
                                            mHandler.obtainMessage(IS_ALIVE)),
                                    HEARTBEAT_PERIOD);
                        }
                        break;

                    case TIMED_OUT:
                        boolean postedDialog = false;
                        synchronized (WebCoreThreadWatchdog.class) {
                            Iterator<WebViewClassic> it = mWebViews.iterator();
                            // Check each WebView we are aware of and find one that is capable of
                            // showing the user a prompt dialog.
                            while (it.hasNext()) {
                                WebView activeView = it.next().getWebView();

                                if (activeView.getWindowToken() != null &&
                                        activeView.getViewRootImpl() != null) {
                                    postedDialog = activeView.post(new PageNotRespondingRunnable(
                                            activeView.getContext(), this));

                                    if (postedDialog) {
                                        // We placed the message into the UI thread for an attached
                                        // WebView so we've made our best attempt to display the
                                        // "page not responding" dialog to the user. Although the
                                        // message is in the queue, there is no guarantee when/if
                                        // the runnable will execute. In the case that the runnable
                                        // never executes, the user will need to terminate the
                                        // process manually.
                                        break;
                                    }
                                }
                            }

                            if (!postedDialog) {
                                // There's no active webview we can use to show the dialog, so
                                // wait again. If we never get a usable view, the user will
                                // never get the chance to terminate the process, and will
                                // need to do it manually.
                                sendMessageDelayed(obtainMessage(TIMED_OUT),
                                        SUBSEQUENT_TIMEOUT_PERIOD);
                            }
                        }
                        break;
                    }
                }
            };
        }
    }

    @Override
    public void run() {
        Looper.prepare();

        createHandler();

        // Send the initial control to WebViewCore and start the timeout timer as long as we aren't
        // paused.
        synchronized (WebCoreThreadWatchdog.class) {
            if (!mPaused) {
                mWebCoreThreadHandler.obtainMessage(EventHub.HEARTBEAT,
                        mHandler.obtainMessage(IS_ALIVE)).sendToTarget();
                mHandler.sendMessageDelayed(mHandler.obtainMessage(TIMED_OUT), TIMEOUT_PERIOD);
            }
        }

        Looper.loop();
    }

    private class PageNotRespondingRunnable implements Runnable {
        Context mContext;
        private Handler mWatchdogHandler;

        public PageNotRespondingRunnable(Context context, Handler watchdogHandler) {
            mContext = context;
            mWatchdogHandler = watchdogHandler;
        }

        @Override
        public void run() {
            // This must run on the UI thread as it is displaying an AlertDialog.
            assert Looper.getMainLooper().getThread() == Thread.currentThread();
            new AlertDialog.Builder(mContext)
                    .setMessage(com.android.internal.R.string.webpage_unresponsive)
                    .setPositiveButton(com.android.internal.R.string.force_close,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    // User chose to force close.
                                    Process.killProcess(Process.myPid());
                                }
                            })
                    .setNegativeButton(com.android.internal.R.string.wait,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    // The user chose to wait. The last HEARTBEAT message
                                    // will still be in the WebCore thread's queue, so all
                                    // we need to do is post another TIMED_OUT so that the
                                    // user will get prompted again if the WebCore thread
                                    // doesn't sort itself out.
                                    mWatchdogHandler.sendMessageDelayed(
                                            mWatchdogHandler.obtainMessage(TIMED_OUT),
                                            SUBSEQUENT_TIMEOUT_PERIOD);
                                }
                            })
                    .setOnCancelListener(
                            new DialogInterface.OnCancelListener() {
                                @Override
                                public void onCancel(DialogInterface dialog) {
                                    mWatchdogHandler.sendMessageDelayed(
                                            mWatchdogHandler.obtainMessage(TIMED_OUT),
                                            SUBSEQUENT_TIMEOUT_PERIOD);
                                }
                            })
                    .setIconAttribute(android.R.attr.alertDialogIcon)
                    .show();
        }
    }
}
