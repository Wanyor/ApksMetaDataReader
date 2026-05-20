# ApksMetaDataReader

从 Android 安装包中提取元数据，无需安装 Android SDK，纯 Java 实现，零依赖。

支持格式：`.apk` `.apks` `.xapk` `.apkm`

## 提取字段

| 字段 | 说明 |
|---|---|
| `appName` | 应用名称 |
| `packageName` | 包名 |
| `versionCode` | 版本号（整数） |
| `versionName` | 版本名（字符串） |
| `minSdkVersion` | 最低 SDK 版本 |
| `targetSdkVersion` | 目标 SDK 版本 |
| `compileSdkVersion` | 编译 SDK 版本 |
| `fileSize` | 文件大小（字节） |
| `fileMd5` | 文件 MD5 |

## 构建

依赖 JDK 8+，无需其他工具。

```bash
# 编译
javac -d out src/com/wanyor/android/app/*.java

# 打包为 JAR
jar --create --file ApksMetaDataReader.jar --main-class com.wanyor.android.app.MetaDataReader -C out .
```

## 使用

### 命令行

```bash
java -jar ApksMetaDataReader.jar <文件路径>
```

示例输出：

```
ApkMetaInfo{
  appName          = Snapseed
  packageName      = com.niksoftware.snapseed
  versionCode      = 1804212
  versionName      = 4.0.1.914366677
  minSdkVersion    = 32
  targetSdkVersion = 36
  compileSdkVersion= 37
  fileSize         = 106800089
  fileMd5          = 04866471cd551f87e9d55e5c73ae8f0
}
```

### 作为库

将 `ApksMetaDataReader.jar` 加入项目 classpath，然后：

```java
import com.wanyor.android.app.ApkMetaInfo;
import com.wanyor.android.app.MetaDataReader;

ApkMetaInfo info = MetaDataReader.readMetaInfo("/path/to/app.apks");

System.out.println(info.getAppName());       // "Snapseed"
System.out.println(info.getPackageName());   // "com.niksoftware.snapseed"
System.out.println(info.getVersionName());   // "4.0.1.914366677"
System.out.println(info.getVersionCode());   // 1804212
System.out.println(info.getMinSdkVersion()); // "32"
System.out.println(info.getFileSize());      // 106800089
System.out.println(info.getFileMd5());       // "04866471..."
```

## 实现说明

各格式的解析路径：

| 格式 | 解析方式 |
|---|---|
| `.apk` | 直接解析 `AndroidManifest.xml`（二进制 XML）+ `resources.arsc` |
| `.apks` | ZIP 包，提取 `base.apk` 后同 apk 路径 |
| `.xapk` | 优先读取 `manifest.json`，缺失字段从 base APK 补全 |
| `.apkm` | ZIP 包，提取 base APK 后同 apk 路径 |

核心解析器基于 Android 官方 [ResourceTypes.h](https://android.googlesource.com/platform/frameworks/base/+/master/libs/androidfw/include/androidfw/ResourceTypes.h) 格式规范：

- **`BinaryXmlParser`** — 解析 AXML（Android 二进制 XML），支持 UTF-8/UTF-16 字符串池，处理资源引用
- **`ResourceTableParser`** — 解析 `resources.arsc`，支持标准、`FLAG_SPARSE`、`FLAG_OFFSET16`、`FLAG_COMPACT` 四种条目格式
