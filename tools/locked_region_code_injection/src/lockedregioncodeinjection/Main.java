/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package lockedregioncodeinjection;

import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

public class Main {
    public static void main(String[] args) throws IOException {
        String inJar = null;
        String outJar = null;

        String legacyTargets = null;
        String legacyPreMethods = null;
        String legacyPostMethods = null;
        for (int i = 0; i < args.length; i++) {
            if ("-i".equals(args[i].trim())) {
                i++;
                inJar = args[i].trim();
            } else if ("-o".equals(args[i].trim())) {
                i++;
                outJar = args[i].trim();
            } else if ("--targets".equals(args[i].trim())) {
                i++;
                legacyTargets = args[i].trim();
            } else if ("--pre".equals(args[i].trim())) {
                i++;
                legacyPreMethods = args[i].trim();
            } else if ("--post".equals(args[i].trim())) {
                i++;
                legacyPostMethods = args[i].trim();
            }

        }

        // TODO(acleung): Better help message than asserts.
        assert inJar != null;
        assert outJar != null;
        assert legacyTargets == null || (legacyPreMethods != null && legacyPostMethods != null);

        ZipFile zipSrc = new ZipFile(inJar);
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outJar));
        List<LockTarget> targets = null;
        if (legacyTargets != null) {
            targets = Utils.getTargetsFromLegacyJackConfig(legacyTargets, legacyPreMethods,
                    legacyPostMethods);
        } else {
            targets = Collections.emptyList();
        }

        Enumeration<? extends ZipEntry> srcEntries = zipSrc.entries();
        while (srcEntries.hasMoreElements()) {
            ZipEntry entry = srcEntries.nextElement();
            ZipEntry newEntry = new ZipEntry(entry.getName());
            zos.putNextEntry(newEntry);
            BufferedInputStream bis = new BufferedInputStream(zipSrc.getInputStream(entry));

            if (entry.getName().endsWith(".class")) {
                convert(bis, zos, targets);
            } else {
                while (bis.available() > 0) {
                    zos.write(bis.read());
                }
                zos.closeEntry();
                bis.close();
            }
        }
        zos.finish();
        zos.close();
        zipSrc.close();
    }

    private static void convert(InputStream in, OutputStream out, List<LockTarget> targets)
            throws IOException {
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = new ClassWriter(0);
        LockFindingClassVisitor cv = new LockFindingClassVisitor(targets, cw);
        cr.accept(cv, 0);
        byte[] data = cw.toByteArray();
        out.write(data);
    }
}
