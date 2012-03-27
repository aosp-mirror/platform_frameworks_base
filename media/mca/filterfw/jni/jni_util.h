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

#include <jni.h>

#include <hash_map>
#include <string>

#include "base/utilities.h"
#include "core/value.h"

#ifndef ANDROID_FILTERFW_JNI_JNI_UTIL_H
#define ANDROID_FILTERFW_JNI_JNI_UTIL_H

// We add this JNI_NULL macro to allow consistent code separation of Java and
// C++ types.
#define JNI_NULL NULL

#if 0
// Pointer to current JavaVM. Do not use this directly. Instead use the funciton
// GetCurrentJavaVM().
extern JavaVM* g_current_java_vm_;

// Wrapper around a java object pointer, which includes the environment
// pointer in which the object "lives". This is used for passing down Java
// objects from the Java layer to C++.
// While an instance of this class does not own the underlying java object, it
// does hold a global reference to it, so that the Java garbage collector does
// not destroy it. It uses reference counting to determine when it can destroy
// the reference.
// TODO: Add multi-thread support!
class JavaObject {
  public:
    // Creates a NULL JavaObject.
    JavaObject();

    // Creates a wrapper around the given object in the given JNI environment.
    JavaObject(jobject object, JNIEnv* env);

    // Copy constructor.
    JavaObject(const JavaObject& java_obj);

    // Destructor.
    ~JavaObject();

    // Assignment operator.
    JavaObject& operator=(const JavaObject& java_obj);

    // Access to the object (non-const as JNI functions are non-const).
    jobject object() const {
      return object_;
    }

    // Resets this object to the NULL JavaObject.
    void Reset();

  private:
    // Retain the instance, i.e. increase reference count.
    void Retain();

    // Release the instance, i.e. decrease reference count.
    void Release();

    // The object pointer (not owned).
    jobject object_;

    // The reference count of this object
    int* ref_count_;
};
#endif

// ObjectPool template class. This class keeps track of C++ instances that are
// coupled to Java objects. This is done by using an "id" field in the Java
// object, which is then mapped to the correct instance here. It should not be
// necessary to use this class directly. Instead, the convenience functions
// below can be used.
template<class T>
class ObjectPool {
  public:
    // Create a new ObjectPool for a specific object type. Pass the path to the
    // Java equivalent class of the C++ class, and the name of the java member
    // field that will store the object's ID.
    static void Setup(const std::string& jclass_name,
                      const std::string& id_fld_name) {
      instance_ = new ObjectPool<T>(jclass_name, id_fld_name);
    }

    // Return the shared instance to this type's pool.
    static ObjectPool* Instance() {
      return instance_;
    }

    // Delete this type's pool.
    static void TearDown() {
      delete instance_;
    }

    // Register a new C++ object with the pool. This does not affect the Java
    // layer. Use WrapObject() instead to perform the necessary Java-side
    // assignments. Pass true to owns if the object pool owns the object.
    int RegisterObject(T* object, bool owns) {
      const int id = next_id_;
      objects_[id] = object;
      owns_[id] = owns;
      ++next_id_;
      return id;
    }

    // Return the object in the pool with the specified ID.
    T* ObjectWithID(int obj_id) const {
      typename CObjMap::const_iterator iter = objects_.find(obj_id);
      return iter == objects_.end() ? NULL : iter->second;
    }

    // Get the ID of a Java object. This ID can be used to look-up the C++
    // object.
    int GetObjectID(JNIEnv* env, jobject j_object) {
      jclass cls = env->GetObjectClass(j_object);
      jfieldID id_field = env->GetFieldID(cls, id_field_name_.c_str(), "I");
      const int result = env->GetIntField(j_object, id_field);
      env->DeleteLocalRef(cls);
      return result;
    }

    // Take a C++ object and wrap it with a given Java object. This will
    // essentially set the ID member of the Java object to the ID of the C++
    // object. Pass true to owns if the object pool owns the object.
    bool WrapObject(T* c_object, JNIEnv* env, jobject j_object, bool owns) {
      const int id = RegisterObject(c_object, owns);
      jclass cls = env->GetObjectClass(j_object);
      jfieldID id_field = env->GetFieldID(cls, id_field_name_.c_str(), "I");
      env->SetIntField(j_object, id_field, id);
      env->DeleteLocalRef(cls);
      return true;
    }

