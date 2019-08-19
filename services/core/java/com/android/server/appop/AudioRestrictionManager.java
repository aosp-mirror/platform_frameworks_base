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

package com.android.server.appop;

import android.app.AppOpsManager;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraDevice.CAMERA_AUDIO_RESTRICTION;
import android.media.AudioAttributes;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import java.io.PrintWriter;

/**
 * AudioRestrictionManager host all audio restriction related logic and states for AppOpsService.
 */
public class AudioRestrictionManager {
    static final String TAG = "AudioRestriction";

    // Audio restrictions coming from Zen mode API
    final SparseArray<SparseArray<Restriction>> mZenModeAudioRestrictions = new SparseArray<>();
    // Audio restrictions coming from Camera2 API
    @CAMERA_AUDIO_RESTRICTION int mCameraAudioRestriction = CameraDevice.AUDIO_RESTRICTION_NONE;
    // Predefined <code, usages> camera audio restriction settings
    static final SparseArray<SparseBooleanArray> CAMERA_AUDIO_RESTRICTIONS;

    static {
        SparseBooleanArray audioMutedUsages = new SparseBooleanArray();
        SparseBooleanArray vibrationMutedUsages = new SparseBooleanArray();
        for (int usage : AudioAttributes.SDK_USAGES) {
            final int suppressionBehavior = AudioAttributes.SUPPRESSIBLE_USAGES.get(usage);
            if (suppressionBehavior == AudioAttributes.SUPPRESSIBLE_NOTIFICATION ||
                    suppressionBehavior == AudioAttributes.SUPPRESSIBLE_CALL ||
                    suppressionBehavior == AudioAttributes.SUPPRESSIBLE_ALARM) {
                audioMutedUsages.append(usage, true);
                vibrationMutedUsages.append(usage, true);
            } else if (suppressionBehavior != AudioAttributes.SUPPRESSIBLE_MEDIA &&
                    suppressionBehavior != AudioAttributes.SUPPRESSIBLE_SYSTEM &&
                    suppressionBehavior != AudioAttributes.SUPPRESSIBLE_NEVER) {
                Slog.e(TAG, "Unknown audio suppression behavior" + suppressionBehavior);
            }
        }
        CAMERA_AUDIO_RESTRICTIONS = new SparseArray<>();
        CAMERA_AUDIO_RESTRICTIONS.append(AppOpsManager.OP_PLAY_AUDIO, audioMutedUsages);
        CAMERA_AUDIO_RESTRICTIONS.append(AppOpsManager.OP_VIBRATE, vibrationMutedUsages);
    }

    private static final class Restriction {
        private static final ArraySet<String> NO_EXCEPTIONS = new ArraySet<String>();
        int mode;
        ArraySet<String> exceptionPackages = NO_EXCEPTIONS;
    }

    public int checkAudioOperation(int code, int usage, int uid, String packageName) {
        synchronized (this) {
            // Check for camera audio restrictions
            if (mCameraAudioRestriction != CameraDevice.AUDIO_RESTRICTION_NONE) {
                if (code == AppOpsManager.OP_VIBRATE || (code == AppOpsManager.OP_PLAY_AUDIO &&
                        mCameraAudioRestriction ==
                                CameraDevice.AUDIO_RESTRICTION_VIBRATION_SOUND)) {
                    final SparseBooleanArray mutedUsages = CAMERA_AUDIO_RESTRICTIONS.get(code);
                    if (mutedUsages != null) {
                        if (mutedUsages.get(usage)) {
                            return AppOpsManager.MODE_IGNORED;
                        }
                    }
                }
            }

            final int mode = checkZenModeRestrictionLocked(code, usage, uid, packageName);
            if (mode != AppOpsManager.MODE_ALLOWED) {
                return mode;
            }
        }
        return AppOpsManager.MODE_ALLOWED;
    }

