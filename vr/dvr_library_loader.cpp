#include <dlfcn.h>
#include <jni.h>

#include <string>

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_google_vr_platform_Dvr_nativeLoadLibrary(
    JNIEnv* env, jclass, jstring java_library) {
  if (!java_library)
    return 0;

  // Convert the Java String object to a C++ null-terminated string.
  const char* data = env->GetStringUTFChars(java_library, NULL);
  size_t size = env->GetStringUTFLength(java_library);
  std::string library(data, size);
  env->ReleaseStringUTFChars(java_library, data);

  // Return the handle to the requested library.
  return reinterpret_cast<jlong>(
      dlopen(library.c_str(), RTLD_NOW | RTLD_LOCAL));
}

}  // extern "C"
