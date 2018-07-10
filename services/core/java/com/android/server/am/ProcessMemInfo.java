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

package com.android.server.am;

public class ProcessMemInfo {
    final String name;
    final int pid;
    final int oomAdj;
    final int procState;
    final String adjType;
    final String adjReason;
    long pss;
    long swapPss;
    long memtrack;

    public ProcessMemInfo(String _name, int _pid, int _oomAdj, int _procState,
            String _adjType, String _adjReason) {
        name = _name;
        pid = _pid;
        oomAdj = _oomAdj;
        procState = _procState;
        adjType = _adjType;
        adjReason = _adjReason;
    }
}
