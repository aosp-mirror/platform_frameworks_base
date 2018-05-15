/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.mtp;

import android.media.MediaFile;
import android.os.FileObserver;
import android.os.storage.StorageVolume;
import android.util.Log;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * MtpStorageManager provides functionality for listing, tracking, and notifying MtpServer of
 * filesystem changes. As directories are listed, this class will cache the results,
 * and send events when objects are added/removed from cached directories.
 * {@hide}
 */
public class MtpStorageManager {
    private static final String TAG = MtpStorageManager.class.getSimpleName();
    public static boolean sDebug = false;

    // Inotify flags not provided by FileObserver
    private static final int IN_ONLYDIR = 0x01000000;
    private static final int IN_Q_OVERFLOW = 0x00004000;
    private static final int IN_IGNORED    = 0x00008000;
    private static final int IN_ISDIR = 0x40000000;

    private class MtpObjectObserver extends FileObserver {
        MtpObject mObject;

        MtpObjectObserver(MtpObject object) {
            super(object.getPath().toString(),
                    MOVED_FROM | MOVED_TO | DELETE | CREATE | IN_ONLYDIR);
            mObject = object;
        }

        @Override
        public void onEvent(int event, String path) {
            synchronized (MtpStorageManager.this) {
                if ((event & IN_Q_OVERFLOW) != 0) {
                    // We are out of space in the inotify queue.
                    Log.e(TAG, "Received Inotify overflow event!");
                }
                MtpObject obj = mObject.getChild(path);
                if ((event & MOVED_TO) != 0 || (event & CREATE) != 0) {
                    if (sDebug)
                        Log.i(TAG, "Got inotify added event for " + path + " " + event);
                    handleAddedObject(mObject, path, (event & IN_ISDIR) != 0);
                } else if ((event & MOVED_FROM) != 0 || (event & DELETE) != 0) {
                    if (obj == null) {
                        Log.w(TAG, "Object was null in event " + path);
                        return;
                    }
                    if (sDebug)
                        Log.i(TAG, "Got inotify removed event for " + path + " " + event);
                    handleRemovedObject(obj);
                } else if ((event & IN_IGNORED) != 0) {
                    if (sDebug)
                        Log.i(TAG, "inotify for " + mObject.getPath() + " deleted");
                    if (mObject.mObserver != null)
                        mObject.mObserver.stopWatching();
                    mObject.mObserver = null;
                } else {
                    Log.w(TAG, "Got unrecognized event " + path + " " + event);
                }
            }
        }

        @Override
        public void finalize() {
            // If the server shuts down and starts up again, the new server's observers can be
            // invalidated by the finalize() calls of the previous server's observers.
            // Hence, disable the automatic stopWatching() call in FileObserver#finalize, and
            // always call stopWatching() manually whenever an observer should be shut down.
        }
    }

    /**
     * Describes how the object is being acted on, to determine how events are handled.
     */
    private enum MtpObjectState {
        NORMAL,
        FROZEN,             // Object is going to be modified in this session.
        FROZEN_ADDED,       // Object was frozen, and has been added.
        FROZEN_REMOVED,     // Object was frozen, and has been removed.
        FROZEN_ONESHOT_ADD, // Object is waiting for single add event before being unfrozen.
        FROZEN_ONESHOT_DEL, // Object is waiting for single remove event and will then be removed.
    }

    /**
     * Describes the current operation being done on an object. Determines whether observers are
     * created on new folders.
     */
    private enum MtpOperation {
        NONE,     // Any new folders not added as part of the session are immediately observed.
        ADD,      // New folders added as part of the session are immediately observed.
        RENAME,   // Renamed or moved folders are not immediately observed.
        COPY,     // Copied folders are immediately observed iff the original was.
        DELETE,   // Exists for debugging purposes only.
    }

    /** MtpObject represents either a file or directory in an associated storage. **/
    public static class MtpObject {
        // null for root objects
        private MtpObject mParent;

        private String mName;
        private int mId;
        private MtpObjectState mState;
        private MtpOperation mOp;

        private boolean mVisited;
        private boolean mIsDir;

        // null if not a directory
        private HashMap<String, MtpObject> mChildren;
        // null if not both a directory and visited
        private FileObserver mObserver;

        MtpObject(String name, int id, MtpObject parent, boolean isDir) {
            mId = id;
            mName = name;
            mParent = parent;
            mObserver = null;
            mVisited = false;
            mState = MtpObjectState.NORMAL;
            mIsDir = isDir;
            mOp = MtpOperation.NONE;

            mChildren = mIsDir ? new HashMap<>() : null;
        }

        /** Public methods for getting object info **/

        public String getName() {
            return mName;
        }

        public int getId() {
            return mId;
        }

        public boolean isDir() {
            return mIsDir;
        }

        public int getFormat() {
            return mIsDir ? MtpConstants.FORMAT_ASSOCIATION : MediaFile.getFormatCode(mName, null);
        }

        public int getStorageId() {
            return getRoot().getId();
        }

