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

package com.android.commands.ime;

import com.android.internal.view.IInputMethodManager;

import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.PrintStreamPrinter;
import android.util.Printer;
import android.view.inputmethod.InputMethodInfo;

import java.util.List;

public final class Ime {
    IInputMethodManager mImm;
    
    private String[] mArgs;
    private int mNextArg;
    private String mCurArgData;
    
    private static final String IMM_NOT_RUNNING_ERR = 
        "Error: Could not access the Input Method Manager.  Is the system running?";
    
    public static void main(String[] args) {
        new Ime().run(args);
    }
    
    public void run(String[] args) {
        if (args.length < 1) {
            showUsage();
            return;
        }

        mImm = IInputMethodManager.Stub.asInterface(ServiceManager.getService("input_method"));
        if (mImm == null) {
            System.err.println(IMM_NOT_RUNNING_ERR);
            return;
        }

        mArgs = args;
        String op = args[0];
        mNextArg = 1;
        
        if ("list".equals(op)) {
            runList();
            return;
        }
        
        if ("enable".equals(op)) {
            runSetEnabled(true);
            return;
        }
        
        if ("disable".equals(op)) {
            runSetEnabled(false);
            return;
        }
        
        if ("set".equals(op)) {
            runSet();
            return;
        }
        
        if (op != null) {
            System.err.println("Error: unknown command '" + op + "'");
        }
        showUsage();
    }
    
    /**
     * Execute the list sub-command.
     */
    private void runList() {
        String opt;
        boolean all = false;
        boolean brief = false;
        while ((opt=nextOption()) != null) {
            if (opt.equals("-a")) {
                all = true;
            } else if (opt.equals("-s")) {
                brief = true;
            } else {
                System.err.println("Error: Unknown option: " + opt);
                showUsage();
                return;
            }
        }

        
        List<InputMethodInfo> methods;
        if (!all) {
            try {
                methods = mImm.getEnabledInputMethodList();
            } catch (RemoteException e) {
                System.err.println(e.toString());
                System.err.println(IMM_NOT_RUNNING_ERR);
                return;
            }
        } else {
            try {
                methods = mImm.getInputMethodList();
            } catch (RemoteException e) {
                System.err.println(e.toString());
                System.err.println(IMM_NOT_RUNNING_ERR);
                return;
            }
        }
        
        if (methods != null) {
            Printer pr = new PrintStreamPrinter(System.out);
            for (int i=0; i<methods.size(); i++) {
                InputMethodInfo imi = methods.get(i);
                if (brief) {
                    System.out.println(imi.getId());
                } else {
                    System.out.println(imi.getId() + ":");
                    imi.dump(pr, "  ");
                }
            }
        }
    }
    
    private void runSetEnabled(boolean state) {
        String id = nextArg();
        if (id == null) {
            System.err.println("Error: no input method ID specified");
            showUsage();
            return;
        }
        
        try {
            boolean res = mImm.setInputMethodEnabled(id, state);
            if (state) {
                System.out.println("Input method " + id + ": "
                        + (res ? "already enabled" : "now enabled"));
            } else {
                System.out.println("Input method " + id + ": "
                        + (res ? "now disabled" : "already disabled"));
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(IMM_NOT_RUNNING_ERR);
            return;
        }
    }
    
    private void runSet() {
        String id = nextArg();
        if (id == null) {
            System.err.println("Error: no input method ID specified");
            showUsage();
            return;
        }
        
        try {
            mImm.setInputMethod(null, id);
            System.out.println("Input method " + id + " selected");
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(IMM_NOT_RUNNING_ERR);
            return;
        }
    }
    
    private String nextOption() {
        if (mNextArg >= mArgs.length) {
            return null;
        }
        String arg = mArgs[mNextArg];
        if (!arg.startsWith("-")) {
            return null;
        }
        mNextArg++;
        if (arg.equals("--")) {
            return null;
        }
        if (arg.length() > 1 && arg.charAt(1) != '-') {
            if (arg.length() > 2) {
                mCurArgData = arg.substring(2);
                return arg.substring(0, 2);
            } else {
                mCurArgData = null;
                return arg;
            }
        }
        mCurArgData = null;
        return arg;
    }

    private String nextOptionData() {
        if (mCurArgData != null) {
            return mCurArgData;
        }
        if (mNextArg >= mArgs.length) {
            return null;
        }
        String data = mArgs[mNextArg];
        mNextArg++;
        return data;
    }

    private String nextArg() {
        if (mNextArg >= mArgs.length) {
            return null;
        }
        String arg = mArgs[mNextArg];
        mNextArg++;
        return arg;
    }

    private static void showUsage() {
        System.err.println("usage: ime list [-a] [-s]");
        System.err.println("       ime enable ID");
        System.err.println("       ime disable ID");
        System.err.println("       ime set ID");
        System.err.println("");
        System.err.println("The list command prints all enabled input methods.  Use");
        System.err.println("the -a option to see all input methods.  Use");
        System.err.println("the -s option to see only a single summary line of each.");
        System.err.println("");
        System.err.println("The enable command allows the given input method ID to be used.");
        System.err.println("");
        System.err.println("The disable command disallows the given input method ID from use.");
        System.err.println("");
        System.err.println("The set command switches to the given input method ID.");
    }
}
