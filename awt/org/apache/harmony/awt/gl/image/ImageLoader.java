/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
/**
 * @author Oleg V. Khaschansky
 * @version $Revision$
 */
/*
 * Created on 18.01.2005
 */
package org.apache.harmony.awt.gl.image;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * This class provides functionality for simultaneous loading of
 * several images and running animation.
 */
public class ImageLoader extends Thread {
    // Contains ImageLoader objects
    // and queue of image sources waiting to be loaded
    static class ImageLoadersStorage {
        private static final int MAX_THREADS = 5;
        private static final int TIMEOUT = 4000;
        static ImageLoadersStorage instance;

        List<DecodingImageSource> queue = new LinkedList<DecodingImageSource>();
        List<Thread> loaders = new ArrayList<Thread>(MAX_THREADS);

        private int freeLoaders;

        private ImageLoadersStorage() {}

        static ImageLoadersStorage getStorage() {
            if (instance == null) {
                instance = new ImageLoadersStorage();
            }

            return instance;
        }
    }

    ImageLoader() {
        super();
        setDaemon(true);
    }

    /**
     * This method creates a new thread which is able to load an image
     * or run animation (if the number of existing loader threads does not
     * exceed the limit).
     */
    private static void createLoader() {
        final ImageLoadersStorage storage = ImageLoadersStorage.getStorage();

        synchronized(storage.loaders) {
            if (storage.loaders.size() < ImageLoadersStorage.MAX_THREADS) {
                AccessController.doPrivileged(
                        new PrivilegedAction<Void>() {
                            public Void run() {
                                ImageLoader loader = new ImageLoader();
                                storage.loaders.add(loader);
                                loader.start();
                                return null;
                            }
                        });
            }
        }
    }

    /**
     * Adds a new image source to the queue and starts a new loader
     * thread if required
     * @param imgSrc - image source
     */
    public static void addImageSource(DecodingImageSource imgSrc) {
        ImageLoadersStorage storage = ImageLoadersStorage.getStorage();
        synchronized(storage.queue) {
            if (!storage.queue.contains(imgSrc)) {
                storage.queue.add(imgSrc);
            }
            if (storage.freeLoaders == 0) {
                createLoader();
            }

            storage.queue.notify();
        }
    }

    /**
     * Waits for a new ImageSource until timout expires.
     * Loader thread will terminate after returning from this method
     * if timeout expired and image source was not picked up from the queue.
     * @return image source picked up from the queue or null if timeout expired
     */
    private static DecodingImageSource getWaitingImageSource() {
        ImageLoadersStorage storage = ImageLoadersStorage.getStorage();

        synchronized(storage.queue) {
            DecodingImageSource isrc = null;

            if (storage.queue.size() == 0) {
                try {
                    storage.freeLoaders++;
                    storage.queue.wait(ImageLoadersStorage.TIMEOUT);
                } catch (InterruptedException e) {
                    return null;
                } finally {
                    storage.freeLoaders--;
                }
            }

            if (storage.queue.size() > 0) {
                isrc = storage.queue.get(0);
                storage.queue.remove(0);
            }

            return isrc;
        }
    }

    /**
     * Entry point of the loader thread. Picks up image sources and
     * runs decoders for them while there are available image sources in the queue.
     * If there are no and timeout expires it terminates.
     */
    @Override
    public void run() {
        ImageLoadersStorage storage = ImageLoadersStorage.getStorage();

        try {
            while (storage.loaders.contains(this)) {
                Thread.interrupted(); // Reset the interrupted flag
                DecodingImageSource isrc = getWaitingImageSource();
                if (isrc != null) {
                    try {
                        isrc.load();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    break; // Don't wait if timeout expired - terminate loader
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            synchronized(storage.loaders) {
                storage.loaders.remove(Thread.currentThread());
            }
        }
    }

    /**
     * Removes current thread from loaders (so we are able
     * to create more loaders) and decreases its priority.
     */
    static void beginAnimation() {
        ImageLoadersStorage storage = ImageLoadersStorage.getStorage();
        Thread currThread = Thread.currentThread();

        synchronized(storage) {
            storage.loaders.remove(currThread);

            if (storage.freeLoaders < storage.queue.size()) {
                createLoader();
            }
        }

        currThread.setPriority(Thread.MIN_PRIORITY);
    }

    /**
     * Sends the current thread to wait for the new images to load
     * if there are free placeholders for loaders
     */
    static void endAnimation() {
        ImageLoadersStorage storage = ImageLoadersStorage.getStorage();
        Thread currThread = Thread.currentThread();

        synchronized(storage) {
            if (storage.loaders.size() < ImageLoadersStorage.MAX_THREADS &&
                    !storage.loaders.contains(currThread)
            ) {
                storage.loaders.add(currThread);
            }
        }

        currThread.setPriority(Thread.NORM_PRIORITY);
    }
}