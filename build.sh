#!/usr/bin/env bash
# 本地一键编译脚本（macOS / Linux）
# 用法：./build.sh
# 前置：已装 JDK 17 + Android SDK（或 Android Studio）
# 产物：app/build/outputs/apk/debug/公主答题可看-debug.apk

set -e

echo "=========================================="
echo " KidsTvGuard 本地编译"
echo "=========================================="

# 1. 检查 JDK
if ! command -v java >/dev/null 2>&1; then
  echo "✗ 未找到 java，请先安装 JDK 17："
  echo "  macOS:  brew install --cask temurin@17"
  echo "  或下载: https://adoptium.net/temurin/releases/?version=17"
  exit 1
fi

JAVA_VER=$(java -version 2>&1 | head -1 | awk -F '"' '{print $2}' | cut -d. -f1)
echo "✓ Java 版本：$JAVA_VER"
if [ "$JAVA_VER" -lt 17 ]; then
  echo "⚠ 建议 JDK 17+，当前 $JAVA_VER 可能编译失败"
fi

# 2. 检查 Android SDK
if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
  # 尝试常见路径
  for p in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "/usr/local/share/android-sdk"; do
    if [ -d "$p" ]; then
      export ANDROID_HOME="$p"
      export ANDROID_SDK_ROOT="$p"
      break
    fi
  done
fi

if [ -z "$ANDROID_HOME" ]; then
  echo "✗ 未找到 Android SDK，请设置 ANDROID_HOME 环境变量："
  echo "  export ANDROID_HOME=\$HOME/Library/Android/sdk"
  echo "  或安装 Android Studio: https://developer.android.com/studio"
  exit 1
fi
echo "✓ Android SDK：$ANDROID_HOME"

# 3. 写 local.properties（Gradle 需要）
if [ ! -f local.properties ]; then
  echo "sdk.dir=$ANDROID_HOME" > local.properties
  echo "✓ 已生成 local.properties"
fi

# 4. 生成 gradle wrapper（如果没有）
if [ ! -f gradlew ] || [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
  echo "→ 生成 Gradle wrapper..."
  if command -v gradle >/dev/null 2>&1; then
    gradle wrapper --gradle-version 8.7
  else
    echo "✗ 未找到 gradle 命令，请先安装："
    echo "  macOS:  brew install gradle"
    echo "  或用 Android Studio 打开工程自动生成"
    exit 1
  fi
fi
chmod +x gradlew 2>/dev/null || true

# 5. 编译 debug APK
echo ""
echo "→ 开始编译 debug APK（首次约 3-5 分钟，之后增量很快）..."
./gradlew assembleDebug --no-daemon

# 6. 输出结果
APK_PATH=$(find app/build/outputs/apk/debug -name "*.apk" -type f 2>/dev/null | head -1)
if [ -n "$APK_PATH" ] && [ -f "$APK_PATH" ]; then
  SIZE=$(ls -lh "$APK_PATH" | awk '{print $5}')
  echo ""
  echo "=========================================="
  echo "✓ 编译成功！"
  echo "  APK: $APK_PATH"
  echo "  大小: $SIZE"
  echo "=========================================="
  echo ""
  echo "安装到电视："
  echo "  adb connect <电视IP>:5555"
  echo "  adb install -r $APK_PATH"
else
  echo "✗ 未找到 APK 产物，请检查上方编译日志"
  exit 1
fi
