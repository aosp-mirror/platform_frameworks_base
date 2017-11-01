package com.android.hotspot2.osu;

import android.annotation.Nullable;
import android.app.Activity;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.http.SslError;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.webkit.SslErrorHandler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.android.hotspot2.R;

public class OSUWebView extends Activity {
    public static final String OSU_URL = "com.android.hotspot2.osu.URL";
    public static final String OSU_NETWORK = "com.android.hotspot2.osu.NETWORK";

    private String mUrl;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(OSUManager.TAG, "Opening OSU Web View");

        ConnectivityManager connectivityManager = ConnectivityManager.from(this);

        mUrl = getIntent().getStringExtra(OSU_URL);
        Network network = getIntent().getParcelableExtra(OSU_NETWORK);
        connectivityManager.bindProcessToNetwork(network);

        getActionBar().setDisplayShowHomeEnabled(false);
        setContentView(R.layout.osu_web_view);
        getActionBar().setDisplayShowHomeEnabled(false);

        final WebView myWebView = findViewById(R.id.webview);
        myWebView.clearCache(true);
        WebSettings webSettings = myWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        MyWebViewClient mWebViewClient = new MyWebViewClient();
        myWebView.setWebViewClient(mWebViewClient);
        Log.d(OSUManager.TAG, "OSU Web View to " + mUrl);
        myWebView.loadUrl(mUrl);
        Log.d(OSUManager.TAG, "OSU Web View loading");
        //myWebView.setWebChromeClient(new MyWebChromeClient());
        // Start initial page load so WebView finishes loading proxy settings.
        // Actual load of mUrl is initiated by MyWebViewClient.
        //myWebView.loadData("", "text/html", null);
    }

    private class MyWebViewClient extends WebViewClient {
        private static final String INTERNAL_ASSETS = "file:///android_asset/";
        // How many Android device-independent-pixels per scaled-pixel
        // dp/sp = (px/sp) / (px/dp) = (1/sp) / (1/dp)
        private final float mDpPerSp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1,
                getResources().getDisplayMetrics()) /
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1,
                        getResources().getDisplayMetrics());
        private int mPagesLoaded;

        // If we haven't finished cleaning up the history, don't allow going back.
        public boolean allowBack() {
            return mPagesLoaded > 1;
        }

        // Convert Android device-independent-pixels (dp) to HTML size.
        private String dp(int dp) {
            // HTML px's are scaled just like dp's, so just add "px" suffix.
            return Integer.toString(dp) + "px";
        }

        // Convert Android scaled-pixels (sp) to HTML size.
        private String sp(int sp) {
            // Convert sp to dp's.
            float dp = sp * mDpPerSp;
            // Apply a scale factor to make things look right.
            dp *= 1.3;
            // Convert dp's to HTML size.
            return dp((int)dp);
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            Log.d(OSUManager.TAG, "TLS error in Web View: " + error);
        }
    }
}
