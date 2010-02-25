/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.sslload;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Calendar;
import java.util.Map;

import android.app.Activity;
import android.content.res.Resources;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Vibrator;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;
import android.util.Log;
import android.net.http.AndroidHttpClient;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.HttpResponse;

public class SslLoad extends Activity implements OnClickListener, Runnable {

    private static final String TAG = SslLoad.class.getSimpleName();

    private Button button;
    private boolean running = false;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        Thread requestThread = new Thread(this);
        requestThread.setDaemon(true);
        requestThread.start();

        button = new Button(this);
        button.setText("GO");
        button.setOnClickListener(this);

        setContentView(button);
    }
    
    @Override
    protected void onStop() {
        super.onStop();

        synchronized (this) {
            running = false;
        }
    }

    public void onClick(View v) {
        synchronized (this) {
            running = !running;
            button.setText(running ? "STOP" : "GO");
            if (running) {
                this.notifyAll();
            }
        }
    }

    public void run() {
        boolean error = false;
        while (true) {
            synchronized (this) {
                while (!running) {
                    try {
                        this.wait();
                    } catch (InterruptedException e) { /* ignored */ }
                }
            }

            AndroidHttpClient client = AndroidHttpClient.newInstance(
                    "Mozilla/5.001 (windows; U; NT4.0; en-us) Gecko/25250101");
            try {
                // Cert. is for "www.google.com", not "google.com".
                String url = error ? "https://google.com/"
                        : "https://www.google.com";
                client.execute(new HttpGet(url),
                        new ResponseHandler<Void>() {
                            public Void handleResponse(HttpResponse response) {
                                /* ignore */
                                return null;
                            }
                        });
                Log.i(TAG, "Request succeeded.");
            } catch (IOException e) {
                Log.w(TAG, "Request failed.", e);
            }

            client.close();
            
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) { /* ignored */ }

            error = !error;
        }
    }
}
