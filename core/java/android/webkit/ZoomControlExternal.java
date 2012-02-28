/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.content.Context;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.View.OnClickListener;
import android.view.animation.AlphaAnimation;
import android.widget.FrameLayout;

@Deprecated
class ZoomControlExternal implements ZoomControlBase {

    // The time that the external controls are visible before fading away
    private static final long ZOOM_CONTROLS_TIMEOUT =
            ViewConfiguration.getZoomControlsTimeout();
    // The view containing the external zoom controls
    private ExtendedZoomControls mZoomControls;
    private Runnable mZoomControlRunnable;
    private final Handler mPrivateHandler = new Handler();

    private final WebViewClassic mWebView;

    public ZoomControlExternal(WebViewClassic webView) {
        mWebView = webView;
    }

    public void show() {
        if(mZoomControlRunnable != null) {
            mPrivateHandler.removeCallbacks(mZoomControlRunnable);
        }
        getControls().show(true);
        mPrivateHandler.postDelayed(mZoomControlRunnable, ZOOM_CONTROLS_TIMEOUT);
    }

    public void hide() {
        if (mZoomControlRunnable != null) {
            mPrivateHandler.removeCallbacks(mZoomControlRunnable);
        }
        if (mZoomControls != null) {
            mZoomControls.hide();
        }
    }

    public boolean isVisible() {
        return mZoomControls != null && mZoomControls.isShown();
    }

    public void update() { }

    public ExtendedZoomControls getControls() {
        if (mZoomControls == null) {
            mZoomControls = createZoomControls();

            /*
             * need to be set to VISIBLE first so that getMeasuredHeight() in
             * {@link #onSizeChanged()} can return the measured value for proper
             * layout.
             */
            mZoomControls.setVisibility(View.VISIBLE);
            mZoomControlRunnable = new Runnable() {
                public void run() {
                    /* Don't dismiss the controls if the user has
                     * focus on them. Wait and check again later.
                     */
                    if (!mZoomControls.hasFocus()) {
                        mZoomControls.hide();
                    } else {
                        mPrivateHandler.removeCallbacks(mZoomControlRunnable);
                        mPrivateHandler.postDelayed(mZoomControlRunnable,
                                ZOOM_CONTROLS_TIMEOUT);
                    }
                }
            };
        }
        return mZoomControls;
    }

    private ExtendedZoomControls createZoomControls() {
        ExtendedZoomControls zoomControls = new ExtendedZoomControls(mWebView.getContext());
        zoomControls.setOnZoomInClickListener(new OnClickListener() {
            public void onClick(View v) {
                // reset time out
                mPrivateHandler.removeCallbacks(mZoomControlRunnable);
                mPrivateHandler.postDelayed(mZoomControlRunnable, ZOOM_CONTROLS_TIMEOUT);
                mWebView.zoomIn();
            }
        });
        zoomControls.setOnZoomOutClickListener(new OnClickListener() {
            public void onClick(View v) {
                // reset time out
                mPrivateHandler.removeCallbacks(mZoomControlRunnable);
                mPrivateHandler.postDelayed(mZoomControlRunnable, ZOOM_CONTROLS_TIMEOUT);
                mWebView.zoomOut();
            }
        });
        return zoomControls;
    }

    private static class ExtendedZoomControls extends FrameLayout {

        private android.widget.ZoomControls mPlusMinusZoomControls;

        public ExtendedZoomControls(Context context) {
            super(context, null);
            LayoutInflater inflater = (LayoutInflater)
                    context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            inflater.inflate(com.android.internal.R.layout.zoom_magnify, this, true);
            mPlusMinusZoomControls = (android.widget.ZoomControls) findViewById(
                    com.android.internal.R.id.zoomControls);
            findViewById(com.android.internal.R.id.zoomMagnify).setVisibility(
                    View.GONE);
        }

        public void show(boolean showZoom) {
            mPlusMinusZoomControls.setVisibility(showZoom ? View.VISIBLE : View.GONE);
            fade(View.VISIBLE, 0.0f, 1.0f);
        }

        public void hide() {
            fade(View.GONE, 1.0f, 0.0f);
        }

        private void fade(int visibility, float startAlpha, float endAlpha) {
            AlphaAnimation anim = new AlphaAnimation(startAlpha, endAlpha);
            anim.setDuration(500);
            startAnimation(anim);
            setVisibility(visibility);
        }

        public boolean hasFocus() {
            return mPlusMinusZoomControls.hasFocus();
        }

        public void setOnZoomInClickListener(OnClickListener listener) {
            mPlusMinusZoomControls.setOnZoomInClickListener(listener);
        }

        public void setOnZoomOutClickListener(OnClickListener listener) {
            mPlusMinusZoomControls.setOnZoomOutClickListener(listener);
        }
    }
}
