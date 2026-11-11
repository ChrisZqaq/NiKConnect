# Nikon 相机伴侣原型设计规格

## Design Read

- Artifact：375 × 812 高保真移动交互原型，单 HTML、离线运行。
- Audience：28–45 岁、熟悉 RAW 与曝光参数的尼康 Z 系列用户。
- Visual language：暖暗房中的精密光学仪器；照片承担色彩，界面保持克制。
- Mode：greenfield。
- Dials：visual variance 4 / motion 3 / density 7 / assets 7 / brand fidelity 6。
- 四个定位：主控台是操作主角；10 cm 近距离阅读；视觉温度安静、权威、微暖；以 375 px 宽度优先保证参数与主操作不溢出。

## Design Decisions

- Color：暖黑 `#12100E`，二级表面 `#1A1714`，主文字 `#E9E5DC`，次文字 `#969087`，细线 `#35312C`，唯一强调色低饱和琥珀 `#C3A36E`（原强调色饱和度降低 30%）。连接完成的绿色仅作为需求指定的语义状态，不作装饰。
- Typography：`Avenir Next / Helvetica Neue / PingFang SC` 为界面字体；`SFMono-Regular / Menlo / Consolas` 为参数与数字字体；无外部字体请求。
- Spacing：4 px 基线，主要间距 8 / 12 / 16 / 24 / 32。
- Radius：普通控件 4 px，卡片与浮层不超过 8 px；快门与状态点按功能使用圆形。
- Shadow：只用于手机外框和必要浮层，界面内部主要靠细线与留白分层。
- Motion：只用于连接/传输/写入进度、滤镜切换与快门反馈；其他状态瞬时切换，并支持 reduced-motion。

## Assets

- `assets/nikon-brand/nikon-logo.png`：Nikon 标志，来源 Wikimedia Commons `File:Nikon Logo.svg` 的页面渲染副本；界面中去彩色显示，原商标权利归 Nikon。
- `assets/nikon-brand/nikon-z6iii.jpg`：Nikon Z6III 实拍，昼落ち，CC0；来源 Wikimedia Commons `File:Nikon Z6III 13 jul 2024g.jpg`。
- `prototype-work/landscape.jpg`：Johannes Plenio 的山湖照片，CC0；来源 Wikimedia Commons `File:Beautiful mountain reflection (Unsplash).jpg`。
- 最终 HTML 将上述资源压缩并转为 data URI，不保留运行时外链。

## Verified Product Context

- Nikon 官方 Z6III 页面将 Imaging Recipes 描述为可下载到相机的 Cloud Picture Control 预设。
- 原型中的文件、传输速度、作品统计及滤镜作者均为演示数据，不代表真实用户数据或真实相机连接。
