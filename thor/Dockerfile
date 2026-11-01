FROM ubuntu:22.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y build-essential git openjdk-17-jdk ccache pkg-config python3-pip curl unzip zip autoconf automake libtool bison flex nasm yasm tar wget ca-certificates && rm -rf /var/lib/apt/lists/*
RUN python3 -m pip install cmake==3.31.6 meson==1.7.2 ninja==1.11.1.4
ENV ANDROID_HOME=/opt/android-sdk
RUN mkdir -p $ANDROID_HOME/cmdline-tools && cd /tmp && curl -sLo ct.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip && unzip -q ct.zip && mv cmdline-tools $ANDROID_HOME/cmdline-tools/latest && rm ct.zip
RUN yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null && $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "ndk;27.2.12479018" "platforms;android-35" "build-tools;35.0.0" "platform-tools" >/dev/null
RUN cd /opt && curl -sLo g.zip https://services.gradle.org/distributions/gradle-8.9-bin.zip && unzip -q g.zip && rm g.zip
ENV PATH=/opt/gradle-8.9/bin:$PATH
RUN git clone -q https://github.com/microsoft/vcpkg.git /opt/vcpkg-thor && git -C /opt/vcpkg-thor checkout -q 42e4e33e1505c9f47b58c21e0f557c1571b751ee && /opt/vcpkg-thor/bootstrap-vcpkg.sh -disableMetrics
ENV VCPKG_ROOT=/opt/vcpkg-thor
ENV ANDROID_NDK_HOME=/opt/android-sdk/ndk/27.2.12479018
