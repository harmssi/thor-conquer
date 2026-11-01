cmake_minimum_required(VERSION 3.22)
project(thorbridge C)
add_library(thorbridge SHARED thorbridge.c)
target_link_libraries(thorbridge log dl)
