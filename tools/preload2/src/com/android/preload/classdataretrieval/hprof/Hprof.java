/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.preload.classdataretrieval.hprof;

import com.android.ddmlib.Client;
import com.android.ddmlib.ClientData;
import com.android.ddmlib.ClientData.IHprofDumpHandler;
import com.android.preload.classdataretrieval.ClassDataRetriever;
import com.android.preload.ui.NullProgressMonitor;
import com.android.tools.perflib.captures.MemoryMappedFileBuffer;
import com.android.tools.perflib.heap.ClassObj;
import com.android.tools.perflib.heap.Queries;
import com.android.tools.perflib.heap.Snapshot;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class Hprof implements ClassDataRetriever {

    private static GeneralHprofDumpHandler hprofHandler;

    public static void init() {
        synchronized(Hprof.class) {
            if (hprofHandler == null) {
                ClientData.setHprofDumpHandler(hprofHandler = new GeneralHprofDumpHandler());
            }
        }
    }

    public static File doHprof(Client client, int timeout) {
        GetHprof gh = new GetHprof(client, timeout);
        return gh.get();
    }

    /**
     * Return a map of class names to class-loader names derived from the hprof dump.
     *
     * @param hprofLocalFile
     */
    public static Map<String, String> analyzeHprof(File hprofLocalFile) throws Exception {
        Snapshot snapshot = Snapshot.createSnapshot(new MemoryMappedFileBuffer(hprofLocalFile));

        Map<String, Set<ClassObj>> classes = Queries.classes(snapshot, null);
        Map<String, String> retValue = new HashMap<String, String>();
        for (Map.Entry<String, Set<ClassObj>> e : classes.entrySet()) {
            for (ClassObj c : e.getValue()) {
                String cl = c.getClassLoader() == null ? null : c.getClassLoader().toString();
                String cName = c.getClassName();
                int aDepth = 0;
                while (cName.endsWith("[]")) {
                    cName = cName.substring(0, cName.length()-2);
                    aDepth++;
                }
                String newName = transformPrimitiveClass(cName);
                if (aDepth > 0) {
                    // Need to use kind-a descriptor syntax. If it was transformed, it is primitive.
                    if (newName.equals(cName)) {
                        newName = "L" + newName + ";";
                    }
                    for (int i = 0; i < aDepth; i++) {
                        newName = "[" + newName;
                    }
                }
                retValue.put(newName, cl);
            }
        }

        // Free up memory.
        snapshot.dispose();

        return retValue;
    }

    private static Map<String, String> primitiveMapping;

    static {
        primitiveMapping = new HashMap<>();
        primitiveMapping.put("boolean", "Z");
        primitiveMapping.put("byte", "B");
        primitiveMapping.put("char", "C");
        primitiveMapping.put("double", "D");
        primitiveMapping.put("float", "F");
        primitiveMapping.put("int", "I");
        primitiveMapping.put("long", "J");
        primitiveMapping.put("short", "S");
        primitiveMapping.put("void", "V");
    }

    private static String transformPrimitiveClass(String name) {
        String rep = primitiveMapping.get(name);
        if (rep != null) {
            return rep;
        }
        return name;
    }

    private static class GetHprof implements IHprofDumpHandler {

        private File target;
        private long timeout;
        private Client client;

        public GetHprof(Client client, long timeout) {
            this.client = client;
            this.timeout = timeout;
        }

        public File get() {
            synchronized (this) {
                hprofHandler.addHandler(this);
                client.dumpHprof();
                if (target == null) {
                    try {
                        wait(timeout);
                    } catch (Exception e) {
                        System.out.println(e);
                    }
                }
            }

            hprofHandler.removeHandler(this);
            return target;
        }

        private void wakeUp() {
            synchronized (this) {
                notifyAll();
            }
        }

        @Override
        public void onEndFailure(Client arg0, String arg1) {
            System.out.println("GetHprof.onEndFailure");
            if (client == arg0) {
                wakeUp();
            }
        }

        private static File createTargetFile() {
            try {
                return File.createTempFile("ddms", ".hprof");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void onSuccess(String arg0, Client arg1) {
            System.out.println("GetHprof.onSuccess");
            if (client == arg1) {
                try {
                    target = createTargetFile();
                    arg1.getDevice().getSyncService().pullFile(arg0,
                            target.getAbsoluteFile().toString(), new NullProgressMonitor());
                } catch (Exception e) {
                    e.printStackTrace();
                    target = null;
                }
                wakeUp();
            }
        }

        @Override
        public void onSuccess(byte[] arg0, Client arg1) {
            System.out.println("GetHprof.onSuccess");
            if (client == arg1) {
                try {
                    target = createTargetFile();
                    BufferedOutputStream out =
                            new BufferedOutputStream(new FileOutputStream(target));
                    out.write(arg0);
                    out.close();
                } catch (Exception e) {
                    e.printStackTrace();
                    target = null;
                }
                wakeUp();
            }
        }
    }

    private int timeout;

    public Hprof(int timeout) {
        this.timeout = timeout;
    }

    @Override
    public Map<String, String> getClassData(Client client) {
        File hprofLocalFile = Hprof.doHprof(client, timeout);
        if (hprofLocalFile == null) {
            throw new RuntimeException("Failed getting dump...");
        }
        System.out.println("Dump file is " + hprofLocalFile);

        try {
            return analyzeHprof(hprofLocalFile);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
