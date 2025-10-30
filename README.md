# PlethoraAPI

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

PlethoraAPI 是一个基于个人喜好编写的 Java 实用工具类集合。它提供了各种用途的工具，使其成为开发人员的多功能库。如果你想使用，请联系作者QQ：1591117599以获取支持。由于作者学业繁忙，没有时间做出文档，请谅解。

## Table of Contents

- [Features](#features)
- [Modules](#modules)
- [Getting Started](#getting-started)
- [License](#license)

## Features

- **String Utilities:** Handy methods for string manipulation.
    - **字符串工具类：** 用于字符串操作的实用方法。
- **Collection Utilities:** Useful functions for working with Java collections.
    - **集合工具类：** 处理 Java 集合的有用功能。
- **JavaFX Utilities:** Simplifies common tasks in JavaFX applications.
    - **JavaFX 工具类：** 简化 JavaFX 应用程序中的常见任务。
- **System Operations Utilities:** Tools for interacting with the operating system.
    - **系统操作工具类：** 用于与操作系统交互的工具。
- **Network Utilities:** Functionality for handling network-related tasks.
    - **网络工具类：** 处理与网络相关的任务的功能。
- **ChatGPT Utilities:** Integration with OpenAI's ChatGPT for natural language processing.
    - **ChatGPT 工具类：** 与 OpenAI 的 ChatGPT 集成，进行自然语言处理。
- **Time and Thread Management Utilities:** Simplifies working with time and managing threads.
    - **时间和线程管理工具类：** 简化与时间相关的操作和线程管理。
- **File Operation Utilities:** Tools for working with files and directories.
    - **文件操作工具类：** 用于处理文件和目录的工具。
- **Security and Encryption Utilities:** Provides functions for secure operations.
    - **安全加密工具类：** 提供安全操作的功能。

## Modules

PlethoraAPI is organized into the following modules:

- `plethora.util`: Core utilities for strings, collections, system operations, and more. 处理字符串、集合、系统操作等的核心工具类。
- `plethora.javafx`: Utilities specific to JavaFX applications.专用于 JavaFX 应用程序的工具类。
- `plethora-net`: Tools for network-related tasks.处理与网络相关任务的工具。
- `plethora.chatgpt`: Integration with OpenAI's ChatGPT.与 OpenAI 的 ChatGPT 集成。
- `plethora.time`: Time and thread management utilities.
- `plethora.management`: File operation utilities.
- `plethora.security`: Security and encryption tools.

## Getting Started 入门指南

要在项目中使用 PlethoraAPI，请按照以下步骤操作。下面是使用 Maven 的示例：

### 步骤 1：克隆项目

将 PlethoraAPI 项目克隆到本地环境。

### 步骤 2：构建项目

使用 Maven 运行以下命令构建项目：

```bash
mvn install
```

步骤 3：添加到项目依赖
将以下 XML 代码片段添加到项目的 Maven 配置文件（pom.xml）中：
```xml
<dependency>
    <groupId>fun.ceroxe</groupId>
    <artifactId>plethora-api</artifactId>
    <version>0.2.2</version>
</dependency>
```