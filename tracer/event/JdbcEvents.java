package ru.inversion.tc.tracer.event;

import java.util.Map;

public class JdbcEvents {

    private JdbcEvents() {}

    public static JdbcStatementEvent beforeExecute(
            Object source,
            String method,
            String sql,
            Map<Integer,Object> params
    ) {
        return null;
    }
}
