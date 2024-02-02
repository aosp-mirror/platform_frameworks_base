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

package com.android.test.hwui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;

public class LooperAcceleration extends Activity {

    static final boolean INCLUDE_WEBVIEW = false;

    static class IsAcceleratedView extends View {

        public IsAcceleratedView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (canvas.isHardwareAccelerated()) {
                canvas.drawARGB(0xFF, 0x00, 0xFF, 0x00);
            } else {
                canvas.drawARGB(0xFF, 0xFF, 0x00, 0x00);
            }
        }

    }

    private View makeView() {
        LinearLayout layout = new LinearLayout(this);
        layout.addView(new IsAcceleratedView(this), LayoutParams.MATCH_PARENT, 60);

        if (INCLUDE_WEBVIEW) {
            WebView wv = new WebView(this);
            wv.setWebViewClient(new WebViewClient());
            wv.setWebChromeClient(new WebChromeClient());
            wv.loadUrl("http://www.webkit.org/blog-files/3d-transforms/poster-circle.html");
            layout.addView(wv, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        }
        return layout;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(makeView());

        new Thread() {
            @Override
            public void run() {
                Looper.prepare();
                final Context context = LooperAcceleration.this;
                Dialog dlg = new Dialog(context);
                dlg.addContentView(makeView(), new LayoutParams(300, 400));
                dlg.setCancelable(true);
                dlg.setCanceledOnTouchOutside(true);
                dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        Looper.myLooper().quit();
                    }
                });
                dlg.setTitle("Not Looper.getMainLooper() check");
                dlg.show();
                Looper.loop();
            }
        }.start();
    }
}
