# 构建与发布

公开版本从 `0.0.1`（versionCode 1）开始。`TESTING.md` 中较大的旧版本号属于上传 GitHub 前的本地开发记录。

主分支提交、Pull Request 和手动运行 `Android APK` 工作流会执行 DOM 检查、JVM 网络回归、Debug 构建和 Lint。Debug APK 位于 Actions 运行的 Artifacts。

推送 `v*` 标签会在检查通过后额外构建经过 R8 压缩、关闭调试的签名 Release APK，验证签名，生成 SHA-256 校验文件并自动创建 GitHub Release。所有构建均无需 Android 模拟器。

## 发布新版本

1. 更新 `app/build.gradle.kts` 的 `versionName`，并递增 `versionCode`。
2. 提交并推送到 `main`。
3. 推送与版本号匹配的标签，例如：

```sh
git tag v0.0.2
git push origin v0.0.2
```

标签与 `versionName` 不一致会让检查失败。成功后在 Releases 下载 `TaiAV-版本号.apk`、`SHA256SUMS.txt` 和 `signature.txt`。R8 mapping 与 Lint 报告保存在对应 Actions 运行的验证产物中。

## 签名配置

仓库 Secrets 使用以下名称，所有版本复用同一签名：

| Secret | 内容 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | JKS 密钥库的 Base64 |
| `ANDROID_KEYSTORE_PASSWORD` | 密钥库密码 |
| `ANDROID_KEY_ALIAS` | 密钥别名 |
| `ANDROID_KEY_PASSWORD` | 私钥密码 |

首次配置时已生成独立发布密钥并写入 Secrets，本地备份保存在被忽略的 `.work/release-signing/`，请保留密钥库与凭据备份。密钥不随源码提交。GitHub 托管 Runner 仅在版本标签构建时解码，并在构建后清理。

本地签名构建可设置 `TAIAV_SIGNING_STORE_FILE`、`TAIAV_SIGNING_STORE_PASSWORD`、`TAIAV_SIGNING_KEY_ALIAS`、`TAIAV_SIGNING_KEY_PASSWORD`，然后执行 `./gradlew assembleRelease lintRelease`。

此前本地 Debug APK 使用开发签名。首次切换为 Release 签名时，Android 要求卸载旧 Debug 版后安装；后续相同签名且 versionCode 递增的 Release 可覆盖更新。卸载会删除应用私有数据，可先通过应用备份入口导出收藏和规则。
