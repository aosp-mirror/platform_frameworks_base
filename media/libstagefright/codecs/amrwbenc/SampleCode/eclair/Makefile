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

# target6
# available: pc, v4(armv4), v5(armv5), v5x(armv5 xscale), v6(armv6), v7(cortex-a8 neon)
VOTT:= v6


# module type
# please specify the type of your module: lib or exe
VOMT:= exe


# module macros
# please append the additional macro definitions here for your module if necessary.
# e.g. -DVISUALON, macro VISUALON defined for your module
VOMM:= #ARMV5E



# please specify the name of your module
VOTARGET:= voAMRWBEnc_Test


# please modify here to be sure to see the g1.mk
include ../../../../Tools/eclair.mk

# dependent libraries.
VODEPLIBS:=-ldl


# module source
# please modify here to be sure to see the ms.mk which specifies all source info of your module
include ../ms.mk


# please specify where is the voRelease on your PC, relative path is suggested
VORELDIR:=../


# please modify here to be sure to see the doit.mk
include ../../../../Tools/doit.mk

