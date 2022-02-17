The dagger modules in this directory can be included by the host SysUI using the Shell library for
explicity injection of Shell components. Apps using this library are not required to use these
dagger modules for setup, but it is recommended for them to include them as needed.

The modules are currently inherited as such:

+- WMShellBaseModule (common shell features across SysUI)
   |
   +- WMShellModule (handheld)
   |
   +- TvPipModule (tv pip)
      |
      +- TvWMShellModule (tv)