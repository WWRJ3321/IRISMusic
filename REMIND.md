# REMIND · 许可与免责声明 / License & Disclaimer

> 本文件是 IRIS Music 的许可与法律免责说明。请在下载、编译、使用、分发本项目**之前**完整阅读。
> 只要你对本项目的代码进行了复制、修改、编译或使用，即视为你已阅读并同意本文件与 `LICENSE`（GPL-3.0）的全部条款。

---

## 1. 版权与许可证 / Copyright & License

Copyright (C) 2026 **WWRJ**（IRIS Music 作者）。

IRIS Music 的**自有源代码**在 **GNU General Public License version 3**（GPL-3.0，或——按你的选择——其后续版本）下授权。完整条款见仓库根目录的 [`LICENSE`](./LICENSE) 文件。

要点（这不构成对 GPL 的修改或放宽，仅是便于阅读的概述）：

- 你**可以**自由地运行、研究、修改、分发本软件，也可以修改后再分发。
- **传染性条款**：如果你分发本软件的修改版、派生作品，或把本软件代码合并进你的程序并对外分发，那么你的整个衍生作品**必须**同样以 GPL-3.0 开源，并向我方提供对应源代码。
- 分发任何副本时，必须**保留**本版权声明、许可证声明、`LICENSE` 文件，以及源文件顶部的 GPL 头部注释。
- 若你**仅个人使用**、不对外分发（包括不公开提供编译好的安装包给他人），则不触发上述开源义务。网络服务分发同样按 GPL-3.0 的相关条款处理。

> 一句话：**用可以，改可以；一旦你把改过的版本发出去给别人，就得同样开源。**

## 2. 关于"音乐内容"的免责 / No bundled content

- 本项目是一个**本地音乐播放器工具**。**不含、不内置、不附带、不提供、不索引、不下载、不分享**任何受版权保护的音乐、音频、歌词或专辑封面。
- 应用只能读取**你自己设备本地**已有、且你**有权访问**的音频文件。导入哪些音乐、你从何处获得这些音乐，完全是你的责任。
- 本仓库（包括示例、演示、占位素材）**不包含**任何第三方音乐录音。请勿在本仓库、Issue、PR 或任何衍生发布中提交受版权保护的音乐文件。

## 3. 不提供任何担保 / No Warranty

本程序按"**现状**"提供，**不提供任何形式的担保**，无论明示或默示，包括但不限于对**适销性**、**特定用途适用性**及**不侵权**的默示担保。全部风险由你承担。（本条与 GPL-3.0 第 15 条一致。）

本软件是个人兴趣项目，**不承诺**无 Bug、不承诺持续维护、不承诺适配你的具体设备或系统版本。

## 4. 责任限制 / Limitation of Liability

在适用法律允许的最大范围内，**任何情况下**，版权持有人或任何修改、分发本软件的人，**均不对**因使用或无法使用本软件而导致的任何损害负责，包括但不限于数据丢失、设备异常、业务中断或其他商业损失、偶然或必然的损害——即使已被告知发生此类损害的可能。（本条与 GPL-3.0 第 16 条一致。）

## 5. 隐私声明 / Privacy

IRIS Music **默认不联网**，不含账号系统、不含广告、不含任何遥测/埋点/数据上报。你的歌单、收藏、统计等数据全部仅保存在你的本机。

唯一例外：**「检查更新」**（设置 → 关于，默认关闭）——开启并手动点击「检查更新」时，应用会发起一次到 `api.github.com/repos/WWRJ3321/IRISMusic/releases/latest` 的单向 HTTP GET（只读取最新版本号用于本地比较），不上传任何数据、不发送设备信息。关闭开关后应用完全不访问网络。详见 `AndroidManifest.xml` 中 `INTERNET` 权限的注释。

## 6. 名称与标识 / Name & Trademark

GPL-3.0 授予的是**代码**的复制与修改权，**不**授予对 "IRIS"、"IRIS Music"、"虹月" 等名称、标识、品牌的使用授权。若你分发实质性修改的版本，请**移除或更换**应用名称与图标，以避免与原版混淆（这也是 GPL 建议的做法）。

## 7. 第三方组件 / Third-party components

本项目依赖的 Android Jetpack、Jetpack Compose、Media3/ExoPlayer、Haze 等开源库各按其自身许可证（多为 Apache-2.0）授权，不受本项目的 GPL-3.0 主张覆盖。清单见 [`NOTICE`](./NOTICE)。

 此外，本项目的**液态玻璃（SDF 折射 / 镜面高光）实现思路参考自 Kyant0/AndroidLiquidGlass（Apache-2.0）**，为本项目的独立重写、非逐行拷贝。相关署名见 [`NOTICE`](./NOTICE)。

 本项目的**"唱片墙"紧凑排布**之交互**灵感**来自 Folia（[chthollyphile/folia-major](https://github.com/chthollyphile/folia-major)，AGPL-3.0），**已获原作者授权同意**。该布局由本项目以 Jetpack Compose **独立实现**，未复制、衍生或链接 Folia 的任何源代码，因此本项目许可仍为 GPL-3.0、不受 AGPL 传染。署名见 [`NOTICE`](./NOTICE)。

## 8. 签名提示 / Signing

仓库内的构建配置为便于直接编译，Release 复用了 Android 的 debug 签名占位。**正式发布**请务必替换为你自己的签名密钥，并妥善保管；**切勿**把签名密钥（`.jks` / `.keystore`）提交进版本库（`.gitignore` 已默认排除）。

---

By using IRIS Music you acknowledge that it is provided **AS IS**, with **ABSOLUTELY NO WARRANTY**, and that the author bears **no liability** for any resulting damages. See `LICENSE` (GPL-3.0) sections 15 and 16.

© 2026 WWRJ · IRIS Music is free software licensed under GPL-3.0.