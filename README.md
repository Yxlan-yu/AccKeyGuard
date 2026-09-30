# 无障碍保活

KernelSU / Magisk 模块，动态守护无障碍服务，防止被系统自动关闭。

## 功能

- 勾选需要守护的无障碍服务，守护进程会定期检查系统已启用列表，缺失时自动补写
- WebUI 可视化配置，通过 KernelSU Manager 打开
- 支持深色 / 浅色 / 跟随系统三种主题
- 状态实时刷新，无需退出重进
- 首次运行自动接管当前已启用的无障碍服务

## 使用

1. 在 KernelSU Manager (或 Magisk) 中安装模块并重启
2. 模块信息页打开 WebUI
3. 勾选需要保活的无障碍服务，点击「保存设置」
4. 守护进程每 20 秒检查一次，WebUI 每 5 秒自动刷新状态

## 配置

配置保存在 KernelSU module config（`/data/adb/ksu/module_configs/acckeyguard`）：

| Key | 说明 | 示例 |
|-----|------|------|
| `enabled_services` | 要保活的服务组件列表，冒号分隔 | `com.example/.Service` |
| `theme` | 主题，`auto` / `light` / `dark` | `auto` |

## 结构

```
acckeyguard/
├── module.prop
├── customize.sh
├── service.sh
├── data/
│   └── accd.sh          # 守护进程
└── webroot/
    ├── index.html
    ├── style.css
    ├── app.js
    ├── kernelsu.js
    └── icon.svg
```

## 日志

守护进程日志：`/data/adb/modules/acckeyguard/data/accd.log`（WebUI 内可查看尾部 50 行）