    private int checkZenModeRestrictionLocked(int code, int usage, int uid, String packageName) {
        final SparseArray<Restriction> usageRestrictions = mZenModeAudioRestrictions.get(code);
        if (usageRestrictions != null) {
            final Restriction r = usageRestrictions.get(usage);
            if (r != null && !r.exceptionPackages.contains(packageName)) {
                return r.mode;
            }
        }
        return AppOpsManager.MODE_ALLOWED;
    }

    public void setZenModeAudioRestriction(int code, int usage, int uid, int mode,
            String[] exceptionPackages) {
        synchronized (this) {
            SparseArray<Restriction> usageRestrictions = mZenModeAudioRestrictions.get(code);
            if (usageRestrictions == null) {
                usageRestrictions = new SparseArray<Restriction>();
                mZenModeAudioRestrictions.put(code, usageRestrictions);
            }
            usageRestrictions.remove(usage);
            if (mode != AppOpsManager.MODE_ALLOWED) {
                final Restriction r = new Restriction();
                r.mode = mode;
                if (exceptionPackages != null) {
                    final int N = exceptionPackages.length;
                    r.exceptionPackages = new ArraySet<String>(N);
                    for (int i = 0; i < N; i++) {
                        final String pkg = exceptionPackages[i];
                        if (pkg != null) {
                            r.exceptionPackages.add(pkg.trim());
                        }
                    }
                }
                usageRestrictions.put(usage, r);
            }
        }
    }

    public void setCameraAudioRestriction(@CAMERA_AUDIO_RESTRICTION int mode) {
        synchronized (this) {
            mCameraAudioRestriction = mode;
        }
    }

    public boolean hasActiveRestrictions() {
        boolean hasActiveRestrictions = false;
        synchronized (this) {
            hasActiveRestrictions = (mZenModeAudioRestrictions.size() > 0 ||
                mCameraAudioRestriction != CameraDevice.AUDIO_RESTRICTION_NONE);
        }
        return hasActiveRestrictions;
    }

    // return: needSep used by AppOpsService#dump
    public boolean dump(PrintWriter pw) {
        boolean printedHeader = false;
        boolean needSep = hasActiveRestrictions();

        synchronized (this) {
            for (int o = 0; o < mZenModeAudioRestrictions.size(); o++) {
                final String op = AppOpsManager.opToName(mZenModeAudioRestrictions.keyAt(o));
                final SparseArray<Restriction> restrictions = mZenModeAudioRestrictions.valueAt(o);
                for (int i = 0; i < restrictions.size(); i++) {
                    if (!printedHeader){
                        pw.println("  Zen Mode Audio Restrictions:");
                        printedHeader = true;

                    }
                    final int usage = restrictions.keyAt(i);
                    pw.print("    "); pw.print(op);
                    pw.print(" usage="); pw.print(AudioAttributes.usageToString(usage));
                    Restriction r = restrictions.valueAt(i);
                    pw.print(": mode="); pw.println(AppOpsManager.modeToName(r.mode));
                    if (!r.exceptionPackages.isEmpty()) {
                        pw.println("      Exceptions:");
                        for (int j = 0; j < r.exceptionPackages.size(); j++) {
                            pw.print("        "); pw.println(r.exceptionPackages.valueAt(j));
                        }
                    }
                }
            }
            if (mCameraAudioRestriction != CameraDevice.AUDIO_RESTRICTION_NONE) {
                pw.println("  Camera Audio Restriction Mode: " +
                        cameraRestrictionModeToName(mCameraAudioRestriction));
            }
        }
        return needSep;
    }

    private static String cameraRestrictionModeToName(@CAMERA_AUDIO_RESTRICTION int mode) {
        switch (mode) {
            case CameraDevice.AUDIO_RESTRICTION_NONE:
                return "None";
            case CameraDevice.AUDIO_RESTRICTION_VIBRATION:
                return "MuteVibration";
            case CameraDevice.AUDIO_RESTRICTION_VIBRATION_SOUND:
                return "MuteVibrationAndSound";
            default:
                return "Unknown";
        }
    }

}
