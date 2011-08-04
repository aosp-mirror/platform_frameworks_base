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
#ifndef __SESSIONMAP_H__
#define __SESSIONMAP_H__

#include <utils/KeyedVector.h>
#include <utils/threads.h>

namespace android {

/**
 * A thread safe wrapper template class for session handlings for Drm Engines. It wraps a
 * pointer type over KeyedVector. It keeps pointer as data in the vector and free up memory
 * allocated pointer can be of any type of structure/class meant for keeping session data.
 * so session object here means pointer to the session data.
 */
template <typename TValue>
class SessionMap {

public:
    SessionMap() {}

    virtual ~SessionMap() {
        Mutex::Autolock lock(mLock);
        destroyMap();
    }

    /**
     * Adds a new value in the session map table. It expects memory to be allocated already
     * for the session object
     *
     * @param key - key or Session ID
     * @param value - session object to add
     *
     * @return boolean result of adding value. returns false if key is already exist.
     */
    bool addValue(int key, TValue value) {
        Mutex::Autolock lock(mLock);
        if (!isCreatedInternal(key)) {
            map.add(key, value);
            return true;
        }
        return false;
    }

    /**
     * returns the session object by the key
     *
     * @param key - key or Session ID
     *
     * @return session object as per the key
     */
    TValue getValue(int key) {
        Mutex::Autolock lock(mLock);
        return getValueInternal(key);
    }

    /**
     * returns the number of objects in the session map table
     *
     * @return count of number of session objects.
     */
    int getSize() {
        Mutex::Autolock lock(mLock);
        return map.size();
    }

    /**
     * returns the session object by the index in the session map table
     *
     * @param index - index of the value required
     *
     * @return session object as per the index
     */
    TValue getValueAt(unsigned int index) {
        TValue value = NULL;
        Mutex::Autolock lock(mLock);

        if (map.size() > index) {
            value = map.valueAt(index);
        }
        return value;
    }

    /**
     * deletes the object from session map. It also frees up memory for the session object.
     *
     * @param key - key of the value to be deleted
     *
     */
    void removeValue(int key) {
        Mutex::Autolock lock(mLock);
        deleteValue(getValueInternal(key));
        map.removeItem(key);
    }

    /**
     * decides if session is already created.
     *
     * @param key - key of the value for the session
     *
     * @return boolean result of whether session is created
     */
    bool isCreated(int key) {
        Mutex::Autolock lock(mLock);
        return isCreatedInternal(key);
    }

    SessionMap<TValue> & operator=(const SessionMap<TValue> & objectCopy) {
        Mutex::Autolock lock(mLock);

        destroyMap();
        map = objectCopy.map;
        return *this;
    }

private:
    KeyedVector<int, TValue> map;
    Mutex mLock;

   /**
    * free up the memory for the session object.
    * Make sure if any reference to the session object anywhere, otherwise it will be a
    * dangle pointer after this call.
    *
    * @param value - session object to free
    *
    */
    void deleteValue(TValue value) {
        delete value;
    }

   /**
    * free up the memory for the entire map.
    * free up any resources in the sessions before calling this funtion.
    *
    */
    void destroyMap() {
        int size = map.size();

        for (int i = 0; i < size; i++) {
            deleteValue(map.valueAt(i));
        }
        map.clear();
    }

   /**
    * decides if session is already created.
    *
    * @param key - key of the value for the session
    *
    * @return boolean result of whether session is created
    */
    bool isCreatedInternal(int key) {
        return(0 <= map.indexOfKey(key));
    }

   /**
    * returns the session object by the key
    *
    * @param key - key or Session ID
    *
    * @return session object as per the key
    */
    TValue getValueInternal(int key) {
        TValue value = NULL;
        if (isCreatedInternal(key)) {
            value = (TValue) map.valueFor(key);
        }
        return value;
    }
};

};

#endif /* __SESSIONMAP_H__ */
