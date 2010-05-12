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

#include "EventPlugin.h"
#include "android_npapi.h"

#include <stdio.h>
#include <sys/time.h>
#include <time.h>
#include <math.h>
#include <string.h>

extern NPNetscapeFuncs*        browser;
extern ANPCanvasInterfaceV0    gCanvasI;
extern ANPLogInterfaceV0       gLogI;
extern ANPPaintInterfaceV0     gPaintI;
extern ANPTypefaceInterfaceV0  gTypefaceI;

///////////////////////////////////////////////////////////////////////////////

EventPlugin::EventPlugin(NPP inst) : SubPlugin(inst) { }

EventPlugin::~EventPlugin() { }

void EventPlugin::drawPlugin(const ANPBitmap& bitmap, const ANPRectI& clip) {

    gLogI.log(kDebug_ANPLogType, " ------ %p drawing the plugin (%d,%d)",
              inst(), bitmap.width, bitmap.height);

    // get the plugin's dimensions according to the DOM
    PluginObject *obj = (PluginObject*) inst()->pdata;
    const int W = obj->window->width;
    const int H = obj->window->height;

    // compute the current zoom level
    const float zoomFactorW = static_cast<float>(bitmap.width) / W;
    const float zoomFactorH = static_cast<float>(bitmap.height) / H;

    // check to make sure the zoom level is uniform
    if (zoomFactorW + .01 < zoomFactorH && zoomFactorW - .01 > zoomFactorH)
        gLogI.log(kError_ANPLogType, " ------ %p zoom is out of sync (%f,%f)",
                  inst(), zoomFactorW, zoomFactorH);

    // scale the variables based on the zoom level
    const int fontSize = (int)(zoomFactorW * 16);
    const int leftMargin = (int)(zoomFactorW * 10);

    // create and clip a canvas
    ANPCanvas* canvas = gCanvasI.newCanvas(&bitmap);

    ANPRectF clipR;
    clipR.left = clip.left;
    clipR.top = clip.top;
    clipR.right = clip.right;
    clipR.bottom = clip.bottom;
    gCanvasI.clipRect(canvas, &clipR);

    gCanvasI.drawColor(canvas, 0xFFFFFFFF);

    // configure the paint
    ANPPaint* paint = gPaintI.newPaint();
    gPaintI.setFlags(paint, gPaintI.getFlags(paint) | kAntiAlias_ANPPaintFlag);
    gPaintI.setColor(paint, 0xFF0000FF);
    gPaintI.setTextSize(paint, fontSize);

    // configure the font
    ANPTypeface* tf = gTypefaceI.createFromName("serif", kItalic_ANPTypefaceStyle);
    gPaintI.setTypeface(paint, tf);
    gTypefaceI.unref(tf);

    // retrieve the font metrics
    ANPFontMetrics fm;
    gPaintI.getFontMetrics(paint, &fm);

    // write text on the canvas
    const char c[] = "Browser Test Plugin";
    gCanvasI.drawText(canvas, c, sizeof(c)-1, leftMargin, -fm.fTop, paint);

    // clean up variables
    gPaintI.deletePaint(paint);
    gCanvasI.deleteCanvas(canvas);
}

void EventPlugin::printToDiv(const char* text, int length) {
    // Get the plugin's DOM object
    NPObject* windowObject = NULL;
    browser->getvalue(inst(), NPNVWindowNPObject, &windowObject);

    if (!windowObject)
        gLogI.log(kError_ANPLogType, " ------ %p Unable to retrieve DOM Window", inst());

    // create a string (JS code) that is stored in memory allocated by the browser
    const char* jsBegin = "var outputDiv = document.getElementById('eventOutput'); outputDiv.innerHTML += ' ";
    const char* jsEnd = "';";

    // allocate memory and configure pointers
    int totalLength = strlen(jsBegin) + length + strlen(jsEnd);
    char* beginMem = (char*)browser->memalloc(totalLength);
    char* middleMem = beginMem + strlen(jsBegin);
    char* endMem = middleMem + length;

    // copy into the allocated memory
    memcpy(beginMem, jsBegin, strlen(jsBegin));
    memcpy(middleMem, text, length);
    memcpy(endMem, jsEnd, strlen(jsEnd));

    gLogI.log(kDebug_ANPLogType, "text: %.*s\n", totalLength, (char*)beginMem);

    // execute the javascript in the plugin's DOM object
    NPString script = { (char*)beginMem, totalLength };
    NPVariant scriptVariant;
    if (!browser->evaluate(inst(), windowObject, &script, &scriptVariant))
        gLogI.log(kError_ANPLogType, " ------ %p Unable to eval the JS.", inst());

    // free the memory allocated within the browser
    browser->memfree(beginMem);
}

int16_t EventPlugin::handleEvent(const ANPEvent* evt) {
    switch (evt->eventType) {

        case kDraw_ANPEventType: {
            switch (evt->data.draw.model) {
                case kBitmap_ANPDrawingModel:
                    drawPlugin(evt->data.draw.data.bitmap, evt->data.draw.clip);
                    return 1;
                default:
                    break;   // unknown drawing model
            }
        }
        case kLifecycle_ANPEventType:
            switch (evt->data.lifecycle.action) {
                case kOnLoad_ANPLifecycleAction: {
                    char msg[] = "lifecycle-onLoad";
                    printToDiv(msg, strlen(msg));
                    break;
                }
                case kGainFocus_ANPLifecycleAction: {
                    char msg[] = "lifecycle-gainFocus";
                    printToDiv(msg, strlen(msg));
                    break;
                }
                case kLoseFocus_ANPLifecycleAction: {
                    char msg[] = "lifecycle-loseFocus";
                    printToDiv(msg, strlen(msg));
                    break;
                }
            }
            return 1;
        case kTouch_ANPEventType:
            gLogI.log(kError_ANPLogType, " ------ %p the plugin did not request touch events", inst());
            break;
        case kKey_ANPEventType:
            gLogI.log(kError_ANPLogType, " ------ %p the plugin did not request key events", inst());
            break;
        default:
            break;
    }
    return 0;   // unknown or unhandled event
}
