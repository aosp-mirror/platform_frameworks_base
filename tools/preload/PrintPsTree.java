/*
 * Copyright (C) 2008 The Android Open Source Project
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

import java.io.IOException;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.io.BufferedInputStream;

/**
 * Prints raw information in CSV format.
 */
public class PrintPsTree {

    public static void main(String[] args)
            throws IOException, ClassNotFoundException {
        if (args.length != 1) {
            System.err.println("Usage: PrintCsv [compiled log file]");
            System.exit(0);
        }

        FileInputStream fin = new FileInputStream(args[0]);
        ObjectInputStream oin = new ObjectInputStream(
                new BufferedInputStream(fin));

        Root root = (Root) oin.readObject();

        for (Proc proc : root.processes.values()) {
            if (proc.parent == null) {
                proc.print();                                
            }
        }
    }
}