        public long getModifiedTime() {
            return getPath().toFile().lastModified() / 1000;
        }

        public MtpObject getParent() {
            return mParent;
        }

        public MtpObject getRoot() {
            return isRoot() ? this : mParent.getRoot();
        }

        public long getSize() {
            return mIsDir ? 0 : getPath().toFile().length();
        }

        public Path getPath() {
            return isRoot() ? Paths.get(mName) : mParent.getPath().resolve(mName);
        }

        public boolean isRoot() {
            return mParent == null;
        }

        /** For MtpStorageManager only **/

        private void setName(String name) {
            mName = name;
        }

        private void setId(int id) {
            mId = id;
        }

        private boolean isVisited() {
            return mVisited;
        }

        private void setParent(MtpObject parent) {
            mParent = parent;
        }

        private void setDir(boolean dir) {
            if (dir != mIsDir) {
                mIsDir = dir;
                mChildren = mIsDir ? new HashMap<>() : null;
            }
        }

        private void setVisited(boolean visited) {
            mVisited = visited;
        }

        private MtpObjectState getState() {
            return mState;
        }

        private void setState(MtpObjectState state) {
            mState = state;
            if (mState == MtpObjectState.NORMAL)
                mOp = MtpOperation.NONE;
        }

        private MtpOperation getOperation() {
            return mOp;
        }

        private void setOperation(MtpOperation op) {
            mOp = op;
        }

        private FileObserver getObserver() {
            return mObserver;
        }

        private void setObserver(FileObserver observer) {
            mObserver = observer;
        }

        private void addChild(MtpObject child) {
            mChildren.put(child.getName(), child);
        }

        private MtpObject getChild(String name) {
            return mChildren.get(name);
        }

        private Collection<MtpObject> getChildren() {
            return mChildren.values();
        }

        private boolean exists() {
            return getPath().toFile().exists();
        }

        private MtpObject copy(boolean recursive) {
            MtpObject copy = new MtpObject(mName, mId, mParent, mIsDir);
            copy.mIsDir = mIsDir;
            copy.mVisited = mVisited;
            copy.mState = mState;
            copy.mChildren = mIsDir ? new HashMap<>() : null;
            if (recursive && mIsDir) {
                for (MtpObject child : mChildren.values()) {
                    MtpObject childCopy = child.copy(true);
                    childCopy.setParent(copy);
                    copy.addChild(childCopy);
                }
            }
            return copy;
        }
    }

    /**
     * A class that processes generated filesystem events.
     */
    public static abstract class MtpNotifier {
        /**
         * Called when an object is added.
         */
        public abstract void sendObjectAdded(int id);

        /**
         * Called when an object is deleted.
         */
        public abstract void sendObjectRemoved(int id);
    }

    private MtpNotifier mMtpNotifier;

    // A cache of MtpObjects. The objects in the cache are keyed by object id.
    // The root object of each storage isn't in this map since they all have ObjectId 0.
    // Instead, they can be found in mRoots keyed by storageId.
    private HashMap<Integer, MtpObject> mObjects;

    // A cache of the root MtpObject for each storage, keyed by storage id.
    private HashMap<Integer, MtpObject> mRoots;

    // Object and Storage ids are allocated incrementally and not to be reused.
    private int mNextObjectId;
    private int mNextStorageId;

    // Special subdirectories. When set, only return objects rooted in these directories, and do
    // not allow them to be modified.
    private Set<String> mSubdirectories;

    private volatile boolean mCheckConsistency;
    private Thread mConsistencyThread;

    public MtpStorageManager(MtpNotifier notifier, Set<String> subdirectories) {
        mMtpNotifier = notifier;
        mSubdirectories = subdirectories;
        mObjects = new HashMap<>();
        mRoots = new HashMap<>();
        mNextObjectId = 1;
        mNextStorageId = 1;

        mCheckConsistency = false; // Set to true to turn on automatic consistency checking
        mConsistencyThread = new Thread(() -> {
            while (mCheckConsistency) {
                try {
                    Thread.sleep(15 * 1000);
                } catch (InterruptedException e) {
                    return;
                }
                if (MtpStorageManager.this.checkConsistency()) {
                    Log.v(TAG, "Cache is consistent");
                } else {
                    Log.w(TAG, "Cache is not consistent");
                }
            }
        });
        if (mCheckConsistency)
            mConsistencyThread.start();
    }

    /**
     * Clean up resources used by the storage manager.
     */
    public synchronized void close() {
        Stream<MtpObject> objs = Stream.concat(mRoots.values().stream(),
                mObjects.values().stream());

        Iterator<MtpObject> iter = objs.iterator();
        while (iter.hasNext()) {
            // Close all FileObservers.
            MtpObject obj = iter.next();
            if (obj.getObserver() != null) {
                obj.getObserver().stopWatching();
                obj.setObserver(null);
            }
        }

        // Shut down the consistency checking thread
        if (mCheckConsistency) {
            mCheckConsistency = false;
            mConsistencyThread.interrupt();
            try {
                mConsistencyThread.join();
            } catch (InterruptedException e) {
                // ignore
            }
        }
    }

