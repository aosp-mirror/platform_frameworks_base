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

package com.android.tools.layoutlib.create;


public class MockLog extends Log {
    StringBuilder mOut = new StringBuilder();
    StringBuilder mErr = new StringBuilder();

    public String getOut() {
        return mOut.toString();
    }

    public String getErr() {
        return mErr.toString();
    }

    @Override
    protected void outPrintln(String msg) {
        mOut.append(msg);
        mOut.append('\n');
    }

    @Override
    protected void errPrintln(String msg) {
        mErr.append(msg);
        mErr.append('\n');
    }
}
