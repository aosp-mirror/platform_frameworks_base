/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.startop.colorchanging;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.os.Trace;
import android.view.View;

public class MainActivity extends AppCompatActivity {
    View view;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        view = this.getWindow().getDecorView();
        view.setBackgroundResource(R.color.gray);
        Trace.beginSection("gray");
    }

    public void goRed(View v) {
        Trace.endSection();
        view.setBackgroundResource(R.color.red);
        Trace.beginSection("red");
    }

    public void goOrange(View v) {
        Trace.endSection();
        view.setBackgroundResource(R.color.orange);
        Trace.beginSection("orange");
    }

    public void goYellow(View v) {
        Trace.endSection();
        view.setBackgroundResource(R.color.yellow);
        Trace.beginSection("yellow");
    }

    public void goGreen(View v) {
        Trace.endSection();
        view.setBackgroundResource(R.color.green);
        Trace.beginSection("green");
    }

    public void goBlue(View v) {
        Trace.endSection();
        view.setBackgroundResource(R.color.blue);
        Trace.beginSection("blue");
    }

    public void goIndigo(View v) {
        Trace.endSection();
        view.setBackgroundResource(R.color.indigo);
        Trace.beginSection("indigo");
    }

    public void goViolet(View v) {
        Trace.endSection();
        view.setBackgroundResource(R.color.violet);
        Trace.beginSection("violet");
    }

    public void goCyan(View v) {
        Trace.endSection();
        view.setBackgroundResource(R.color.cyan);
        Trace.beginSection("cyan");
    }

    public void goBlack(View v) {
        Trace.endSection();
        view.setBackgroundResource(R.color.black);
        Trace.beginSection("black");
    }

}
