/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include <string>

#include "jni/jni_util.h"

#include "base/logging.h"

#if 0
JavaObject::JavaObject()
    : object_(JNI_NULL),
      ref_count_(new int(0)) {
}

JavaObject::JavaObject(const JavaObject& java_obj)
    : object_(java_obj.object_),
      ref_count_(java_obj.ref_count_) {
  Retain();
}

JavaObject::JavaObject(jobject object, JNIEnv* env)
    : object_(NULL),
      ref_count_(new int(0)) {
  Retain();
  object_ = env->NewGlobalRef(object_);
}

JavaObject::~JavaObject() {
  Release();
}

JavaObject& JavaObject::operator=(const JavaObject& java_obj) {
  Release();
  object_ = java_obj.object_;
  ref_count_ = java_obj.ref_count_;
  Retain();
  return *this;
}

void JavaObject::Retain() {
  if (ref_count_)
    ++(*ref_count_);
  else
    ALOGE("JavaObject: Reference count is NULL! JavaObject may be corrupted.");
}

void JavaObject::Release() {
  if (ref_count_) {
    if (*ref_count_ > 0)
      --(*ref_count_);
    if (*ref_count_ == 0) {
      JNIEnv* env = GetCurrentJNIEnv();
      if (!env)
        ALOGE("JavaObject: Releasing outside of Java thread. Will just leak!");
      else if (object_)
        env->DeleteGlobalRef(object_);
      delete ref_count_;
      ref_count_ = NULL;
    }
  } else {
    ALOGE("JavaObject: Reference count is NULL! JavaObject may be corrupted.");
  }
}

void JavaObject::Reset() {
  Release();
  object_ = NULL;
  ref_count_ = new int(0);
}

JavaVM* GetCurrentJavaVM() {
  return g_current_java_vm_;
}

JNIEnv* GetCurrentJNIEnv() {
  JavaVM* vm = GetCurrentJavaVM();
  JNIEnv* env = NULL;
  const jint result = vm->GetEnv(reinterpret_cast<void**>(&env),
                                 JNI_VERSION_1_4);
  return result == JNI_OK ? env : NULL;
}
#endif

jstring ToJString(JNIEnv* env, const std::string& value) {
  return env->NewStringUTF(value.c_str());
}

std::string ToCppString(JNIEnv* env, jstring value) {
  jboolean isCopy;
  const char* c_value = env->GetStringUTFChars(value, &isCopy);
  std::string result(c_value);
  if (isCopy == JNI_TRUE)
    env->ReleaseStringUTFChars(value, c_value);
  return result;
}

jboolean ToJBool(bool value) {
  return value ? JNI_TRUE : JNI_FALSE;
}

bool ToCppBool(jboolean value) {
  return value == JNI_TRUE;
}

// TODO: We actually shouldn't use such a function as it requires a class name lookup at every
// invocation. Instead, store the class objects and use those.
bool IsJavaInstanceOf(JNIEnv* env, jobject object, const std::string& class_name) {
  jclass clazz = env->FindClass(class_name.c_str());
  return clazz ? env->IsInstanceOf(object, clazz) == JNI_TRUE : false;
}

template<typename T>
jobject CreateJObject(JNIEnv* env, const std::string& class_name, const std::string& signature, T value) {
  jobject result = JNI_NULL;

  return result;
}

Value ToCValue(JNIEnv* env, jobject object) {
  Value result = MakeNullValue();
  if (object != NULL) {
    if (IsJavaInstanceOf(env, object, "java/lang/Boolean")) {
      jmethodID method = env->GetMethodID(env->GetObjectClass(object), "booleanValue", "()Z");
      result = MakeIntValue(env->CallBooleanMethod(object, method) == JNI_TRUE ? 1 : 0);
    } else if (IsJavaInstanceOf(env, object, "java/lang/Integer")) {
      jmethodID method = env->GetMethodID(env->GetObjectClass(object), "intValue", "()I");
      result = MakeIntValue(env->CallIntMethod(object, method));
    } else if (IsJavaInstanceOf(env, object, "java/lang/Float")) {
      jmethodID method = env->GetMethodID(env->GetObjectClass(object), "floatValue", "()F");
      result = MakeFloatValue(env->CallFloatMethod(object, method));
    } else if (IsJavaInstanceOf(env, object, "java/lang/String")) {
      result = MakeStringValue(ToCppString(env, static_cast<jstring>(object)).c_str());
    } else if (IsJavaInstanceOf(env, object, "[I")) {
      jint* elems = env->GetIntArrayElements(static_cast<jintArray>(object), NULL);
      const jint count = env->GetArrayLength(static_cast<jintArray>(object));
      result = MakeIntArrayValue(elems, count);
      env->ReleaseIntArrayElements(static_cast<jintArray>(object), elems, JNI_ABORT);
    } else if (IsJavaInstanceOf(env, object, "[F")) {
      jfloat* elems = env->GetFloatArrayElements(static_cast<jfloatArray>(object), NULL);
      const jint count = env->GetArrayLength(static_cast<jfloatArray>(object));
      result = MakeFloatArrayValue(elems, count);
      env->ReleaseFloatArrayElements(static_cast<jfloatArray>(object), elems, JNI_ABORT);
    }
  }
  return result;
}

jobject ToJObject(JNIEnv* env, const Value& value) {
  jobject result = JNI_NULL;
  if (ValueIsInt(value)) {
    jclass clazz = env->FindClass("java/lang/Integer");
    jmethodID constructorID = env->GetMethodID(clazz, "<init>", "(I)V");
    result = env->NewObject(clazz, constructorID, GetIntValue(value));
  } else if (ValueIsFloat(value)) {
    jclass clazz = env->FindClass("java/lang/Float");
    jmethodID constructorID = env->GetMethodID(clazz, "<init>", "(F)V");
    result = env->NewObject(clazz, constructorID, GetFloatValue(value));
  } else if (ValueIsString(value)) {
    result = ToJString(env, GetStringValue(value));
  } else if (ValueIsIntArray(value)) {
    result = env->NewIntArray(GetValueCount(value));
    env->SetIntArrayRegion(static_cast<jintArray>(result),
                           0,
                           GetValueCount(value),
                           reinterpret_cast<const jint*>(GetIntArrayValue(value)));
  } else if (ValueIsFloatArray(value)) {
    result = env->NewFloatArray(GetValueCount(value));
    env->SetFloatArrayRegion(static_cast<jfloatArray>(result),
                             0,
                             GetValueCount(value),
                             reinterpret_cast<const jfloat*>(GetFloatArrayValue(value)));
  }
  return result;
}
