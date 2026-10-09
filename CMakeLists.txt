cmake_minimum_required(VERSION 3.16)
project(flipper_ai_pc_bridge_host_tests C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(bridge_protocol
    src/protocol/protocol.c
    src/protocol/crc.c
    src/protocol/actions.c
    src/safety/safety.c
    src/queue/command_queue.c)
target_sources(bridge_protocol PRIVATE src/protocol/sequence_window.c)
target_include_directories(bridge_protocol PUBLIC src)

add_executable(protocol_test tests/protocol_test.c)
target_link_libraries(protocol_test PRIVATE bridge_protocol)
add_executable(actions_test tests/actions_test.c)
target_link_libraries(actions_test PRIVATE bridge_protocol)
add_executable(queue_test tests/queue_test.c)
target_link_libraries(queue_test PRIVATE bridge_protocol)
add_executable(sequence_test tests/sequence_test.c)
target_link_libraries(sequence_test PRIVATE bridge_protocol)
add_executable(safety_test tests/safety_test.c)
target_link_libraries(safety_test PRIVATE bridge_protocol)
enable_testing()
add_test(NAME protocol_test COMMAND protocol_test)
add_test(NAME actions_test COMMAND actions_test)
add_test(NAME queue_test COMMAND queue_test)
add_test(NAME sequence_test COMMAND sequence_test)
add_test(NAME safety_test COMMAND safety_test)
