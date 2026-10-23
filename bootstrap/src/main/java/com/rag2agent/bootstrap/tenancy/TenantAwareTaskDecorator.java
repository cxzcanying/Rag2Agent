package com.rag2agent.bootstrap.tenancy;

import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

public class TenantAwareTaskDecorator implements TaskDecorator {

    private final TaskDecorator contextDecorator = new ContextPropagatingTaskDecorator();

    @Override
    public Runnable decorate(Runnable runnable) {
        return contextDecorator.decorate(TenantContext.wrap(runnable));
    }
}
