cmake_minimum_required(VERSION 3.22)
project(droidrunner_native C)

add_library(nnapi_probe SHARED nnapi_probe.c)
target_link_libraries(nnapi_probe)

# Kept separate from the runner and accelerator probes. forkpty is provided
# by Android Bionic (minSdk 28; forkpty is available from API 23).
add_library(qnn_probe SHARED qnn_probe.c)
target_link_libraries(qnn_probe dl)

add_library(terminal_pty SHARED terminal_pty.c)
