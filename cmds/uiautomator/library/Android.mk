#
# Copyright (C) 2012 The Android Open Source Project
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

LOCAL_PATH:= $(call my-dir)

###############################################
# API check
# Please refer to build/core/tasks/apicheck.mk.
uiautomator_api_dir := frameworks/base/cmds/uiautomator/api
last_released_sdk_version := $(lastword $(call numerically_sort, \
    $(filter-out current, \
        $(patsubst $(uiautomator_api_dir)/%.txt,%, $(wildcard $(uiautomator_api_dir)/*.txt)) \
    )))

checkapi_last_error_level_flags := \
    -hide 2 -hide 3 -hide 4 -hide 5 -hide 6 -hide 24 -hide 25 \
    -error 7 -error 8 -error 9 -error 10 -error 11 -error 12 -error 13 -error 14 -error 15 \
    -error 16 -error 17 -error 18

# Check that the API we're building hasn't broken the last-released SDK version.
$(eval $(call check-api, \
    uiautomator-checkapi-last, \
    $(uiautomator_api_dir)/$(last_released_sdk_version).txt, \
    $(INTERNAL_PLATFORM_UIAUTOMATOR_API_FILE), \
    $(uiautomator_api_dir)/removed.txt, \
    $(INTERNAL_PLATFORM_UIAUTOMATOR_REMOVED_API_FILE), \
    $(checkapi_last_error_level_flags), \
    cat $(LOCAL_PATH)/apicheck_msg_last.txt, \
    uiautomator.core, \
    $(OUT_DOCS)/uiautomator-stubs-docs-stubs.srcjar))

checkapi_current_error_level_flags := \
    -error 2 -error 3 -error 4 -error 5 -error 6 \
    -error 7 -error 8 -error 9 -error 10 -error 11 -error 12 -error 13 -error 14 -error 15 \
    -error 16 -error 17 -error 18 -error 19 -error 20 -error 21 -error 23 -error 24 \
    -error 25

# Check that the API we're building hasn't changed from the not-yet-released
# SDK version.
$(eval $(call check-api, \
    uiautomator-checkapi-current, \
    $(uiautomator_api_dir)/current.txt, \
    $(INTERNAL_PLATFORM_UIAUTOMATOR_API_FILE), \
    $(uiautomator_api_dir)/removed.txt, \
    $(INTERNAL_PLATFORM_UIAUTOMATOR_REMOVED_API_FILE), \
    $(checkapi_current_error_level_flags), \
    cat $(LOCAL_PATH)/apicheck_msg_current.txt, \
    uiautomator.core, \
    $(OUT_DOCS)/uiautomator-stubs-docs-stubs.srcjar))

.PHONY: update-uiautomator-api
update-uiautomator-api: PRIVATE_API_DIR := $(uiautomator_api_dir)
update-uiautomator-api: PRIVATE_REMOVED_API_FILE := $(INTERNAL_PLATFORM_UIAUTOMATOR_REMOVED_API_FILE)
update-uiautomator-api: $(INTERNAL_PLATFORM_UIAUTOMATOR_API_FILE)
	@echo Copying uiautomator current.txt
	$(hide) cp $< $(PRIVATE_API_DIR)/current.txt
	@echo Copying uiautomator removed.txt
	$(hide) cp $(PRIVATE_REMOVED_API_FILE) $(PRIVATE_API_DIR)/removed.txt
###############################################
# clean up temp vars
uiautomator_api_dir :=
checkapi_last_error_level_flags :=
checkapi_current_error_level_flags :=
