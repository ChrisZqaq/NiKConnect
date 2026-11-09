# KIMI-CODE 交互原型

这是 NiKConnect 的交互与视觉参考，不是可连接相机的应用程序。

## 运行

需要本机已有 Node.js，无需安装 npm 依赖。

```bash
cd /Users/chrisz/Documents/projects/NiKConnect/docs/prototype
npm run dev
```

浏览器打开：<http://localhost:7100>

也可以直接用现代浏览器打开 `index.html`；若浏览器对本地文件行为有限制，使用上述静态服务器。

## 文件

- `index.html`：内联 CSS、JavaScript 与演示图片的单文件原型。
- `server.mjs`：静态服务器，所有路由返回该页面。
- `package.json`：仅提供 `npm run dev` 启动命令。

## 与 PRD 的边界

近期参考其中的连接、图库、大图查看和传输队列。Live View、参数拨盘、远程快门、滤镜预览、作品集、地点与统计均为概念探索，不属于近期交付承诺。页面中的设备、照片、速度和任务状态是模拟数据。

原始来源：`/Users/chrisz/Documents/projects/nikon-connect/prototype/`。复制后三个核心文件的 SHA-256 已逐一核对一致。
