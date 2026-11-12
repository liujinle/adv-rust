cmake_minimum_required(VERSION 3.22.1)

project(crashprobe C)

add_library(crashprobe SHARED crash_probe.c)

find_library(log-lib log)
target_link_libraries(crashprobe ${log-lib})

# Android 15+ 的 16 KB 页大小要求：LOAD 段必须按 16 KB 对齐，否则系统会弹
# 「此应用不符合 16 KB 对齐要求」并在 16 KB 页设备上直接 dlopen 失败。
# NDK r27 的 clang 默认仍是 4 KB（0x1000），这里显式顶到 0x4000。
target_link_options(crashprobe PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
