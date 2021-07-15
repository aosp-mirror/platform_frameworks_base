/*
 * Copyright (C) 2013 The Android Open Source Project
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

#define ATRACE_TAG ATRACE_TAG_VIEW
#include <gui/TraceUtils.h>
#include <hwui/Typeface.h>
#include <minikin/FontCollection.h>
#include <minikin/FontFamily.h>
#include <minikin/FontFileParser.h>
#include <minikin/SystemFonts.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>
#include "FontUtils.h"
#include "GraphicsJNI.h"
#include "SkData.h"
#include "SkTypeface.h"
#include "fonts/Font.h"

#include <mutex>
#include <unordered_map>

#ifdef __ANDROID__
#include <sys/stat.h>
#endif

using namespace android;

static inline Typeface* toTypeface(jlong ptr) {
    return reinterpret_cast<Typeface*>(ptr);
}

template<typename Ptr> static inline jlong toJLong(Ptr ptr) {
    return reinterpret_cast<jlong>(ptr);
}

static jlong Typeface_createFromTypeface(JNIEnv* env, jobject, jlong familyHandle, jint style) {
    Typeface* family = toTypeface(familyHandle);
    Typeface* face = Typeface::createRelative(family, (Typeface::Style)style);
    // TODO: the following logic shouldn't be necessary, the above should always succeed.
    // Try to find the closest matching font, using the standard heuristic
    if (NULL == face) {
        face = Typeface::createRelative(family, (Typeface::Style)(style ^ Typeface::kItalic));
    }
    for (int i = 0; NULL == face && i < 4; i++) {
        face = Typeface::createRelative(family, (Typeface::Style)i);
    }
    return toJLong(face);
}

static jlong Typeface_createFromTypefaceWithExactStyle(JNIEnv* env, jobject, jlong nativeInstance,
        jint weight, jboolean italic) {
    return toJLong(Typeface::createAbsolute(toTypeface(nativeInstance), weight, italic));
}

static jlong Typeface_createFromTypefaceWithVariation(JNIEnv* env, jobject, jlong familyHandle,
        jobject listOfAxis) {
    std::vector<minikin::FontVariation> variations;
    ListHelper list(env, listOfAxis);
    for (jint i = 0; i < list.size(); i++) {
        jobject axisObject = list.get(i);
        if (axisObject == nullptr) {
            continue;
        }
        AxisHelper axis(env, axisObject);
        variations.push_back(minikin::FontVariation(axis.getTag(), axis.getStyleValue()));
    }
    return toJLong(Typeface::createFromTypefaceWithVariation(toTypeface(familyHandle), variations));
}

static jlong Typeface_createWeightAlias(JNIEnv* env, jobject, jlong familyHandle, jint weight) {
    return toJLong(Typeface::createWithDifferentBaseWeight(toTypeface(familyHandle), weight));
}

static void releaseFunc(jlong ptr) {
    delete toTypeface(ptr);
}

// CriticalNative
static jlong Typeface_getReleaseFunc(CRITICAL_JNI_PARAMS) {
    return toJLong(&releaseFunc);
}

// CriticalNative
static jint Typeface_getStyle(CRITICAL_JNI_PARAMS_COMMA jlong faceHandle) {
    return toTypeface(faceHandle)->fAPIStyle;
}

// CriticalNative
static jint Typeface_getWeight(CRITICAL_JNI_PARAMS_COMMA jlong faceHandle) {
    return toTypeface(faceHandle)->fStyle.weight();
}

static jlong Typeface_createFromArray(JNIEnv *env, jobject, jlongArray familyArray,
                                      jlong fallbackPtr, int weight, int italic) {
    ScopedLongArrayRO families(env, familyArray);
    std::vector<std::shared_ptr<minikin::FontFamily>> familyVec;
    Typeface* typeface = (fallbackPtr == 0) ? nullptr : toTypeface(fallbackPtr);
    if (typeface != nullptr) {
        const std::vector<std::shared_ptr<minikin::FontFamily>>& fallbackFamilies =
            toTypeface(fallbackPtr)->fFontCollection->getFamilies();
        familyVec.reserve(families.size() + fallbackFamilies.size());
        for (size_t i = 0; i < families.size(); i++) {
            FontFamilyWrapper* family = reinterpret_cast<FontFamilyWrapper*>(families[i]);
            familyVec.emplace_back(family->family);
        }
        for (size_t i = 0; i < fallbackFamilies.size(); i++) {
            familyVec.emplace_back(fallbackFamilies[i]);
        }
    } else {
        familyVec.reserve(families.size());
        for (size_t i = 0; i < families.size(); i++) {
            FontFamilyWrapper* family = reinterpret_cast<FontFamilyWrapper*>(families[i]);
            familyVec.emplace_back(family->family);
        }
    }
    return toJLong(Typeface::createFromFamilies(std::move(familyVec), weight, italic));
}

// CriticalNative
static void Typeface_setDefault(CRITICAL_JNI_PARAMS_COMMA jlong faceHandle) {
    Typeface::setDefault(toTypeface(faceHandle));
    minikin::SystemFonts::registerDefault(toTypeface(faceHandle)->fFontCollection);
}

static jobject Typeface_getSupportedAxes(JNIEnv *env, jobject, jlong faceHandle) {
    Typeface* face = toTypeface(faceHandle);
    const std::unordered_set<minikin::AxisTag>& tagSet = face->fFontCollection->getSupportedTags();
    const size_t length = tagSet.size();
    if (length == 0) {
        return nullptr;
    }
    std::vector<jint> tagVec(length);
    int index = 0;
    for (const auto& tag : tagSet) {
        tagVec[index++] = tag;
    }
    std::sort(tagVec.begin(), tagVec.end());
    const jintArray result = env->NewIntArray(length);
    env->SetIntArrayRegion(result, 0, length, tagVec.data());
    return result;
}

static void Typeface_registerGenericFamily(JNIEnv *env, jobject, jstring familyName, jlong ptr) {
    ScopedUtfChars familyNameChars(env, familyName);
    minikin::SystemFonts::registerFallback(familyNameChars.c_str(),
                                           toTypeface(ptr)->fFontCollection);
}

#ifdef __ANDROID__

static bool getVerity(const std::string& path) {
    struct statx out = {};
    if (statx(AT_FDCWD, path.c_str(), 0 /* flags */, STATX_ALL, &out) != 0) {
        ALOGE("statx failed for %s, errno = %d", path.c_str(), errno);
        return false;
    }

    // Validity check.
    if ((out.stx_attributes_mask & STATX_ATTR_VERITY) == 0) {
        // STATX_ATTR_VERITY not supported by kernel.
        return false;
    }

    return (out.stx_attributes & STATX_ATTR_VERITY) != 0;
}

