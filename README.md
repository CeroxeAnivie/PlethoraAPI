# CeroxeAPI

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

CeroxeAPI 是一个基于个人喜好编写的 Java 实用工具类集合。现已采用 **Maven 多模块架构** 重构，旨在提供更轻量、模块化的开发体验。

如果你想使用，请联系作者QQ：**1591117599** 以获取支持。由于作者学业繁忙，没有时间做出详细文档，请谅解。

## Table of Contents 目录

- [Features (功能特性)](#features)
- [Modules (模块说明)](#modules)
- [Getting Started (快速开始)](#getting-started)
- [License (开源协议)](#license)

## Features

根据最新版本结构，主要包含以下功能：

- **Security & Encryption:** Ready-to-use AES and RSA encryption utilities.
    - **安全与加密：** 开箱即用的 AES 和 RSA 加密工具类 (`security` 包)。
- **Network Utilities:** Secure socket implementations and network helpers.
    - **网络工具：** 安全套接字 (SecureSocket) 实现及网络辅助工具 (`net` 包)。
- **Logging & Printing:** Custom logging wrappers for structured output.
    - **日志与打印：** 自定义的日志记录器 (Loggist) 和打印工具 (`print` 包)。
- **System Detection:** Hardware info (via Oshi) and Windows-specific operations.
    - **系统检测：** 硬件信息检测 (OshiUtils) 和 Windows 系统操作 (`ceroxe-detector` 模块)。
- **Console Utilities:** Enhanced console interactions (TUI helpers).
    - **控制台工具：** 增强的控制台交互工具 (MyConsole) (`utils` 包)。
- **Thread & Time:** Thread management and time calculation helpers.
    - **线程与时间：** 线程管理器 (ThreadManager) 与时间工具 (TimeUtils)。

## Modules

CeroxeAPI 现在分为以下 Maven 模块，您可以按需引用：

| Module Artifact ID    | Description                         | Key Packages                                  |
|:----------------------|:------------------------------------|:----------------------------------------------|
| **`ceroxe-core`**     | **核心模块**。包含绝大多数基础工具（加密、网络、日志、通用工具）。 | `security`, `net`, `print`, `utils`, `thread` |
| **`ceroxe-detector`** | **检测器模块**。专注于操作系统交互和硬件信息获取。         | `oshi`, `windows`                             |

## Getting Started 入门指南

要在项目中使用 CeroxeAPI，请按照以下步骤操作。

### 步骤 1：构建项目

将项目克隆到本地后，在根目录（`ceroxe-parent` 所在目录）运行：

```bash
mvn clean install
```

### 步骤 2：添加到项目依赖

现在您需要根据需求引用具体的子模块，而不是整个父工程。

**如果您只需要基础工具（加密、网络、日志等）：**

```xml
<dependency>
    <groupId>fun.ceroxe.api</groupId>
    <artifactId>ceroxe-core</artifactId>
    <version>0.2.5</version>
</dependency>
```

**如果您需要系统硬件检测功能：**

```xml
<dependency>
    <groupId>fun.ceroxe.api</groupId>
    <artifactId>ceroxe-detector</artifactId>
    <version>0.2.5</version>
</dependency>
```

*(注意：`ceroxe-detector` 通常会自动依赖 `ceroxe-core`，具体取决于您的内部配置)*

## License

此项目遵循 [MIT License](https://opensource.org/licenses/MIT) 开源协议。
