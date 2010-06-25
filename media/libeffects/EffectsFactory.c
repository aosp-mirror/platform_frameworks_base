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

#define LOG_TAG "EffectsFactory"
//#define LOG_NDEBUG 0

#include "EffectsFactory.h"
#include <string.h>
#include <stdlib.h>
#include <dlfcn.h>


static list_elem_t *gEffectList; // list of effect_entry_t: all currently created effects
static list_elem_t *gLibraryList; // list of lib_entry_t: all currently loaded libraries
static pthread_mutex_t gLibLock = PTHREAD_MUTEX_INITIALIZER; // controls access to gLibraryList
static uint32_t gNumEffects;         // total number number of effects
static list_elem_t *gCurLib;    // current library in enumeration process
static list_elem_t *gCurEffect; // current effect in enumeration process
static uint32_t gCurEffectIdx;       // current effect index in enumeration process

static const char * const gEffectLibPath = "/system/lib/soundfx"; // path to built-in effect libraries
static int gInitDone; // true is global initialization has been preformed
static int gNextLibId; // used by loadLibrary() to allocate unique library handles
static int gCanQueryEffect; // indicates that call to EffectQueryEffect() is valid, i.e. that the list of effects
                          // was not modified since last call to EffectQueryNumberEffects()

/////////////////////////////////////////////////
//      Local functions prototypes
/////////////////////////////////////////////////

static int init();
static int loadLibrary(const char *libPath, int *handle);
static int unloadLibrary(int handle);
static void resetEffectEnumeration();
static uint32_t updateNumEffects();
static int findEffect(effect_uuid_t *uuid, lib_entry_t **lib, effect_descriptor_t **desc);
static void dumpEffectDescriptor(effect_descriptor_t *desc, char *str, size_t len);

/////////////////////////////////////////////////
//      Effect Control Interface functions
/////////////////////////////////////////////////

int Effect_Process(effect_interface_t self, audio_buffer_t *inBuffer, audio_buffer_t *outBuffer)
{
    int ret = init();
    if (ret < 0) {
        return ret;
    }
    effect_entry_t *fx = (effect_entry_t *)self;
    pthread_mutex_lock(&gLibLock);
    if (fx->lib == NULL) {
        pthread_mutex_unlock(&gLibLock);
        return -EPIPE;
    }
    pthread_mutex_lock(&fx->lib->lock);
    pthread_mutex_unlock(&gLibLock);

    ret = (*fx->subItfe)->process(fx->subItfe, inBuffer, outBuffer);
    pthread_mutex_unlock(&fx->lib->lock);
    return ret;
}

int Effect_Command(effect_interface_t self, int cmdCode, int cmdSize, void *pCmdData, int *replySize, void *pReplyData)
{
    int ret = init();
    if (ret < 0) {
        return ret;
    }
    effect_entry_t *fx = (effect_entry_t *)self;
    pthread_mutex_lock(&gLibLock);
    if (fx->lib == NULL) {
        pthread_mutex_unlock(&gLibLock);
        return -EPIPE;
    }
    pthread_mutex_lock(&fx->lib->lock);
    pthread_mutex_unlock(&gLibLock);

    ret = (*fx->subItfe)->command(fx->subItfe, cmdCode, cmdSize, pCmdData, replySize, pReplyData);
    pthread_mutex_unlock(&fx->lib->lock);
    return ret;
}

const struct effect_interface_s gInterface = {
        Effect_Process,
        Effect_Command
};

/////////////////////////////////////////////////
//      Effect Factory Interface functions
/////////////////////////////////////////////////

int EffectQueryNumberEffects(uint32_t *pNumEffects)
{
    int ret = init();
    if (ret < 0) {
        return ret;
    }
    if (pNumEffects == NULL) {
        return -EINVAL;
    }

    pthread_mutex_lock(&gLibLock);
    *pNumEffects = gNumEffects;
    gCanQueryEffect = 1;
    pthread_mutex_unlock(&gLibLock);
    LOGV("EffectQueryNumberEffects(): %d", *pNumEffects);
    return ret;
}

int EffectQueryEffect(uint32_t index, effect_descriptor_t *pDescriptor)
{
    int ret = init();
    if (ret < 0) {
        return ret;
    }
    if (pDescriptor == NULL ||
        index >= gNumEffects) {
        return -EINVAL;
    }
    if (gCanQueryEffect == 0) {
        return -ENOSYS;
    }

    pthread_mutex_lock(&gLibLock);
    ret = -ENOENT;
    if (index < gCurEffectIdx) {
        resetEffectEnumeration();
    }
    while (gCurLib) {
        if (gCurEffect) {
            if (index == gCurEffectIdx) {
                memcpy(pDescriptor, gCurEffect->object, sizeof(effect_descriptor_t));
                ret = 0;
                break;
            } else {
                gCurEffect = gCurEffect->next;
                gCurEffectIdx++;
            }
        } else {
            gCurLib = gCurLib->next;
            gCurEffect = ((lib_entry_t *)gCurLib->object)->effects;
        }
    }

#if (LOG_NDEBUG == 0)
    char str[256];
    dumpEffectDescriptor(pDescriptor, str, 256);
    LOGV("EffectQueryEffect() desc:%s", str);
#endif
    pthread_mutex_unlock(&gLibLock);
    return ret;
}

