#/*
# ** Copyright 2003-2010, VisualOn, Inc.
# **
# ** Licensed under the Apache License, Version 2.0 (the "License");
# ** you may not use this file except in compliance with the License.
# ** You may obtain a copy of the License at
# **
# **     http://www.apache.org/licenses/LICENSE-2.0
# **
# ** Unless required by applicable law or agreed to in writing, software
# ** distributed under the License is distributed on an "AS IS" BASIS,
# ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# ** See the License for the specific language governing permissions and
# ** limitations under the License.
# */

VERBOSE:=@


VOMT ?= lib

ifeq ($(VOMT), lib)
LIB_STATIC=$(VOTARGET).a
LIB_DYNAMIC=$(VOTARGET).so
endif

ifeq ($(VOMT), exe)
TARGET=$(VOTARGET)
endif

CFLAGS=$(VOCFLAGS) $(addprefix -I, $(VOSRCDIR))
CPPFLAGS=$(VOCPPFLAGS) $(addprefix -I, $(VOSRCDIR))
ifneq ($(VOTT), pc)
ASFLAGS=$(VOASFLAGS) $(addprefix -I, $(VOSRCDIR))
endif

LDFLAGS:=$(VOLDFLAGS)
VOTEDEPS+=$(VODEPLIBS)
VOTLDEPS+=$(VODEPLIBS)
VOSTCLIBS ?=

vpath %.c $(VOSRCDIR)
vpath %.cpp $(VOSRCDIR)
ifneq ($(VOTT), pc)
vpath %.s $(VOSRCDIR)
endif

ifeq ($(VOTT), pc)
BLTDIRS=$(VORELDIR)/Linux/static
BLTDIRD=$(VORELDIR)/Linux/shared
else
BLTDIRS=$(VORELDIR)/Google/$(VONJ)/lib/$(VOTT)
BLTDIRD=$(VORELDIR)/Google/$(VONJ)/so/$(VOTT)
endif


.PRECIOUS: $(OBJDIR)/%.o

ifeq ($(VOMT), lib)
all: mkdirs $(LIB_STATIC) $(LIB_DYNAMIC)
mkdirs: $(OBJDIR) $(BLTDIRS) $(BLTDIRD)
else
all: mkdirs $(TARGET)
mkdirs: $(OBJDIR)
endif

$(OBJDIR):
	@if test ! -d $@; then \
		mkdir -p $@; \
	fi;

ifeq ($(VOMT), lib)
$(BLTDIRS):
	@if test ! -d $@; then \
		mkdir -p $@; \
	fi;
$(BLTDIRD):
	@if test ! -d $@; then \
		mkdir -p $@; \
	fi;
endif


ifeq ($(VOMT), lib)
$(LIB_STATIC):$(OBJS)
	$(AR) cr $@ $(OBJDIR)/*.o $(VOSTCLIBS)
	$(RANLIB) $@
ifneq ($(VODBG), yes)
	#$(STRIP) $@
endif

$(LIB_DYNAMIC):$(OBJS)
	$(GG) $(LDFLAGS) -o $@ $(OBJDIR)/*.o -Wl,--whole-archive $(VOSTCLIBS) -Wl,--no-whole-archive $(VOTLDEPS)
ifneq ($(VODBG), yes)
		$(STRIP) $@
endif

else

$(TARGET):$(OBJS)
	$(GG) $(LDFLAGS) -o $@ $(OBJDIR)/*.o -Wl,--whole-archive $(VOSTCLIBS) -Wl,--no-whole-archive $(VOTEDEPS)
ifneq ($(VODBG), yes)
	$(STRIP) $@
endif

endif


.SUFFIXES: .c .cpp .s .o
.c.o:
	$(VERBOSE) $(CC) $(CFLAGS) -o $(OBJDIR)/$@ -c $<
#%.c:$(OBJDIR)/%.o
#	$(VERBOSE) $(CC) $(CFLAGS) -o $@ -c $<
.cpp.o:
	$(VERBOSE) $(GG) $(CPPFLAGS) -o $(OBJDIR)/$@ -c $<
ifneq ($(VOTT), pc)
.s.o:
	$(VERBOSE) $(AS) $(ASFLAGS) -o $(OBJDIR)/$@ $<
endif


.PHONY: clean devel
clean:
ifeq ($(VOMT), lib)
	-rm -fr $(OBJDIR) .*.sw* $(VOTARGET).*
else
	-rm -fr $(OBJDIR) .*.sw* $(VOTARGET)
endif

devel:
	cp -a $(LIB_STATIC) $(BLTDIRS)
	cp -a $(LIB_DYNAMIC) $(BLTDIRD)

