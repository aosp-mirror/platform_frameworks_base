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

namespace android {

/**
 * A wrapper template class for handling DRM Engine sessions.
 */
template <typename NODE>
class SessionMap {

public:
    KeyedVector<int, NODE> map;

    SessionMap() {}

    virtual ~SessionMap() {
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
bool addValue(int key, NODE value) {
    bool result = false;

    if (!isCreated(key)) {
        map.add(key, value);
        result = true;
    }

    return result;
}


/**
 * returns the session object by the key
 *
 * @param key - key or Session ID
 *
 * @return session object as per the key
 */
NODE getValue(int key) {
    NODE value = NULL;

    if (isCreated(key)) {
        value = (NODE) map.valueFor(key);
    }

    return value;
}

/**
 * returns the number of objects in the session map table
 *
 * @return count of number of session objects.
 */
int getSize() {
    return map.size();
}

/**
 * returns the session object by the index in the session map table
 *
 * @param index - index of the value required
 *
 * @return session object as per the index
 */
NODE getValueAt(unsigned int index) {
    NODE value = NULL;

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
    deleteValue(getValue(key));
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
    return (0 <= map.indexOfKey(key));
}

/**
 * empty the entire session table. It releases all the memory for session objects.
 */
void destroyMap() {
    int size = map.size();
    int i = 0;

    for (i = 0; i < size; i++) {
        deleteValue(map.valueAt(i));
    }

    map.clear();
}

/**
 * free up the memory for the session object.
 * Make sure if any reference to the session object anywhere, otherwise it will be a
 * dangle pointer after this call.
 *
 * @param value - session object to free
 *
 */
void deleteValue(NODE value) {
    delete value;
}

};

};

#endif /* __SESSIONMAP_H__ */
