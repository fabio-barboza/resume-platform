package dev.resumeplatform.resumeai.agent;

import java.lang.reflect.Type;

import org.springframework.ai.tool.execution.ToolCallResultConverter;

public class RawStringResultConverter implements ToolCallResultConverter {
    @Override
    public String convert(Object result, Type returnType) {
        return result == null ? "" : result.toString();
    }
}
