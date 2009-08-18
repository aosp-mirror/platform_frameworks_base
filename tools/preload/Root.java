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

import java.io.Serializable;
import java.io.IOException;
import java.io.Writer;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.io.BufferedInputStream;
import java.io.ObjectOutputStream;
import java.io.BufferedOutputStream;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.Arrays;
import java.nio.charset.Charset;

/**
 * Root of our data model.
 */
public class Root implements Serializable {

    private static final long serialVersionUID = 0;

    /** pid -> Proc */
    final Map<Integer, Proc> processes = new HashMap<Integer, Proc>();

    /** Class name -> LoadedClass */
    final Map<String, LoadedClass> loadedClasses
            = new HashMap<String, LoadedClass>();

    MemoryUsage baseline = MemoryUsage.baseline();

    /**
     * Records class loads and initializations.
     */
    void indexClassOperation(Record record) {
        Proc process = processes.get(record.pid);

        // Ignore dexopt output. It loads applications classes through the
        // system class loader and messes us up.
        if (record.processName.equals("dexopt")) {
            return;
        }

        String name = record.className;
        LoadedClass loadedClass = loadedClasses.get(name);
        Operation o = null;

        switch (record.type) {
            case START_LOAD:
            case START_INIT:
                if (loadedClass == null) {
                    loadedClass = new LoadedClass(
                            name, record.classLoader == 0);
                    if (loadedClass.systemClass) {
                        // Only measure memory for classes in the boot
                        // classpath.
                        loadedClass.measureMemoryUsage();
                    }
                    loadedClasses.put(name, loadedClass);
                }
                break;

            case END_LOAD:
            case END_INIT:
                o = process.endOperation(record.tid, record.className,
                        loadedClass, record.time);
                if (o == null) {
                    return;
                }
        }

        switch (record.type) {
            case START_LOAD:
                process.startOperation(record.tid, loadedClass, record.time,
                        Operation.Type.LOAD);
                break;

            case START_INIT:
                process.startOperation(record.tid, loadedClass, record.time,
                        Operation.Type.INIT);
                break;

            case END_LOAD:
                loadedClass.loads.add(o);
                break;

            case END_INIT:
                loadedClass.initializations.add(o);
                break;
        }
    }

    /**
     * Indexes information about the process from the given record.
     */
    void indexProcess(Record record) {
        Proc proc = processes.get(record.pid);

        if (proc == null) {
            // Create a new process object.
            Proc parent = processes.get(record.ppid);
            proc = new Proc(parent, record.pid);
            processes.put(proc.id, proc);
            if (parent != null) {
                parent.children.add(proc);
            }
        }

        proc.setName(record.processName);
    }

    /**
     * Writes this graph to a file.
     */
    void toFile(String fileName) throws IOException {
        FileOutputStream out = new FileOutputStream(fileName);
        ObjectOutputStream oout = new ObjectOutputStream(
                new BufferedOutputStream(out));

        System.err.println("Writing object model...");

        oout.writeObject(this);

        oout.close();

        System.err.println("Done!");
    }

    /**
     * Reads Root from a file.
     */
    static Root fromFile(String fileName)
            throws IOException, ClassNotFoundException {
        FileInputStream fin = new FileInputStream(fileName);
        ObjectInputStream oin = new ObjectInputStream(
                new BufferedInputStream(fin));

        Root root = (Root) oin.readObject();

        oin.close();

        return root;
    }
}