    /**
     * Sets the special subdirectories, which are the subdirectories of root storage that queries
     * are restricted to. Must be done before any root storages are accessed.
     * @param subDirs Subdirectories to set, or null to reset.
     */
    public synchronized void setSubdirectories(Set<String> subDirs) {
        mSubdirectories = subDirs;
    }

    /**
     * Allocates an MTP storage id for the given volume and add it to current roots.
     * @param volume Storage to add.
     * @return the associated MtpStorage
     */
    public synchronized MtpStorage addMtpStorage(StorageVolume volume) {
        int storageId = ((getNextStorageId() & 0x0000FFFF) << 16) + 1;
        MtpStorage storage = new MtpStorage(volume, storageId);
        MtpObject root = new MtpObject(storage.getPath(), storageId, null, true);
        mRoots.put(storageId, root);
        return storage;
    }

    /**
     * Removes the given storage and all associated items from the cache.
     * @param storage Storage to remove.
     */
    public synchronized void removeMtpStorage(MtpStorage storage) {
        removeObjectFromCache(getStorageRoot(storage.getStorageId()), true, true);
    }

    /**
     * Checks if the given object can be renamed, moved, or deleted.
     * If there are special subdirectories, they cannot be modified.
     * @param obj Object to check.
     * @return Whether object can be modified.
     */
    private synchronized boolean isSpecialSubDir(MtpObject obj) {
        return obj.getParent().isRoot() && mSubdirectories != null
                && !mSubdirectories.contains(obj.getName());
    }

    /**
     * Get the object with the specified path. Visit any necessary directories on the way.
     * @param path Full path of the object to find.
     * @return The desired object, or null if it cannot be found.
     */
    public synchronized MtpObject getByPath(String path) {
        MtpObject obj = null;
        for (MtpObject root : mRoots.values()) {
            if (path.startsWith(root.getName())) {
                obj = root;
                path = path.substring(root.getName().length());
            }
        }
        for (String name : path.split("/")) {
            if (obj == null || !obj.isDir())
                return null;
            if ("".equals(name))
                continue;
            if (!obj.isVisited())
                getChildren(obj);
            obj = obj.getChild(name);
        }
        return obj;
    }

    /**
     * Get the object with specified id.
     * @param id Id of object. must not be 0 or 0xFFFFFFFF
     * @return Object, or null if error.
     */
    public synchronized MtpObject getObject(int id) {
        if (id == 0 || id == 0xFFFFFFFF) {
            Log.w(TAG, "Can't get root storages with getObject()");
            return null;
        }
        if (!mObjects.containsKey(id)) {
            Log.w(TAG, "Id " + id + " doesn't exist");
            return null;
        }
        return mObjects.get(id);
    }

    /**
     * Get the storage with specified id.
     * @param id Storage id.
     * @return Object that is the root of the storage, or null if error.
     */
    public MtpObject getStorageRoot(int id) {
        if (!mRoots.containsKey(id)) {
            Log.w(TAG, "StorageId " + id + " doesn't exist");
            return null;
        }
        return mRoots.get(id);
    }

    private int getNextObjectId() {
        int ret = mNextObjectId;
        // Treat the id as unsigned int
        mNextObjectId = (int) ((long) mNextObjectId + 1);
        return ret;
    }

    private int getNextStorageId() {
        return mNextStorageId++;
    }

    /**
     * Get all objects matching the given parent, format, and storage
     * @param parent object id of the parent. 0 for all objects, 0xFFFFFFFF for all object in root
     * @param format format of returned objects. 0 for any format
     * @param storageId storage id to look in. 0xFFFFFFFF for all storages
     * @return A stream of matched objects, or null if error
     */
    public synchronized Stream<MtpObject> getObjects(int parent, int format, int storageId) {
        boolean recursive = parent == 0;
        if (parent == 0xFFFFFFFF)
            parent = 0;
        if (storageId == 0xFFFFFFFF) {
            // query all stores
            if (parent == 0) {
                // Get the objects of this format and parent in each store.
                ArrayList<Stream<MtpObject>> streamList = new ArrayList<>();
                for (MtpObject root : mRoots.values()) {
                    streamList.add(getObjects(root, format, recursive));
                }
                return Stream.of(streamList).flatMap(Collection::stream).reduce(Stream::concat)
                        .orElseGet(Stream::empty);
            }
        }
        MtpObject obj = parent == 0 ? getStorageRoot(storageId) : getObject(parent);
        if (obj == null)
            return null;
        return getObjects(obj, format, recursive);
    }