int EffectGetDescriptor(effect_uuid_t *uuid, effect_descriptor_t *pDescriptor)
{
    lib_entry_t *l = NULL;
    effect_descriptor_t *d = NULL;

    int ret = init();
    if (ret < 0) {
        return ret;
    }
    if (pDescriptor == NULL || uuid == NULL) {
        return -EINVAL;
    }
    pthread_mutex_lock(&gLibLock);
    ret = findEffect(uuid, &l, &d);
    if (ret == 0) {
        memcpy(pDescriptor, d, sizeof(effect_descriptor_t));
    }
    pthread_mutex_unlock(&gLibLock);
    return ret;
}

int EffectCreate(effect_uuid_t *uuid, int32_t sessionId, int32_t ioId, effect_interface_t *pInterface)
{
    list_elem_t *e = gLibraryList;
    lib_entry_t *l = NULL;
    effect_descriptor_t *d = NULL;
    effect_interface_t itfe;
    effect_entry_t *fx;
    int found = 0;
    int ret;

    if (uuid == NULL || pInterface == NULL) {
        return -EINVAL;
    }

    LOGV("EffectCreate() UUID: %08X-%04X-%04X-%04X-%02X%02X%02X%02X%02X%02X\n",
            uuid->timeLow, uuid->timeMid, uuid->timeHiAndVersion,
            uuid->clockSeq, uuid->node[0], uuid->node[1],uuid->node[2],
            uuid->node[3],uuid->node[4],uuid->node[5]);

    ret = init();

    if (ret < 0) {
        LOGW("EffectCreate() init error: %d", ret);
        return ret;
    }

    pthread_mutex_lock(&gLibLock);

    ret = findEffect(uuid, &l, &d);
    if (ret < 0){
        goto exit;
    }

    // create effect in library
    ret = l->createFx(uuid, sessionId, ioId, &itfe);
    if (ret != 0) {
        LOGW("EffectCreate() library %s: could not create fx %s, error %d", l->path, d->name, ret);
        goto exit;
    }

    // add entry to effect list
    fx = (effect_entry_t *)malloc(sizeof(effect_entry_t));
    fx->subItfe = itfe;
    fx->itfe = (struct effect_interface_s *)&gInterface;
    fx->lib = l;

    e = (list_elem_t *)malloc(sizeof(list_elem_t));
    e->object = fx;
    e->next = gEffectList;
    gEffectList = e;

    *pInterface = (effect_interface_t)fx;

    LOGV("EffectCreate() created entry %p with sub itfe %p in library %s", *pInterface, itfe, l->path);

exit:
    pthread_mutex_unlock(&gLibLock);
    return ret;
}

int EffectRelease(effect_interface_t interface)
{
    effect_entry_t *fx;
    list_elem_t *e1;
    list_elem_t *e2;

    int ret = init();
    if (ret < 0) {
        return ret;
    }

    // remove effect from effect list
    pthread_mutex_lock(&gLibLock);
    e1 = gEffectList;
    e2 = NULL;
    while (e1) {
        if (e1->object == interface) {
            if (e2) {
                e2->next = e1->next;
            } else {
                gEffectList = e1->next;
            }
            fx = (effect_entry_t *)e1->object;
            free(e1);
            break;
        }
        e2 = e1;
        e1 = e1->next;
    }
    if (e1 == NULL) {
        ret = -ENOENT;
        goto exit;
    }

    // release effect in library
    if (fx->lib == NULL) {
        LOGW("EffectRelease() fx %p library already unloaded", interface);
    } else {
        pthread_mutex_lock(&fx->lib->lock);
        fx->lib->releaseFx(fx->subItfe);
        pthread_mutex_unlock(&fx->lib->lock);
    }
    free(fx);

exit:
    pthread_mutex_unlock(&gLibLock);
    return ret;
}

int EffectLoadLibrary(const char *libPath, int *handle)
{
    int ret = init();
    if (ret < 0) {
        return ret;
    }
    if (libPath == NULL) {
        return -EINVAL;
    }

    ret = loadLibrary(libPath, handle);
    updateNumEffects();
    return ret;
}

int EffectUnloadLibrary(int handle)
{
    int ret = init();
    if (ret < 0) {
        return ret;
    }

    ret = unloadLibrary(handle);
    updateNumEffects();
    return ret;
}

