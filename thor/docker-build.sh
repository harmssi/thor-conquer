#!/bin/bash
# Laeuft IM Container "thor-build" (siehe build.sh). /work = /mnt/cache/thor-conquer auf dem Host.
#   1) SDL2 2.30.9 + openal-soft 1.24.3 mit dem NDK bauen  -> /work/prefix
#   2) Vanilla Conquer (vanillara + vanillatd)             -> /work/build/engine
#   3) thorbridge (JNI)                                     -> /work/build/bridge
#   4) .so + SDL-Java stagen, Gradle assembleRelease        -> /work/out/ThorConquer.apk
set -euo pipefail

W=/work
SRC=$W/src
NDK=${ANDROID_NDK_HOME:-/opt/android-sdk/ndk/27.2.12479018}
TC=$NDK/build/cmake/android.toolchain.cmake
ABI=arm64-v8a
API=33
PREFIX=$W/prefix
SDL_VER=2.30.9
OAL_VER=1.24.3
JOBS=$(nproc)
APP=$SRC/thor/android
COMMON=(-G Ninja -DCMAKE_TOOLCHAIN_FILE=$TC -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-$API
        -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release)

mkdir -p $W/deps-src $W/deps-build $W/build $W/out

fetch() { # url datei
    [ -f "$W/deps-src/$2" ] || curl -fsSL -o "$W/deps-src/$2" "$1"
}
fetch https://github.com/libsdl-org/SDL/releases/download/release-$SDL_VER/SDL2-$SDL_VER.tar.gz SDL2-$SDL_VER.tar.gz
fetch https://github.com/kcat/openal-soft/archive/refs/tags/$OAL_VER.tar.gz openal-soft-$OAL_VER.tar.gz
[ -d $W/deps-build/SDL2-$SDL_VER ] || tar xf $W/deps-src/SDL2-$SDL_VER.tar.gz -C $W/deps-build
[ -d $W/deps-build/openal-soft-$OAL_VER ] || tar xf $W/deps-src/openal-soft-$OAL_VER.tar.gz -C $W/deps-build

echo "=== [1/4] SDL2 $SDL_VER"
if [ ! -f $PREFIX/lib/libSDL2.so ]; then
    cmake -S $W/deps-build/SDL2-$SDL_VER -B $W/build/sdl2 "${COMMON[@]}" -DCMAKE_INSTALL_PREFIX=$PREFIX \
        -DSDL_SHARED=ON -DSDL_STATIC=OFF -DSDL_TEST=OFF
    cmake --build $W/build/sdl2 -j$JOBS
    cmake --install $W/build/sdl2
fi

echo "=== [1/4] openal-soft $OAL_VER"
if [ ! -f $PREFIX/lib/libopenal.so ]; then
    cmake -S $W/deps-build/openal-soft-$OAL_VER -B $W/build/openal "${COMMON[@]}" -DCMAKE_INSTALL_PREFIX=$PREFIX \
        -DALSOFT_UTILS=OFF -DALSOFT_EXAMPLES=OFF -DALSOFT_TESTS=OFF -DALSOFT_INSTALL_CONFIG=OFF \
        -DALSOFT_INSTALL_HRTF_DATA=OFF -DALSOFT_INSTALL_AMBDEC_PRESETS=OFF -DALSOFT_BACKEND_OPENSL=ON \
        -DALSOFT_REQUIRE_OPENSL=ON
    cmake --build $W/build/openal -j$JOBS
    cmake --install $W/build/openal
fi

echo "=== [2/4] Vanilla Conquer"
cmake -S $SRC -B $W/build/engine "${COMMON[@]}" -DCMAKE_FIND_ROOT_PATH=$PREFIX \
    -DSDL2_INCLUDE_DIR=$PREFIX/include/SDL2 -DSDL2_LIBRARY=$PREFIX/lib/libSDL2.so \
    -DOPENAL_INCLUDE_DIR=$PREFIX/include -DOPENAL_LIBRARY=$PREFIX/lib/libopenal.so \
    -DBUILD_VANILLATD=ON -DBUILD_VANILLARA=ON -DBUILD_TOOLS=OFF -DBUILD_TESTS=OFF -DNETWORKING=ON
cmake --build $W/build/engine -j$JOBS

echo "=== [3/4] thorbridge"
cmake -S $SRC/thor/native -B $W/build/bridge "${COMMON[@]}"
cmake --build $W/build/bridge -j$JOBS

echo "=== [4/4] Stagen + Gradle"
JNI=$APP/app/src/main/jniLibs/$ABI
rm -rf $JNI $APP/app/src/main/java-sdl
mkdir -p $JNI $APP/app/src/main/java-sdl
cp $PREFIX/lib/libSDL2.so $PREFIX/lib/libopenal.so $JNI/
cp $W/build/engine/redalert/libvanillara.so $W/build/engine/tiberiandawn/libvanillatd.so $JNI/
cp $W/build/bridge/libthorbridge.so $JNI/
cp $NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so $JNI/
cp -r $W/deps-build/SDL2-$SDL_VER/android-project/app/src/main/java/org $APP/app/src/main/java-sdl/
ls -la $JNI

# Signierschluessel einmalig anlegen und BEHALTEN (Updates muessen darueber installieren).
mkdir -p $W/keystore
if [ ! -f $W/keystore/thorconquer.jks ]; then
    keytool -genkeypair -v -keystore $W/keystore/thorconquer.jks -storepass thorconquer -keypass thorconquer \
        -alias thorconquer -keyalg RSA -keysize 2048 -validity 20000 \
        -dname "CN=Thor Conquer Debug, O=harmssi, C=DE"
fi
export THOR_KEYSTORE=$W/keystore/thorconquer.jks
export GRADLE_USER_HOME=$W/gradle-home
echo "sdk.dir=${ANDROID_HOME:-/opt/android-sdk}" > $APP/local.properties
cd $APP
gradle --no-daemon -q assembleRelease ${THOR_GRADLE_ARGS:-}
cp $APP/app/build/outputs/apk/release/app-release.apk $W/out/ThorConquer.apk
ls -la $W/out/ThorConquer.apk
