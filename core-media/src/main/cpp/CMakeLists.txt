cmake_minimum_required(VERSION 3.22.1)

project(edgevoice_media CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 无锁 SPSC 环形缓冲区 + JNI 桥接：生产者(采集线程) 与 消费者(网络/播放线程) 内存隔离，零拷贝
add_library(edgevoice_media SHARED
        ring_buffer.cpp
        native_bridge.cpp)

target_compile_options(edgevoice_media PRIVATE -Wall -Wextra -O3)

find_library(log-lib log)
target_link_libraries(edgevoice_media ${log-lib})