int EffectIsNullUuid(effect_uuid_t *uuid)
{
    if (memcmp(uuid, EFFECT_UUID_NULL, sizeof(effect_uuid_t))) {
        return 0;
    }
    return 1;
}

/////////////////////////////////////////////////
//      Local functions
/////////////////////////////////////////////////

int init() {
    struct dirent *ent;
    DIR *dir = NULL;
    char libpath[PATH_MAX];
    int hdl;

    if (gInitDone) {
        return 0;
    }

    pthread_mutex_init(&gLibLock, NULL);

    // load built-in libraries
    dir = opendir(gEffectLibPath);
    if (dir == NULL) {
        return -ENODEV;
    }
    while ((ent = readdir(dir)) != NULL) {
        LOGV("init() reading file %s", ent->d_name);
        if ((strlen(ent->d_name) < 3) ||
            strncmp(ent->d_name, "lib", 3) != 0 ||
            strncmp(ent->d_name + strlen(ent->d_name) - 3, ".so", 3) != 0) {
            continue;
        }
        strcpy(libpath, gEffectLibPath);
        strcat(libpath, "/");
        strcat(libpath, ent->d_name);
        if (loadLibrary(libpath, &hdl) < 0) {
            LOGW("init() failed to load library %s",libpath);
        }
    }
    closedir(dir);
    updateNumEffects();
    gInitDone = 1;
    LOGV("init() done");
    return 0;
}


int loadLibrary(const char *libPath, int *handle)
{
    void *hdl;
    effect_QueryNumberEffects_t queryNumFx;
    effect_QueryEffect_t queryFx;
    effect_CreateEffect_t createFx;
    effect_ReleaseEffect_t releaseFx;
    uint32_t numFx;
    uint32_t fx;
    int ret;
    list_elem_t *e, *descHead = NULL;
    lib_entry_t *l;

    if (handle == NULL) {
        return -EINVAL;
    }

    *handle = 0;

    hdl = dlopen(libPath, RTLD_NOW);
    if (hdl == 0) {
        LOGW("could open lib %s", libPath);
        return -ENODEV;
    }

    // Check functions availability
    queryNumFx = (effect_QueryNumberEffects_t)dlsym(hdl, "EffectQueryNumberEffects");
    if (queryNumFx == NULL) {
        LOGW("could not get EffectQueryNumberEffects from lib %s", libPath);
        ret = -ENODEV;
        goto error;
    }
    queryFx = (effect_QueryEffect_t)dlsym(hdl, "EffectQueryEffect");
    if (queryFx == NULL) {
        LOGW("could not get EffectQueryEffect from lib %s", libPath);
        ret = -ENODEV;
        goto error;
    }
    createFx = (effect_CreateEffect_t)dlsym(hdl, "EffectCreate");
    if (createFx == NULL) {
        LOGW("could not get EffectCreate from lib %s", libPath);
        ret = -ENODEV;
        goto error;
    }
    releaseFx = (effect_ReleaseEffect_t)dlsym(hdl, "EffectRelease");
    if (releaseFx == NULL) {
        LOGW("could not get EffectRelease from lib %s", libPath);
        ret = -ENODEV;
        goto error;
    }

    // load effect descriptors
    ret = queryNumFx(&numFx);
    if (ret) {
        goto error;
    }

    for (fx = 0; fx < numFx; fx++) {
        effect_descriptor_t *d = malloc(sizeof(effect_descriptor_t));
        if (d == NULL) {
            ret = -ENOMEM;
            goto error;
        }
        ret = queryFx(fx, d);
        if (ret == 0) {
#if (LOG_NDEBUG==0)
            char s[256];
            dumpEffectDescriptor(d, s, 256);
            LOGV("loadLibrary() read descriptor %p:%s",d, s);
#endif
            if (d->apiVersion != EFFECT_API_VERSION) {
                LOGW("Bad API version %04x on lib %s", d->apiVersion, libPath);
                free(d);
                continue;
            }
            e = malloc(sizeof(list_elem_t));
            if (e == NULL) {
                free(d);
                ret = -ENOMEM;
                goto error;
            }
            e->object = d;
            e->next = descHead;
            descHead = e;
        } else {
            LOGW("Error querying effect # %d on lib %s", fx, libPath);
        }
    }

    pthread_mutex_lock(&gLibLock);

    // add entry for library in gLibraryList
    l = malloc(sizeof(lib_entry_t));
    l->id = ++gNextLibId;
    l->handle = hdl;
    strncpy(l->path, libPath, PATH_MAX);
    l->createFx = createFx;
    l->releaseFx = releaseFx;
    l->effects = descHead;
    pthread_mutex_init(&l->lock, NULL);

    e = malloc(sizeof(list_elem_t));
    e->next = gLibraryList;
    e->object = l;
    gLibraryList = e;
    pthread_mutex_unlock(&gLibLock);
    LOGV("loadLibrary() linked library %p", l);

    *handle = l->id;

    return 0;

error:
    LOGW("loadLibrary() error: %d on lib: %s", ret, libPath);
    while (descHead) {
        free(descHead->object);
        e = descHead->next;
        free(descHead);
        descHead = e;;
    }
    dlclose(hdl);
    return ret;
}

