##
# Environment dependent variables
##

AAPT := aapt2
ZIPALIGN := zipalign -f 4
FRAMEWORK := ../../../../../out/target/common/obj/APPS/framework-res_intermediates/package-export.apk

##
# Project depenedent variables
##

LOCAL_PACKAGE := com.android.app
LOCAL_RESOURCE_DIR := res
LOCAL_LIBS := lib/out/package.apk
LOCAL_OUT := out
LOCAL_GEN := out/gen
LOCAL_PROGUARD := out/proguard.rule

##
# AAPT2 custom rules.
##

PRIVATE_R_FILE := $(LOCAL_GEN)/$(subst .,/,$(LOCAL_PACKAGE))/R.java
$(info PRIVATE_R_FILE = $(PRIVATE_R_FILE))

# Eg: framework.apk, etc.
PRIVATE_INCLUDES := $(FRAMEWORK)
$(info PRIVATE_INCLUDES = $(PRIVATE_INCLUDES))

# Eg: res/drawable/icon.png, res/values/styles.xml
PRIVATE_RESOURCES := $(shell find $(LOCAL_RESOURCE_DIR) -mindepth 1 -maxdepth 2 -type f)
$(info PRIVATE_RESOURCES = $(PRIVATE_RESOURCES))

PRIVATE_RESOURCE_OBJECTS := $(subst /,_,$(patsubst $(LOCAL_RESOURCE_DIR)/%,%,$(filter $(LOCAL_RESOURCE_DIR)/values%,$(PRIVATE_RESOURCES))))
PRIVATE_RESOURCE_OBJECTS := $(addprefix $(LOCAL_OUT)/,$(PRIVATE_RESOURCE_OBJECTS:.xml=.arsc.flat))
$(info PRIVATE_RESOURCE_OBJECTS = $(PRIVATE_RESOURCE_OBJECTS))

PRIVATE_FILE_OBJECTS := $(subst /,_,$(patsubst $(LOCAL_RESOURCE_DIR)/%,%,$(filter-out $(LOCAL_RESOURCE_DIR)/values%,$(PRIVATE_RESOURCES))))
PRIVATE_FILE_OBJECTS := $(addprefix $(LOCAL_OUT)/,$(addsuffix .flat,$(PRIVATE_FILE_OBJECTS)))
$(info PRIVATE_FILE_OBJECTS = $(PRIVATE_FILE_OBJECTS))

.SECONDEXPANSION:

$(LOCAL_OUT)/%.arsc.flat: $(LOCAL_RESOURCE_DIR)/$$(subst _,/,%).xml
	$(AAPT) compile -o $(LOCAL_OUT) $<

$(LOCAL_OUT)/%.flat: $(LOCAL_RESOURCE_DIR)/$$(subst _,/,%)
	$(AAPT) compile -o $(LOCAL_OUT) $<

$(LOCAL_PROGUARD) $(LOCAL_OUT)/package.apk: AndroidManifest.xml
$(PRIVATE_R_FILE) $(LOCAL_PROGUARD) $(LOCAL_OUT)/package.apk: $(PRIVATE_FILE_OBJECTS) $(PRIVATE_RESOURCE_OBJECTS)
	$(AAPT) link -o $(LOCAL_OUT)/package.apk --manifest AndroidManifest.xml --java $(LOCAL_GEN) --proguard $(LOCAL_PROGUARD) -I $(PRIVATE_INCLUDES) $(filter-out AndroidManifest.xml,$^) -v

# Create the out directory if needed.
dummy := $(shell test -d $(LOCAL_OUT) || mkdir -p $(LOCAL_OUT))

.PHONY: all
all: $(LOCAL_OUT)/package.apk $(LOCAL_PROGUARD) $(PRIVATE_R_FILE)

.DEFAULT_GOAL := all
