/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include <cutils/compiler.h>
#include <cutils/trace.h>
#include <jni.h>
#include <log/log.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/scoped_utf_chars.h>
#include <nativehelper/utils.h>
#include <tracing_sdk.h>

static constexpr ssize_t kMaxStrLen = 4096;
namespace android {
template <typename T>
inline static T* toPointer(jlong ptr) {
    return reinterpret_cast<T*>(static_cast<uintptr_t>(ptr));
}

template <typename T>
inline static jlong toJLong(T* ptr) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(ptr));
}

static jlong android_os_PerfettoTrackEventExtraArgInt64_init(JNIEnv* env, jclass, jstring name) {
    ScopedUtfChars name_chars = GET_UTF_OR_RETURN(env, name);
    return toJLong(new tracing_perfetto::DebugArg<int64_t>(name_chars.c_str()));
}

static jlong android_os_PerfettoTrackEventExtraArgBool_init(JNIEnv* env, jclass, jstring name) {
    ScopedUtfChars name_chars = GET_UTF_OR_RETURN(env, name);
    return toJLong(new tracing_perfetto::DebugArg<bool>(name_chars.c_str()));
}

static jlong android_os_PerfettoTrackEventExtraArgDouble_init(JNIEnv* env, jclass, jstring name) {
    ScopedUtfChars name_chars = GET_UTF_OR_RETURN(env, name);
    return toJLong(new tracing_perfetto::DebugArg<double>(name_chars.c_str()));
}

static jlong android_os_PerfettoTrackEventExtraArgString_init(JNIEnv* env, jclass, jstring name) {
    ScopedUtfChars name_chars = GET_UTF_OR_RETURN(env, name);
    return toJLong(new tracing_perfetto::DebugArg<const char*>(name_chars.c_str()));
}

static jlong android_os_PerfettoTrackEventExtraArgInt64_delete() {
    return toJLong(&tracing_perfetto::DebugArg<int64_t>::delete_arg);
}

static jlong android_os_PerfettoTrackEventExtraArgBool_delete() {
    return toJLong(&tracing_perfetto::DebugArg<bool>::delete_arg);
}

static jlong android_os_PerfettoTrackEventExtraArgDouble_delete() {
    return toJLong(&tracing_perfetto::DebugArg<double>::delete_arg);
}

static jlong android_os_PerfettoTrackEventExtraArgString_delete() {
    return toJLong(&tracing_perfetto::DebugArg<const char*>::delete_arg);
}

static jlong android_os_PerfettoTrackEventExtraArgInt64_get_extra_ptr(jlong ptr) {
    tracing_perfetto::DebugArg<int64_t>* arg = toPointer<tracing_perfetto::DebugArg<int64_t>>(ptr);
    return toJLong(arg->get());
}

static jlong android_os_PerfettoTrackEventExtraArgBool_get_extra_ptr(jlong ptr) {
    tracing_perfetto::DebugArg<bool>* arg = toPointer<tracing_perfetto::DebugArg<bool>>(ptr);
    return toJLong(arg->get());
}

static jlong android_os_PerfettoTrackEventExtraArgDouble_get_extra_ptr(jlong ptr) {
    tracing_perfetto::DebugArg<double>* arg = toPointer<tracing_perfetto::DebugArg<double>>(ptr);
    return toJLong(arg->get());
}

static jlong android_os_PerfettoTrackEventExtraArgString_get_extra_ptr(jlong ptr) {
    tracing_perfetto::DebugArg<const char*>* arg =
            toPointer<tracing_perfetto::DebugArg<const char*>>(ptr);
    return toJLong(arg->get());
}

static void android_os_PerfettoTrackEventExtraArgInt64_set_value(jlong ptr, jlong val) {
    tracing_perfetto::DebugArg<int64_t>* arg = toPointer<tracing_perfetto::DebugArg<int64_t>>(ptr);
    arg->set_value(val);
}

