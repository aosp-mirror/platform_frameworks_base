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

#include <cutils/misc.h>
#include <cutils/config_utils.h>
#include <audio_effects/audio_effects_conf.h>

static list_elem_t *gEffectList; // list of effect_entry_t: all currently created effects
static list_elem_t *gLibraryList; // list of lib_entry_t: all currently loaded libraries
static pthread_mutex_t gLibLock = PTHREAD_MUTEX_INITIALIZER; // controls access to gLibraryList
static uint32_t gNumEffects;         // total number number of effects
static list_elem_t *gCurLib;    // current library in enumeration process
static list_elem_t *gCurEffect; // current effect in enumeration process
static uint32_t gCurEffectIdx;       // current effect index in enumeration process
static lib_entry_t *gCachedLibrary;  // last library accessed by getLibrary()

static int gInitDone; // true is global initialization has been preformed
static int gCanQueryEffect; // indicates that call to EffectQueryEffect() is valid, i.e. that the list of effects
                          // was not modified since last call to EffectQueryNumberEffects()


/////////////////////////////////////////////////
//      Local functions prototypes
/////////////////////////////////////////////////

static int init();
static int loadEffectConfigFile(const char *path);
static int loadLibraries(cnode *root);
static int loadLibrary(cnode *root, const char *name);
static int loadEffects(cnode *root);
static int loadEffect(cnode *node);
static lib_entry_t *getLibrary(const char *path);
static void resetEffectEnumeration();
static uint32_t updateNumEffects();
static int findEffect(effect_uuid_t *type,
               effect_uuid_t *uuid,
               lib_entry_t **lib,
               effect_descriptor_t **desc);
static void dumpEffectDescriptor(effect_descriptor_t *desc, char *str, size_t len);
static int stringToUuid(const char *str, effect_uuid_t *uuid);
static int uuidToString(const effect_uuid_t *uuid, char *str, size_t maxLen);

/////////////////////////////////////////////////
//      Effect Control Interface functions
/////////////////////////////////////////////////

int Effect_Process(effect_handle_t self, audio_buffer_t *inBuffer, audio_buffer_t *outBuffer)
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

int Effect_Command(effect_handle_t self,
                   uint32_t cmdCode,
                   uint32_t cmdSize,
                   void *pCmdData,
                   uint32_t *replySize,
                   void *pReplyData)
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

int Effect_GetDescriptor(effect_handle_t self,
                         effect_descriptor_t *desc)
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

    ret = (*fx->subItfe)->get_descriptor(fx->subItfe, desc);
    pthread_mutex_unlock(&fx->lib->lock);
    return ret;
}

int Effect_ProcessReverse(effect_handle_t self, audio_buffer_t *inBuffer, audio_buffer_t *outBuffer)
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

    if ((*fx->subItfe)->process_reverse != NULL) {
        ret = (*fx->subItfe)->process_reverse(fx->subItfe, inBuffer, outBuffer);
    } else {
        ret = -ENOSYS;
    }
    pthread_mutex_unlock(&fx->lib->lock);
    return ret;
}


const struct effect_interface_s gInterface = {
        Effect_Process,
        Effect_Command,
        Effect_GetDescriptor,
        NULL
};

const struct effect_interface_s gInterfaceWithReverse = {
        Effect_Process,
        Effect_Command,
        Effect_GetDescriptor,
        Effect_ProcessReverse
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
    ALOGV("EffectQueryNumberEffects(): %d", *pNumEffects);
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
    ALOGV("EffectQueryEffect() desc:%s", str);
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
    ret = findEffect(NULL, uuid, &l, &d);
    if (ret == 0) {
        memcpy(pDescriptor, d, sizeof(effect_descriptor_t));
    }
    pthread_mutex_unlock(&gLibLock);
    return ret;
}

