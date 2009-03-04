/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.os;

import java.io.FileDescriptor;

/**
 * @hide
 * Tools for executing commands.  Not for public consumption.
 */

public class Exec
{
    /**
     * @param cmd The command to execute
     * @param arg0 The first argument to the command, may be null
     * @param arg1 the second argument to the command, may be null
     * @return the file descriptor of the started process.
     * 
     */
    public static FileDescriptor createSubprocess(
        String cmd, String arg0, String arg1) {
        return createSubprocess(cmd, arg0, arg1, null);
    }
    
    /**
     * @param cmd The command to execute
     * @param arg0 The first argument to the command, may be null
     * @param arg1 the second argument to the command, may be null
     * @param processId A one-element array to which the process ID of the
     * started process will be written.
     * @return the file descriptor of the started process.
     * 
     */
     public static native FileDescriptor createSubprocess(
        String cmd, String arg0, String arg1, int[] processId);
    
     public static native void setPtyWindowSize(FileDescriptor fd,
       int row, int col, int xpixel, int ypixel);
    /**
     * Causes the calling thread to wait for the process associated with the
     * receiver to finish executing.
     * 
     * @return The exit value of the Process being waited on
     * 
     */
    public static native int waitFor(int processId);
}