    private synchronized Stream<MtpObject> getObjects(MtpObject parent, int format, boolean rec) {
        Collection<MtpObject> children = getChildren(parent);
        if (children == null)
            return null;
        Stream<MtpObject> ret = Stream.of(children).flatMap(Collection::stream);

        if (format != 0) {
            ret = ret.filter(o -> o.getFormat() == format);
        }
        if (rec) {
            // Get all objects recursively.
            ArrayList<Stream<MtpObject>> streamList = new ArrayList<>();
            streamList.add(ret);
            for (MtpObject o : children) {
                if (o.isDir())
                    streamList.add(getObjects(o, format, true));
            }
            ret = Stream.of(streamList).filter(Objects::nonNull).flatMap(Collection::stream)
                    .reduce(Stream::concat).orElseGet(Stream::empty);
        }
        return ret;
    }

    /**
     * Return the children of the given object. If the object hasn't been visited yet, add
     * its children to the cache and start observing it.
     * @param object the parent object
     * @return The collection of child objects or null if error
     */
    private synchronized Collection<MtpObject> getChildren(MtpObject object) {
        if (object == null || !object.isDir()) {
            Log.w(TAG, "Can't find children of " + (object == null ? "null" : object.getId()));
            return null;
        }
        if (!object.isVisited()) {
            Path dir = object.getPath();
            /*
             * If a file is added after the observer starts watching the directory, but before
             * the contents are listed, it will generate an event that will get processed
             * after this synchronized function returns. We handle this by ignoring object
             * added events if an object at that path already exists.
             */
            if (object.getObserver() != null)
                Log.e(TAG, "Observer is not null!");
            object.setObserver(new MtpObjectObserver(object));
            object.getObserver().startWatching();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path file : stream) {
                    addObjectToCache(object, file.getFileName().toString(),
                            file.toFile().isDirectory());
                }
            } catch (IOException | DirectoryIteratorException e) {
                Log.e(TAG, e.toString());
                object.getObserver().stopWatching();
                object.setObserver(null);
                return null;
            }
            object.setVisited(true);
        }
        return object.getChildren();
    }

    /**
     * Create a new object from the given path and add it to the cache.
     * @param parent The parent object
     * @param newName Path of the new object
     * @return the new object if success, else null
     */
    private synchronized MtpObject addObjectToCache(MtpObject parent, String newName,
            boolean isDir) {
        if (!parent.isRoot() && getObject(parent.getId()) != parent)
            // parent object has been removed
            return null;
        if (parent.getChild(newName) != null) {
            // Object already exists
            return null;
        }
        if (mSubdirectories != null && parent.isRoot() && !mSubdirectories.contains(newName)) {
            // Not one of the restricted subdirectories.
            return null;
        }

        MtpObject obj = new MtpObject(newName, getNextObjectId(), parent, isDir);
        mObjects.put(obj.getId(), obj);
        parent.addChild(obj);
        return obj;
    }

    /**
     * Remove the given path from the cache.
     * @param removed The removed object
     * @param removeGlobal Whether to remove the object from the global id map
     * @param recursive Whether to also remove its children recursively.
     * @return true if successfully removed
     */
    private synchronized boolean removeObjectFromCache(MtpObject removed, boolean removeGlobal,
            boolean recursive) {
        boolean ret = removed.isRoot()
                || removed.getParent().mChildren.remove(removed.getName(), removed);
        if (!ret && sDebug)
            Log.w(TAG, "Failed to remove from parent " + removed.getPath());
        if (removed.isRoot()) {
            ret = mRoots.remove(removed.getId(), removed) && ret;
        } else if (removeGlobal) {
            ret = mObjects.remove(removed.getId(), removed) && ret;
        }
        if (!ret && sDebug)
            Log.w(TAG, "Failed to remove from global cache " + removed.getPath());
        if (removed.getObserver() != null) {
            removed.getObserver().stopWatching();
            removed.setObserver(null);
        }
        if (removed.isDir() && recursive) {
            // Remove all descendants from cache recursively
            Collection<MtpObject> children = new ArrayList<>(removed.getChildren());
            for (MtpObject child : children) {
                ret = removeObjectFromCache(child, removeGlobal, true) && ret;
            }
        }
        return ret;
    }

    private synchronized void handleAddedObject(MtpObject parent, String path, boolean isDir) {
        MtpOperation op = MtpOperation.NONE;
        MtpObject obj = parent.getChild(path);
        if (obj != null) {
            MtpObjectState state = obj.getState();
            op = obj.getOperation();
            if (obj.isDir() != isDir && state != MtpObjectState.FROZEN_REMOVED)
                Log.d(TAG, "Inconsistent directory info! " + obj.getPath());
            obj.setDir(isDir);
            switch (state) {
                case FROZEN:
                case FROZEN_REMOVED:
                    obj.setState(MtpObjectState.FROZEN_ADDED);
                    break;
                case FROZEN_ONESHOT_ADD:
                    obj.setState(MtpObjectState.NORMAL);
                    break;
                case NORMAL:
                case FROZEN_ADDED:
                    // This can happen when handling listed object in a new directory.
                    return;
                default:
                    Log.w(TAG, "Unexpected state in add " + path + " " + state);
            }
            if (sDebug)
                Log.i(TAG, state + " transitioned to " + obj.getState() + " in op " + op);
        } else {
            obj = MtpStorageManager.this.addObjectToCache(parent, path, isDir);
            if (obj != null) {
                MtpStorageManager.this.mMtpNotifier.sendObjectAdded(obj.getId());
            } else {
                if (sDebug)
                    Log.w(TAG, "object " + path + " already exists");
                return;
            }
        }
        if (isDir) {
            // If this was added as part of a rename do not visit or send events.
            if (op == MtpOperation.RENAME)
                return;

            // If it was part of a copy operation, then only add observer if it was visited before.
            if (op == MtpOperation.COPY && !obj.isVisited())
                return;

            if (obj.getObserver() != null) {
                Log.e(TAG, "Observer is not null!");
                return;
            }
            obj.setObserver(new MtpObjectObserver(obj));
            obj.getObserver().startWatching();
            obj.setVisited(true);

            // It's possible that objects were added to a watched directory before the watch can be
            // created, so manually handle those.
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(obj.getPath())) {
                for (Path file : stream) {
                    if (sDebug)
                        Log.i(TAG, "Manually handling event for " + file.getFileName().toString());
                    handleAddedObject(obj, file.getFileName().toString(),
                            file.toFile().isDirectory());
                }
            } catch (IOException | DirectoryIteratorException e) {
                Log.e(TAG, e.toString());
                obj.getObserver().stopWatching();
                obj.setObserver(null);
            }
        }
    }

    private synchronized void handleRemovedObject(MtpObject obj) {
        MtpObjectState state = obj.getState();
        MtpOperation op = obj.getOperation();
        switch (state) {
            case FROZEN_ADDED:
                obj.setState(MtpObjectState.FROZEN_REMOVED);
                break;
            case FROZEN_ONESHOT_DEL:
                removeObjectFromCache(obj, op != MtpOperation.RENAME, false);
                break;
            case FROZEN:
                obj.setState(MtpObjectState.FROZEN_REMOVED);
                break;
            case NORMAL:
                if (MtpStorageManager.this.removeObjectFromCache(obj, true, true))
                    MtpStorageManager.this.mMtpNotifier.sendObjectRemoved(obj.getId());
                break;
            default:
                // This shouldn't happen; states correspond to objects that don't exist
                Log.e(TAG, "Got unexpected object remove for " + obj.getName());
        }
        if (sDebug)
            Log.i(TAG, state + " transitioned to " + obj.getState() + " in op " + op);
    }

    /**
     * Block the caller until all events currently in the event queue have been
     * read and processed. Used for testing purposes.
     */
    public void flushEvents() {
        try {
            // TODO make this smarter
            Thread.sleep(500);
        } catch (InterruptedException e) {

        }
    }

    /**
     * Dumps a representation of the cache to log.
     */
    public synchronized void dump() {
        for (int key : mObjects.keySet()) {
            MtpObject obj = mObjects.get(key);
            Log.i(TAG, key + " | " + (obj.getParent() == null ? obj.getParent().getId() : "null")
                    + " | " + obj.getName() + " | " + (obj.isDir() ? "dir" : "obj")
                    + " | " + (obj.isVisited() ? "v" : "nv") + " | " + obj.getState());
        }
    }

    /**
     * Checks consistency of the cache. This checks whether all objects have correct links
     * to their parent, and whether directories are missing or have extraneous objects.
     * @return true iff cache is consistent
     */
    public synchronized boolean checkConsistency() {
        Stream<MtpObject> objs = Stream.concat(mRoots.values().stream(),
                mObjects.values().stream());
        Iterator<MtpObject> iter = objs.iterator();
        boolean ret = true;
        while (iter.hasNext()) {
            MtpObject obj = iter.next();
            if (!obj.exists()) {
                Log.w(TAG, "Object doesn't exist " + obj.getPath() + " " + obj.getId());
                ret = false;
            }
            if (obj.getState() != MtpObjectState.NORMAL) {
                Log.w(TAG, "Object " + obj.getPath() + " in state " + obj.getState());
                ret = false;
            }
            if (obj.getOperation() != MtpOperation.NONE) {
                Log.w(TAG, "Object " + obj.getPath() + " in operation " + obj.getOperation());
                ret = false;
            }
            if (!obj.isRoot() && mObjects.get(obj.getId()) != obj) {
                Log.w(TAG, "Object " + obj.getPath() + " is not in map correctly");
                ret = false;
            }
            if (obj.getParent() != null) {
                if (obj.getParent().isRoot() && obj.getParent()
                        != mRoots.get(obj.getParent().getId())) {
                    Log.w(TAG, "Root parent is not in root mapping " + obj.getPath());
                    ret = false;
                }
                if (!obj.getParent().isRoot() && obj.getParent()
                        != mObjects.get(obj.getParent().getId())) {
                    Log.w(TAG, "Parent is not in object mapping " + obj.getPath());
                    ret = false;
                }
                if (obj.getParent().getChild(obj.getName()) != obj) {
                    Log.w(TAG, "Child does not exist in parent " + obj.getPath());
                    ret = false;
                }
            }
            if (obj.isDir()) {
                if (obj.isVisited() == (obj.getObserver() == null)) {
                    Log.w(TAG, obj.getPath() + " is " + (obj.isVisited() ? "" : "not ")
                            + " visited but observer is " + obj.getObserver());
                    ret = false;
                }
                if (!obj.isVisited() && obj.getChildren().size() > 0) {
                    Log.w(TAG, obj.getPath() + " is not visited but has children");
                    ret = false;
                }
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(obj.getPath())) {
                    Set<String> files = new HashSet<>();
                    for (Path file : stream) {
                        if (obj.isVisited() &&
                                obj.getChild(file.getFileName().toString()) == null &&
                                (mSubdirectories == null || !obj.isRoot() ||
                                        mSubdirectories.contains(file.getFileName().toString()))) {
                            Log.w(TAG, "File exists in fs but not in children " + file);
                            ret = false;
                        }
                        files.add(file.toString());
                    }
                    for (MtpObject child : obj.getChildren()) {
                        if (!files.contains(child.getPath().toString())) {
                            Log.w(TAG, "File in children doesn't exist in fs " + child.getPath());
                            ret = false;
                        }
                        if (child != mObjects.get(child.getId())) {
                            Log.w(TAG, "Child is not in object map " + child.getPath());
                            ret = false;
                        }
                    }
                } catch (IOException | DirectoryIteratorException e) {
                    Log.w(TAG, e.toString());
                    ret = false;
                }
            }
        }
        return ret;
    }

    /**
     * Informs MtpStorageManager that an object with the given path is about to be added.
     * @param parent The parent object of the object to be added.
     * @param name Filename of object to add.
     * @return Object id of the added object, or -1 if it cannot be added.
     */
    public synchronized int beginSendObject(MtpObject parent, String name, int format) {
        if (sDebug)
            Log.v(TAG, "beginSendObject " + name);
        if (!parent.isDir())
            return -1;
        if (parent.isRoot() && mSubdirectories != null && !mSubdirectories.contains(name))
            return -1;
        getChildren(parent); // Ensure parent is visited
        MtpObject obj  = addObjectToCache(parent, name, format == MtpConstants.FORMAT_ASSOCIATION);
        if (obj == null)
            return -1;
        obj.setState(MtpObjectState.FROZEN);
        obj.setOperation(MtpOperation.ADD);
        return obj.getId();
    }

    /**
     * Clean up the object state after a sendObject operation.
     * @param obj The object, returned from beginAddObject().
     * @param succeeded Whether the file was successfully created.
     * @return Whether cache state was successfully cleaned up.
     */
    public synchronized boolean endSendObject(MtpObject obj, boolean succeeded) {
        if (sDebug)
            Log.v(TAG, "endSendObject " + succeeded);
        return generalEndAddObject(obj, succeeded, true);
    }

    /**
     * Informs MtpStorageManager that the given object is about to be renamed.
     * If this returns true, it must be followed with an endRenameObject()
     * @param obj Object to be renamed.
     * @param newName New name of the object.
     * @return Whether renaming is allowed.
     */
    public synchronized boolean beginRenameObject(MtpObject obj, String newName) {
        if (sDebug)
            Log.v(TAG, "beginRenameObject " + obj.getName() + " " + newName);
        if (obj.isRoot())
            return false;
        if (isSpecialSubDir(obj))
            return false;
        if (obj.getParent().getChild(newName) != null)
            // Object already exists in parent with that name.
            return false;

        MtpObject oldObj = obj.copy(false);
        obj.setName(newName);
        obj.getParent().addChild(obj);
        oldObj.getParent().addChild(oldObj);
        return generalBeginRenameObject(oldObj, obj);
    }

    /**
     * Cleans up cache state after a rename operation and sends any events that were missed.
     * @param obj The object being renamed, the same one that was passed in beginRenameObject().
     * @param oldName The previous name of the object.
     * @param success Whether the rename operation succeeded.
     * @return Whether state was successfully cleaned up.
     */
    public synchronized boolean endRenameObject(MtpObject obj, String oldName, boolean success) {
        if (sDebug)
            Log.v(TAG, "endRenameObject " + success);
        MtpObject parent = obj.getParent();
        MtpObject oldObj = parent.getChild(oldName);
        if (!success) {
            // If the rename failed, we want oldObj to be the original and obj to be the dummy.
            // Switch the objects, except for their name and state.
            MtpObject temp = oldObj;
            MtpObjectState oldState = oldObj.getState();
            temp.setName(obj.getName());
            temp.setState(obj.getState());
            oldObj = obj;
            oldObj.setName(oldName);
            oldObj.setState(oldState);
            obj = temp;
            parent.addChild(obj);
            parent.addChild(oldObj);
        }
        return generalEndRenameObject(oldObj, obj, success);
    }

    /**
     * Informs MtpStorageManager that the given object is about to be deleted by the initiator,
     * so don't send an event.
     * @param obj Object to be deleted.
     * @return Whether cache deletion is allowed.
     */
    public synchronized boolean beginRemoveObject(MtpObject obj) {
        if (sDebug)
            Log.v(TAG, "beginRemoveObject " + obj.getName());
        return !obj.isRoot() && !isSpecialSubDir(obj)
                && generalBeginRemoveObject(obj, MtpOperation.DELETE);
    }

    /**
     * Clean up cache state after a delete operation and send any events that were missed.
     * @param obj Object to be deleted, same one passed in beginRemoveObject().
     * @param success Whether operation was completed successfully.
     * @return Whether cache state is correct.
     */
    public synchronized boolean endRemoveObject(MtpObject obj, boolean success) {
        if (sDebug)
            Log.v(TAG, "endRemoveObject " + success);
        boolean ret = true;
        if (obj.isDir()) {
            for (MtpObject child : new ArrayList<>(obj.getChildren()))
                if (child.getOperation() == MtpOperation.DELETE)
                    ret = endRemoveObject(child, success) && ret;
        }
        return generalEndRemoveObject(obj, success, true) && ret;
    }

    /**
     * Informs MtpStorageManager that the given object is about to be moved to a new parent.
     * @param obj Object to be moved.
     * @param newParent The new parent object.
     * @return Whether the move is allowed.
     */
    public synchronized boolean beginMoveObject(MtpObject obj, MtpObject newParent) {
        if (sDebug)
            Log.v(TAG, "beginMoveObject " + newParent.getPath());
        if (obj.isRoot())
            return false;
        if (isSpecialSubDir(obj))
            return false;
        getChildren(newParent); // Ensure parent is visited
        if (newParent.getChild(obj.getName()) != null)
            // Object already exists in parent with that name.
            return false;
        if (obj.getStorageId() != newParent.getStorageId()) {
            /*
             * The move is occurring across storages. The observers will not remain functional
             * after the move, and the move will not be atomic. We have to copy the file tree
             * to the destination and recreate the observers once copy is complete.
             */
            MtpObject newObj = obj.copy(true);
            newObj.setParent(newParent);
            newParent.addChild(newObj);
            return generalBeginRemoveObject(obj, MtpOperation.RENAME)
                    && generalBeginCopyObject(newObj, false);
        }
        // Move obj to new parent, create a dummy object in the old parent.
        MtpObject oldObj = obj.copy(false);
        obj.setParent(newParent);
        oldObj.getParent().addChild(oldObj);
        obj.getParent().addChild(obj);
        return generalBeginRenameObject(oldObj, obj);
    }

    /**
     * Clean up cache state after a move operation and send any events that were missed.
     * @param oldParent The old parent object.
     * @param newParent The new parent object.
     * @param name The name of the object being moved.
     * @param success Whether operation was completed successfully.
     * @return Whether cache state is correct.
     */
    public synchronized boolean endMoveObject(MtpObject oldParent, MtpObject newParent, String name,
            boolean success) {
        if (sDebug)
            Log.v(TAG, "endMoveObject " + success);
        MtpObject oldObj = oldParent.getChild(name);
        MtpObject newObj = newParent.getChild(name);
        if (oldObj == null || newObj == null)
            return false;
        if (oldParent.getStorageId() != newObj.getStorageId()) {
            boolean ret = endRemoveObject(oldObj, success);
            return generalEndCopyObject(newObj, success, true) && ret;
        }
        if (!success) {
            // If the rename failed, we want oldObj to be the original and obj to be the dummy.
            // Switch the objects, except for their parent and state.
            MtpObject temp = oldObj;
            MtpObjectState oldState = oldObj.getState();
            temp.setParent(newObj.getParent());
            temp.setState(newObj.getState());
            oldObj = newObj;
            oldObj.setParent(oldParent);
            oldObj.setState(oldState);
            newObj = temp;
            newObj.getParent().addChild(newObj);
            oldParent.addChild(oldObj);
        }
        return generalEndRenameObject(oldObj, newObj, success);
    }

    /**
     * Informs MtpStorageManager that the given object is about to be copied recursively.
     * @param object Object to be copied
     * @param newParent New parent for the object.
     * @return The object id for the new copy, or -1 if error.
     */
    public synchronized int beginCopyObject(MtpObject object, MtpObject newParent) {
        if (sDebug)
            Log.v(TAG, "beginCopyObject " + object.getName() + " to " + newParent.getPath());
        String name = object.getName();
        if (!newParent.isDir())
            return -1;
        if (newParent.isRoot() && mSubdirectories != null && !mSubdirectories.contains(name))
            return -1;
        getChildren(newParent); // Ensure parent is visited
        if (newParent.getChild(name) != null)
            return -1;
        MtpObject newObj  = object.copy(object.isDir());
        newParent.addChild(newObj);
        newObj.setParent(newParent);
        if (!generalBeginCopyObject(newObj, true))
            return -1;
        return newObj.getId();
    }

    /**
     * Cleans up cache state after a copy operation.
     * @param object Object that was copied.
     * @param success Whether the operation was successful.
     * @return Whether cache state is consistent.
     */
    public synchronized boolean endCopyObject(MtpObject object, boolean success) {
        if (sDebug)
            Log.v(TAG, "endCopyObject " + object.getName() + " " + success);
        return generalEndCopyObject(object, success, false);
    }

    private synchronized boolean generalEndAddObject(MtpObject obj, boolean succeeded,
            boolean removeGlobal) {
        switch (obj.getState()) {
            case FROZEN:
                // Object was never created.
                if (succeeded) {
                    // The operation was successful so the event must still be in the queue.
                    obj.setState(MtpObjectState.FROZEN_ONESHOT_ADD);
                } else {
                    // The operation failed and never created the file.
                    if (!removeObjectFromCache(obj, removeGlobal, false)) {
                        return false;
                    }
                }
                break;
            case FROZEN_ADDED:
                obj.setState(MtpObjectState.NORMAL);
                if (!succeeded) {
                    MtpObject parent = obj.getParent();
                    // The operation failed but some other process created the file. Send an event.
                    if (!removeObjectFromCache(obj, removeGlobal, false))
                        return false;
                    handleAddedObject(parent, obj.getName(), obj.isDir());
                }
                // else: The operation successfully created the object.
                break;
            case FROZEN_REMOVED:
                if (!removeObjectFromCache(obj, removeGlobal, false))
                    return false;
                if (succeeded) {
                    // Some other process deleted the object. Send an event.
                    mMtpNotifier.sendObjectRemoved(obj.getId());
                }
                // else: Mtp deleted the object as part of cleanup. Don't send an event.
                break;
            default:
                return false;
        }
        return true;
    }

    private synchronized boolean generalEndRemoveObject(MtpObject obj, boolean success,
            boolean removeGlobal) {
        switch (obj.getState()) {
            case FROZEN:
                if (success) {
                    // Object was deleted successfully, and event is still in the queue.
                    obj.setState(MtpObjectState.FROZEN_ONESHOT_DEL);
                } else {
                    // Object was not deleted.
                    obj.setState(MtpObjectState.NORMAL);
                }
                break;
            case FROZEN_ADDED:
                // Object was deleted, and then readded.
                obj.setState(MtpObjectState.NORMAL);
                if (success) {
                    // Some other process readded the object.
                    MtpObject parent = obj.getParent();
                    if (!removeObjectFromCache(obj, removeGlobal, false))
                        return false;
                    handleAddedObject(parent, obj.getName(), obj.isDir());
                }
                // else : Object still exists after failure.
                break;
            case FROZEN_REMOVED:
                if (!removeObjectFromCache(obj, removeGlobal, false))
                    return false;
                if (!success) {
                    // Some other process deleted the object.
                    mMtpNotifier.sendObjectRemoved(obj.getId());
                }
                // else : This process deleted the object as part of the operation.
                break;
            default:
                return false;
        }
        return true;
    }

    private synchronized boolean generalBeginRenameObject(MtpObject fromObj, MtpObject toObj) {
        fromObj.setState(MtpObjectState.FROZEN);
        toObj.setState(MtpObjectState.FROZEN);
        fromObj.setOperation(MtpOperation.RENAME);
        toObj.setOperation(MtpOperation.RENAME);
        return true;
    }

    private synchronized boolean generalEndRenameObject(MtpObject fromObj, MtpObject toObj,
            boolean success) {
        boolean ret = generalEndRemoveObject(fromObj, success, !success);
        return generalEndAddObject(toObj, success, success) && ret;
    }

    private synchronized boolean generalBeginRemoveObject(MtpObject obj, MtpOperation op) {
        obj.setState(MtpObjectState.FROZEN);
        obj.setOperation(op);
        if (obj.isDir()) {
            for (MtpObject child : obj.getChildren())
                generalBeginRemoveObject(child, op);
        }
        return true;
    }

    private synchronized boolean generalBeginCopyObject(MtpObject obj, boolean newId) {
        obj.setState(MtpObjectState.FROZEN);
        obj.setOperation(MtpOperation.COPY);
        if (newId) {
            obj.setId(getNextObjectId());
            mObjects.put(obj.getId(), obj);
        }
        if (obj.isDir())
            for (MtpObject child : obj.getChildren())
                if (!generalBeginCopyObject(child, newId))
                    return false;
        return true;
    }

    private synchronized boolean generalEndCopyObject(MtpObject obj, boolean success, boolean addGlobal) {
        if (success && addGlobal)
            mObjects.put(obj.getId(), obj);
        boolean ret = true;
        if (obj.isDir()) {
            for (MtpObject child : new ArrayList<>(obj.getChildren())) {
                if (child.getOperation() == MtpOperation.COPY)
                    ret = generalEndCopyObject(child, success, addGlobal) && ret;
            }
        }
        ret = generalEndAddObject(obj, success, success || !addGlobal) && ret;
        return ret;
    }
}