static void android_os_PerfettoTrackEventExtraArgBool_set_value(jlong ptr, jboolean val) {
    tracing_perfetto::DebugArg<bool>* arg = toPointer<tracing_perfetto::DebugArg<bool>>(ptr);
    arg->set_value(val);
}

static void android_os_PerfettoTrackEventExtraArgDouble_set_value(jlong ptr, jdouble val) {
    tracing_perfetto::DebugArg<double>* arg = toPointer<tracing_perfetto::DebugArg<double>>(ptr);
    arg->set_value(val);
}

static void android_os_PerfettoTrackEventExtraArgString_set_value(JNIEnv* env, jclass, jlong ptr,
                                                                  jstring val) {
    ScopedUtfChars val_chars = GET_UTF_OR_RETURN_VOID(env, val);

    tracing_perfetto::DebugArg<const char*>* arg =
            toPointer<tracing_perfetto::DebugArg<const char*>>(ptr);
    arg->set_value(strdup(val_chars.c_str()));
}

static jlong android_os_PerfettoTrackEventExtraFieldInt64_init() {
    return toJLong(new tracing_perfetto::ProtoField<int64_t>());
}

static jlong android_os_PerfettoTrackEventExtraFieldDouble_init() {
    return toJLong(new tracing_perfetto::ProtoField<double>());
}

static jlong android_os_PerfettoTrackEventExtraFieldString_init() {
    return toJLong(new tracing_perfetto::ProtoField<const char*>());
}

static jlong android_os_PerfettoTrackEventExtraFieldNested_init() {
    return toJLong(new tracing_perfetto::ProtoFieldNested());
}

static jlong android_os_PerfettoTrackEventExtraFieldInt64_delete() {
    return toJLong(&tracing_perfetto::ProtoField<int64_t>::delete_field);
}

static jlong android_os_PerfettoTrackEventExtraFieldDouble_delete() {
    return toJLong(&tracing_perfetto::ProtoField<double>::delete_field);
}

static jlong android_os_PerfettoTrackEventExtraFieldString_delete() {
    return toJLong(&tracing_perfetto::ProtoField<const char*>::delete_field);
}

static jlong android_os_PerfettoTrackEventExtraFieldNested_delete() {
    return toJLong(&tracing_perfetto::ProtoFieldNested::delete_field);
}

static jlong android_os_PerfettoTrackEventExtraFieldInt64_get_extra_ptr(jlong ptr) {
    tracing_perfetto::ProtoField<int64_t>* field =
            toPointer<tracing_perfetto::ProtoField<int64_t>>(ptr);
    return toJLong(field->get());
}

static jlong android_os_PerfettoTrackEventExtraFieldDouble_get_extra_ptr(jlong ptr) {
    tracing_perfetto::ProtoField<double>* field =
            toPointer<tracing_perfetto::ProtoField<double>>(ptr);
    return toJLong(field->get());
}

static jlong android_os_PerfettoTrackEventExtraFieldString_get_extra_ptr(jlong ptr) {
    tracing_perfetto::ProtoField<const char*>* field =
            toPointer<tracing_perfetto::ProtoField<const char*>>(ptr);
    return toJLong(field->get());
}

static jlong android_os_PerfettoTrackEventExtraFieldNested_get_extra_ptr(jlong ptr) {
    tracing_perfetto::ProtoFieldNested* field = toPointer<tracing_perfetto::ProtoFieldNested>(ptr);
    return toJLong(field->get());
}

static void android_os_PerfettoTrackEventExtraFieldInt64_set_value(jlong ptr, jlong id, jlong val) {
    tracing_perfetto::ProtoField<int64_t>* field =
            toPointer<tracing_perfetto::ProtoField<int64_t>>(ptr);
    field->set_value(id, val);
}

static void android_os_PerfettoTrackEventExtraFieldDouble_set_value(jlong ptr, jlong id,
                                                                    jdouble val) {
    tracing_perfetto::ProtoField<double>* field =
            toPointer<tracing_perfetto::ProtoField<double>>(ptr);
    field->set_value(id, val);
}

