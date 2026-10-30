cmake_minimum_required(VERSION 3.18.1)
project("motioncore")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(motioncore SHARED motioncore.cpp)

find_library(log-lib log)
target_link_libraries(motioncore ${log-lib} GLESv2)
