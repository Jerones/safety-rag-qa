package com.example.safetyrag.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HostsFileGuard} 的判定矩阵。
 *
 * <p>回归保护：{@code jdk.net.hosts.file} 指向不可用文件时 JDK 会让所有域名解析
 * 直接失败（不回退系统 DNS），必须被守卫清掉，否则表现为"模型调用全部失败、
 * 回答降级成抽取式"。
 */
class HostsFileGuardTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearProperty() {
        System.clearProperty(HostsFileGuard.PROPERTY);
    }

    @Test
    @DisplayName("未设置该属性时不做任何处理")
    void noProperty_noAction() {
        System.clearProperty(HostsFileGuard.PROPERTY);
        assertNull(HostsFileGuard.clearIfUnusable());
    }

    @Test
    @DisplayName("文件不存在：清除属性并返回原因")
    void missingFile_clearsProperty() {
        Path missing = tempDir.resolve("hosts-not-here");
        System.setProperty(HostsFileGuard.PROPERTY, missing.toString());

        String reason = HostsFileGuard.clearIfUnusable();

        assertNotNull(reason, "应报告问题");
        assertTrue(reason.contains("不存在"), "原因应说明文件不存在，实际：" + reason);
        assertNull(System.getProperty(HostsFileGuard.PROPERTY), "属性应被清除");
    }

    @Test
    @DisplayName("文件只有注释和空行：等价空 hosts，同样清除")
    void commentOnlyFile_clearsProperty() throws IOException {
        Path file = tempDir.resolve("hosts");
        Files.writeString(file, "# 只有注释\n\n   \n");
        System.setProperty(HostsFileGuard.PROPERTY, file.toString());

        String reason = HostsFileGuard.clearIfUnusable();

        assertNotNull(reason, "空 hosts 会让所有域名解析失败，应报告问题");
        assertTrue(reason.contains("没有"), "原因应说明无有效映射行，实际：" + reason);
        assertNull(System.getProperty(HostsFileGuard.PROPERTY), "属性应被清除");
    }

    @Test
    @DisplayName("文件合法：保留属性，不动配置")
    void validFile_keepsProperty() throws IOException {
        Path file = tempDir.resolve("hosts");
        Files.writeString(file, "# comment\n8.140.217.18 dashscope.aliyuncs.com\n");
        System.setProperty(HostsFileGuard.PROPERTY, file.toString());

        assertNull(HostsFileGuard.clearIfUnusable(), "合法文件不应被清理");
        assertNotNull(System.getProperty(HostsFileGuard.PROPERTY), "属性应保留");
    }

    @Test
    @DisplayName("非法路径：不抛异常，按不可用处理")
    void invalidPath_treatedAsUnusable() {
        // Windows 上含非法字符的路径会让 Path.of 抛 InvalidPathException
        System.setProperty(HostsFileGuard.PROPERTY, "\u0000bad\u0000path");

        String reason = HostsFileGuard.clearIfUnusable();

        assertNotNull(reason, "非法路径应报告问题而不是抛出异常");
        assertNull(System.getProperty(HostsFileGuard.PROPERTY), "属性应被清除");
    }
}
