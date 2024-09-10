# GraalVM Ideal Graph Visualizer (IGV)

## Prerequisites

- JDK 21 is the recommended Java runtime platform for IGV, but any release between 17 and 22 is
  supported by the NetBeans 22 platform.

## Building and testing IGV

### MX

IGV is an MX project and for convenience should be built and tested as such.  It's also a NetBeans
22 project based on Maven so it can be developed using any tool chain which supports Maven.
Certains kind of edits, like editing the NetBeans module exlusions or editing the special UI
components, will require using NetBeans.

There is a known issue with opening the Maven based project in NetBeans because of a bug with the
`mx` project support built into NetBeans itself.`  Because 

#### Building

To build run the command:
`mx build`

#### Testing

To do the unittests included in IGV run command:
`mx unittest`

### Important files

 - `IdealGraphVisualizer/pom.xml` contains a `properties` section for values which affect the build
  - the base NetBeans platform version is `netbeans.version`

## Running IGV

### Linux

Run: `>$ idealgraphvisualizer/bin/idealgraphvisualizer`

### MacOS

Run: `>$ idealgraphvisualizer/bin/idealgraphvisualizer`

### Windows

Execute: `idealgraphvisualizer/bin/idealgraphvisualizer64.exe` or `idealgraphvisualizer/bin/idealgraphvisualizer.exe`

### Command Line Options
- `--jdkhome <path>` sets path to jdk to run IGV with (IGV runtime Java platform).
- `--open <file>` or `<file>` (*.bgv) opens specified file immediately after IGV is started.
