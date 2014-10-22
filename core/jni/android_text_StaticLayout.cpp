/*
 * Copyright (C) 2014 The Android Open Source Project
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

#define LOG_TAG "StaticLayout"

#include "ScopedIcuLocale.h"
#include "unicode/locid.h"
#include "unicode/brkiter.h"
#include "utils/misc.h"
#include "utils/Log.h"
#include "ScopedPrimitiveArray.h"
#include "JNIHelp.h"
#include <android_runtime/AndroidRuntime.h>
#include <vector>

namespace android {

class ScopedBreakIterator {
    public:
        ScopedBreakIterator(JNIEnv* env, BreakIterator* breakIterator, jcharArray inputText,
                            jint length) : mBreakIterator(breakIterator), mChars(env, inputText) {
            UErrorCode status = U_ZERO_ERROR;
            mUText = utext_openUChars(NULL, mChars.get(), length, &status);
            if (mUText == NULL) {
                return;
            }

            mBreakIterator->setText(mUText, status);
        }

        inline BreakIterator* operator->() {
            return mBreakIterator;
        }

        ~ScopedBreakIterator() {
            utext_close(mUText);
            delete mBreakIterator;
        }
    private:
        BreakIterator* mBreakIterator;
        ScopedCharArrayRO mChars;
        UText* mUText;

        // disable copying and assignment
        ScopedBreakIterator(const ScopedBreakIterator&);
        void operator=(const ScopedBreakIterator&);
};

static jintArray nLineBreakOpportunities(JNIEnv* env, jclass, jstring javaLocaleName,
                                        jcharArray inputText, jint length,
                                        jintArray recycle) {
    jintArray ret;
    std::vector<jint> breaks;

    ScopedIcuLocale icuLocale(env, javaLocaleName);
    if (icuLocale.valid()) {
        UErrorCode status = U_ZERO_ERROR;
        BreakIterator* it = BreakIterator::createLineInstance(icuLocale.locale(), status);
        if (!U_SUCCESS(status) || it == NULL) {
            if (it) {
                delete it;
            }
        } else {
            ScopedBreakIterator breakIterator(env, it, inputText, length);
            for (int loc = breakIterator->first(); loc != BreakIterator::DONE;
                    loc = breakIterator->next()) {
                breaks.push_back(loc);
            }
        }
    }

    breaks.push_back(-1); // sentinel terminal value

    if (recycle != NULL && static_cast<size_t>(env->GetArrayLength(recycle)) >= breaks.size()) {
        ret = recycle;
    } else {
        ret = env->NewIntArray(breaks.size());
    }

    if (ret != NULL) {
        env->SetIntArrayRegion(ret, 0, breaks.size(), &breaks.front());
    }

    return ret;
}

static JNINativeMethod gMethods[] = {
    {"nLineBreakOpportunities", "(Ljava/lang/String;[CI[I)[I", (void*) nLineBreakOpportunities}
};

int register_android_text_StaticLayout(JNIEnv* env)
{
    return AndroidRuntime::registerNativeMethods(env, "android/text/StaticLayout",
            gMethods, NELEM(gMethods));
}

}
