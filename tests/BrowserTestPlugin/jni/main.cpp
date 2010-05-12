/*
 * Copyright 2009, The Android Open Source Project
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS ``AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL APPLE COMPUTER, INC. OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include "android_npapi.h"
#include "main.h"
#include "PluginObject.h"
#include "EventPlugin.h"

NPNetscapeFuncs* browser;
#define EXPORT __attribute__((visibility("default")))

NPError NPP_New(NPMIMEType pluginType, NPP instance, uint16_t mode, int16_t argc,
        char* argn[], char* argv[], NPSavedData* saved);
NPError NPP_Destroy(NPP instance, NPSavedData** save);
NPError NPP_SetWindow(NPP instance, NPWindow* window);
NPError NPP_NewStream(NPP instance, NPMIMEType type, NPStream* stream,
        NPBool seekable, uint16_t* stype);
NPError NPP_DestroyStream(NPP instance, NPStream* stream, NPReason reason);
int32_t   NPP_WriteReady(NPP instance, NPStream* stream);
int32_t   NPP_Write(NPP instance, NPStream* stream, int32_t offset, int32_t len,
        void* buffer);
void    NPP_StreamAsFile(NPP instance, NPStream* stream, const char* fname);
void    NPP_Print(NPP instance, NPPrint* platformPrint);
int16_t   NPP_HandleEvent(NPP instance, void* event);
void    NPP_URLNotify(NPP instance, const char* URL, NPReason reason,
        void* notifyData);
NPError NPP_GetValue(NPP instance, NPPVariable variable, void *value);
NPError NPP_SetValue(NPP instance, NPNVariable variable, void *value);

extern "C" {
EXPORT NPError NP_Initialize(NPNetscapeFuncs* browserFuncs, NPPluginFuncs* pluginFuncs, void *java_env);
EXPORT NPError NP_GetValue(NPP instance, NPPVariable variable, void *value);
EXPORT const char* NP_GetMIMEDescription(void);
EXPORT void NP_Shutdown(void);
};

ANPAudioTrackInterfaceV0    gSoundI;
ANPBitmapInterfaceV0        gBitmapI;
ANPCanvasInterfaceV0        gCanvasI;
ANPLogInterfaceV0           gLogI;
ANPPaintInterfaceV0         gPaintI;
ANPPathInterfaceV0          gPathI;
ANPTypefaceInterfaceV0      gTypefaceI;
ANPWindowInterfaceV0        gWindowI;

#define ARRAY_COUNT(array)      (sizeof(array) / sizeof(array[0]))

NPError NP_Initialize(NPNetscapeFuncs* browserFuncs, NPPluginFuncs* pluginFuncs, void *java_env)
{
    // Make sure we have a function table equal or larger than we are built against.
    if (browserFuncs->size < sizeof(NPNetscapeFuncs)) {
        return NPERR_GENERIC_ERROR;
    }

    // Copy the function table (structure)
    browser = (NPNetscapeFuncs*) malloc(sizeof(NPNetscapeFuncs));
    memcpy(browser, browserFuncs, sizeof(NPNetscapeFuncs));

    // Build the plugin function table
    pluginFuncs->version = 11;
    pluginFuncs->size = sizeof(pluginFuncs);
    pluginFuncs->newp = NPP_New;
    pluginFuncs->destroy = NPP_Destroy;
    pluginFuncs->setwindow = NPP_SetWindow;
    pluginFuncs->newstream = NPP_NewStream;
    pluginFuncs->destroystream = NPP_DestroyStream;
    pluginFuncs->asfile = NPP_StreamAsFile;
    pluginFuncs->writeready = NPP_WriteReady;
    pluginFuncs->write = (NPP_WriteProcPtr)NPP_Write;
    pluginFuncs->print = NPP_Print;
    pluginFuncs->event = NPP_HandleEvent;
    pluginFuncs->urlnotify = NPP_URLNotify;
    pluginFuncs->getvalue = NPP_GetValue;
    pluginFuncs->setvalue = NPP_SetValue;

    static const struct {
        NPNVariable     v;
        uint32_t        size;
        ANPInterface*   i;
    } gPairs[] = {
        { kCanvasInterfaceV0_ANPGetValue,       sizeof(gCanvasI),   &gCanvasI },
        { kLogInterfaceV0_ANPGetValue,          sizeof(gLogI),      &gLogI },
        { kPaintInterfaceV0_ANPGetValue,        sizeof(gPaintI),    &gPaintI },
        { kTypefaceInterfaceV0_ANPGetValue,     sizeof(gTypefaceI), &gTypefaceI },
    };
    for (size_t i = 0; i < ARRAY_COUNT(gPairs); i++) {
        gPairs[i].i->inSize = gPairs[i].size;
        NPError err = browser->getvalue(NULL, gPairs[i].v, gPairs[i].i);
        if (err) {
            return err;
        }
    }

    return NPERR_NO_ERROR;
}

void NP_Shutdown(void)
{

}

const char *NP_GetMIMEDescription(void)
{
    return "application/x-browsertestplugin:btp:Android Browser Test Plugin";
}

NPError NPP_New(NPMIMEType pluginType, NPP instance, uint16_t mode, int16_t argc,
                char* argn[], char* argv[], NPSavedData* saved)
{


    gLogI.log(kDebug_ANPLogType, "creating plugin");

    PluginObject *obj = NULL;

    // Scripting functions appeared in NPAPI version 14
    if (browser->version >= 14) {
    instance->pdata = browser->createobject (instance, getPluginClass());
    obj = static_cast<PluginObject*>(instance->pdata);
    memset(obj, 0, sizeof(*obj));
    } else {
        return NPERR_GENERIC_ERROR;
    }

    // select the drawing model
    ANPDrawingModel model = kBitmap_ANPDrawingModel;

    // notify the plugin API of the drawing model we wish to use. This must be
    // done prior to creating certain subPlugin objects (e.g. surfaceViews)
    NPError err = browser->setvalue(instance, kRequestDrawingModel_ANPSetValue,
                            reinterpret_cast<void*>(model));
    if (err) {
        gLogI.log(kError_ANPLogType, "request model %d err %d", model, err);
        return err;
    }

    // create the sub-plugin
    obj->subPlugin = new EventPlugin(instance);

    return NPERR_NO_ERROR;
}

NPError NPP_Destroy(NPP instance, NPSavedData** save)
{
    PluginObject *obj = (PluginObject*) instance->pdata;
    if (obj) {
        delete obj->subPlugin;
        browser->releaseobject(&obj->header);
    }

    return NPERR_NO_ERROR;
}

NPError NPP_SetWindow(NPP instance, NPWindow* window)
{
    PluginObject *obj = (PluginObject*) instance->pdata;

    // Do nothing if browser didn't support NPN_CreateObject which would have created the PluginObject.
    if (obj != NULL) {
        obj->window = window;
    }

    return NPERR_NO_ERROR;
}

NPError NPP_NewStream(NPP instance, NPMIMEType type, NPStream* stream, NPBool seekable, uint16_t* stype)
{
    *stype = NP_ASFILEONLY;
    return NPERR_NO_ERROR;
}

NPError NPP_DestroyStream(NPP instance, NPStream* stream, NPReason reason)
{
    return NPERR_NO_ERROR;
}

int32_t NPP_WriteReady(NPP instance, NPStream* stream)
{
    return 0;
}

int32_t NPP_Write(NPP instance, NPStream* stream, int32_t offset, int32_t len, void* buffer)
{
    return 0;
}

void NPP_StreamAsFile(NPP instance, NPStream* stream, const char* fname)
{
}

void NPP_Print(NPP instance, NPPrint* platformPrint)
{
}

int16_t NPP_HandleEvent(NPP instance, void* event)
{
    PluginObject *obj = reinterpret_cast<PluginObject*>(instance->pdata);
    const ANPEvent* evt = reinterpret_cast<const ANPEvent*>(event);

    if(!obj->subPlugin) {
        gLogI.log(kError_ANPLogType, "the sub-plugin is null.");
        return 0; // unknown or unhandled event
    }
    else {
        return obj->subPlugin->handleEvent(evt);
    }
}

void NPP_URLNotify(NPP instance, const char* url, NPReason reason, void* notifyData)
{
}

EXPORT NPError NP_GetValue(NPP instance, NPPVariable variable, void *value) {

    if (variable == NPPVpluginNameString) {
        const char **str = (const char **)value;
        *str = "Browser Test Plugin";
        return NPERR_NO_ERROR;
    }

    if (variable == NPPVpluginDescriptionString) {
        const char **str = (const char **)value;
        *str = "Description of Browser Test Plugin";
        return NPERR_NO_ERROR;
    }

    return NPERR_GENERIC_ERROR;
}

NPError NPP_GetValue(NPP instance, NPPVariable variable, void *value)
{
    if (variable == NPPVpluginScriptableNPObject) {
        void **v = (void **)value;
        PluginObject *obj = (PluginObject*) instance->pdata;

        if (obj)
            browser->retainobject((NPObject*)obj);

        *v = obj;
        return NPERR_NO_ERROR;
    }

    return NPERR_GENERIC_ERROR;
}

NPError NPP_SetValue(NPP instance, NPNVariable variable, void *value)
{
    return NPERR_GENERIC_ERROR;
}

