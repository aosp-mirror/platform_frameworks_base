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

#ifndef __PLUGIN_MANAGER_H__
#define __PLUGIN_MANAGER_H__

#include <dlfcn.h>
#include <sys/types.h>
#include <dirent.h>

#include <utils/String8.h>
#include <utils/Vector.h>
#include <utils/KeyedVector.h>

namespace android {

const char* const PLUGIN_MANAGER_CREATE = "create";
const char* const PLUGIN_MANAGER_DESTROY = "destroy";
const char* const PLUGIN_EXTENSION = ".so";

/**
 * This is the template class for Plugin manager.
 *
 * The DrmManager uses this class to handle the plugins.
 *
 */
template<typename Type>
class TPlugInManager {
private:
    typedef void*      HANDLE;
    typedef Type*      create_t(void);
    typedef void       destroy_t(Type*);
    typedef create_t*  FPCREATE;
    typedef destroy_t* FPDESTORY;

    typedef struct _PlugInContainer {
        String8   sPath;
        HANDLE    hHandle;
        FPCREATE  fpCreate;
        FPDESTORY fpDestory;
        Type*     pInstance;

        _PlugInContainer():
            sPath("")
            ,hHandle(NULL)
            ,fpCreate(NULL)
            ,fpDestory(NULL)
            ,pInstance(NULL)
            {}
    } PlugInContainer;

    typedef KeyedVector<String8, PlugInContainer*> PlugInMap;
    PlugInMap m_plugInMap;

    typedef Vector<String8> PlugInIdList;
    PlugInIdList m_plugInIdList;

public:
    /**
     * Load all the plug-ins in the specified directory
     *
     * @param[in] rsPlugInDirPath
     *     Directory path which plug-ins (dynamic library) are stored
     * @note Plug-ins should be implemented according to the specification
     */
    void loadPlugIns(const String8& rsPlugInDirPath) {
        Vector<String8> plugInFileList = getPlugInPathList(rsPlugInDirPath);

        if (!plugInFileList.isEmpty()) {
            for (unsigned int i = 0; i < plugInFileList.size(); ++i) {
                loadPlugIn(plugInFileList[i]);
            }
        }
    }

    /**
     * Unload all the plug-ins
     *
     */
    void unloadPlugIns() {
        for (unsigned int i = 0; i < m_plugInIdList.size(); ++i) {
            unloadPlugIn(m_plugInIdList[i]);
        }
        m_plugInIdList.clear();
    }

    /**
     * Get all the IDs of available plug-ins
     *
     * @return[in] plugInIdList
     *     String type Vector in which all plug-in IDs are stored
     */
    Vector<String8> getPlugInIdList() const {
        return m_plugInIdList;
    }

    /**
     * Get a plug-in reference of specified ID
     *
     * @param[in] rsPlugInId
     *     Plug-in ID to be used
     * @return plugIn
     *     Reference of specified plug-in instance
     */
    Type& getPlugIn(const String8& rsPlugInId) {
        if (!contains(rsPlugInId)) {
            // This error case never happens
        }
        return *(m_plugInMap.valueFor(rsPlugInId)->pInstance);
    }

public:
    /**
     * Load a plug-in stored in the specified path
     *
     * @param[in] rsPlugInPath
     *     Plug-in (dynamic library) file path
     * @note Plug-in should be implemented according to the specification
     */
    void loadPlugIn(const String8& rsPlugInPath) {
        if (contains(rsPlugInPath)) {
            return;
        }

        PlugInContainer* pPlugInContainer = new PlugInContainer();

        pPlugInContainer->hHandle = dlopen(rsPlugInPath.string(), RTLD_LAZY);

        if (NULL == pPlugInContainer->hHandle) {
            delete pPlugInContainer;
            pPlugInContainer = NULL;
            return;
        }

        pPlugInContainer->sPath = rsPlugInPath;
        pPlugInContainer->fpCreate
                = (FPCREATE)dlsym(pPlugInContainer->hHandle, PLUGIN_MANAGER_CREATE);
        pPlugInContainer->fpDestory
                = (FPDESTORY)dlsym(pPlugInContainer->hHandle, PLUGIN_MANAGER_DESTROY);

        if (NULL != pPlugInContainer->fpCreate && NULL != pPlugInContainer->fpDestory) {
            pPlugInContainer->pInstance = (Type*)pPlugInContainer->fpCreate();
            m_plugInIdList.add(rsPlugInPath);
            m_plugInMap.add(rsPlugInPath, pPlugInContainer);
        } else {
            dlclose(pPlugInContainer->hHandle);
            delete pPlugInContainer;
            pPlugInContainer = NULL;
            return;
        }
    }

    /**
     * Unload a plug-in stored in the specified path
     *
     * @param[in] rsPlugInPath
     *     Plug-in (dynamic library) file path
     */
    void unloadPlugIn(const String8& rsPlugInPath) {
        if (!contains(rsPlugInPath)) {
            return;
        }

        PlugInContainer* pPlugInContainer = m_plugInMap.valueFor(rsPlugInPath);
        pPlugInContainer->fpDestory(pPlugInContainer->pInstance);
        dlclose(pPlugInContainer->hHandle);

        m_plugInMap.removeItem(rsPlugInPath);
        delete pPlugInContainer;
        pPlugInContainer = NULL;
    }

private:
    /**
     * True if TPlugInManager contains rsPlugInId
     */
    bool contains(const String8& rsPlugInId) {
        return m_plugInMap.indexOfKey(rsPlugInId) != NAME_NOT_FOUND;
    }

    /**
     * Return file path list of plug-ins stored in the specified directory
     *
     * @param[in] rsDirPath
     *     Directory path in which plug-ins are stored
     * @return plugInFileList
     *     String type Vector in which file path of plug-ins are stored
     */
    Vector<String8> getPlugInPathList(const String8& rsDirPath) {
        Vector<String8> fileList;
        DIR* pDir = opendir(rsDirPath.string());
        struct dirent* pEntry;

        while (NULL != pDir && NULL != (pEntry = readdir(pDir))) {
            if (!isPlugIn(pEntry)) {
                continue;
            }
            String8 plugInPath;
            plugInPath += rsDirPath;
            plugInPath += "/";
            plugInPath += pEntry->d_name;

            fileList.add(plugInPath);
        }

        if (NULL != pDir) {
            closedir(pDir);
        }

        return fileList;
    }

    /**
     * True if the input name denotes plug-in
     */
    bool isPlugIn(const struct dirent* pEntry) const {
        String8 sName(pEntry->d_name);
        String8 extension(sName.getPathExtension());
        // Note that the plug-in extension must exactly match case
        return extension == String8(PLUGIN_EXTENSION);
    }

    /**
     * True if the input entry is "." or ".."
     */
    bool isDotOrDDot(const struct dirent* pEntry) const {
        String8 sName(pEntry->d_name);
        return "." == sName || ".." == sName;
    }

    /**
     * True if input entry is directory
     */
    bool isDirectory(const struct dirent* pEntry) const {
        return DT_DIR == pEntry->d_type;
    }

    /**
     * True if input entry is regular file
     */
    bool isRegularFile(const struct dirent* pEntry) const {
        return DT_REG == pEntry->d_type;
    }

    /**
     * True if input entry is link
     */
    bool isLink(const struct dirent* pEntry) const {
        return DT_LNK == pEntry->d_type;
    }
};

};

#endif /* __PLUGIN_MANAGER_H__ */