static void android_os_PerfettoTrackEventExtraFieldString_set_value(JNIEnv* env, jclass, jlong ptr,
                                                                    jlong id, jstring val) {
    ScopedUtfChars val_chars = GET_UTF_OR_RETURN_VOID(env, val);

    tracing_perfetto::ProtoField<const char*>* field =
            toPointer<tracing_perfetto::ProtoField<const char*>>(ptr);
    field->set_value(id, strdup(val_chars.c_str()));
}

static void android_os_PerfettoTrackEventExtraFieldNested_add_field(jlong field_ptr,
                                                                    jlong arg_ptr) {
    tracing_perfetto::ProtoFieldNested* field =
            toPointer<tracing_perfetto::ProtoFieldNested>(field_ptr);
    field->add_field(toPointer<PerfettoTeHlProtoField>(arg_ptr));
}

static void android_os_PerfettoTrackEventExtraFieldNested_set_id(jlong ptr, jlong id) {
    tracing_perfetto::ProtoFieldNested* field = toPointer<tracing_perfetto::ProtoFieldNested>(ptr);
    field->set_id(id);
}

static jlong android_os_PerfettoTrackEventExtraFlow_init() {
    return toJLong(new tracing_perfetto::Flow());
}

static void android_os_PerfettoTrackEventExtraFlow_set_process_flow(jlong ptr, jlong id) {
    tracing_perfetto::Flow* flow = toPointer<tracing_perfetto::Flow>(ptr);
    flow->set_process_flow(id);
}

static void android_os_PerfettoTrackEventExtraFlow_set_process_terminating_flow(jlong ptr,
                                                                                jlong id) {
    tracing_perfetto::Flow* flow = toPointer<tracing_perfetto::Flow>(ptr);
    flow->set_process_terminating_flow(id);
}

static jlong android_os_PerfettoTrackEventExtraFlow_delete() {
    return toJLong(&tracing_perfetto::Flow::delete_flow);
}

static jlong android_os_PerfettoTrackEventExtraFlow_get_extra_ptr(jlong ptr) {
    tracing_perfetto::Flow* flow = toPointer<tracing_perfetto::Flow>(ptr);
    return toJLong(flow->get());
}

static jlong android_os_PerfettoTrackEventExtraNamedTrack_init(JNIEnv* env, jclass, jlong id,
                                                               jstring name, jlong parent_uuid) {
    ScopedUtfChars name_chars = GET_UTF_OR_RETURN(env, name);
    return toJLong(new tracing_perfetto::NamedTrack(id, parent_uuid, name_chars.c_str()));
}

static jlong android_os_PerfettoTrackEventExtraNamedTrack_delete() {
    return toJLong(&tracing_perfetto::NamedTrack::delete_track);
}

static jlong android_os_PerfettoTrackEventExtraNamedTrack_get_extra_ptr(jlong ptr) {
    tracing_perfetto::NamedTrack* track = toPointer<tracing_perfetto::NamedTrack>(ptr);
    return toJLong(track->get());
}

static jlong android_os_PerfettoTrackEventExtraCounterTrack_init(JNIEnv* env, jclass, jstring name,
                                                                 jlong parent_uuid) {
    ScopedUtfChars name_chars = GET_UTF_OR_RETURN(env, name);

    return toJLong(new tracing_perfetto::RegisteredTrack(1, parent_uuid, name_chars.c_str(), true));
}

static jlong android_os_PerfettoTrackEventExtraCounterTrack_delete() {
    return toJLong(&tracing_perfetto::RegisteredTrack::delete_track);
}

static jlong android_os_PerfettoTrackEventExtraCounterTrack_get_extra_ptr(jlong ptr) {
    tracing_perfetto::RegisteredTrack* track = toPointer<tracing_perfetto::RegisteredTrack>(ptr);
    return toJLong(track->get());
}

static jlong android_os_PerfettoTrackEventExtraCounterInt64_init() {
    return toJLong(new tracing_perfetto::Counter<int64_t>());
}

