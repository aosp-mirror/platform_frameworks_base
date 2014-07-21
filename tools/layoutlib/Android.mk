#
# Copyright (C) 2008 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
LOCAL_PATH := $(my-dir)
include $(CLEAR_VARS)

LOCAL_JAVACFLAGS := -source 6 -target 6

#
# Define rules to build temp_layoutlib.jar, which contains a subset of
# the classes in framework.jar.  The layoutlib_create tool is used to
# transform the framework jar into the temp_layoutlib jar.
#

# We need to process the framework classes.jar file, but we can't
# depend directly on it (private vars won't be inherited correctly).
# So, we depend on framework's BUILT file.
built_framework_dep := $(call java-lib-deps,framework)
built_framework_classes := $(call java-lib-files,framework)

built_core_dep := $(call java-lib-deps,core-libart)
built_core_classes := $(call java-lib-files,core-libart)

built_ext_dep := $(call java-lib-deps,ext)
built_ext_classes := $(call java-lib-files,ext)
built_ext_data := $(call intermediates-dir-for, \
			JAVA_LIBRARIES,ext,,COMMON)/javalib.jar

built_layoutlib_create_jar := $(call intermediates-dir-for, \
			JAVA_LIBRARIES,layoutlib_create,HOST)/javalib.jar

# This is mostly a copy of config/host_java_library.mk
LOCAL_MODULE := temp_layoutlib
LOCAL_MODULE_CLASS := JAVA_LIBRARIES
LOCAL_MODULE_SUFFIX := $(COMMON_JAVA_PACKAGE_SUFFIX)
LOCAL_IS_HOST_MODULE := true
LOCAL_BUILT_MODULE_STEM := javalib.jar

#######################################
include $(BUILD_SYSTEM)/base_rules.mk
#######################################

$(LOCAL_BUILT_MODULE): $(built_core_dep) \
                       $(built_framework_dep) \
                       $(built_ext_dep) \
                       $(built_ext_data) \
                       $(built_layoutlib_create_jar)
	$(hide) echo "host layoutlib_create: $@"
	$(hide) mkdir -p $(dir $@)
	$(hide) rm -f $@
	$(hide) ls -l $(built_framework_classes)
	$(hide) java -ea -jar $(built_layoutlib_create_jar) \
	             $@ \
	             $(built_core_classes) \
	             $(built_framework_classes) \
	             $(built_ext_classes) \
	             $(built_ext_data)
	$(hide) ls -l $(built_framework_classes)


#
# Include the subdir makefiles.
#
include $(call all-makefiles-under,$(LOCAL_PATH))
