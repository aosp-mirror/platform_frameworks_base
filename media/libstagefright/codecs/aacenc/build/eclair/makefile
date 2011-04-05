#/*
#** Copyright 2003-2010, VisualOn, Inc.
#**
#** Licensed under the Apache License, Version 2.0 (the "License");
#** you may not use this file except in compliance with the License.
#** You may obtain a copy of the License at
#**
#**     http://www.apache.org/licenses/LICENSE-2.0
#**
#** Unless required by applicable law or agreed to in writing, software
#** distributed under the License is distributed on an "AS IS" BASIS,
#** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#** See the License for the specific language governing permissions and
#** limitations under the License.
#*/

# Just acting as Father Makefile of Modules
# please keep the name 'makefile' unchanged

# Module Subdirs
VOMSD:=$(dir $(shell find . -name 'Makefile'))

all:
	for dir in $(VOMSD); \
		do \
			$(MAKE) -C $$dir; \
		done

.PHONY:clean devel
clean:
	for dir in $(VOMSD); \
		do \
			$(MAKE) -C $$dir clean; \
		done

devel:
	for dir in $(VOMSD); \
		do \
			$(MAKE) -C $$dir devel; \
		done