static jlong android_os_PerfettoTrackEventExtraCounterInt64_delete() {
    return toJLong(&tracing_perfetto::Counter<int64_t>::delete_counter);
}

static void android_os_PerfettoTrackEventExtraCounterInt64_set_value(jlong ptr, jlong val) {
    tracing_perfetto::Counter<int64_t>* counter =
            toPointer<tracing_perfetto::Counter<int64_t>>(ptr);
    counter->set_value(val);
}

static jlong android_os_PerfettoTrackEventExtraCounterInt64_get_extra_ptr(jlong ptr) {
    tracing_perfetto::Counter<int64_t>* counter =
            toPointer<tracing_perfetto::Counter<int64_t>>(ptr);
    return toJLong(counter->get());
}

static jlong android_os_PerfettoTrackEventExtraCounterDouble_init() {
    return toJLong(new tracing_perfetto::Counter<double>());
}

static jlong android_os_PerfettoTrackEventExtraCounterDouble_delete() {
    return toJLong(&tracing_perfetto::Counter<double>::delete_counter);
}

static void android_os_PerfettoTrackEventExtraCounterDouble_set_value(jlong ptr, jdouble val) {
    tracing_perfetto::Counter<double>* counter = toPointer<tracing_perfetto::Counter<double>>(ptr);
    counter->set_value(val);
}

static jlong android_os_PerfettoTrackEventExtraCounterDouble_get_extra_ptr(jlong ptr) {
    tracing_perfetto::Counter<double>* counter = toPointer<tracing_perfetto::Counter<double>>(ptr);
    return toJLong(counter->get());
}

static jlong android_os_PerfettoTrackEventExtra_init() {
    return toJLong(new tracing_perfetto::Extra());
}

static jlong android_os_PerfettoTrackEventExtra_delete() {
    return toJLong(&tracing_perfetto::Extra::delete_extra);
}

static void android_os_PerfettoTrackEventExtra_add_arg(jlong extra_ptr, jlong arg_ptr) {
    tracing_perfetto::Extra* extra = toPointer<tracing_perfetto::Extra>(extra_ptr);
    extra->push_extra(toPointer<PerfettoTeHlExtra>(arg_ptr));
}

static void android_os_PerfettoTrackEventExtra_clear_args(jlong ptr) {
    tracing_perfetto::Extra* extra = toPointer<tracing_perfetto::Extra>(ptr);
    extra->clear_extras();
}

static void android_os_PerfettoTrackEventExtra_emit(JNIEnv* env, jclass, jint type, jlong cat_ptr,
                                                    jstring name, jlong extra_ptr) {
    ScopedUtfChars name_chars = GET_UTF_OR_RETURN_VOID(env, name);

    tracing_perfetto::Category* category = toPointer<tracing_perfetto::Category>(cat_ptr);
    tracing_perfetto::trace_event(type, category->get(), name_chars.c_str(),
                                  toPointer<tracing_perfetto::Extra>(extra_ptr));
}

static jlong android_os_PerfettoTrackEventExtraProto_init() {
    return toJLong(new tracing_perfetto::Proto());
}

static jlong android_os_PerfettoTrackEventExtraProto_delete() {
    return toJLong(&tracing_perfetto::Proto::delete_proto);
}

static jlong android_os_PerfettoTrackEventExtraProto_get_extra_ptr(jlong ptr) {
    tracing_perfetto::Proto* proto = toPointer<tracing_perfetto::Proto>(ptr);
    return toJLong(proto->get());
}

static void android_os_PerfettoTrackEventExtraProto_add_field(long proto_ptr, jlong arg_ptr) {
    tracing_perfetto::Proto* proto = toPointer<tracing_perfetto::Proto>(proto_ptr);
    proto->add_field(toPointer<PerfettoTeHlProtoField>(arg_ptr));
}

static void android_os_PerfettoTrackEventExtraProto_clear_fields(jlong ptr) {
    tracing_perfetto::Proto* proto = toPointer<tracing_perfetto::Proto>(ptr);
    proto->clear_fields();
}