int EffectCreate(effect_uuid_t *uuid, int32_t sessionId, int32_t ioId, effect_handle_t *pHandle)
{
    list_elem_t *e = gLibraryList;
    lib_entry_t *l = NULL;
    effect_descriptor_t *d = NULL;
    effect_handle_t itfe;
    effect_entry_t *fx;
    int found = 0;
    int ret;

    if (uuid == NULL || pHandle == NULL) {
        return -EINVAL;
    }

    ALOGV("EffectCreate() UUID: %08X-%04X-%04X-%04X-%02X%02X%02X%02X%02X%02X\n",
            uuid->timeLow, uuid->timeMid, uuid->timeHiAndVersion,
            uuid->clockSeq, uuid->node[0], uuid->node[1],uuid->node[2],
            uuid->node[3],uuid->node[4],uuid->node[5]);

    ret = init();

    if (ret < 0) {
        ALOGW("EffectCreate() init error: %d", ret);
        return ret;
    }

    pthread_mutex_lock(&gLibLock);

    ret = findEffect(NULL, uuid, &l, &d);
    if (ret < 0){
        goto exit;
    }

    // create effect in library
    ret = l->desc->create_effect(uuid, sessionId, ioId, &itfe);
    if (ret != 0) {
        ALOGW("EffectCreate() library %s: could not create fx %s, error %d", l->name, d->name, ret);
        goto exit;
    }

    // add entry to effect list
    fx = (effect_entry_t *)malloc(sizeof(effect_entry_t));
    fx->subItfe = itfe;
    if ((*itfe)->process_reverse != NULL) {
        fx->itfe = (struct effect_interface_s *)&gInterfaceWithReverse;
        ALOGV("EffectCreate() gInterfaceWithReverse");
    }   else {
        fx->itfe = (struct effect_interface_s *)&gInterface;
        ALOGV("EffectCreate() gInterface");
    }
    fx->lib = l;

    e = (list_elem_t *)malloc(sizeof(list_elem_t));
    e->object = fx;
    e->next = gEffectList;
    gEffectList = e;

    *pHandle = (effect_handle_t)fx;

    ALOGV("EffectCreate() created entry %p with sub itfe %p in library %s", *pHandle, itfe, l->name);

exit:
    pthread_mutex_unlock(&gLibLock);
    return ret;
}

int EffectRelease(effect_handle_t handle)
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
        if (e1->object == handle) {
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
        ALOGW("EffectRelease() fx %p library already unloaded", handle);
    } else {
        pthread_mutex_lock(&fx->lib->lock);
        fx->lib->desc->release_effect(fx->subItfe);
        pthread_mutex_unlock(&fx->lib->lock);
    }
    free(fx);

exit:
    pthread_mutex_unlock(&gLibLock);
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
    int hdl;

    if (gInitDone) {
        return 0;
    }

    pthread_mutex_init(&gLibLock, NULL);

    if (access(AUDIO_EFFECT_VENDOR_CONFIG_FILE, R_OK) == 0) {
        loadEffectConfigFile(AUDIO_EFFECT_VENDOR_CONFIG_FILE);
    } else if (access(AUDIO_EFFECT_DEFAULT_CONFIG_FILE, R_OK) == 0) {
        loadEffectConfigFile(AUDIO_EFFECT_DEFAULT_CONFIG_FILE);
    }

    updateNumEffects();
    gInitDone = 1;
    ALOGV("init() done");
    return 0;
}

int loadEffectConfigFile(const char *path)
{
    cnode *root;
    char *data;

    data = load_file(path, NULL);
    if (data == NULL) {
        return -ENODEV;
    }
    root = config_node("", "");
    config_load(root, data);
    loadLibraries(root);
    loadEffects(root);
    config_free(root);
    free(root);
    free(data);

    return 0;
}

int loadLibraries(cnode *root)
{
    cnode *node;

    node = config_find(root, LIBRARIES_TAG);
    if (node == NULL) {
        return -ENOENT;
    }
    node = node->first_child;
    while (node) {
        loadLibrary(node, node->name);
        node = node->next;
    }
    return 0;
}

