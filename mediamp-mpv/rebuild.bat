@echo off
call "C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\Common7\Tools\VsDevCmd.bat" -arch=x64 -host_arch=x64
cd /d "F:\Desktop\mnt\NuvioMobile\mediamp\mediamp-mpv"
if exist build_cmake rmdir /s /q build_cmake
mkdir build_cmake
cd build_cmake
cmake .. -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build . --config Release
echo REBUILD COMPLETE