static const JNINativeMethod gExtraMethods[] =
        {{"native_init", "()J", (void*)android_os_PerfettoTrackEventExtra_init},
         {"native_delete", "()J", (void*)android_os_PerfettoTrackEventExtra_delete},
         {"native_add_arg", "(JJ)V", (void*)android_os_PerfettoTrackEventExtra_add_arg},
         {"native_clear_args", "(J)V", (void*)android_os_PerfettoTrackEventExtra_clear_args},
         {"native_emit", "(IJLjava/lang/String;J)V",
          (void*)android_os_PerfettoTrackEventExtra_emit}};

static const JNINativeMethod gProtoMethods[] =
        {{"native_init", "()J", (void*)android_os_PerfettoTrackEventExtraProto_init},
         {"native_delete", "()J", (void*)android_os_PerfettoTrackEventExtraProto_delete},
         {"native_get_extra_ptr", "(J)J",
          (void*)android_os_PerfettoTrackEventExtraProto_get_extra_ptr},
         {"native_add_field", "(JJ)V", (void*)android_os_PerfettoTrackEventExtraProto_add_field},
         {"native_clear_fields", "(J)V",
          (void*)android_os_PerfettoTrackEventExtraProto_clear_fields}};

static const JNINativeMethod gArgInt64Methods[] = {
        {"native_init", "(Ljava/lang/String;)J",
         (void*)android_os_PerfettoTrackEventExtraArgInt64_init},
        {"native_delete", "()J", (void*)android_os_PerfettoTrackEventExtraArgInt64_delete},
        {"native_get_extra_ptr", "(J)J",
         (void*)android_os_PerfettoTrackEventExtraArgInt64_get_extra_ptr},
        {"native_set_value", "(JJ)V", (void*)android_os_PerfettoTrackEventExtraArgInt64_set_value},
};

static const JNINativeMethod gArgBoolMethods[] = {
        {"native_init", "(Ljava/lang/String;)J",
         (void*)android_os_PerfettoTrackEventExtraArgBool_init},
        {"native_delete", "()J", (void*)android_os_PerfettoTrackEventExtraArgBool_delete},
        {"native_get_extra_ptr", "(J)J",
         (void*)android_os_PerfettoTrackEventExtraArgBool_get_extra_ptr},
        {"native_set_value", "(JZ)V", (void*)android_os_PerfettoTrackEventExtraArgBool_set_value},
};

static const JNINativeMethod gArgDoubleMethods[] = {
        {"native_init", "(Ljava/lang/String;)J",
         (void*)android_os_PerfettoTrackEventExtraArgDouble_init},
        {"native_delete", "()J", (void*)android_os_PerfettoTrackEventExtraArgDouble_delete},
        {"native_get_extra_ptr", "(J)J",
         (void*)android_os_PerfettoTrackEventExtraArgDouble_get_extra_ptr},
        {"native_set_value", "(JD)V", (void*)android_os_PerfettoTrackEventExtraArgDouble_set_value},
};

static const JNINativeMethod gArgStringMethods[] = {
        {"native_init", "(Ljava/lang/String;)J",
         (void*)android_os_PerfettoTrackEventExtraArgString_init},
        {"native_delete", "()J", (void*)android_os_PerfettoTrackEventExtraArgString_delete},
        {"native_get_extra_ptr", "(J)J",
         (void*)android_os_PerfettoTrackEventExtraArgString_get_extra_ptr},
        {"native_set_value", "(JLjava/lang/String;)V",
         (void*)android_os_PerfettoTrackEventExtraArgString_set_value},
};

static const JNINativeMethod gFieldInt64Methods[] = {
        {"native_init", "()J", (void*)android_os_PerfettoTrackEventExtraFieldInt64_init},
        {"native_delete", "()J", (void*)android_os_PerfettoTrackEventExtraFieldInt64_delete},
        {"native_get_extra_ptr", "(J)J",
         (void*)android_os_PerfettoTrackEventExtraFieldInt64_get_extra_ptr},
        {"native_set_value", "(JJJ)V",
         (void*)android_os_PerfettoTrackEventExtraFieldInt64_set_value},
};

