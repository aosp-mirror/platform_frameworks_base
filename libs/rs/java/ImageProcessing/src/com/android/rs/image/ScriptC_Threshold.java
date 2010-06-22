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

package com.android.rs.image;

import android.renderscript.*;
import android.content.res.Resources;
import android.util.Log;

public class ScriptC_Threshold extends ScriptC {
    // Constructor
    public  ScriptC_Threshold(RenderScript rs, Resources resources, int id, boolean isRoot) {
        super(rs, resources, id, isRoot);
    }

    private final static int mExportVarIdx_height = 0;
    private int mExportVar_height;
    public void set_height(int v) {
        mExportVar_height = v;
        setVar(mExportVarIdx_height, v);
    }

    public int get_height() {
        return mExportVar_height;
    }

    private final static int mExportVarIdx_width = 1;
    private int mExportVar_width;
    public void set_width(int v) {
        mExportVar_width = v;
        setVar(mExportVarIdx_width, v);
    }

    public int get_width() {
        return mExportVar_width;
    }

    private final static int mExportVarIdx_radius = 2;
    private int mExportVar_radius;
    public void set_radius(int v) {
        mExportVar_radius = v;
        setVar(mExportVarIdx_radius, v);
    }

    public int get_radius() {
        return mExportVar_radius;
    }

    private final static int mExportVarIdx_InPixel = 3;
    private Allocation mExportVar_InPixel;
    public void bind_InPixel(Allocation v) {
        mExportVar_InPixel = v;
        if(v == null) bindAllocation(null, mExportVarIdx_InPixel);
        else bindAllocation(v, mExportVarIdx_InPixel);
    }

    public Allocation get_InPixel() {
        return mExportVar_InPixel;
    }

    private final static int mExportVarIdx_OutPixel = 4;
    private Allocation mExportVar_OutPixel;
    public void bind_OutPixel(Allocation v) {
        mExportVar_OutPixel = v;
        if(v == null) bindAllocation(null, mExportVarIdx_OutPixel);
        else bindAllocation(v, mExportVarIdx_OutPixel);
    }

    public Allocation get_OutPixel() {
        return mExportVar_OutPixel;
    }

    private final static int mExportVarIdx_ScratchPixel = 5;
    private Allocation mExportVar_ScratchPixel;
    public void bind_ScratchPixel(Allocation v) {
        mExportVar_ScratchPixel = v;
        if(v == null) bindAllocation(null, mExportVarIdx_ScratchPixel);
        else bindAllocation(v, mExportVarIdx_ScratchPixel);
    }

    public Allocation get_ScratchPixel() {
        return mExportVar_ScratchPixel;
    }

    private final static int mExportVarIdx_inBlack = 6;
    private float mExportVar_inBlack;
    public void set_inBlack(float v) {
        mExportVar_inBlack = v;
        setVar(mExportVarIdx_inBlack, v);
    }

    public float get_inBlack() {
        return mExportVar_inBlack;
    }

    private final static int mExportVarIdx_outBlack = 7;
    private float mExportVar_outBlack;
    public void set_outBlack(float v) {
        mExportVar_outBlack = v;
        setVar(mExportVarIdx_outBlack, v);
    }

    public float get_outBlack() {
        return mExportVar_outBlack;
    }

    private final static int mExportVarIdx_inWhite = 8;
    private float mExportVar_inWhite;
    public void set_inWhite(float v) {
        mExportVar_inWhite = v;
        setVar(mExportVarIdx_inWhite, v);
    }

    public float get_inWhite() {
        return mExportVar_inWhite;
    }

    private final static int mExportVarIdx_outWhite = 9;
    private float mExportVar_outWhite;
    public void set_outWhite(float v) {
        mExportVar_outWhite = v;
        setVar(mExportVarIdx_outWhite, v);
    }

    public float get_outWhite() {
        return mExportVar_outWhite;
    }

    private final static int mExportVarIdx_gamma = 10;
    private float mExportVar_gamma;
    public void set_gamma(float v) {
        mExportVar_gamma = v;
        setVar(mExportVarIdx_gamma, v);
    }

    public float get_gamma() {
        return mExportVar_gamma;
    }

    private final static int mExportVarIdx_saturation = 11;
    private float mExportVar_saturation;
    public void set_saturation(float v) {
        mExportVar_saturation = v;
        setVar(mExportVarIdx_saturation, v);
    }

    public float get_saturation() {
        return mExportVar_saturation;
    }

    private final static int mExportFuncIdx_filter = 0;
    public void invoke_filter() {
        invoke(mExportFuncIdx_filter);
    }

    private final static int mExportFuncIdx_filterBenchmark = 1;
    public void invoke_filterBenchmark() {
        invoke(mExportFuncIdx_filterBenchmark);
    }

}

