cmake_minimum_required(VERSION 3.22.1)
project(xixi_node_mobile)

add_library(native-lib SHARED native-lib.cpp)

add_library(libnode SHARED IMPORTED)
set_target_properties(libnode PROPERTIES
        IMPORTED_LOCATION ${CMAKE_CURRENT_LIST_DIR}/../../../libnode/bin/${ANDROID_ABI}/libnode.so)

target_include_directories(native-lib PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}/../../../libnode/include/node)

find_library(log-lib log)

target_link_libraries(native-lib libnode ${log-lib})