#else

static bool getVerity(const std::string&) {
    // verity check is not enabled on desktop.
    return false;
}

#endif  // __ANDROID__

static sk_sp<SkData> makeSkDataCached(const std::string& path, bool hasVerity) {
    // We don't clear cache as Typeface objects created by Typeface_readTypefaces() will be stored
    // in a static field and will not be garbage collected.
    static std::unordered_map<std::string, sk_sp<SkData>> cache;
    static std::mutex mutex;
    ALOG_ASSERT(!path.empty());
    if (hasVerity && !getVerity(path)) {
        LOG_ALWAYS_FATAL("verity bit was removed from %s", path.c_str());
        return nullptr;
    }
    std::lock_guard lock{mutex};
    sk_sp<SkData>& entry = cache[path];
    if (entry.get() == nullptr) {
        entry = SkData::MakeFromFileName(path.c_str());
    }
    return entry;
}

static std::shared_ptr<minikin::MinikinFont> loadMinikinFontSkia(minikin::BufferReader);

static minikin::Font::TypefaceLoader* readMinikinFontSkia(minikin::BufferReader* reader) {
    // Advance reader's position.
    reader->skipString(); // fontPath
    reader->skip<int>(); // fontIndex
    reader->skipArray<minikin::FontVariation>(); // axesPtr, axesCount
    bool hasVerity = static_cast<bool>(reader->read<int8_t>());
    if (hasVerity) {
        reader->skip<uint32_t>(); // expectedFontRevision
        reader->skipString(); // expectedPostScriptName
    }
    return &loadMinikinFontSkia;
}