int loadLibrary(cnode *root, const char *name)
{
    cnode *node;
    void *hdl;
    audio_effect_library_t *desc;
    list_elem_t *e;
    lib_entry_t *l;

    node = config_find(root, PATH_TAG);
    if (node == NULL) {
        return -EINVAL;
    }

    hdl = dlopen(node->value, RTLD_NOW);
    if (hdl == NULL) {
        ALOGW("loadLibrary() failed to open %s", node->value);
        goto error;
    }

    desc = (audio_effect_library_t *)dlsym(hdl, AUDIO_EFFECT_LIBRARY_INFO_SYM_AS_STR);
    if (desc == NULL) {
        ALOGW("loadLibrary() could not find symbol %s", AUDIO_EFFECT_LIBRARY_INFO_SYM_AS_STR);
        goto error;
    }

    if (AUDIO_EFFECT_LIBRARY_TAG != desc->tag) {
        ALOGW("getLibrary() bad tag %08x in lib info struct", desc->tag);
        goto error;
    }

    if (EFFECT_API_VERSION_MAJOR(desc->version) !=
            EFFECT_API_VERSION_MAJOR(EFFECT_LIBRARY_API_VERSION)) {
        ALOGW("loadLibrary() bad lib version %08x", desc->version);
        goto error;
    }

    // add entry for library in gLibraryList
    l = malloc(sizeof(lib_entry_t));
    l->name = strndup(name, PATH_MAX);
    l->path = strndup(node->value, PATH_MAX);
    l->handle = hdl;
    l->desc = desc;
    l->effects = NULL;
    pthread_mutex_init(&l->lock, NULL);

    e = malloc(sizeof(list_elem_t));
    e->object = l;
    pthread_mutex_lock(&gLibLock);
    e->next = gLibraryList;
    gLibraryList = e;
    pthread_mutex_unlock(&gLibLock);
    ALOGV("getLibrary() linked library %p for path %s", l, node->value);

    return 0;

error:
    if (hdl != NULL) {
        dlclose(hdl);
    }
    return -EINVAL;
}

int loadEffects(cnode *root)
{
    cnode *node;

    node = config_find(root, EFFECTS_TAG);
    if (node == NULL) {
        return -ENOENT;
    }
    node = node->first_child;
    while (node) {
        loadEffect(node);
        node = node->next;
    }
    return 0;
}

int loadEffect(cnode *root)
{
    cnode *node;
    effect_uuid_t uuid;
    lib_entry_t *l;
    effect_descriptor_t *d;
    list_elem_t *e;

    node = config_find(root, LIBRARY_TAG);
    if (node == NULL) {
        return -EINVAL;
    }

    l = getLibrary(node->value);
    if (l == NULL) {
        ALOGW("loadEffect() could not get library %s", node->value);
        return -EINVAL;
    }

    node = config_find(root, UUID_TAG);
    if (node == NULL) {
        return -EINVAL;
    }
    if (stringToUuid(node->value, &uuid) != 0) {
        ALOGW("loadEffect() invalid uuid %s", node->value);
        return -EINVAL;
    }

    d = malloc(sizeof(effect_descriptor_t));
    if (l->desc->get_descriptor(&uuid, d) != 0) {
        char s[40];
        uuidToString(&uuid, s, 40);
        ALOGW("Error querying effect %s on lib %s", s, l->name);
        free(d);
        return -EINVAL;
    }
#if (LOG_NDEBUG==0)
    char s[256];
    dumpEffectDescriptor(d, s, 256);
    ALOGV("loadEffect() read descriptor %p:%s",d, s);
#endif
    if (EFFECT_API_VERSION_MAJOR(d->apiVersion) !=
            EFFECT_API_VERSION_MAJOR(EFFECT_CONTROL_API_VERSION)) {
        ALOGW("Bad API version %08x on lib %s", d->apiVersion, l->name);
        free(d);
        return -EINVAL;
    }
    e = malloc(sizeof(list_elem_t));
    e->object = d;
    e->next = l->effects;
    l->effects = e;

    return 0;
}

