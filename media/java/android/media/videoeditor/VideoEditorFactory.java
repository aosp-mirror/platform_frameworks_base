/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.media.videoeditor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;


/**
 * The VideoEditorFactory class must be used to instantiate VideoEditor objects
 * by creating a new project {@link #create(String)} or by loading an
 * existing project {@link #load(String)}.
 * {@hide}
 */
public class VideoEditorFactory {
    // VideoEditor implementation classes
    public static final String TEST_CLASS_IMPLEMENTATION
            = "android.media.videoeditor.VideoEditorTestImpl";
    public static final String DEFAULT_CLASS_IMPLEMENTATION
            = "android.media.videoeditor.VideoEditorImpl";

    /**
     * This is the factory method for creating a new VideoEditor instance.
     *
     * @param projectPath The path where all VideoEditor internal
     *            files are stored. When a project is deleted the application is
     *            responsible for deleting the path and its contents.
     * @param className The implementation class name
     *
     * @return The VideoEditor instance
     *
     * @throws IOException if path does not exist or if the path can
     *             not be accessed in read/write mode
     * @throws IllegalStateException if a previous VideoEditor instance has not
     *             been released
     * @throws ClassNotFoundException, NoSuchMethodException,
     *             InvocationTargetException, IllegalAccessException,
     *             InstantiationException if the implementation class cannot
     *             be instantiated.
     */
    public static VideoEditor create(String projectPath, String className) throws IOException,
            ClassNotFoundException, NoSuchMethodException, InvocationTargetException,
            IllegalAccessException, InstantiationException {
        // If the project path does not exist create it
        final File dir = new File(projectPath);
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                throw new FileNotFoundException("Cannot create project path: " + projectPath);
            }
        }

        final Class<?> cls = Class.forName(className);
        final Class<?> partypes[] = new Class[1];
        partypes[0] = String.class;
        final Constructor<?> ct = cls.getConstructor(partypes);
        final Object arglist[] = new Object[1];
        arglist[0] = projectPath;

        return (VideoEditor)ct.newInstance(arglist);
    }

    /**
     * This is the factory method for instantiating a VideoEditor from the
     * internal state previously saved with the
     * {@link VideoEditor#save(String)} method.
     *
     * @param projectPath The path where all VideoEditor internal files
     *            are stored. When a project is deleted the application is
     *            responsible for deleting the path and its contents.
     * @param className The implementation class name
     * @param generatePreview if set to true the
     *      {@link MediaEditor#generatePreview()} will be called internally to
     *      generate any needed transitions.
     *
     * @return The VideoEditor instance
     *
     * @throws IOException if path does not exist or if the path can
     *             not be accessed in read/write mode or if one of the resource
     *             media files cannot be retrieved
     * @throws IllegalStateException if a previous VideoEditor instance has not
     *             been released
     */
    public static VideoEditor load(String projectPath, String className, boolean generatePreview)
            throws IOException, ClassNotFoundException, NoSuchMethodException,
            InvocationTargetException, IllegalAccessException, InstantiationException {
        final Class<?> cls = Class.forName(className);
        final Class<?> partypes[] = new Class[1];
        partypes[0] = String.class;
        final Constructor<?> ct = cls.getConstructor(partypes);
        final Object arglist[] = new Object[1];
        arglist[0] = projectPath;

        final VideoEditor videoEditor = (VideoEditor)ct.newInstance(arglist);
        if (generatePreview) {
            videoEditor.generatePreview();
        }
        return videoEditor;
    }
}