static std::shared_ptr<minikin::MinikinFont> loadMinikinFontSkia(minikin::BufferReader reader) {
    std::string_view fontPath = reader.readString();
    std::string path(fontPath.data(), fontPath.size());
    ATRACE_FORMAT("Loading font %s", path.c_str());
    int fontIndex = reader.read<int>();
    const minikin::FontVariation* axesPtr;
    uint32_t axesCount;
    std::tie(axesPtr, axesCount) = reader.readArray<minikin::FontVariation>();
    bool hasVerity = static_cast<bool>(reader.read<int8_t>());
    uint32_t expectedFontRevision;
    std::string_view expectedPostScriptName;
    if (hasVerity) {
        expectedFontRevision = reader.read<uint32_t>();
        expectedPostScriptName = reader.readString();
    }
    sk_sp<SkData> data = makeSkDataCached(path, hasVerity);
    if (data.get() == nullptr) {
        // This may happen if:
        // 1. When the process failed to open the file (e.g. invalid path or permission).
        // 2. When the process failed to map the file (e.g. hitting max_map_count limit).
        ALOGE("Failed to make SkData from file name: %s", path.c_str());
        return nullptr;
    }
    const void* fontPtr = data->data();
    size_t fontSize = data->size();
    if (hasVerity) {
        // Verify font metadata if verity is enabled.
        minikin::FontFileParser parser(fontPtr, fontSize, fontIndex);
        std::optional<uint32_t> revision = parser.getFontRevision();
        if (!revision.has_value() || revision.value() != expectedFontRevision) {
            LOG_ALWAYS_FATAL("Wrong font revision: %s", path.c_str());
            return nullptr;
        }
        std::optional<std::string> psName = parser.getPostScriptName();
        if (!psName.has_value() || psName.value() != expectedPostScriptName) {
            LOG_ALWAYS_FATAL("Wrong PostScript name: %s", path.c_str());
            return nullptr;
        }
    }
    std::vector<minikin::FontVariation> axes(axesPtr, axesPtr + axesCount);
    std::shared_ptr<minikin::MinikinFont> minikinFont = fonts::createMinikinFontSkia(
            std::move(data), fontPath, fontPtr, fontSize, fontIndex, axes);
    if (minikinFont == nullptr) {
        ALOGE("Failed to create MinikinFontSkia: %s", path.c_str());
        return nullptr;
    }
    return minikinFont;
}

static void writeMinikinFontSkia(minikin::BufferWriter* writer,
        const minikin::MinikinFont* typeface) {
    // When you change the format of font metadata, please update code to parse
    // typefaceMetadataReader() in
    // frameworks/base/libs/hwui/jni/fonts/Font.cpp too.
    const std::string& path = typeface->GetFontPath();
    writer->writeString(path);
    writer->write<int>(typeface->GetFontIndex());
    const std::vector<minikin::FontVariation>& axes = typeface->GetAxes();
    writer->writeArray<minikin::FontVariation>(axes.data(), axes.size());
    bool hasVerity = getVerity(path);
    writer->write<int8_t>(static_cast<int8_t>(hasVerity));
    if (hasVerity) {
        // Write font metadata for verification only when verity is enabled.
        minikin::FontFileParser parser(typeface->GetFontData(), typeface->GetFontSize(),
                                       typeface->GetFontIndex());
        std::optional<uint32_t> revision = parser.getFontRevision();
        LOG_ALWAYS_FATAL_IF(!revision.has_value());
        writer->write<uint32_t>(revision.value());
        std::optional<std::string> psName = parser.getPostScriptName();
        LOG_ALWAYS_FATAL_IF(!psName.has_value());
        writer->writeString(psName.value());
    }
}

static jint Typeface_writeTypefaces(JNIEnv *env, jobject, jobject buffer, jlongArray faceHandles) {
    ScopedLongArrayRO faces(env, faceHandles);
    std::vector<Typeface*> typefaces;
    typefaces.reserve(faces.size());
    for (size_t i = 0; i < faces.size(); i++) {
        typefaces.push_back(toTypeface(faces[i]));
    }
    void* addr = buffer == nullptr ? nullptr : env->GetDirectBufferAddress(buffer);
    minikin::BufferWriter writer(addr);
    std::vector<std::shared_ptr<minikin::FontCollection>> fontCollections;
    std::unordered_map<std::shared_ptr<minikin::FontCollection>, size_t> fcToIndex;
    for (Typeface* typeface : typefaces) {
        bool inserted = fcToIndex.emplace(typeface->fFontCollection, fontCollections.size()).second;
        if (inserted) {
            fontCollections.push_back(typeface->fFontCollection);
        }
    }
    minikin::FontCollection::writeVector<writeMinikinFontSkia>(&writer, fontCollections);
    writer.write<uint32_t>(typefaces.size());
    for (Typeface* typeface : typefaces) {
      writer.write<uint32_t>(fcToIndex.find(typeface->fFontCollection)->second);
      typeface->fStyle.writeTo(&writer);
      writer.write<Typeface::Style>(typeface->fAPIStyle);
      writer.write<int>(typeface->fBaseWeight);
    }
    return static_cast<jint>(writer.size());
}

