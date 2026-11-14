![Build Status](https://github.com/MMRyanSheep/OreViewer/actions/workflows/build.yml/badge.svg)

## 手动构建并发布 Release

将工作流推送到 GitHub 默认分支后，进入 **Actions → Release Fabric Mod → Run workflow**，选择要构建的分支并运行。

- `tag`：可选，默认使用 `gradle.properties` 中的 `mod_version`，例如 `v1.0.0`。每次发布应使用新的标签；已有同名 Release 时会失败，不会覆盖附件。
- `prerelease`：勾选后发布为预发布版本，默认发布正式版本。

工作流使用 Java 25 执行 `clean build`，将模组 JAR 和源码 JAR 上传到 GitHub Release，并自动生成发布说明。使用内置 `GITHUB_TOKEN`，无需额外配置 Secret。

发布新模组版本前，请同步更新 `gradle.properties` 的 `mod_version` 和 `src/main/resources/fabric.mod.json` 的 `version`。自定义标签只影响 Release 标签，不改变模组版本。
