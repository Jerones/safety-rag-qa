package com.example.safetyrag.util;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

/**
 * 向量库异常的判定工具。
 *
 * <p><b>为什么需要这个类：</b>从 Chroma 换到 Qdrant 后，底层传输从 HTTP/REST
 * 变成了 gRPC，异常类型也跟着变了——Chroma 客户端抛的是 Spring 的
 * {@link ResourceAccessException}，Qdrant 抛的是 {@link StatusRuntimeException}。
 * 原来两处 {@code catch (ResourceAccessException)} 的退避重试在 Qdrant 下
 * <b>永远不会触发</b>，表现为"偶发连接失败直接冒到用户面前，没有任何重试"。
 * 这类问题编译期完全看不出来，只能靠改异常类型发现。</p>
 *
 * <p>判断逻辑放在一处，是为了避免"检索侧重试、入库侧不重试"这种不一致。</p>
 */
public final class VectorStoreErrors {

    private VectorStoreErrors() {
    }

    /**
     * 异常链上是否存在"连接类"故障（值得重试）。
     *
     * <p>只对这些情况重试是有意的：参数错、维度不匹配、集合不存在这类错误
     * 重试一万次也一样，立即抛出才能让用户看到真正的原因。</p>
     */
    public static boolean isConnectionFailure(Throwable e) {
        for (Throwable cur = e; cur != null && cur != cur.getCause(); cur = cur.getCause()) {
            if (cur instanceof ResourceAccessException
                    || cur instanceof ConnectException
                    || cur instanceof SocketTimeoutException) {
                return true;
            }
            if (cur instanceof StatusRuntimeException status) {
                Status.Code code = status.getStatus().getCode();
                // UNAVAILABLE = 连接不上/服务重启；DEADLINE_EXCEEDED = 超时
                if (code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED) {
                    return true;
                }
                continue;
            }
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("unavailable") || lower.contains("connection refused")
                        || lower.contains("connection reset") || lower.contains("failed to connect")
                        || lower.contains("i/o error")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 取最内层 cause 的 message，gRPC 的真实原因通常在 cause 链末端。 */
    public static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null || msg.isBlank() ? cur.getClass().getSimpleName() : msg;
    }
}