lib_entry_t *getLibrary(const char *name)
{
    list_elem_t *e;

    if (gCachedLibrary &&
            !strncmp(gCachedLibrary->name, name, PATH_MAX)) {
        return gCachedLibrary;
    }

    e = gLibraryList;
    while (e) {
        lib_entry_t *l = (lib_entry_t *)e->object;
        if (!strcmp(l->name, name)) {
            gCachedLibrary = l;
            return l;
        }
        e = e->next;
    }

    return NULL;
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

int findEffect(effect_uuid_t *type,
               effect_uuid_t *uuid,
               lib_entry_t **lib,
               effect_descriptor_t **desc)
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
            if (type != NULL && memcmp(&d->type, type, sizeof(effect_uuid_t)) == 0) {
                found = 1;
                break;
            }
            if (uuid != NULL && memcmp(&d->uuid, uuid, sizeof(effect_uuid_t)) == 0) {
                found = 1;
                break;
            }
            efx = efx->next;
        }
        e = e->next;
    }
    if (!found) {
        ALOGV("findEffect() effect not found");
        ret = -ENOENT;
    } else {
        ALOGV("findEffect() found effect: %s in lib %s", d->name, l->name);
        *lib = l;
        if (desc) {
            *desc = d;
        }
    }

    return ret;
}

void dumpEffectDescriptor(effect_descriptor_t *desc, char *str, size_t len) {
    char s[256];

    snprintf(str, len, "\nEffect Descriptor %p:\n", desc);
    strncat(str, "- TYPE: ", len);
    uuidToString(&desc->uuid, s, 256);
    snprintf(str, len, "- UUID: %s\n", s);
    uuidToString(&desc->type, s, 256);
    snprintf(str, len, "- TYPE: %s\n", s);
    sprintf(s, "- apiVersion: %08X\n- flags: %08X\n",
            desc->apiVersion, desc->flags);
    strncat(str, s, len);
    sprintf(s, "- name: %s\n", desc->name);
    strncat(str, s, len);
    sprintf(s, "- implementor: %s\n", desc->implementor);
    strncat(str, s, len);
}

int stringToUuid(const char *str, effect_uuid_t *uuid)
{
    int tmp[10];

    if (sscanf(str, "%08x-%04x-%04x-%04x-%02x%02x%02x%02x%02x%02x",
            tmp, tmp+1, tmp+2, tmp+3, tmp+4, tmp+5, tmp+6, tmp+7, tmp+8, tmp+9) < 10) {
        return -EINVAL;
    }
    uuid->timeLow = (uint32_t)tmp[0];
    uuid->timeMid = (uint16_t)tmp[1];
    uuid->timeHiAndVersion = (uint16_t)tmp[2];
    uuid->clockSeq = (uint16_t)tmp[3];
    uuid->node[0] = (uint8_t)tmp[4];
    uuid->node[1] = (uint8_t)tmp[5];
    uuid->node[2] = (uint8_t)tmp[6];
    uuid->node[3] = (uint8_t)tmp[7];
    uuid->node[4] = (uint8_t)tmp[8];
    uuid->node[5] = (uint8_t)tmp[9];

    return 0;
}

int uuidToString(const effect_uuid_t *uuid, char *str, size_t maxLen)
{

    snprintf(str, maxLen, "%08x-%04x-%04x-%04x-%02x%02x%02x%02x%02x%02x",
            uuid->timeLow,
            uuid->timeMid,
            uuid->timeHiAndVersion,
            uuid->clockSeq,
            uuid->node[0],
            uuid->node[1],
            uuid->node[2],
            uuid->node[3],
            uuid->node[4],
            uuid->node[5]);

    return 0;
}

