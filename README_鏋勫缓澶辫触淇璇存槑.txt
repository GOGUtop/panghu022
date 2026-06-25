本版修复 GitHub Actions 报错：AgpWithBuiltInKotlinAppliedCheck / Process completed with exit code 1。

修改内容：
1. Android Gradle Plugin 从 9.1.1 降到 8.7.3。
2. Kotlin 从 2.2.20 降到 2.0.21。
3. Gradle Wrapper 改为 8.10.2。
4. compileSdk / targetSdk 改为 35，更适合 GitHub Actions 稳定编译。
5. GitHub Actions 使用 JDK 17 + Android SDK 35。

用法：
把这个压缩包内容覆盖到 GitHub 仓库，重新 Run workflow 即可。