static const JNINativeMethod gFieldDoubleMethods[] = {
        {"native_init", "()J", (void*)android_os_PerfettoTrackEventExtraFieldDouble_init},
        {"native_delete", "()J", (void*)android_os_PerfettoTrackEventExtraFieldDouble_delete},
        {"native_get_extra_ptr", "(J)J",
         (void*)android_os_PerfettoTrackEventExtraFieldDouble_get_extra_ptr},
        {"native_set_value", "(JJD)V",
         (void*)android_os_PerfettoTrackEventExtraFieldDouble_set_value},
};

static const JNINativeMethod gFieldStringMethods[] = {
        {"native_init", "()J", (void*)android_os_PerfettoTrackEventExtraFieldString_init},
        {"native_delete", "()J", (void*)android_os_PerfettoTrackEventExtraFieldString_delete},
        {"native_get_extra_ptr", "(J)J",
         (void*)android_os_PerfettoTrackEventExtraFieldString_get_extra_ptr},
        {"native_set_value", "(JJLjava/lang/String;)V",
         (void*)android_os_PerfettoTrackEventExtraFieldString_set_value},
};

static const JNINativeMethod gFieldNestedMethods[] =
        {{"native_init", "()J", (void*)android_os_PerfettoTrackEventExtraFieldNested_init},
         {"native_delete", "()J", (void*)android_os_PerfettoTrackEventExtraFieldNested_delete},
         {"native_get_extra_ptr", "(J)J",
          (void*)android_os_PerfettoTrackEventExtraFieldNested_get_extra_ptr},
         {"native_add_field", "(JJ)V",
          (void*)android_os_PerfettoTrackEventExtraFieldNested_add_field},
         {"native_set_id", "(JJ)V", (void*)android_os_PerfettoTrackEventExtraFieldNested_set_id}};

static const JNINativeMethod gFlowMethods[] = {
        {"native_init", "()J", (void*)android_os_PerfettoTrackEventExtraFlow_init},
        {"native_delete", "()J", (void*)android_os_PerfettoTrackEventExtraFlow_delete},
        {"native_set_process_flow", "(JJ)V",
         (void*)android_os_PerfettoTrackEventExtraFlow_set_process_flow},
        {"native_set_process_terminating_flow", "(JJ)V",
         (void*)android_os_PerfettoTrackEventExtraFlow_set_process_terminating_flow},
        {"native_get_extra_ptr", "(J)J",
         (void*)android_os_PerfettoTrackEventExtraFlow_get_extra_ptr},
};

static const JNINativeMethod gNamedTrackMethods[] = {
        {"native_init", "(JLjava/lang/String;J)J",
         (void*)android_os_PerfettoTrackEventExtraNamedTrack_init},
        {"native_delete", "()J", (void*)android_os_PerfettoTrackEventExtraNamedTrack_delete},
        {"native_get_extra_ptr", "(J)J",
         (void*)android_os_PerfettoTrackEventExtraNamedTrack_get_extra_ptr},
};

static const JNINativeMethod gCounterTrackMethods[] =
        {{"native_init", "(Ljava/lang/String;J)J",
          (void*)android_os_PerfettoTrackEventExtraCounterTrack_init},
         {"native_delete", "()J", (void*)android_os_PerfettoTrackEventExtraCounterTrack_delete},
         {"native_get_extra_ptr", "(J)J",
          (void*)android_os_PerfettoTrackEventExtraCounterTrack_get_extra_ptr}};

