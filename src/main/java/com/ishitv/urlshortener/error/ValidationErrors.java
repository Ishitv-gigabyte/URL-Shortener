package com.ishitv.urlshortener.error;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** FastAPI/Pydantic's 422 body: {@code {"detail": [{"type", "loc", "msg", "input", "ctx"?}]}}. */
public record ValidationErrors(List<Item> detail) {

    public static ValidationErrors of(Item item) {
        return new ValidationErrors(List.of(item));
    }

    /**
     * One Pydantic error. {@code input} is always present (it may be JSON null); {@code ctx} is omitted
     * when Pydantic omits it.
     */
    public record Item(
            String type,
            List<Object> loc,
            String msg,
            Object input,
            @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> ctx) {
    }
}
