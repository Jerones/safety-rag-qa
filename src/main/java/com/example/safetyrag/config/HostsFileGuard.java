package com.example.safetyrag.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/**
 * 启动期守卫：清理失效的 {@code jdk.net.hosts.file} 设置。
 *
 * <p><b>为什么需要它</b>：{@code -Djdk.net.hosts.file=<路径>} 一旦设置，JDK 就会
 * 用该文件完全替代系统 DNS 解析，<b>并且文件不可用时不会回退到平台解析器</b>，
 * 而是对任何域名都直接抛 {@code UnknownHostException}。实测（JDK 21）：
 *
 * <pre>
 * 不带参数          → resolve = 4 addrs, 请求 HTTP 401 / 375ms
 * 带参数但文件已删   → UnknownHostException: Unable to resolve host ... as hosts
 *                     file ... not found，请求 45ms 直接失败
 * </pre>
 *
 * <p>本项目的 {@code hosts} 文件是临时产物（由 {@code tools/pin_dashscope_ip.py}
 * 生成，用来绕开特定网络下的 PMTU 黑洞），换网络后就该删除。但启动参数容易残留在
 * IDEA 运行配置、命令行历史或脚本里——参数还在、文件没了，表现是"模型调用全部失败、
 * 回答降级成抽取式、向量维度显示 0"，看起来完全像网络故障，极难定位。本项目实际
 * 踩到过两次，因此在这里做一道兜底。
 *
 * <p>判定为不可用的情况：文件不存在 / 无法读取 / 只有注释与空行（等于空 hosts，
 * 同样会让所有域名解析失败）。命中任意一种就清除该属性，回退到系统 DNS。
 */
public final class HostsFileGuard {

    /** JDK 用于覆盖 hosts 解析的系统属性名。 */
    public static final String PROPERTY = "jdk.net.hosts.file";

    private HostsFileGuard() {
    }

    /**
     * 检查并清理失效的 {@code jdk.net.hosts.file}。
     *
     * <p>必须在应用发起任何网络请求之前调用（{@link com.example.safetyrag.SafetyRagApplication#main}
     * 的第一行）：JDK 的 hosts 解析服务是首次解析时才初始化的，晚于此时清理可能不再生效。
     *
     * @return 若清除了属性，返回一句可读的原因说明；无需处理时返回 {@code null}
     */
    public static String clearIfUnusable() {
        String configured = System.getProperty(PROPERTY);
        if (configured == null || configured.isBlank()) {
            return null;
        }
        String problem = inspect(configured);
        if (problem == null) {
            return null;
        }
        System.clearProperty(PROPERTY);
        return "检测到 -D" + PROPERTY + "=" + configured + " " + problem
                + "。JDK 在该设置失效时不会回退系统 DNS，会让所有外部请求直接失败，"
                + "已自动清除该设置并改用系统 DNS 解析。"
                + "建议同时在 IDEA 运行配置（VM options）或启动脚本里删掉这个参数。";
    }

    /**
     * 判断给定的 hosts 文件路径是否可用。
     *
     * @return 不可用原因；可用时返回 {@code null}
     */
    static String inspect(String configuredPath) {
        Path path;
        try {
            path = Path.of(configuredPath);
        } catch (InvalidPathException e) {
            return "不是合法的文件路径（" + e.getMessage() + "）";
        }
        if (!Files.isRegularFile(path)) {
            return "指向的文件不存在";
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(path);
        } catch (IOException e) {
            return "文件无法读取（" + e.getMessage() + "）";
        }
        boolean hasEntry = lines.stream()
                .map(String::trim)
                .anyMatch(line -> !line.isEmpty() && !line.startsWith("#"));
        if (!hasEntry) {
            return "文件里没有任何有效的主机映射行（只有空行或注释），等价于空 hosts";
        }
        return null;
    }
}
