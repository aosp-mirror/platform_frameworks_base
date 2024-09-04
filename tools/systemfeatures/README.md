# Build-time system feature support

## Overview

System features exposed from `PackageManager` are defined and aggregated as
`<feature>` xml attributes across various partitions, and are currently queried
at runtime through the framework. This directory contains tooling that will
support *build-time* queries of select system features, enabling optimizations
like code stripping and conditionally dependencies when so configured.

### TODO(b/203143243): Expand readme after landing codegen.
