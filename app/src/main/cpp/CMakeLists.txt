cmake_minimum_required(VERSION 3.22.1)
project("bitperfect_engine" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_C_STANDARD 11)

# ── libusb (vendored v1.0.27, shared) ───────────────────────
# usbfs 백엔드만 포함 (Android에서는 /dev/bus/usb fd를 libusb_wrap_sys_device로 감싼다)
# Shared (libusb.so) so it can be dlopen-swapped at runtime (Stage 9, dl/dllib.h).
add_library(usb SHARED
    usb/libusb/core.c
    usb/libusb/descriptor.c
    usb/libusb/hotplug.c
    usb/libusb/io.c
    usb/libusb/strerror.c
    usb/libusb/sync.c
    usb/libusb/os/events_posix.c
    usb/libusb/os/linux_usbfs.c
    usb/libusb/os/threads_posix.c
)

# libusbi.h 가 os/events_posix.h·os/threads_posix.h 을 PLATFORM_POSIX 기준으로만 include 한다.
# Android(bionic/pthread)는 POSIX 백엔드라 이 매크로를 켜야 usbi_mutex_t·usbi_cond_t·
# usbi_event_t·usbi_os_handle_t·usbi_tls_key_* 가 정의된다. (libusb 상류 CMake도 이렇게 켬)
target_compile_definitions(usb PUBLIC PLATFORM_POSIX)

# DEFAULT_VISIBILITY: 상류 libusb 는 config.h 에 선언(컴파일러 지원 시
# __attribute__((visibility("default"))), 아니면 비어 있음). 우리가 -fvisibility=hidden
# 을 쓰지 않고 정적 라이브러리를 libengine.so 에 묶는 만큼 빈 매크로가 정확하다.
# (libusbi.h:130-131, core.c/io.c/hotplug.c 의 함수 선언에 사용)
# CMake 는 '=' 없는 정의는 1 로 간주하므로 반드시 "NAME=" 으로 빈 매크로를 만들어야 한다.
target_compile_definitions(usb PRIVATE "DEFAULT_VISIBILITY=")

target_include_directories(usb PUBLIC
    ${CMAKE_SOURCE_DIR}/usb
    ${CMAKE_SOURCE_DIR}/usb/libusb
    ${CMAKE_SOURCE_DIR}/usb/libusb/os
)

find_library(log-lib log)
find_library(dl-lib dl)
find_library(m-lib m)

# ── Engine shared library ──────────────────────────────────
add_library(engine SHARED
    engine/engine.cpp
    usb/usbplay.cpp
    dl/dllib.cpp
)

# The engine does NOT link libusb.so. It is dlopen-ed at runtime (dl/dllib.cpp)
# and called through the g_usb function table. The include dirs below only
# provide the headers (types/constants) for the shim #defines in usbplay.cpp.
target_include_directories(engine PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${CMAKE_SOURCE_DIR}/usb/libusb
    ${CMAKE_SOURCE_DIR}/usb/libusb/os
)

target_link_libraries(engine
    ${log-lib}
    ${dl-lib}
)
