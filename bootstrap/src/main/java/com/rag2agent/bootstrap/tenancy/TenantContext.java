package com.rag2agent.bootstrap.tenancy;

import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import java.util.function.Supplier;

/** HTTP 请求、异步任务和后台扫描共享的租户上下文。 */
public final class TenantContext {

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static Long getTenantId() {
        State state = CURRENT.get();
        return state == null ? null : state.tenantId();
    }

    public static long requireTenantId() {
        Long tenantId = getTenantId();
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少有效的租户上下文");
        }
        return tenantId;
    }

    public static boolean isSystem() {
        State state = CURRENT.get();
        return state != null && state.system();
    }

    public static void set(long tenantId) {
        CURRENT.set(new State(tenantId, false));
    }

    public static <T> T callAs(long tenantId, Supplier<T> action) {
        State previous = CURRENT.get();
        CURRENT.set(new State(tenantId, false));
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    public static void runAs(long tenantId, Runnable action) {
        callAs(tenantId, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T callAsSystem(Supplier<T> action) {
        State previous = CURRENT.get();
        CURRENT.set(new State(0, true));
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    public static void runAsSystem(Runnable action) {
        callAsSystem(() -> {
            action.run();
            return null;
        });
    }

    public static Runnable wrap(Runnable action) {
        State captured = CURRENT.get();
        return () -> {
            State previous = CURRENT.get();
            if (captured == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(captured);
            }
            try {
                action.run();
            } finally {
                restore(previous);
            }
        };
    }

    public static void clear() {
        CURRENT.remove();
    }

    private static void restore(State previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    private record State(long tenantId, boolean system) {}
}