static const JNINativeMethod gCounterInt64Methods[] =
        {{"native_init", "()J", (void*)android_os_PerfettoTrackEventExtraCounterInt64_init},
         {"native_delete", "()J", (void*)android_os_PerfettoTrackEventExtraCounterInt64_delete},
         {"native_set_value", "(JJ)V",
          (void*)android_os_PerfettoTrackEventExtraCounterInt64_set_value},
         {"native_get_extra_ptr", "(J)J",
          (void*)android_os_PerfettoTrackEventExtraCounterInt64_get_extra_ptr}};

static const JNINativeMethod gCounterDoubleMethods[] =
        {{"native_init", "()J", (void*)android_os_PerfettoTrackEventExtraCounterDouble_init},
         {"native_delete", "()J", (void*)android_os_PerfettoTrackEventExtraCounterDouble_delete},
         {"native_set_value", "(JD)V",
          (void*)android_os_PerfettoTrackEventExtraCounterDouble_set_value},
         {"native_get_extra_ptr", "(J)J",
          (void*)android_os_PerfettoTrackEventExtraCounterDouble_get_extra_ptr}};

int register_android_os_PerfettoTrackEventExtra(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/os/PerfettoTrackEventExtra$ArgInt64",
                                       gArgInt64Methods, NELEM(gArgInt64Methods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register arg int64 native methods.");

    res = jniRegisterNativeMethods(env, "android/os/PerfettoTrackEventExtra$ArgBool",
                                   gArgBoolMethods, NELEM(gArgBoolMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register arg bool native methods.");

    res = jniRegisterNativeMethods(env, "android/os/PerfettoTrackEventExtra$ArgDouble",
                                   gArgDoubleMethods, NELEM(gArgDoubleMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register arg double native methods.");

    res = jniRegisterNativeMethods(env, "android/os/PerfettoTrackEventExtra$ArgString",
                                   gArgStringMethods, NELEM(gArgStringMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register arg string native methods.");

    res = jniRegisterNativeMethods(env, "android/os/PerfettoTrackEventExtra$FieldInt64",
                                   gFieldInt64Methods, NELEM(gFieldInt64Methods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register field int64 native methods.");

    res = jniRegisterNativeMethods(env, "android/os/PerfettoTrackEventExtra$FieldDouble",
                                   gFieldDoubleMethods, NELEM(gFieldDoubleMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register field double native methods.");

    res = jniRegisterNativeMethods(env, "android/os/PerfettoTrackEventExtra$FieldString",
                                   gFieldStringMethods, NELEM(gFieldStringMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register field string native methods.");

    res = jniRegisterNativeMethods(env, "android/os/PerfettoTrackEventExtra$FieldNested",
                                   gFieldNestedMethods, NELEM(gFieldNestedMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register field nested native methods.");

    res = jniRegisterNativeMethods(env, "android/os/PerfettoTrackEventExtra", gExtraMethods,
                                   NELEM(gExtraMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register extra native methods.");

    res = jniRegisterNativeMethods(env, "android/os/PerfettoTrackEventExtra$Proto", gProtoMethods,
                                   NELEM(gProtoMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register proto native methods.");

    res = jniRegisterNativeMethods(env, "android/os/PerfettoTrackEventExtra$Flow", gFlowMethods,
                                   NELEM(gFlowMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register flow native methods.");

    res = jniRegisterNativeMethods(env, "android/os/PerfettoTrackEventExtra$NamedTrack",
                                   gNamedTrackMethods, NELEM(gNamedTrackMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register named track native methods.");

    res = jniRegisterNativeMethods(env, "android/os/PerfettoTrackEventExtra$CounterTrack",
                                   gCounterTrackMethods, NELEM(gCounterTrackMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register counter track native methods.");

    res = jniRegisterNativeMethods(env, "android/os/PerfettoTrackEventExtra$CounterInt64",
                                   gCounterInt64Methods, NELEM(gCounterInt64Methods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register counter int64 native methods.");

    res = jniRegisterNativeMethods(env, "android/os/PerfettoTrackEventExtra$CounterDouble",
                                   gCounterDoubleMethods, NELEM(gCounterDoubleMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register counter double native methods.");
    return 0;
}

} // namespace android
