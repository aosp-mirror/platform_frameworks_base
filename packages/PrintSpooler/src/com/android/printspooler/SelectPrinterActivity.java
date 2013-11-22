/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.printspooler;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.print.PrinterId;

import com.android.printspooler.SelectPrinterFragment.OnPrinterSelectedListener;

public class SelectPrinterActivity extends Activity implements OnPrinterSelectedListener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.select_printer_activity);
    }

    @Override
    public void onPrinterSelected(PrinterId printer) {
        Intent intent = new Intent();
        intent.putExtra(PrintJobConfigActivity.INTENT_EXTRA_PRINTER_ID, printer);
        setResult(RESULT_OK, intent);
        finish();
    }
}
