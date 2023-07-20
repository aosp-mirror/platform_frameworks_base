/*
 * Copyright (C) 2022 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.oplus.os;

import com.oplus.os.WaveformEffect;

/** @hide */
interface ILinearmotorVibratorService {
    void vibrate(in WaveformEffect effect);
}
