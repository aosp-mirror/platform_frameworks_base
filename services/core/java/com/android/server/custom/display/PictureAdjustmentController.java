/*
 * Copyright (C) 2016 The CyanogenMod Project
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
package com.android.server.custom.display;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Range;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import com.android.internal.custom.hardware.LineageHardwareManager;
import com.android.internal.custom.hardware.DisplayMode;
import com.android.internal.custom.hardware.HSIC;
import com.android.internal.custom.hardware.LiveDisplayManager;
import android.provider.Settings;

public class PictureAdjustmentController extends LiveDisplayFeature {

    private static final String TAG = "LiveDisplay-PAC";

    private final LineageHardwareManager mHardware;
    private final boolean mUsePictureAdjustment;
    private final boolean mHasDisplayModes;

    private List<Range<Float>> mRanges = new ArrayList<Range<Float>>();

    public PictureAdjustmentController(Context context, Handler handler) {
        super(context, handler);
        mHardware = LineageHardwareManager.getInstance(context);
        mHasDisplayModes = mHardware.isSupported(LineageHardwareManager.FEATURE_DISPLAY_MODES);

        boolean usePA = mHardware.isSupported(LineageHardwareManager.FEATURE_PICTURE_ADJUSTMENT);
        if (usePA) {
            mRanges.addAll(mHardware.getPictureAdjustmentRanges());
            if (mRanges.size() < 4) {
                usePA = false;
            } else {
                for (Range<Float> range : mRanges) {
                    if (range.getLower() == 0.0f && range.getUpper() == 0.0f) {
                        usePA = false;
                        break;
                    }
                }
            }
        }
        if (!usePA) {
            mRanges.clear();
        }
        mUsePictureAdjustment = usePA;
    }

    @Override
    public void onStart() {
        if (!mUsePictureAdjustment) {
            return;
        }

        registerSettings(
                Settings.System.getUriFor(Settings.System.DISPLAY_PICTURE_ADJUSTMENT));
    }

    @Override
    protected void onSettingsChanged(Uri uri) {// nothing to do for mode switch
        updatePictureAdjustment();
    }

    @Override
    protected void onUpdate() {
        updatePictureAdjustment();
    }

    private void updatePictureAdjustment() {
        if (mUsePictureAdjustment && isScreenOn()) {
            final HSIC hsic = getPictureAdjustment();
            if (hsic != null) {
                if (!mHardware.setPictureAdjustment(hsic)) {
                    Slog.e(TAG, "Failed to set picture adjustment! " + hsic.toString());
                }
            }
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        if (mUsePictureAdjustment) {
            pw.println();
            pw.println("PictureAdjustmentController Configuration:");
            pw.println("  adjustment=" + getPictureAdjustment());
            pw.println("  hueRange=" + getHueRange());
            pw.println("  saturationRange=" + getSaturationRange());
            pw.println("  intensityRange=" + getIntensityRange());
            pw.println("  contrastRange=" + getContrastRange());
            pw.println("  saturationThresholdRange=" + getSaturationThresholdRange());
            pw.println("  defaultAdjustment=" + getDefaultPictureAdjustment());
        }
    }


    @Override
    public boolean getCapabilities(BitSet caps) {
        if (mUsePictureAdjustment) {
            caps.set(LiveDisplayManager.FEATURE_PICTURE_ADJUSTMENT);
        }
        return mUsePictureAdjustment;
    }

    Range<Float> getHueRange() {
        return mUsePictureAdjustment && mRanges.size() > 0
                ? mRanges.get(0) : Range.create(0.0f, 0.0f);
    }

    Range<Float> getSaturationRange() {
        return mUsePictureAdjustment && mRanges.size() > 1
                ? mRanges.get(1) : Range.create(0.0f, 0.0f);
    }

    Range<Float> getIntensityRange() {
        return mUsePictureAdjustment && mRanges.size() > 2
                ? mRanges.get(2) : Range.create(0.0f, 0.0f);
    }

    Range<Float> getContrastRange() {
        return mUsePictureAdjustment && mRanges.size() > 3 ?
                mRanges.get(3) : Range.create(0.0f, 0.0f);
    }

    Range<Float> getSaturationThresholdRange() {
        return mUsePictureAdjustment && mRanges.size() > 4 ?
                mRanges.get(4) : Range.create(0.0f, 0.0f);
    }

    HSIC getDefaultPictureAdjustment() {
        HSIC hsic = null;
        if (mUsePictureAdjustment) {
            hsic = mHardware.getDefaultPictureAdjustment();
        }
        if (hsic == null) {
            hsic = new HSIC(0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        }
        return hsic;
    }

    HSIC getPictureAdjustment() {
        HSIC hsic = null;
        if (mUsePictureAdjustment) {
            int modeID = 0;
            if (mHasDisplayModes) {
                DisplayMode mode = mHardware.getCurrentDisplayMode();
                if (mode != null) {
                    modeID = mode.id;
                }
            }
            hsic = getPAForMode(modeID);
        }
        if (hsic == null) {
            hsic = new HSIC(0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        }
        return hsic;
    }

    boolean setPictureAdjustment(HSIC hsic) {
        if (mUsePictureAdjustment && hsic != null) {
            int modeID = 0;
            if (mHasDisplayModes) {
                DisplayMode mode = mHardware.getCurrentDisplayMode();
                if (mode != null) {
                    modeID = mode.id;
                }
            }
            setPAForMode(modeID, hsic);
            return true;
        }
        return false;
    }

    // TODO: Expose mode-based settings to upper layers

    private HSIC getPAForMode(int mode) {
        final SparseArray<HSIC> prefs = unpackPreference();
        if (prefs.indexOfKey(mode) >= 0) {
            return prefs.get(mode);
        }
        return getDefaultPictureAdjustment();
    }

    private void setPAForMode(int mode, HSIC hsic) {
        final SparseArray<HSIC> prefs = unpackPreference();
        prefs.put(mode, hsic);
        packPreference(prefs);
    }

    private SparseArray<HSIC> unpackPreference() {
        final SparseArray<HSIC> ret = new SparseArray<HSIC>();

        String pref = getString(Settings.System.DISPLAY_PICTURE_ADJUSTMENT);
        if (pref != null) {
            String[] byMode = TextUtils.split(pref, ",");
            for (String mode : byMode) {
                String[] modePA = TextUtils.split(mode, ":");
                if (modePA.length == 2) {
                    ret.put(Integer.valueOf(modePA[0]), HSIC.unflattenFrom(modePA[1]));
                }
            }
        }
        return ret;
    }

    private void packPreference(final SparseArray<HSIC> modes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < modes.size(); i++) {
            int id = modes.keyAt(i);
            HSIC m = modes.get(id);
            if (i > 0) {
                sb.append(",");
            }
            sb.append(id).append(":").append(m.flatten());
        }
        putString(Settings.System.DISPLAY_PICTURE_ADJUSTMENT, sb.toString());
    }

}
