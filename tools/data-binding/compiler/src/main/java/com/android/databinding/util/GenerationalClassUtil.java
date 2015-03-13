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

package com.android.databinding.util;

import com.android.databinding.util.L;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

/**
 * A utility class that helps adding build specific objects to the jar file
 * and their extraction later on.
 */
public class GenerationalClassUtil {
    public static <T extends Serializable> List<T> loadObjects(ClassLoader classLoader, Filter filter) {
        final List<T> result = new ArrayList<T>();
        if (!(classLoader instanceof URLClassLoader)) {
            L.d("class loader is not url class loader (%s). skipping.", classLoader.getClass());
            return result;
        }
        final URLClassLoader urlClassLoader = (URLClassLoader) classLoader;
        for (URL url : urlClassLoader.getURLs()) {
            L.d("checking url %s for intermediate data", url);
            try {
                final File file = new File(url.toURI());
                if (!file.exists()) {
                    L.d("cannot load file for %s", url);
                    continue;
                }
                if (file.isDirectory()) {
                    // probably exported classes dir.
                    loadFromDirectory(filter, result, file);
                } else {
                    // assume it is a zip file
                    loadFomZipFile(filter, result, file);
                }
            } catch (IOException e) {
                L.d("cannot open zip file from %s", url);
            } catch (URISyntaxException e) {
                L.d("cannot open zip file from %s", url);
            }
        }
        return result;
    }

    private static <T extends Serializable> void loadFromDirectory(final Filter filter, List<T> result,
            File directory) {
        //noinspection unchecked
        Collection<File> files = FileUtils.listFiles(directory, new IOFileFilter() {
            @Override
            public boolean accept(File file) {
                return filter.accept(file.getName());
            }

            @Override
            public boolean accept(File dir, String name) {
                return filter.accept(name);
            }
        }, TrueFileFilter.INSTANCE);
        for (File file : files) {
            InputStream inputStream = null;
            try {
                inputStream = FileUtils.openInputStream(file);
                T item = fromInputStream(result, inputStream);
                L.d("loaded item %s from file", item);
                if (item != null) {
                    result.add(item);
                }
            } catch (IOException e) {
                L.e(e, "Could not merge in Bindables from %s", file.getAbsolutePath());
            } catch (ClassNotFoundException e) {
                L.e(e, "Could not read Binding properties intermediate file. %s", file.getAbsolutePath());
            } finally {
                IOUtils.closeQuietly(inputStream);
            }
        }
    }

    private static <T extends Serializable> void loadFomZipFile(Filter filter,
            List<T> result, File file) throws IOException {
        ZipFile zipFile = new ZipFile(file);
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!filter.accept(entry.getName())) {
                continue;
            }
            L.d("loading data from file %s", entry.getName());
            InputStream inputStream = null;
            try {
                inputStream = zipFile.getInputStream(entry);
                T item = fromInputStream(result, inputStream);
                L.d("loaded item %s from zip file", item);
                if (item != null) {
                    result.add(item);
                }
            } catch (IOException e) {
                L.e(e, "Could not merge in Bindables from %s", file.getAbsolutePath());
            } catch (ClassNotFoundException e) {
                L.e(e, "Could not read Binding properties intermediate file. %s", file.getAbsolutePath());
            } finally {
                IOUtils.closeQuietly(inputStream);
            }
        }
    }

    private static <T extends Serializable> T fromInputStream(List<T> result,
            InputStream inputStream) throws IOException, ClassNotFoundException {
        ObjectInputStream in = new ObjectInputStream(inputStream);
        return (T) in.readObject();

    }

    public static void writeIntermediateFile(ProcessingEnvironment processingEnv,
            String packageName, String fileName, Serializable object) {
        ObjectOutputStream oos = null;
        try {
            FileObject intermediate = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, packageName,
                    fileName);
            OutputStream ios = intermediate.openOutputStream();
            oos = new ObjectOutputStream(ios);
            oos.writeObject(object);
            oos.close();
            L.d("wrote intermediate bindable file %s %s", packageName, fileName);
        } catch (IOException e) {
            L.e(e, "Could not write to intermediate file: %s", fileName);
        } finally {
            IOUtils.closeQuietly(oos);
        }
    }


    public static interface Filter {
        public boolean accept(String entryName);
    }

    public static class ExtensionFilter implements Filter {
        private final String mExtension;
        public ExtensionFilter(String extension) {
            mExtension = extension;
        }

        @Override
        public boolean accept(String entryName) {
            return entryName.endsWith(mExtension);
        }
    }
}