    // Remove the object with the given ID from this pool, and delete it. This
    // does not affect the Java layer.
    bool DeleteObjectWithID(int obj_id) {
      typename CObjMap::iterator iter = objects_.find(obj_id);
      const bool found = iter != objects_.end();
      if (found) {
        if (owns_[obj_id])
          delete iter->second;
        objects_.erase(iter);
      }
      return found;
    }

    // Instantiates a new java object for this class. The Java class must have
    // a default constructor for this to succeed.
    jobject CreateJavaObject(JNIEnv* env) {
      jclass cls = env->FindClass(jclass_name_.c_str());
      jmethodID constructor = env->GetMethodID(
        cls,
        "<init>",
        "(Landroid/filterfw/core/NativeAllocatorTag;)V");
      jobject result = env->NewObject(cls, constructor, JNI_NULL);
      env->DeleteLocalRef(cls);
      return result;
    }

    int GetObjectCount() const {
      return objects_.size();
    }

    const std::string& GetJavaClassName() const {
      return jclass_name_;
    }

  private:
    explicit ObjectPool(const std::string& jclass_name,
                        const std::string& id_fld_name)
      : jclass_name_(jclass_name),
        id_field_name_(id_fld_name),
        next_id_(0) { }

    typedef std::hash_map<int, T*>    CObjMap;
    typedef std::hash_map<int, bool>  FlagMap;
    static ObjectPool* instance_;
    std::string jclass_name_;
    std::string id_field_name_;
    int next_id_;
    CObjMap objects_;
    FlagMap owns_;

    DISALLOW_COPY_AND_ASSIGN(ObjectPool);
};

template<typename T> ObjectPool<T>* ObjectPool<T>::instance_ = NULL;

// Convenience Functions ///////////////////////////////////////////////////////

// This function "links" the C++ instance and the Java instance, so that they
// can be mapped to one another. This must be called for every C++ instance
// which is wrapped by a Java front-end interface. Pass true to owns, if the
// Java layer should own the object.
template<typename T>
bool WrapObjectInJava(T* c_object, JNIEnv* env, jobject j_object, bool owns) {
  ObjectPool<T>* pool = ObjectPool<T>::Instance();
  return pool ? pool->WrapObject(c_object, env, j_object, owns) : false;
}

// Creates a new Java instance, which wraps the passed C++ instance. Returns
// the wrapped object or JNI_NULL if there was an error. Pass true to owns, if
// the Java layer should own the object.
template<typename T>
jobject WrapNewObjectInJava(T* c_object, JNIEnv* env, bool owns) {
  ObjectPool<T>* pool = ObjectPool<T>::Instance();
  if (pool) {
    jobject result = pool->CreateJavaObject(env);
    if (WrapObjectInJava(c_object, env, result, owns))
      return result;
  }
  return JNI_NULL;
}

// Use ConvertFromJava to obtain a C++ instance given a Java object. This
// instance must have been wrapped in Java using the WrapObjectInJava()
// function.
template<typename T>
T* ConvertFromJava(JNIEnv* env, jobject j_object) {
  ObjectPool<T>* pool = ObjectPool<T>::Instance();
  return pool && j_object
    ? pool->ObjectWithID(pool->GetObjectID(env, j_object))
    : NULL;
}

// Delete the native object given a Java instance. This should be called from
// the Java object's finalizer.
template<typename T>
bool DeleteNativeObject(JNIEnv* env, jobject j_object) {
  ObjectPool<T>* pool = ObjectPool<T>::Instance();
  return pool && j_object
    ? pool->DeleteObjectWithID(pool->GetObjectID(env, j_object))
    : false;
}

#if 0
// Get the current JNI VM, or NULL if there is no current VM
JavaVM* GetCurrentJavaVM();

// Get the current JNI environment, or NULL if this is not a JNI thread
JNIEnv* GetCurrentJNIEnv();
#endif

// Convert C++ boolean to Java boolean.
jboolean ToJBool(bool value);

// Convert Java boolean to C++ boolean.
bool ToCppBool(jboolean value);

// Convert Java String to C++ string.
jstring ToJString(JNIEnv* env, const std::string& value);

// Convert C++ string to Java String.
std::string ToCppString(JNIEnv* env, jstring value);

// Convert Java object to a (C) Value object.
Value ToCValue(JNIEnv* env, jobject object);

// Convert a (C) Value object to a Java object.
jobject ToJObject(JNIEnv* env, const Value& value);

// Returns true, iff the passed object is an instance of the class specified
// by its fully qualified class name.
bool IsJavaInstanceOf(JNIEnv* env, jobject object,
                      const std::string& class_name);

#endif // ANDROID_FILTERFW_JNI_JNI_UTIL_H
