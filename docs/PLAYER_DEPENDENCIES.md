# 播放器依赖

| 组件 | 固定版本 | 上游及许可入口 |
|---|---|---|
| AndroidX Media3（ExoPlayer / HLS / DASH / UI / OkHttp） | 1.9.4 | [源码及 Apache-2.0 许可证](https://github.com/androidx/media) |
| libmpv Android JNI 包装层 | 1.0.0 | [源码、构建脚本及 MIT 许可证](https://github.com/jarnedemeulemeester/libmpv-android) |
| mpv 与 FFmpeg 原生库 | 随上述 AAR 提供 | [mpv](https://github.com/mpv-player/mpv)、[FFmpeg](https://ffmpeg.org/legal.html)；上游 FFmpeg 构建脚本开启 GPL 与 version3，原生库的许可应按 GPL 条款处理，区别于 JNI 包装层的 MIT 许可 |

构建通过 Maven Central / Google Maven 获取原始依赖，不修改 AAR 中的原生库。libmpv AAR 同时包含 FFmpeg、libc++ 等共享库及四种 ABI。对应原生依赖版本、补丁和构建方法以该版本上游构建脚本为准；本项目的源代码压缩包包含应用实现，不包含完整的第三方原生依赖源代码。

播放器按钮采用 [Google Material Design Icons](https://github.com/google/material-design-icons) 的 24px 矢量路径，依据 Apache-2.0 许可；路径仅转换为 Android VectorDrawable，许可文本保存在 [material-icons-LICENSE.txt](licenses/material-icons-LICENSE.txt)。
