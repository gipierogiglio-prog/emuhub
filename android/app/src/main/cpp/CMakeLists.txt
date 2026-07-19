cmake_minimum_required(VERSION 3.18)
project("emuhub_emulator")

# Libretro cores são carregados dinamicamente via dlopen
# O wrapper se liga nas chamadas JNI do EmulatorActivity

add_library(emuhub_emulator SHARED
    libretro_bridge.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(EGL-lib EGL)
find_library(GLESv3-lib GLESv3)

target_link_libraries(emuhub_emulator
    ${log-lib}
    ${android-lib}
    ${EGL-lib}
    ${GLESv3-lib}
    dl
)

target_include_directories(emuhub_emulator PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# Suporte a 16KB page size (Android 16+)
target_compile_options(emuhub_emulator PRIVATE -DANDROID_PAGE_SIZE_16KB=1)
target_link_options(emuhub_emulator PRIVATE -Wl,-z,max-page-size=16384)