static jlongArray Typeface_readTypefaces(JNIEnv *env, jobject, jobject buffer) {
    void* addr = buffer == nullptr ? nullptr : env->GetDirectBufferAddress(buffer);
    if (addr == nullptr) return nullptr;
    minikin::BufferReader reader(addr);
    std::vector<std::shared_ptr<minikin::FontCollection>> fontCollections =
            minikin::FontCollection::readVector<readMinikinFontSkia>(&reader);
    uint32_t typefaceCount = reader.read<uint32_t>();
    std::vector<jlong> faceHandles;
    faceHandles.reserve(typefaceCount);
    for (uint32_t i = 0; i < typefaceCount; i++) {
        Typeface* typeface = new Typeface;
        typeface->fFontCollection = fontCollections[reader.read<uint32_t>()];
        typeface->fStyle = minikin::FontStyle(&reader);
        typeface->fAPIStyle = reader.read<Typeface::Style>();
        typeface->fBaseWeight = reader.read<int>();
        faceHandles.push_back(toJLong(typeface));
    }
    const jlongArray result = env->NewLongArray(typefaceCount);
    env->SetLongArrayRegion(result, 0, typefaceCount, faceHandles.data());
    return result;
}


static void Typeface_forceSetStaticFinalField(JNIEnv *env, jclass cls, jstring fieldName,
        jobject typeface) {
    ScopedUtfChars fieldNameChars(env, fieldName);
    jfieldID fid =
            env->GetStaticFieldID(cls, fieldNameChars.c_str(), "Landroid/graphics/Typeface;");
    if (fid == 0) {
        jniThrowRuntimeException(env, "Unable to find field");
        return;
    }
    env->SetStaticObjectField(cls, fid, typeface);
}

// Critical Native
static jint Typeface_getFamilySize(CRITICAL_JNI_PARAMS_COMMA jlong faceHandle) {
    return toTypeface(faceHandle)->fFontCollection->getFamilies().size();
}

// Critical Native
static jlong Typeface_getFamily(CRITICAL_JNI_PARAMS_COMMA jlong faceHandle, jint index) {
    std::shared_ptr<minikin::FontFamily> family =
            toTypeface(faceHandle)->fFontCollection->getFamilies()[index];
    return reinterpret_cast<jlong>(new FontFamilyWrapper(std::move(family)));
}

// Regular JNI
static void Typeface_warmUpCache(JNIEnv* env, jobject, jstring jFilePath) {
    ScopedUtfChars filePath(env, jFilePath);
    makeSkDataCached(filePath.c_str(), false /* fs verity */);
}

// Critical Native
static void Typeface_addFontCollection(CRITICAL_JNI_PARAMS_COMMA jlong faceHandle) {
    std::shared_ptr<minikin::FontCollection> collection = toTypeface(faceHandle)->fFontCollection;
    minikin::SystemFonts::addFontMap(std::move(collection));
}

///////////////////////////////////////////////////////////////////////////////

static const JNINativeMethod gTypefaceMethods[] = {
        {"nativeCreateFromTypeface", "(JI)J", (void*)Typeface_createFromTypeface},
        {"nativeCreateFromTypefaceWithExactStyle", "(JIZ)J",
         (void*)Typeface_createFromTypefaceWithExactStyle},
        {"nativeCreateFromTypefaceWithVariation", "(JLjava/util/List;)J",
         (void*)Typeface_createFromTypefaceWithVariation},
        {"nativeCreateWeightAlias", "(JI)J", (void*)Typeface_createWeightAlias},
        {"nativeGetReleaseFunc", "()J", (void*)Typeface_getReleaseFunc},
        {"nativeGetStyle", "(J)I", (void*)Typeface_getStyle},
        {"nativeGetWeight", "(J)I", (void*)Typeface_getWeight},
        {"nativeCreateFromArray", "([JJII)J", (void*)Typeface_createFromArray},
        {"nativeSetDefault", "(J)V", (void*)Typeface_setDefault},
        {"nativeGetSupportedAxes", "(J)[I", (void*)Typeface_getSupportedAxes},
        {"nativeRegisterGenericFamily", "(Ljava/lang/String;J)V",
         (void*)Typeface_registerGenericFamily},
        {"nativeWriteTypefaces", "(Ljava/nio/ByteBuffer;[J)I", (void*)Typeface_writeTypefaces},
        {"nativeReadTypefaces", "(Ljava/nio/ByteBuffer;)[J", (void*)Typeface_readTypefaces},
        {"nativeForceSetStaticFinalField", "(Ljava/lang/String;Landroid/graphics/Typeface;)V",
         (void*)Typeface_forceSetStaticFinalField},
        {"nativeGetFamilySize", "(J)I", (void*)Typeface_getFamilySize},
        {"nativeGetFamily", "(JI)J", (void*)Typeface_getFamily},
        {"nativeWarmUpCache", "(Ljava/lang/String;)V", (void*)Typeface_warmUpCache},
        {"nativeAddFontCollections", "(J)V", (void*)Typeface_addFontCollection},
};

int register_android_graphics_Typeface(JNIEnv* env)
{
    return RegisterMethodsOrDie(env, "android/graphics/Typeface", gTypefaceMethods,
                                NELEM(gTypefaceMethods));
}