int unloadLibrary(int handle)
{
    void *hdl;
    int ret;
    list_elem_t *el1, *el2;
    lib_entry_t *l;
    effect_entry_t *fx;

    pthread_mutex_lock(&gLibLock);
    el1 = gLibraryList;
    el2 = NULL;
    while (el1) {
        l = (lib_entry_t *)el1->object;
        if (handle == l->id) {
            if (el2) {
                el2->next = el1->next;
            } else {
                gLibraryList = el1->next;
            }
            free(el1);
            break;
        }
        el2 = el1;
        el1 = el1->next;
    }
    pthread_mutex_unlock(&gLibLock);
    if (el1 == NULL) {
        return -ENOENT;
    }

    // clear effect descriptor list
    el1 = l->effects;
    while (el1) {
        free(el1->object);
        el2 = el1->next;
        free(el1);
        el1 = el2;
    }

    // disable all effects from this library
    pthread_mutex_lock(&l->lock);

    el1 = gEffectList;
    while (el1) {
        fx = (effect_entry_t *)el1->object;
        if (fx->lib == l) {
            fx->lib = NULL;
        }
        el1 = el1->next;
    }
    pthread_mutex_unlock(&l->lock);

    dlclose(l->handle);
    free(l);
    return 0;
}

void resetEffectEnumeration()
{
    gCurLib = gLibraryList;
    gCurEffect = NULL;
    if (gCurLib) {
        gCurEffect = ((lib_entry_t *)gCurLib->object)->effects;
    }
    gCurEffectIdx = 0;
}

uint32_t updateNumEffects() {
    list_elem_t *e;
    uint32_t cnt = 0;

    resetEffectEnumeration();

    e = gLibraryList;
    while (e) {
        lib_entry_t *l = (lib_entry_t *)e->object;
        list_elem_t *efx = l->effects;
        while (efx) {
            cnt++;
            efx = efx->next;
        }
        e = e->next;
    }
    gNumEffects = cnt;
    gCanQueryEffect = 0;
    return cnt;
}

int findEffect(effect_uuid_t *uuid, lib_entry_t **lib, effect_descriptor_t **desc)
{
    list_elem_t *e = gLibraryList;
    lib_entry_t *l = NULL;
    effect_descriptor_t *d = NULL;
    int found = 0;
    int ret = 0;

    while (e && !found) {
        l = (lib_entry_t *)e->object;
        list_elem_t *efx = l->effects;
        while (efx) {
            d = (effect_descriptor_t *)efx->object;
            if (memcmp(&d->uuid, uuid, sizeof(effect_uuid_t)) == 0) {
                found = 1;
                break;
            }
            efx = efx->next;
        }
        e = e->next;
    }
    if (!found) {
        LOGV("findEffect() effect not found");
        ret = -ENOENT;
    } else {
        LOGV("findEffect() found effect: %s in lib %s", d->name, l->path);
        *lib = l;
        *desc = d;
    }

    return ret;
}

void dumpEffectDescriptor(effect_descriptor_t *desc, char *str, size_t len) {
    char s[256];

    snprintf(str, len, "\nEffect Descriptor %p:\n", desc);
    sprintf(s, "- UUID: %08X-%04X-%04X-%04X-%02X%02X%02X%02X%02X%02X\n",
            desc->uuid.timeLow, desc->uuid.timeMid, desc->uuid.timeHiAndVersion,
            desc->uuid.clockSeq, desc->uuid.node[0], desc->uuid.node[1],desc->uuid.node[2],
            desc->uuid.node[3],desc->uuid.node[4],desc->uuid.node[5]);
    strncat(str, s, len);
    sprintf(s, "- TYPE: %08X-%04X-%04X-%04X-%02X%02X%02X%02X%02X%02X\n",
                desc->type.timeLow, desc->type.timeMid, desc->type.timeHiAndVersion,
                desc->type.clockSeq, desc->type.node[0], desc->type.node[1],desc->type.node[2],
                desc->type.node[3],desc->type.node[4],desc->type.node[5]);
    strncat(str, s, len);
    sprintf(s, "- apiVersion: %04X\n- flags: %08X\n",
            desc->apiVersion, desc->flags);
    strncat(str, s, len);
    sprintf(s, "- name: %s\n", desc->name);
    strncat(str, s, len);
    sprintf(s, "- implementor: %s\n", desc->implementor);
    strncat(str, s, len);
}

