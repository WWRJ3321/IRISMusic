<div align="center">

<img src="docs/logo.png" width="96" alt="IRIS Music">

# IRIS Music

**一个安静的本地音乐播放器**

把手机里的歌，用简单的卡片铺开。不联网，不要账号，数据只留在你手里。

[![Release](https://img.shields.io/github/v/release/WWRJ3321/IRISMusic?style=flat-square&color=111111&label=release)](https://github.com/WWRJ3321/IRISMusic/releases/latest)
[![License](https://img.shields.io/badge/license-GPL--3.0-111111?style=flat-square)](LICENSE)
![Android](https://img.shields.io/badge/Android-7.0%2B-111111?style=flat-square)
![Network](https://img.shields.io/badge/network-none-111111?style=flat-square)

[下载](https://github.com/WWRJ3321/IRISMusic/releases/latest) · [功能](#功能) · [截图](#截图) · [English](README.en.md) · [免责声明](REMIND.md)

</div>

<br>

<table>
<tr>
<td align="center"><img src="docs/screenshots/Main_f.png" width="190"><br><sub>歌单</sub></td>
<td align="center"><img src="docs/screenshots/Playcard1_f.png" width="190"><br><sub>播放卡片</sub></td>
<td align="center"><img src="docs/screenshots/Playcard3_f.png" width="190"><br><sub>堆叠</sub></td>
</tr>
</table>

<br>

## 5.0 更新了什么

- **音律震动重写**：只跟着鼓点震，安静的段落不震；自动对齐扬声器与蓝牙耳机的延迟。
- **更顺**：播放进度独立刷新，大曲库滚动、切歌不再拖累整个界面。
- **更整齐**：设置从 9 个分区收成 5 个；全 App 统一的缓动、展开与弹层动画。
- **更细的地方**：取色方块自定义配色、歌词译文成组显示、堆叠卡片歌词可铺满封面、音效开关不再"啪"一声，以及 20 余处修复。

完整说明见 [Release](https://github.com/WWRJ3321/IRISMusic/releases/latest)。

## 功能

**看得见的**
- 四种排布：纵向列表、横向卡片、堆叠、唱片墙。排布、配色、明暗、材质互不绑定，可以任意组合。
- 三种材质：实色、毛玻璃、真折射液态玻璃。
- 配色：黑白、深海蓝、青草绿，或者用取色方块调出自己的主色与次色。
- 歌词：全屏滚动歌词、逐字扫光、悬浮歌词、封面歌词。

**听得见的**
- 自由曲线均衡器，加虚拟低音、宽场环绕、动态范围增强、防失真保护、响度均衡。
- 音律震动：手机跟着底鼓轻轻震，力度随鼓点强弱变化。

**看不见的**
- 本地推荐：根据点赞、播放、跳过给整个曲库打分，可调「探索度」。
- 听歌报告：最常听的歌和收听统计。
- 默认不联网。只有你在设置里手动打开「检查更新」时，才会向 GitHub 发一次请求，不含任何统计。

> 唱片墙的交互灵感来自 [Folia](https://github.com/chthollyphile/folia-major)，已获原作者授权，代码为独立实现。

## 截图

<table>
<tr>
<td align="center"><img src="docs/screenshots/Playcard2_f.png" width="190"><br><sub>播放卡片</sub></td>
<td align="center"><img src="docs/screenshots/Report_f.png" width="190"><br><sub>听歌报告</sub></td>
<td align="center"><img src="docs/screenshots/Recordwall1_f.png" width="190"><br><sub>唱片墙</sub></td>
</tr>
</table>

## 安装

在 [Releases](https://github.com/WWRJ3321/IRISMusic/releases/latest) 下载 `IRISMusic-*-arm64-v8a.apk`（近几年的手机基本都是 arm64），直接安装。

首次打开授予「读取音频」权限即可。悬浮歌词需要在系统设置里允许「显示在其他应用上层」。

## 关于

IRIS Music 是一个个人项目：没有团队，没有 KPI，也不商业化，只做我自己想用的样子。

使用前请先阅读 [REMIND.md](REMIND.md)。本软件按「原样」提供，不附带任何担保；不包含任何音乐内容，导入的音频由使用者自行负责。

## 许可

Copyright © 2026 WWRJ

自有源码以 [GPL-3.0](LICENSE) 授权：可以自由使用、修改和分发；对外分发修改版时，整个衍生作品也必须以 GPL-3.0 开源。详见 [REMIND.md](REMIND.md) 与 [NOTICE](NOTICE)。

<br>
<div align="center"><sub>Made by WWRJ</sub></div>
