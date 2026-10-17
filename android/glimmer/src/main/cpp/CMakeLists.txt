cmake_minimum_required(VERSION 3.22.1)
project(glimmer_android C)
add_library(glimmer_android SHARED glimmer_android.c glimmer_more.c glimmer_fetch.c)
target_link_libraries(glimmer_android PRIVATE log dl)
