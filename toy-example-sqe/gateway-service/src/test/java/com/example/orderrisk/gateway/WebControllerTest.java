package com.example.orderrisk.gateway;

import io.javalin.http.Context;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebControllerTest {

    /** Minimal fake Javalin context that only answers formParam(...). */
    private static Context formContext(Map<String, String> form) {
        return (Context) Proxy.newProxyInstance(Context.class.getClassLoader(), new Class<?>[]{Context.class},
                (proxy, method, args) -> method.getName().equals("formParam") ? form.get((String) args[0]) : null);
    }

    @Test
    void emptyItemRowsAreSkipped() {
        Context ctx = formContext(Map.of("sku1", "SKU-1001", "qty1", "2", "sku2", "", "qty2", "1"));

        List<Map<String, Object>> items = WebController.items(ctx);

        assertThat(items).containsExactly(Map.of("sku", "SKU-1001", "quantity", 2));
    }

    @Test
    void nonNumericQuantityIsRejected() {
        Context ctx = formContext(Map.of("sku1", "SKU-1001", "qty1", "two"));

        assertThatThrownBy(() -> WebController.items(ctx)).isInstanceOf(IllegalArgumentException.class);
    }
}
