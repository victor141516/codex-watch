cmake_minimum_required(VERSION 3.16)
include($ENV{IDF_PATH}/tools/cmake/project.cmake)

if(CODEX_WATCH_DIAGNOSTIC)
    # Make the diagnostic flag visible to managed display components too, so
    # they can feed the app's lock-free flush counters without logging from an
    # ISR.  The normal firmware compiles this instrumentation out completely.
    add_compile_definitions(CODEX_WATCH_DIAGNOSTIC=1)
endif()

project(codex_watch_ble)
