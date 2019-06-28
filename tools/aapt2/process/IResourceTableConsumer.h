/*
 * Copyright (C) 2015 The Android Open Source Project
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

#ifndef AAPT_PROCESS_IRESOURCETABLECONSUMER_H
#define AAPT_PROCESS_IRESOURCETABLECONSUMER_H

#include <iostream>
#include <list>
#include <set>
#include <sstream>

#include "Diagnostics.h"
#include "NameMangler.h"
#include "Resource.h"
#include "ResourceValues.h"
#include "Source.h"

namespace aapt {

class ResourceTable;
class SymbolTable;

// The type of package to build.
enum class PackageType {
  kApp,
  kSharedLib,
  kStaticLib,
};

struct IAaptContext {
  virtual ~IAaptContext() = default;

  virtual PackageType GetPackageType() = 0;
  virtual SymbolTable* GetExternalSymbols() = 0;
  virtual IDiagnostics* GetDiagnostics() = 0;
  virtual const std::string& GetCompilationPackage() = 0;
  virtual uint8_t GetPackageId() = 0;
  virtual NameMangler* GetNameMangler() = 0;
  virtual bool IsVerbose() = 0;
  virtual int GetMinSdkVersion() = 0;
  virtual const std::set<std::string>& GetSplitNameDependencies() = 0;
};

struct IResourceTableConsumer {
  virtual ~IResourceTableConsumer() = default;

  virtual bool Consume(IAaptContext* context, ResourceTable* table) = 0;
};

namespace xml {
class XmlResource;
}

struct IXmlResourceConsumer {
  virtual ~IXmlResourceConsumer() = default;

  virtual bool Consume(IAaptContext* context, xml::XmlResource* resource) = 0;
};

}  // namespace aapt

#endif /* AAPT_PROCESS_IRESOURCETABLECONSUMER_H */
