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

package com.android.wm.shell;

import com.android.wm.shell.hidedisplaycutout.HideDisplayCutout;
import com.android.wm.shell.onehanded.OneHanded;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.splitscreen.SplitScreen;

import java.io.PrintWriter;
import java.util.Optional;

/**
 * An entry point into the shell for dumping shell internal state.
 */
public class ShellDump {

    private final Optional<SplitScreen> mSplitScreenOptional;
    private final Optional<Pip> mPipOptional;
    private final Optional<OneHanded> mOneHandedOptional;
    private final Optional<HideDisplayCutout> mHideDisplayCutout;
    private final ShellTaskOrganizer mShellTaskOrganizer;

    public ShellDump(ShellTaskOrganizer shellTaskOrganizer,
            Optional<SplitScreen> splitScreenOptional,
            Optional<Pip> pipOptional,
            Optional<OneHanded> oneHandedOptional,
            Optional<HideDisplayCutout> hideDisplayCutout) {
        mShellTaskOrganizer = shellTaskOrganizer;
        mSplitScreenOptional = splitScreenOptional;
        mPipOptional = pipOptional;
        mOneHandedOptional = oneHandedOptional;
        mHideDisplayCutout = hideDisplayCutout;
    }

    public void dump(PrintWriter pw) {
        mShellTaskOrganizer.dump(pw, "");
        pw.println();
        pw.println();
        mPipOptional.ifPresent(pip -> pip.dump(pw));
        mSplitScreenOptional.ifPresent(splitScreen -> splitScreen.dump(pw));
        mOneHandedOptional.ifPresent(oneHanded -> oneHanded.dump(pw));
        mHideDisplayCutout.ifPresent(hideDisplayCutout -> hideDisplayCutout.dump(pw));
    }
}
