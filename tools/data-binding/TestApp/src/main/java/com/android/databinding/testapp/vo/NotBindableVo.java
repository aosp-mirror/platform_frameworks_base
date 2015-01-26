/*
 * Copyright (C) 2015 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.databinding.testapp.vo;

public class NotBindableVo {
    private int intValue;
    private String stringValue;

    public NotBindableVo() {
    }

    public NotBindableVo(int intValue) {
        this.intValue = intValue;
    }

    public NotBindableVo(String stringValue) {
        this.stringValue = stringValue;
    }

    public NotBindableVo(int intValue, String stringValue) {
        this.intValue = intValue;
        this.stringValue = stringValue;
    }

    public int getIntValue() {
        return intValue;
    }

    public void setIntValue(int intValue) {
        this.intValue = intValue;
    }

    public String getStringValue() {
        return stringValue;
    }

    public void setStringValue(String stringValue) {
        this.stringValue = stringValue;
    }

    public String mergeStringFields(NotBindableVo other) {
        return stringValue + (other == null ? "" : other.stringValue);
    }
}
